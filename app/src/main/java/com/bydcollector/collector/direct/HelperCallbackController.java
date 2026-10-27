package com.bydcollector.collector.direct;

import android.os.SystemClock;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.bydcollector.collector.data.direct.DirectFidEntry;
import com.bydcollector.collector.data.direct.DirectFidRegistry;
import com.bydcollector.collector.data.direct.DirectValueDecoders;
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Owns BYDAuto callback registration, bounded raw batching, and callback-first scalar caching. */
final class HelperCallbackController implements AutoCloseable {
    static final long IDLE_CONTROL_WAKE_MS = 1_000L;
    static final long SECONDARY_EXCEPTION_POLL_MS = 500L;
    private static final Set<CollectorHelperDaemon.Address> KPI_FIELD_SOURCES = kpiSources(NormalizedFieldCatalog.INSTANCE.getKpiSourceKeys());
    private static final CollectorHelperDaemon.Address KPI_POWER_SOURCE = registryAddress("bodywork_power_level");
    private static final Set<CollectorHelperDaemon.Address> KPI_SOURCES = allKpiSources(KPI_FIELD_SOURCES, KPI_POWER_SOURCE);
    static boolean isKpiSource(CollectorHelperDaemon.Address address) { return KPI_SOURCES.contains(address); }
    static final int MAX_ADDITION = 128;
    static final int MAX_DEVICE_FIDS = 4_096;
    static final int PERMISSION_DENIED = -2147482644;
    private static final long[] RETRY_MS = { 1_000L, 2_000L, 5_000L, 10_000L, 30_000L };
    private static final long[] SEED_RETRY_DELAYS_MS = { 500L, 1_500L };
    private static final Set<CollectorHelperDaemon.Address> SECONDARY_POLL_SOURCES = secondaryPollSources();
    private static final Map<CollectorHelperDaemon.Address, DirectFidEntry> DIRECT_ENTRIES = directEntryMap();

    interface Poller {
        CollectorHelperDaemon.BatchResult poll(List<CollectorHelperDaemon.Address> rows) throws Throwable;
        default boolean canceled() { return false; }
    }
    interface KpiInvalidationSender {
        void send(IBinder listener, long subscriptionId, String bootId, String generation, long sequence) throws Throwable;
    }
    interface Host {
        boolean appOwns(int stream);
        boolean captureAllowed(int stream);
        boolean publishingHeld(int stream);
        long liveBytes(int stream);
        CallbackSpool.AppendResult deliver(TelemetryCallbackBatch batch);
        CallbackSpool.AppendResult spill(int stream);
        void recordLoss(int stream, TelemetryCallbackQueue.Loss loss);
        default void recordLostBatch(TelemetryCallbackBatch batch, String reason, Throwable cause) {
            recordLoss(batch.stream, loss(batch, reason));
        }
        void noteError(String message);
        default void noteContext(String event, String message) { }
    }

    private final Object lock = new Object();
    private final Object kpiLock = new Object();
    private final String bootId;
    private final String generation;
    private final Platform platform;
    private final Host host;
    private final LongSupplier elapsedClock;
    private final LongSupplier wallClock;
    private final KpiInvalidationSender kpiInvalidationSender;
    private final ScheduledExecutorService[] streamWorkers = new ScheduledExecutorService[3];
    private final ScheduledFuture<?>[] flushTasks = new ScheduledFuture<?>[3];
    private final long[] flushDeadlines = { Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE };
    private final long[] flushTokens = new long[3];
    private final ScheduledExecutorService registrationWorker;
    private String lastRegistrationDiagnostic;
    private final ReentrantLock closeLock = new ReentrantLock();
    private final TelemetryCallbackQueue[] queues = new TelemetryCallbackQueue[3];
    private final long[] eventSequence = new long[3];
    private final long[] sourceEpoch = new long[3];
    private final long[] readIdentitySequence = new long[3];
    private final long[] inFlightBytes = new long[3];
    private final boolean[] desired = new boolean[3];
    private final boolean[] capturePermitted = new boolean[3];
    private final boolean[] captureEnabled = new boolean[3];
    private final boolean[] fenceActive = new boolean[3];
    private final long[] epochs = new long[3];
    private final long[] plannedEpochs = new long[3];
    private final Set<NativeKey> mainKeys;
    private final Set<NativeKey> secondaryKeys;
    private final List<CollectorHelperDaemon.Address> mainRows;
    private final List<CollectorHelperDaemon.Address> secondaryRows;
    private final Map<CollectorHelperDaemon.Address, Integer> mainOrdinals;
    private final Map<CollectorHelperDaemon.Address, Integer> secondaryOrdinals;
    private final HelperDueAddressSchedule mainDue;
    private final HelperDueAddressSchedule secondaryDue;
    private final Map<NativeKey, List<CollectorHelperDaemon.Address>> scalarAddresses;
    private final List<CollectorHelperDaemon.Address> kpiRows;
    private final List<CollectorHelperDaemon.Address> kpiFieldRows;
    private final CollectorHelperDaemon.Address kpiPowerRow;
    private final Set<NativeKey> kpiKeys;
    private final int missingKpiSourceCount;
    private final Map<CollectorHelperDaemon.Address, KpiValue> kpiMailbox = new LinkedHashMap<>();
    private final Map<CollectorHelperDaemon.Address, KpiAcquisitionState> kpiAcquisition = new LinkedHashMap<>();
    private final Map<NativeKey, List<CollectorHelperDaemon.Address>> kpiRowsByNative;
    private final ThreadPoolExecutor kpiNotificationWorker;
    private final Map<CollectorHelperDaemon.Address, CallbackValueSource> mainSources = new LinkedHashMap<>();
    private final Map<CollectorHelperDaemon.Address, CallbackValueSource> secondarySources = new LinkedHashMap<>();
    private final Map<CollectorHelperDaemon.Address, Long> mainCallbackWatermarks = new LinkedHashMap<>();
    private final Map<CollectorHelperDaemon.Address, Long> secondaryCallbackWatermarks = new LinkedHashMap<>();
    private final Map<CollectorHelperDaemon.Address, AcquisitionState> mainAcquisition = new LinkedHashMap<>();
    private final Map<CollectorHelperDaemon.Address, AcquisitionState> secondaryAcquisition = new LinkedHashMap<>();
    private final Object registrationLock = new Object();
    private ScheduledFuture<?> registrationFuture;
    private volatile boolean closed;
    private volatile boolean streamWorkersShutdown;
    private volatile boolean streamWorkersTerminated;
    private volatile boolean restartListener;
    private volatile String retryReason;
    private KpiSubscriber kpiSubscriber;
    private long nextKpiSubscriptionId;
    private long kpiSequence;
    private long kpiReadIdentitySequence;
    private int kpiListenerStatus = CollectorHelperProtocol.KPI_LISTENER_REGISTERING;
    private String kpiListenerError;
    private boolean kpiPowerKnown;
    private boolean kpiPowerOn;
    private boolean kpiNotificationPending;
    private boolean kpiNotificationQueued;
    private boolean kpiNotificationSent;
    private int retryIndex;
    private long callbacksReceived;
    private long queueLossCount;
    private long initialSeedGetAttempts;
    private long sourceRecoveryGetAttempts;
    private long secondaryExceptionGetAttempts;
    private long callbackListenerGeneration;
    private HelperStreamRuntimeState.StreamView lastMainView;
    private HelperStreamRuntimeState.StreamView lastSecondaryView;

    HelperCallbackController(String bootId, String generation,
        List<CollectorHelperDaemon.Address> mainRows, List<CollectorHelperDaemon.Address> secondaryRows,
        Platform platform, Host host) {
        this(bootId, generation, mainRows, secondaryRows, platform, host,
            SystemClock::elapsedRealtime, System::currentTimeMillis);
    }

    HelperCallbackController(String bootId, String generation,
        List<CollectorHelperDaemon.Address> mainRows, List<CollectorHelperDaemon.Address> secondaryRows,
        Platform platform, Host host, LongSupplier elapsedClock, LongSupplier wallClock) {
        this(bootId, generation, mainRows, secondaryRows, platform, host, elapsedClock, wallClock,
            HelperCallbackController::sendBinderInvalidation);
    }

    HelperCallbackController(String bootId, String generation,
        List<CollectorHelperDaemon.Address> mainRows, List<CollectorHelperDaemon.Address> secondaryRows,
        Platform platform, Host host, LongSupplier elapsedClock, LongSupplier wallClock,
        KpiInvalidationSender kpiInvalidationSender) {
        this.bootId = bootId;
        this.generation = generation;
        this.platform = platform;
        this.host = host;
        this.elapsedClock = elapsedClock;
        this.wallClock = wallClock;
        this.kpiInvalidationSender = kpiInvalidationSender;
        this.mainRows = Collections.unmodifiableList(new ArrayList<>(mainRows));
        this.secondaryRows = Collections.unmodifiableList(new ArrayList<>(secondaryRows));
        this.mainOrdinals = ordinalIndex(this.mainRows);
        this.secondaryOrdinals = ordinalIndex(this.secondaryRows);
        long initialDue = Math.max(0L, elapsedClock.getAsLong());
        this.mainDue = new HelperDueAddressSchedule(this.mainRows.size(), initialDue);
        this.secondaryDue = new HelperDueAddressSchedule(this.secondaryRows.size(), initialDue);
        this.mainKeys = nativeKeys(this.mainRows);
        this.secondaryKeys = nativeKeys(this.secondaryRows);
        this.scalarAddresses = scalarAddresses(this.mainRows, this.secondaryRows);
        if (KPI_SOURCES.size() > CollectorHelperProtocol.MAX_KPI_VALUES) {
            throw new IllegalStateException("canonical KPI source count exceeds helper mailbox capacity");
        }
        this.kpiRows = eligibleKpiRows(this.mainRows, this.secondaryRows);
        this.kpiFieldRows = filterKpiRows(this.kpiRows, KPI_FIELD_SOURCES);
        this.kpiPowerRow = KPI_POWER_SOURCE != null && this.kpiRows.contains(KPI_POWER_SOURCE)
            ? KPI_POWER_SOURCE : null;
        this.kpiKeys = nativeKeys(kpiRows);
        this.missingKpiSourceCount = KPI_SOURCES.size() - kpiRows.size();
        this.kpiRowsByNative = indexKpiRowsByNative(kpiRows);
        for (CollectorHelperDaemon.Address row : kpiRows) {
            KpiAcquisitionState state = new KpiAcquisitionState();
            state.nextDueElapsedMs = row.equals(kpiPowerRow) ? initialDue : Long.MAX_VALUE;
            kpiAcquisition.put(row, state);
        }
        if (!platform.available()) {
            kpiListenerStatus = CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE;
            kpiListenerError = "callback listener platform unavailable";
        } else if (kpiPowerRow == null) {
            kpiListenerStatus = CollectorHelperProtocol.KPI_LISTENER_DEGRADED;
            kpiListenerError = "bodywork power source is absent from the helper whitelist";
        }
        queues[CollectorHelperProtocol.STREAM_MAIN] = new TelemetryCallbackQueue(
            CollectorHelperProtocol.STREAM_MAIN, bootId, generation, sourceEpoch[CollectorHelperProtocol.STREAM_MAIN]);
        queues[CollectorHelperProtocol.STREAM_SECONDARY] = new TelemetryCallbackQueue(
            CollectorHelperProtocol.STREAM_SECONDARY, bootId, generation, sourceEpoch[CollectorHelperProtocol.STREAM_SECONDARY]);
        streamWorkers[CollectorHelperProtocol.STREAM_MAIN] = newStreamWorker("main");
        streamWorkers[CollectorHelperProtocol.STREAM_SECONDARY] = newStreamWorker("secondary");
        registrationWorker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "byd-helper-callback-registration");
            thread.setDaemon(true);
            return thread;
        });
        kpiNotificationWorker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "byd-helper-kpi-invalidation");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        platform.setListener(new Listener() {
            @Override public void changed(int dev, int fid, int type, int rawBits, byte[] bytes) {
                onCallback(dev, fid, type, rawBits, bytes);
            }
            @Override public void error(int code, String message) { onListenerError(code, message); }
        });
    }

    void updatePlan(HelperStreamRuntimeState.StreamView main, HelperStreamRuntimeState.StreamView secondary) {
        List<TelemetryCallbackBatch> displaced;
        boolean unionChanged;
        synchronized (lock) {
            if (sameView(lastMainView, main) && sameView(lastSecondaryView, secondary)) return;
            boolean firstPlan = lastMainView == null || lastSecondaryView == null;
            displaced = new ArrayList<>();
            boolean oldMain = desired[CollectorHelperProtocol.STREAM_MAIN];
            boolean oldSecondary = desired[CollectorHelperProtocol.STREAM_SECONDARY];
            updateStream(CollectorHelperProtocol.STREAM_MAIN, main, displaced);
            updateStream(CollectorHelperProtocol.STREAM_SECONDARY, secondary, displaced);
            boolean desiredChanged = oldMain != desired[CollectorHelperProtocol.STREAM_MAIN] ||
                oldSecondary != desired[CollectorHelperProtocol.STREAM_SECONDARY];
            unionChanged = firstPlan || (desiredChanged && !desiredUnion(oldMain, oldSecondary).equals(
                desiredUnion(desired[CollectorHelperProtocol.STREAM_MAIN], desired[CollectorHelperProtocol.STREAM_SECONDARY])));
            lastMainView = main;
            lastSecondaryView = secondary;
            // Lease loss must spill retained RAM even when no new callback arrives.
            scheduleFlushLocked(CollectorHelperProtocol.STREAM_MAIN, 0L);
            scheduleFlushLocked(CollectorHelperProtocol.STREAM_SECONDARY, 0L);
        }
        for (TelemetryCallbackBatch batch : displaced) submitBatch(batch);
        if (unionChanged) scheduleRegistration(0L, true);
    }

    /** Stops new events for one stream and waits until its queue/in-flight delivery is stable. */
    boolean quiesce(int stream, long timeoutMs) {
        List<TelemetryCallbackBatch> batches;
        synchronized (lock) {
            captureEnabled[stream] = false;
            fenceActive[stream] = true;
            batches = drainAllLocked(stream);
            if (desired[stream]) {
                epochs[stream] = nextEpoch(epochs[stream]);
                captureEnabled[stream] = capturePermitted[stream];
            }
        }
        for (TelemetryCallbackBatch batch : batches) submitBatch(batch);
        long deadline = nowElapsed() + timeoutMs;
        synchronized (lock) {
            while (inFlightBytes[stream] != 0L) {
                long remaining = deadline - nowElapsed();
                if (remaining <= 0L) { fenceActive[stream] = false; return false; }
                try { lock.wait(Math.min(remaining, 100L)); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); fenceActive[stream] = false; return false;
                }
            }
            fenceActive[stream] = false;
        }
        return true;
    }

    boolean busy(int stream) {
        synchronized (lock) { return inFlightBytes[stream] != 0L; }
    }

    List<Integer> takeDueSecondaryOrdinals(int limit) {
        return secondaryDue.takeDue(nowElapsed(), Math.min(128, limit));
    }

    CollectorHelperDaemon.Address secondaryAddressAt(int ordinal) {
        if (ordinal < 0 || ordinal >= secondaryRows.size()) throw new IllegalArgumentException("invalid secondary ordinal");
        return secondaryRows.get(ordinal);
    }

    long secondaryNextDueDelayMs() {
        return secondaryDue.nextDelayMs(nowElapsed(), IDLE_CONTROL_WAKE_MS);
    }

    KpiSeedRequest takeDueKpiSeeds() {
        long now = nowElapsed();
        synchronized (kpiLock) {
            if (closed) return null;
            List<CollectorHelperDaemon.Address> due = new ArrayList<>();
            Map<CollectorHelperDaemon.Address, Long> epochs = new LinkedHashMap<>();
            Map<CollectorHelperDaemon.Address, Long> callbackWatermarks = new LinkedHashMap<>();
            for (CollectorHelperDaemon.Address row : kpiRows) {
                KpiAcquisitionState state = kpiAcquisition.get(row);
                boolean active = row.equals(kpiPowerRow) || (kpiPowerKnown && kpiPowerOn);
                if (!active || !state.registrationAccepted || state.sourceAvailable || state.inFlight ||
                    state.nextDueElapsedMs > now) continue;
                due.add(row);
                epochs.put(row, state.sourceEpoch);
                callbackWatermarks.put(row, state.callbackWatermark);
            }
            if (due.isEmpty()) return null;
            long requestId = incrementKpiCounter(kpiReadIdentitySequence);
            kpiReadIdentitySequence = requestId;
            String identity = "kpi-get:" + bootId.length() + ":" + bootId + ":" +
                generation.length() + ":" + generation + ":" + requestId;
            for (CollectorHelperDaemon.Address row : due) {
                KpiAcquisitionState state = kpiAcquisition.get(row);
                state.inFlight = true;
                state.inFlightRequestId = requestId;
            }
            return new KpiSeedRequest(requestId, identity, due, epochs, callbackWatermarks);
        }
    }

    void completeKpiSeeds(KpiSeedRequest request, CollectorHelperDaemon.BatchResult result,
                          long startedWallMs, long startedElapsedMs,
                          long completedWallMs, long completedElapsedMs) {
        if (request == null || result == null || result.values == null ||
            result.values.length != request.rows.size()) {
            failKpiSeeds(request, new IllegalStateException("KPI GET result count does not match attempted rows"),
                startedWallMs, startedElapsedMs, completedWallMs, completedElapsedMs);
            return;
        }
        boolean notify = false;
        boolean registrationChanged = false;
        synchronized (kpiLock) {
            if (closed) return;
            for (int index = 0; index < request.rows.size(); index++) {
                CollectorHelperDaemon.Address row = request.rows.get(index);
                KpiAcquisitionState state = kpiAcquisition.get(row);
                if (state == null || !state.inFlight || state.inFlightRequestId != request.requestId) continue;
                state.inFlight = false;
                state.inFlightRequestId = -1L;
                boolean current = state.sourceEpoch == request.sourceEpochs.get(row) &&
                    state.callbackWatermark == request.callbackWatermarks.get(row) &&
                    (row.equals(kpiPowerRow) || (kpiPowerKnown && kpiPowerOn)) &&
                    state.registrationAccepted;
                if (!current) continue;

                CollectorHelperDaemon.ReadValue actual = result.values[index];
                String quality = actual != null && actual.status == CollectorHelperProtocol.STATUS_OK && actual.raw != null
                    ? kpiQuality(row, expectedNativeType(row), actual.raw, null) : "read_error";
                if (quality == null) {
                    long elapsed = Math.max(0L, completedElapsedMs);
                    KpiValue previous = kpiMailbox.get(row);
                    if (previous != null && elapsed < previous.observedElapsedMs) continue;
                    state.sourceAvailable = true;
                    state.seededOnce = true;
                    state.seedAttempts = 0;
                    state.nextDueElapsedMs = Long.MAX_VALUE;
                    putKpiValueLocked(row, CollectorHelperProtocol.STATUS_OK,
                        expectedNativeType(row), true, actual.raw, null, completedWallMs, elapsed,
                        CollectorHelperProtocol.KPI_SOURCE_GET, state.sourceEpoch, "get_usable",
                        request.identity + ":" + row.tx + ":" + row.dev + ":" + row.fid,
                        startedWallMs, startedElapsedMs, null, null);
                    notify = true;
                    if (row.equals(kpiPowerRow)) {
                        boolean wasOn = kpiPowerKnown && kpiPowerOn;
                        kpiPowerKnown = true;
                        kpiPowerOn = actual.raw > 0;
                        if (!kpiPowerOn && wasOn) {
                            invalidateKpiFieldsLocked("vehicle_power_off",
                                "Valid bodywork power level GET reported OFF", completedWallMs, elapsed);
                            registrationChanged = true;
                        } else if (kpiPowerOn && !wasOn) {
                            scheduleKpiFieldsLocked(elapsed);
                            registrationChanged = true;
                        }
                    }
                } else {
                    Integer raw = actual == null ? null : actual.raw;
                    int status = actual == null ? CollectorHelperProtocol.STATUS_READ_ERROR : actual.status;
                    String reason = "read_error".equals(quality) ? "kpi_seed_get_failed" : "invalid_get_value";
                    String diagnostic = actual == null ? "KPI GET returned a missing row" :
                        actual.error != null ? actual.error : "invalid KPI GET: status=" + status +
                            " raw=" + (raw == null ? "missing" : "0x" + Integer.toUnsignedString(raw, 16));
                    recordKpiLossLocked(row, CollectorHelperProtocol.STATUS_READ_ERROR,
                        expectedNativeType(row), raw, null, completedWallMs, completedElapsedMs,
                        quality, reason, diagnostic,
                        request.identity + ":" + row.tx + ":" + row.dev + ":" + row.fid,
                        startedWallMs, startedElapsedMs, !"read_error".equals(quality));
                    state.seedAttempts++;
                    state.nextSeedReason = state.seededOnce ? "source_recovery" : "initial_seed";
                    scheduleKpiSeedRetry(state, completedElapsedMs);
                    notify = true;
                    if (row.equals(kpiPowerRow)) {
                        boolean wasOn = kpiPowerKnown && kpiPowerOn;
                        kpiPowerKnown = false;
                        kpiPowerOn = false;
                        if (wasOn) {
                            invalidateKpiFieldsLocked("power_source_unavailable",
                                "POWER seed GET did not produce a valid value", completedWallMs, completedElapsedMs);
                            registrationChanged = true;
                        }
                    }
                }
            }
        }
        if (notify) scheduleKpiNotification();
        if (registrationChanged) {
            scheduleRegistration(0L, true);
            updateKpiRegistrationStatus(null);
        }
    }

    void failKpiSeeds(KpiSeedRequest request, Throwable error,
                      long startedWallMs, long startedElapsedMs,
                      long completedWallMs, long completedElapsedMs) {
        if (request == null || error == null) return;
        boolean notify = false;
        boolean getStarted = startedWallMs >= 0L && startedElapsedMs >= 0L;
        synchronized (kpiLock) {
            if (closed) return;
            for (CollectorHelperDaemon.Address row : request.rows) {
                KpiAcquisitionState state = kpiAcquisition.get(row);
                if (state == null || !state.inFlight || state.inFlightRequestId != request.requestId) continue;
                state.inFlight = false;
                state.inFlightRequestId = -1L;
                if (state.sourceEpoch != request.sourceEpochs.get(row) ||
                    state.callbackWatermark != request.callbackWatermarks.get(row) ||
                    !(row.equals(kpiPowerRow) || (kpiPowerKnown && kpiPowerOn)) || !state.registrationAccepted) continue;
                recordKpiLossLocked(row, CollectorHelperProtocol.STATUS_READ_ERROR,
                    expectedNativeType(row), null, null, completedWallMs, completedElapsedMs,
                    "read_error", "kpi_seed_get_failed", describe(error),
                    getStarted ? request.identity + ":" + row.tx + ":" + row.dev + ":" + row.fid : null,
                    getStarted ? startedWallMs : -1L, getStarted ? startedElapsedMs : -1L, false);
                state.seedAttempts++;
                state.nextSeedReason = state.seededOnce ? "source_recovery" : "initial_seed";
                scheduleKpiSeedRetry(state, completedElapsedMs);
                notify = true;
                if (row.equals(kpiPowerRow)) {
                    boolean wasOn = kpiPowerKnown && kpiPowerOn;
                    kpiPowerKnown = false;
                    kpiPowerOn = false;
                    if (wasOn) invalidateKpiFieldsLocked("power_source_unavailable",
                        "POWER seed GET failed: " + describe(error), completedWallMs, completedElapsedMs);
                }
            }
        }
        if (notify) scheduleKpiNotification();
    }

    void cancelKpiSeeds(KpiSeedRequest request, long retryDelayMs) {
        if (request == null) return;
        synchronized (kpiLock) {
            for (CollectorHelperDaemon.Address row : request.rows) {
                KpiAcquisitionState state = kpiAcquisition.get(row);
                if (state == null || state.inFlightRequestId != request.requestId) continue;
                state.inFlight = false;
                state.inFlightRequestId = -1L;
                if (!closed && state.registrationAccepted && !state.sourceAvailable &&
                    (row.equals(kpiPowerRow) || (kpiPowerKnown && kpiPowerOn))) {
                    state.nextDueElapsedMs = saturatedAdd(nowElapsed(), Math.max(0L, retryDelayMs));
                }
            }
        }
    }

    private static void scheduleKpiSeedRetry(KpiAcquisitionState state, long nowElapsedMs) {
        if (state.seedAttempts <= 0 || state.seedAttempts > SEED_RETRY_DELAYS_MS.length) {
            state.nextDueElapsedMs = Long.MAX_VALUE;
        } else {
            state.nextDueElapsedMs = saturatedAdd(nowElapsedMs, SEED_RETRY_DELAYS_MS[state.seedAttempts - 1]);
        }
    }

    CollectorHelperDaemon.BatchResult readHybrid(
        int stream, List<CollectorHelperDaemon.Address> rows, boolean forceAll, Poller poller
    ) throws Throwable {
        long now = nowElapsed();
        List<CollectorHelperDaemon.Address> polls = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        Map<CollectorHelperDaemon.Address, Long> callbackWatermarkBefore = new LinkedHashMap<>();
        long capturedListenerGeneration;
        long capturedSourceEpoch;
        long nextDue = Long.MAX_VALUE;
        synchronized (lock) {
            capturedListenerGeneration = callbackListenerGeneration;
            capturedSourceEpoch = sourceEpoch[stream];
            for (int i = 0; i < rows.size(); i++) {
                CollectorHelperDaemon.Address row = rows.get(i);
                AcquisitionState state = acquisition(stream).computeIfAbsent(row, unused -> newAcquisition(now));
                CallbackValueSource source = sources(stream).get(row);
                if (source != null && !validCandidate(stream, source)) {
                    sources(stream).remove(row);
                    state.sourceAvailable = false;
                    state.seedAttempts = 0;
                    state.nextSeedReason = seedReason(state);
                    state.nextDueElapsedMs = now;
                }
                boolean periodicException = stream == CollectorHelperProtocol.STREAM_SECONDARY &&
                    SECONDARY_POLL_SOURCES.contains(row);
                if (!capturePermitted[stream]) {
                    state.nextDueElapsedMs = Long.MAX_VALUE;
                    scheduleAddress(stream, row, Long.MAX_VALUE);
                    continue;
                }
                if (state.sourceAvailable && !periodicException) {
                    state.nextDueElapsedMs = Long.MAX_VALUE;
                    scheduleAddress(stream, row, Long.MAX_VALUE);
                    continue;
                }
                boolean shouldPoll = forceAll
                    ? (periodicException || !state.sourceAvailable)
                    : now >= state.nextDueElapsedMs;
                if (shouldPoll) {
                    polls.add(row);
                    indexes.add(i);
                    callbackWatermarkBefore.put(row, callbackWatermarks(stream).getOrDefault(row, -1L));
                } else {
                    nextDue = Math.min(nextDue, state.nextDueElapsedMs);
                }
            }
        }
        if (polls.isEmpty()) {
            long replyNow = nowElapsed();
            if (nextDue == Long.MAX_VALUE) nextDue = saturatedAdd(replyNow, IDLE_CONTROL_WAKE_MS);
            return new CollectorHelperDaemon.BatchResult(
                CollectorHelperProtocol.STATUS_OK, CollectorHelperProtocol.MODE_NO_WORK, false,
                0, 0, 0, 0, 0L, new CollectorHelperDaemon.ReadValue[0], new int[0],
                true, positiveDelay(replyNow, nextDue), null);
        }
        long readStartedElapsedMs = nowElapsed();
        long readStartedWallMs = wallClock.getAsLong();
        Map<CollectorHelperDaemon.Address, String> getReasons = new LinkedHashMap<>();
        synchronized (lock) {
            for (CollectorHelperDaemon.Address row : polls) {
                AcquisitionState state = acquisition(stream).get(row);
                String reason = stream == CollectorHelperProtocol.STREAM_SECONDARY &&
                    SECONDARY_POLL_SOURCES.contains(row)
                        ? "secondary_poll_exception" : state.nextSeedReason;
                getReasons.put(row, reason);
                if ("initial_seed".equals(reason)) initialSeedGetAttempts = saturatedIncrement(initialSeedGetAttempts);
                else if ("source_recovery".equals(reason)) sourceRecoveryGetAttempts = saturatedIncrement(sourceRecoveryGetAttempts);
                else if ("secondary_poll_exception".equals(reason)) {
                    secondaryExceptionGetAttempts = saturatedIncrement(secondaryExceptionGetAttempts);
                }
            }
        }
        CollectorHelperDaemon.BatchResult polled;
        try {
            polled = poller.poll(polls);
            if (polled == null || polled.values == null || polled.values.length != polls.size()) {
                throw new IllegalStateException("GET result count does not match attempted rows");
            }
            for (CollectorHelperDaemon.ReadValue value : polled.values) {
                if (value == null) throw new IllegalStateException("GET returned a missing row result");
            }
        } catch (Throwable error) {
            boolean canceled = false;
            try { canceled = poller.canceled(); }
            catch (Throwable checkError) {
                if (checkError != error) error.addSuppressed(checkError);
            }
            rescheduleFailedPoll(stream, polls, capturedSourceEpoch, canceled);
            throw error;
        }
        long readCompletedElapsedMs = nowElapsed();
        long readCompletedWallMs = wallClock.getAsLong();
        CollectorHelperDaemon.ReadValue[] lineagedValues = new CollectorHelperDaemon.ReadValue[polled.values.length];
        List<TelemetryCallbackBatch> displaced = new ArrayList<>();
        synchronized (lock) {
            long readIdSequence = readIdentitySequence[stream]++;
            String readIdentity = "get:" + bootId.length() + ":" + bootId + ":" + generation.length() + ":" +
                generation + ":" + stream + ":" + capturedSourceEpoch + ":" + readIdSequence;
            for (int resultIndex = 0; resultIndex < polls.size(); resultIndex++) {
                CollectorHelperDaemon.Address row = polls.get(resultIndex);
                AcquisitionState state = acquisition(stream).get(row);
                CallbackValueSource current = sources(stream).get(row);
                CollectorHelperDaemon.ReadValue actual = polled.values[resultIndex];
                long watermarkStart = callbackWatermarkBefore.getOrDefault(row, -1L);
                long watermarkEnd = callbackWatermarks(stream).getOrDefault(row, -1L);
                boolean supersededByCallback = watermarkEnd > watermarkStart;
                long recordSequence = eventSequence[stream]++;
                HelperGetLineage lineage = new HelperGetLineage(
                    bootId, generation, stream, capturedSourceEpoch, readIdentity, recordSequence,
                    readStartedWallMs, readStartedElapsedMs, readCompletedWallMs, readCompletedElapsedMs,
                    watermarkStart, watermarkEnd, supersededByCallback);
                boolean nativeSuccess = actual.status == CollectorHelperProtocol.STATUS_OK && actual.raw != null;
                boolean usable = nativeSuccess && addressValueUsable(row,
                    row.tx == CollectorHelperProtocol.AUTO_TX_FLOAT
                        ? TelemetryCallbackBatch.TYPE_FLOAT : TelemetryCallbackBatch.TYPE_INT, actual.raw);
                int valueStatus = nativeSuccess && !usable
                    ? CollectorHelperProtocol.STATUS_READ_ERROR : actual.status;
                String valueError = nativeSuccess && !usable
                    ? "typed or sentinel telemetry value rejected: raw=0x" +
                        Integer.toUnsignedString(actual.raw, 16) : actual.error;
                lineagedValues[resultIndex] = new CollectorHelperDaemon.ReadValue(
                    valueStatus, actual.raw, valueError, null, lineage);
                boolean registrationAccepted = platform.accepts(new NativeKey(row.dev, row.fid));
                boolean periodicException = "secondary_poll_exception".equals(getReasons.get(row));
                boolean sourceEpochCurrent = capturedSourceEpoch == sourceEpoch[stream];
                boolean listenerCurrent = capturedListenerGeneration == callbackListenerGeneration;
                if (supersededByCallback) {
                    // Keep the GET raw/lineage, but the raced callback owns the seed decision.
                    state.nextSeedReason = seedReason(state);
                    if (periodicException) {
                        scheduleAfterAvailable(stream, row, state, readCompletedElapsedMs);
                    } else if (!state.sourceAvailable) {
                        if (state.seedAttempts < 3) state.seedAttempts++;
                        scheduleSeedRetry(stream, row, state, readCompletedElapsedMs);
                    }
                    else scheduleAfterAvailable(stream, row, state, readCompletedElapsedMs);
                } else if (usable && registrationAccepted && sourceEpochCurrent && listenerCurrent) {
                    if (!state.sourceAvailable) enqueueGetAvailableLocked(
                        stream, row, lineage, readCompletedWallMs, readCompletedElapsedMs, displaced);
                    state.sourceAvailable = true;
                    state.seededOnce = true;
                    state.seedAttempts = 0;
                    scheduleAfterAvailable(stream, row, state, readCompletedElapsedMs);
                    state.nextSeedReason = seedReason(state);
                } else if (state.sourceAvailable && !registrationAccepted && sourceEpochCurrent && listenerCurrent) {
                    enqueueLostAddressLocked(stream, row, "native_registration_lost",
                        "GET value retained, but no accepted native callback registration exists",
                        readCompletedWallMs, readCompletedElapsedMs, displaced);
                    state.sourceAvailable = false;
                    state.seedAttempts = periodicException ? 0 : 1;
                    state.nextSeedReason = seedReason(state);
                    if (periodicException) scheduleAfterAvailable(stream, row, state, readCompletedElapsedMs);
                    else scheduleSeedRetry(stream, row, state, readCompletedElapsedMs);
                } else if (state.sourceAvailable && nativeSuccess && !usable && sourceEpochCurrent && listenerCurrent) {
                    enqueueLostAddressLocked(stream, row, "invalid_get_value", valueError,
                        readCompletedWallMs, readCompletedElapsedMs, displaced);
                    state.sourceAvailable = false;
                    state.seedAttempts = periodicException ? 0 : 1;
                    state.nextSeedReason = seedReason(state);
                    if (periodicException) scheduleAfterAvailable(stream, row, state, readCompletedElapsedMs);
                    else scheduleSeedRetry(stream, row, state, readCompletedElapsedMs);
                } else if (periodicException && sourceEpochCurrent && listenerCurrent) {
                    // The nine explicit poll-only addresses keep their 500 ms cadence even
                    // when callbacks are unavailable or the current value is invalid.
                    scheduleAfterAvailable(stream, row, state, readCompletedElapsedMs);
                } else if (state.sourceAvailable && sourceEpochCurrent && listenerCurrent) {
                    // A failed periodic GET is a failed observation; it does not prove the
                    // accepted callback source has stopped producing valid values.
                    scheduleAfterAvailable(stream, row, state, readCompletedElapsedMs);
                } else if (!state.sourceAvailable && sourceEpochCurrent && listenerCurrent) {
                    if (state.seedAttempts < 3) state.seedAttempts++;
                    state.nextSeedReason = seedReason(state);
                    scheduleSeedRetry(stream, row, state, readCompletedElapsedMs);
                } else {
                    scheduleAddress(stream, row, Long.MAX_VALUE);
                }
                nextDue = Math.min(nextDue, state.nextDueElapsedMs);
            }
            for (TelemetryCallbackBatch batch : displaced) submitBatch(batch);
        }
        long replyNow = nowElapsed();
        int[] requestedIndices = new int[indexes.size()];
        for (int i = 0; i < indexes.size(); i++) requestedIndices[i] = indexes.get(i);
        if (nextDue == Long.MAX_VALUE) nextDue = saturatedAdd(replyNow, IDLE_CONTROL_WAKE_MS);
        return new CollectorHelperDaemon.BatchResult(polled.batchStatus, polled.mode, polled.nativeAvailable,
            polled.nativeGroupCount, polled.fallbackGroupCount, polled.fallbackReadCount,
            polled.groupFailureCount, polled.elapsedMs, lineagedValues, requestedIndices,
            false, positiveDelay(replyNow, nextDue), polled.error);
    }

    private void rescheduleFailedPoll(
        int stream, List<CollectorHelperDaemon.Address> rows, long capturedSourceEpoch, boolean canceled
    ) {
        long now = nowElapsed();
        synchronized (lock) {
            for (CollectorHelperDaemon.Address row : rows) {
                AcquisitionState state = acquisition(stream).get(row);
                if (state == null) continue;
                boolean periodicException = stream == CollectorHelperProtocol.STREAM_SECONDARY &&
                    SECONDARY_POLL_SOURCES.contains(row);
                if (capturedSourceEpoch != sourceEpoch[stream] || !capturePermitted[stream]) {
                    state.nextDueElapsedMs = Long.MAX_VALUE;
                    scheduleAddress(stream, row, Long.MAX_VALUE);
                } else if (canceled) {
                    state.nextDueElapsedMs = now;
                    scheduleAddress(stream, row, now);
                } else if (periodicException || state.sourceAvailable) {
                    scheduleAfterAvailable(stream, row, state, now);
                } else {
                    if (state.seedAttempts < 3) state.seedAttempts++;
                    state.nextSeedReason = seedReason(state);
                    scheduleSeedRetry(stream, row, state, now);
                }
            }
        }
    }

    CollectorHelperDaemon.BatchResult readDueSecondary(Poller poller) throws Throwable {
        List<Integer> ordinals = takeDueSecondaryOrdinals(CollectorHelperProtocol.SECONDARY_CHUNK_SIZE);
        if (ordinals.isEmpty()) {
            long now = nowElapsed();
            return noWorkResult(secondaryDue.nextDelayMs(now, IDLE_CONTROL_WAKE_MS));
        }
        List<CollectorHelperDaemon.Address> rows = new ArrayList<>(ordinals.size());
        for (Integer ordinal : ordinals) rows.add(secondaryAddressAt(ordinal));
        CollectorHelperDaemon.BatchResult result = readHybrid(
            CollectorHelperProtocol.STREAM_SECONDARY, rows, true, poller);
        int[] requestedIndices = new int[result.requestedIndices.length];
        for (int index = 0; index < requestedIndices.length; index++) {
            requestedIndices[index] = ordinals.get(result.requestedIndices[index]);
        }
        long remainingDueDelay = secondaryDue.nextDelayMs(nowElapsed(), IDLE_CONTROL_WAKE_MS);
        long nextDueDelay = result.nextDueDelayMs <= 0L ? remainingDueDelay
            : Math.min(result.nextDueDelayMs, remainingDueDelay);
        return new CollectorHelperDaemon.BatchResult(
            result.batchStatus, result.mode, result.nativeAvailable, result.nativeGroupCount,
            result.fallbackGroupCount, result.fallbackReadCount, result.groupFailureCount,
            result.elapsedMs, result.values, requestedIndices, result.noWork,
            Math.max(1L, nextDueDelay), result.error);
    }

    private static CollectorHelperDaemon.BatchResult noWorkResult(long nextDueDelayMs) {
        return new CollectorHelperDaemon.BatchResult(
            CollectorHelperProtocol.STATUS_OK, CollectorHelperProtocol.MODE_NO_WORK, false,
            0, 0, 0, 0, 0L, new CollectorHelperDaemon.ReadValue[0], new int[0],
            true, Math.max(1L, nextDueDelayMs), null);
    }

    private void scheduleAfterAvailable(
        int stream, CollectorHelperDaemon.Address row, AcquisitionState state, long nowElapsedMs
    ) {
        state.nextDueElapsedMs = stream == CollectorHelperProtocol.STREAM_SECONDARY &&
            SECONDARY_POLL_SOURCES.contains(row)
                ? saturatedAdd(nowElapsedMs, SECONDARY_EXCEPTION_POLL_MS) : Long.MAX_VALUE;
        scheduleAddress(stream, row, state.nextDueElapsedMs);
    }

    private void scheduleSeedRetry(
        int stream, CollectorHelperDaemon.Address row, AcquisitionState state, long nowElapsedMs
    ) {
        if (state.seedAttempts <= 0 || state.seedAttempts > SEED_RETRY_DELAYS_MS.length) {
            state.nextDueElapsedMs = Long.MAX_VALUE;
        } else {
            state.nextDueElapsedMs = saturatedAdd(nowElapsedMs, SEED_RETRY_DELAYS_MS[state.seedAttempts - 1]);
        }
        scheduleAddress(stream, row, state.nextDueElapsedMs);
    }

    private void scheduleAddress(int stream, CollectorHelperDaemon.Address row, long dueElapsedMs) {
        Integer ordinal = (stream == CollectorHelperProtocol.STREAM_MAIN
            ? mainOrdinals : secondaryOrdinals).get(row);
        if (ordinal == null) return;
        (stream == CollectorHelperProtocol.STREAM_MAIN ? mainDue : secondaryDue)
            .schedule(ordinal, dueElapsedMs);
    }

    private void enqueueGetAvailableLocked(
        int stream, CollectorHelperDaemon.Address row, HelperGetLineage lineage,
        long wallMs, long elapsedMs, List<TelemetryCallbackBatch> displaced
    ) {
        long boundarySequence = eventSequence[stream]++;
        TelemetryCallbackBatch.SourceBoundary boundary = new TelemetryCallbackBatch.SourceBoundary(
            boundarySequence, TelemetryCallbackBatch.SourceBoundary.SCOPE_ADDRESS,
            TelemetryCallbackBatch.SourceBoundary.STATE_AVAILABLE, row.dev, row.fid, row.tx,
            wallMs, elapsedMs, "get_seed", "native registration accepted; real GET value passed typed validity",
            "get", lineage.readIdentity, lineage.recordSequence);
        TelemetryCallbackQueue.OfferResult offered = queues[stream].offerBoundary(boundary, epochs[stream]);
        if (offered == TelemetryCallbackQueue.OfferResult.FLUSH_EPOCH_FIRST) {
            displaced.addAll(drainAllLocked(stream));
            offered = queues[stream].offerBoundary(boundary, epochs[stream]);
        }
        if (offered == TelemetryCallbackQueue.OfferResult.ACCEPTED) scheduleFlushLocked(stream, 0L);
        else host.recordLoss(stream, new TelemetryCallbackQueue.Loss(
            1L, wallMs, wallMs, "source_available_boundary_rejected"));
    }

    private void enqueueLostAddressLocked(
        int stream, CollectorHelperDaemon.Address row, String reason, String diagnostic,
        long wallMs, long elapsedMs, List<TelemetryCallbackBatch> displaced
    ) {
        long sequence = eventSequence[stream]++;
        TelemetryCallbackBatch.SourceBoundary boundary = new TelemetryCallbackBatch.SourceBoundary(
            sequence, TelemetryCallbackBatch.SourceBoundary.SCOPE_ADDRESS,
            TelemetryCallbackBatch.SourceBoundary.STATE_LOST, row.dev, row.fid, row.tx,
            wallMs, elapsedMs, reason, diagnostic, null, null, null);
        TelemetryCallbackQueue.OfferResult offered = queues[stream].offerBoundary(boundary, epochs[stream]);
        if (offered == TelemetryCallbackQueue.OfferResult.FLUSH_EPOCH_FIRST) {
            displaced.addAll(drainAllLocked(stream));
            offered = queues[stream].offerBoundary(boundary, epochs[stream]);
        }
        if (offered == TelemetryCallbackQueue.OfferResult.ACCEPTED) scheduleFlushLocked(stream, 0L);
        else host.recordLoss(stream, new TelemetryCallbackQueue.Loss(1L, wallMs, wallMs,
            "source_lost_boundary_rejected"));
    }

    KpiSubscription subscribeKpi(IBinder listener) {
        if (listener == null) return KpiSubscription.failure(
            CollectorHelperProtocol.STATUS_INVALID_REQUEST, "KPI invalidation listener is required");
        KpiSubscriber replaced;
        long subscriptionId;
        synchronized (kpiLock) {
            if (closed) return KpiSubscription.failure(
                CollectorHelperProtocol.STATUS_INVALID_REQUEST, "helper is closing");
            subscriptionId = nextKpiSubscriptionId == Long.MAX_VALUE ? 1L : nextKpiSubscriptionId + 1L;
            IBinder.DeathRecipient death = () -> clearKpiSubscription(subscriptionId);
            try { listener.linkToDeath(death, 0); }
            catch (RemoteException error) {
                return KpiSubscription.failure(
                    CollectorHelperProtocol.STATUS_INVALID_REQUEST, "KPI listener is already dead");
            }
            nextKpiSubscriptionId = subscriptionId;
            replaced = kpiSubscriber;
            kpiSubscriber = new KpiSubscriber(subscriptionId, listener, death);
            kpiSequence = nextSequence(kpiSequence);
            kpiNotificationPending = true;
            kpiNotificationSent = false;
        }
        unlinkKpiSubscriber(replaced);
        scheduleKpiNotification();
        synchronized (kpiLock) {
            return new KpiSubscription(CollectorHelperProtocol.STATUS_OK, subscriptionId,
                bootId, generation, kpiListenerStatus, kpiListenerError, null);
        }
    }

    KpiSnapshot drainKpi(long subscriptionId) {
        synchronized (kpiLock) {
            if (kpiSubscriber == null || kpiSubscriber.id != subscriptionId) {
                return KpiSnapshot.failure(subscriptionId,
                    CollectorHelperProtocol.STATUS_INVALID_KPI_SUBSCRIPTION, "KPI subscription is stale");
            }
            List<KpiValue> values = new ArrayList<>(kpiMailbox.values().size());
            for (CollectorHelperDaemon.Address row : kpiRows) {
                KpiValue value = kpiMailbox.get(row);
                if (value != null) values.add(value.copy());
            }
            kpiNotificationPending = false;
            kpiNotificationSent = false;
            return new KpiSnapshot(CollectorHelperProtocol.STATUS_OK, subscriptionId,
                bootId, generation, kpiSequence, kpiListenerStatus, kpiListenerError, values, null);
        }
    }

    KpiActionResult unsubscribeKpi(long subscriptionId) {
        KpiSubscriber removed;
        synchronized (kpiLock) {
            if (kpiSubscriber == null || kpiSubscriber.id != subscriptionId) {
                return new KpiActionResult(CollectorHelperProtocol.STATUS_INVALID_KPI_SUBSCRIPTION,
                    "KPI subscription is stale");
            }
            removed = kpiSubscriber;
            kpiSubscriber = null;
            kpiNotificationPending = false;
            kpiNotificationSent = false;
        }
        unlinkKpiSubscriber(removed);
        return new KpiActionResult(CollectorHelperProtocol.STATUS_OK, null);
    }

    private void clearKpiSubscription(long subscriptionId) {
        KpiSubscriber removed;
        synchronized (kpiLock) {
            if (kpiSubscriber == null || kpiSubscriber.id != subscriptionId) return;
            removed = kpiSubscriber;
            kpiSubscriber = null;
            kpiNotificationPending = false;
            kpiNotificationSent = false;
        }
        unlinkKpiSubscriber(removed);
    }

    private static void unlinkKpiSubscriber(KpiSubscriber subscriber) {
        if (subscriber != null) subscriber.listener.unlinkToDeath(subscriber.death, 0);
    }

    private void captureKpi(int dev, int fid, int type, int rawBits, byte[] bytes,
                            long wall, long elapsed) {
        NativeKey key = new NativeKey(dev, fid);
        if (!kpiKeys.contains(key)) return;
        boolean captured = false;
        boolean registrationChanged = false;
        synchronized (kpiLock) {
            if (closed) return;
            List<CollectorHelperDaemon.Address> rows = kpiRowsByNative.get(key);
            if (rows == null) return;
            for (CollectorHelperDaemon.Address row : rows) {
                boolean power = row.equals(kpiPowerRow);
                if (!power && !(kpiPowerKnown && kpiPowerOn)) continue;
                KpiAcquisitionState state = kpiAcquisition.get(row);
                if (state == null || !state.registrationAccepted) continue;
                state.callbackWatermark = incrementKpiCounter(state.callbackWatermark);
                String quality = kpiQuality(row, type, rawBits, bytes);
                if (quality != null) {
                    boolean wasAvailable = state.sourceAvailable;
                    boolean wasInFlight = state.inFlight;
                    byte[] retainedBytes = bytes != null && bytes.length <= CollectorHelperProtocol.MAX_KPI_RAW_BYTES
                        ? bytes : null;
                    String diagnostic = "invalid KPI callback: type=" + type + " raw=0x" +
                        Integer.toUnsignedString(rawBits, 16) + (bytes != null ? " bytes=" + bytes.length : "");
                    recordKpiLossLocked(row, CollectorHelperProtocol.STATUS_READ_ERROR, type,
                        type == TelemetryCallbackBatch.TYPE_BYTES ? null : rawBits, retainedBytes,
                        wall, elapsed, quality, "invalid_callback_value", diagnostic,
                        null, -1L, -1L, true);
                    if (wasAvailable) {
                        state.seedAttempts = 0;
                        state.nextSeedReason = "source_recovery";
                        state.nextDueElapsedMs = elapsed;
                    } else if (wasInFlight) {
                        state.seedAttempts++;
                        state.nextSeedReason = state.seededOnce ? "source_recovery" : "initial_seed";
                        scheduleKpiSeedRetry(state, elapsed);
                    }
                    if (power) {
                        boolean wasOn = kpiPowerKnown && kpiPowerOn;
                        kpiPowerKnown = false;
                        kpiPowerOn = false;
                        if (wasOn) {
                            invalidateKpiFieldsLocked("power_source_unavailable",
                                "POWER callback contained an unusable value", wall, elapsed);
                            registrationChanged = true;
                        }
                    } else if (kpiPowerKnown && kpiPowerOn) {
                        state.nextDueElapsedMs = elapsed;
                    }
                    captured = true;
                    continue;
                }
                KpiValue previous = kpiMailbox.get(row);
                // Concurrent callbacks can acquire this lock out of observation order.
                if (previous != null && elapsed < previous.observedElapsedMs) continue;
                state.sourceAvailable = true;
                state.seededOnce = true;
                state.seedAttempts = 0;
                state.nextDueElapsedMs = Long.MAX_VALUE;
                state.inFlight = false;
                state.inFlightRequestId = -1L;
                putKpiValueLocked(row, CollectorHelperProtocol.STATUS_OK, type,
                    type != TelemetryCallbackBatch.TYPE_BYTES, rawBits, bytes, wall, elapsed,
                    CollectorHelperProtocol.KPI_SOURCE_CALLBACK, state.sourceEpoch, "callback_usable",
                    null, -1L, -1L, null, null);
                captured = true;
                if (power) {
                    boolean wasOn = kpiPowerKnown && kpiPowerOn;
                    kpiPowerKnown = true;
                    kpiPowerOn = rawBits > 0;
                    if (!kpiPowerOn && wasOn) {
                        invalidateKpiFieldsLocked("vehicle_power_off",
                            "Valid bodywork power level reported OFF", wall, elapsed);
                        registrationChanged = true;
                    } else if (kpiPowerOn && !wasOn) {
                        scheduleKpiFieldsLocked(elapsed);
                        registrationChanged = true;
                    }
                }
            }
        }
        if (captured) scheduleKpiNotification();
        if (registrationChanged) {
            scheduleRegistration(0L, true);
            updateKpiRegistrationStatus(null);
        }
    }

    private static int expectedNativeType(CollectorHelperDaemon.Address row) {
        return row.tx == CollectorHelperProtocol.AUTO_TX_FLOAT
            ? TelemetryCallbackBatch.TYPE_FLOAT : TelemetryCallbackBatch.TYPE_INT;
    }

    private static String kpiQuality(
        CollectorHelperDaemon.Address row, int type, int rawBits, byte[] bytes
    ) {
        if (bytes != null && bytes.length > CollectorHelperProtocol.MAX_KPI_RAW_BYTES) return "oversize_payload";
        if (bytes != null || type != expectedNativeType(row)) return "type_mismatch";
        if (type == TelemetryCallbackBatch.TYPE_FLOAT &&
            !Float.isFinite(Float.intBitsToFloat(rawBits))) return "non_finite";
        return addressValueUsable(row, type, rawBits) ? null : "invalid_sentinel";
    }

    private void putKpiValueLocked(
        CollectorHelperDaemon.Address row, int status, int nativeType, boolean hasRaw, int rawBits,
        byte[] bytes, long wall, long elapsed, int sourceKind, long sourceEpoch, String quality,
        String sourceIdentity, long sourceStartWallMs, long sourceStartElapsedMs,
        String lossReason, String diagnostic
    ) {
        KpiAcquisitionState state = kpiAcquisition.get(row);
        if (state == null) return;
        state.recordSequence = incrementKpiCounter(state.recordSequence);
        kpiSequence = nextSequence(kpiSequence);
        kpiMailbox.put(row, new KpiValue(row, status, nativeType, hasRaw, rawBits, bytes,
            wall, elapsed, kpiSequence, sourceKind, sourceEpoch, state.recordSequence, quality,
            sourceIdentity, sourceStartWallMs, sourceStartElapsedMs, lossReason, diagnostic));
        kpiNotificationPending = true;
    }

    private void recordKpiLossLocked(
        CollectorHelperDaemon.Address row, int status, int nativeType, Integer rawBits, byte[] bytes,
        long wall, long elapsed, String quality, String reason, String diagnostic,
        String sourceIdentity, long sourceStartWallMs, long sourceStartElapsedMs, boolean advanceEpoch
    ) {
        KpiAcquisitionState state = kpiAcquisition.get(row);
        if (state == null) return;
        if (advanceEpoch) state.sourceEpoch = incrementKpiCounter(state.sourceEpoch);
        state.sourceAvailable = false;
        state.callbackWatermark = incrementKpiCounter(state.callbackWatermark);
        state.inFlight = false;
        state.inFlightRequestId = -1L;
        putKpiValueLocked(row, status, nativeType, rawBits != null, rawBits == null ? 0 : rawBits,
            bytes, wall, elapsed, CollectorHelperProtocol.KPI_SOURCE_LOST, state.sourceEpoch,
            quality, sourceIdentity, sourceStartWallMs, sourceStartElapsedMs, reason, diagnostic);
    }

    private void scheduleKpiFieldsLocked(long nowElapsedMs) {
        for (CollectorHelperDaemon.Address row : kpiFieldRows) {
            KpiAcquisitionState state = kpiAcquisition.get(row);
            if (state == null || state.sourceAvailable) continue;
            state.seedAttempts = 0;
            state.nextSeedReason = state.seededOnce ? "source_recovery" : "initial_seed";
            state.nextDueElapsedMs = nowElapsedMs;
        }
    }

    private void invalidateKpiFieldsLocked(String reason, String diagnostic, long wall, long elapsed) {
        for (CollectorHelperDaemon.Address row : kpiFieldRows) {
            KpiAcquisitionState state = kpiAcquisition.get(row);
            if (state == null) continue;
            KpiValue current = kpiMailbox.get(row);
            if (state.sourceAvailable || (current != null && current.status == CollectorHelperProtocol.STATUS_OK &&
                current.sourceKind != CollectorHelperProtocol.KPI_SOURCE_LOST)) {
                recordKpiLossLocked(row, CollectorHelperProtocol.STATUS_READ_ERROR,
                    expectedNativeType(row), null, null, wall, elapsed, "source_lost", reason,
                    diagnostic, null, -1L, -1L, true);
            } else {
                state.callbackWatermark = incrementKpiCounter(state.callbackWatermark);
                state.inFlight = false;
                state.inFlightRequestId = -1L;
            }
            state.seedAttempts = 0;
            state.nextSeedReason = "source_recovery";
            state.nextDueElapsedMs = Long.MAX_VALUE;
        }
    }

    private static long incrementKpiCounter(long value) {
        if (value == Long.MAX_VALUE) throw new IllegalStateException("KPI source sequence exhausted");
        return value + 1L;
    }

    private void scheduleKpiNotification() {
        synchronized (kpiLock) {
            if (closed || kpiSubscriber == null || !kpiNotificationPending ||
                kpiNotificationSent || kpiNotificationQueued) return;
            kpiNotificationQueued = true;
        }
        try { kpiNotificationWorker.execute(this::sendKpiInvalidation); }
        catch (RuntimeException error) {
            synchronized (kpiLock) { kpiNotificationQueued = false; }
            noteKpiRegistration(CollectorHelperProtocol.KPI_LISTENER_FAILED,
                "KPI invalidation worker unavailable");
        }
    }

    private void sendKpiInvalidation() {
        KpiSubscriber subscriber;
        long sequence;
        synchronized (kpiLock) {
            subscriber = kpiSubscriber;
            if (closed || subscriber == null || !kpiNotificationPending || kpiNotificationSent) {
                kpiNotificationQueued = false;
                return;
            }
            sequence = kpiSequence;
            kpiNotificationSent = true;
        }
        try {
            kpiInvalidationSender.send(subscriber.listener, subscriber.id, bootId, generation, sequence);
        } catch (Throwable error) {
            clearKpiSubscription(subscriber.id);
        } finally {
            synchronized (kpiLock) { kpiNotificationQueued = false; }
            scheduleKpiNotification();
        }
    }

    private void noteKpiRegistration(int status, String error) {
        boolean changed = false;
        synchronized (kpiLock) {
            if (kpiListenerStatus != status || !java.util.Objects.equals(kpiListenerError, error)) {
                kpiListenerStatus = status;
                kpiListenerError = error;
                kpiSequence = nextSequence(kpiSequence);
                kpiNotificationPending = true;
                changed = true;
            }
        }
        if (changed) scheduleKpiNotification();
    }

    private static Set<CollectorHelperDaemon.Address> kpiSources(Set<String> keys) {
        Set<CollectorHelperDaemon.Address> rows = new LinkedHashSet<>();
        for (DirectFidEntry entry : DirectFidRegistry.INSTANCE.getEntries()) {
            if (keys.contains(entry.getKey())) {
                rows.add(new CollectorHelperDaemon.Address(entry.getTx(), entry.getDev(), entry.getFid()));
            }
        }
        return Collections.unmodifiableSet(rows);
    }

    private static CollectorHelperDaemon.Address registryAddress(String key) {
        for (DirectFidEntry entry : DirectFidRegistry.INSTANCE.getEntries()) {
            if (key.equals(entry.getKey())) {
                return new CollectorHelperDaemon.Address(entry.getTx(), entry.getDev(), entry.getFid());
            }
        }
        return null;
    }

    private static Set<CollectorHelperDaemon.Address> allKpiSources(
        Set<CollectorHelperDaemon.Address> fields, CollectorHelperDaemon.Address power
    ) {
        Set<CollectorHelperDaemon.Address> rows = new LinkedHashSet<>(fields);
        if (power != null) rows.add(power);
        return Collections.unmodifiableSet(rows);
    }

    private static List<CollectorHelperDaemon.Address> filterKpiRows(
        List<CollectorHelperDaemon.Address> rows, Set<CollectorHelperDaemon.Address> selected
    ) {
        List<CollectorHelperDaemon.Address> filtered = new ArrayList<>();
        for (CollectorHelperDaemon.Address row : rows) if (selected.contains(row)) filtered.add(row);
        return Collections.unmodifiableList(filtered);
    }

    private static Map<NativeKey, List<CollectorHelperDaemon.Address>> indexKpiRowsByNative(
        List<CollectorHelperDaemon.Address> rows
    ) {
        Map<NativeKey, List<CollectorHelperDaemon.Address>> indexed = new LinkedHashMap<>();
        for (CollectorHelperDaemon.Address row : rows) {
            NativeKey key = new NativeKey(row.dev, row.fid);
            List<CollectorHelperDaemon.Address> values = indexed.get(key);
            if (values == null) {
                values = new ArrayList<>();
                indexed.put(key, values);
            }
            values.add(row);
        }
        for (Map.Entry<NativeKey, List<CollectorHelperDaemon.Address>> entry : indexed.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Set<CollectorHelperDaemon.Address> secondaryPollSources() {
        Set<CollectorHelperDaemon.Address> rows = new LinkedHashSet<>();
        rows.add(new CollectorHelperDaemon.Address(5, 1001, 947912744));
        rows.add(new CollectorHelperDaemon.Address(5, 1001, 947912764));
        rows.add(new CollectorHelperDaemon.Address(5, 1001, 947912766));
        rows.add(new CollectorHelperDaemon.Address(5, 1007, 1138753568));
        rows.add(new CollectorHelperDaemon.Address(5, 1007, 1138753576));
        rows.add(new CollectorHelperDaemon.Address(5, 1007, 1138753594));
        rows.add(new CollectorHelperDaemon.Address(5, 1023, 1138753589));
        rows.add(new CollectorHelperDaemon.Address(5, 1043, 573571092));
        rows.add(new CollectorHelperDaemon.Address(7, 1043, 573571104));
        return Collections.unmodifiableSet(rows);
    }

    private static Map<CollectorHelperDaemon.Address, DirectFidEntry> directEntryMap() {
        Map<CollectorHelperDaemon.Address, DirectFidEntry> entries = new LinkedHashMap<>();
        for (DirectFidEntry entry : DirectFidRegistry.INSTANCE.getEntries()) {
            entries.put(new CollectorHelperDaemon.Address(entry.getTx(), entry.getDev(), entry.getFid()), entry);
        }
        return Collections.unmodifiableMap(entries);
    }

    private static List<CollectorHelperDaemon.Address> eligibleKpiRows(
        List<CollectorHelperDaemon.Address> main, List<CollectorHelperDaemon.Address> secondary
    ) {
        Set<CollectorHelperDaemon.Address> allowed = new LinkedHashSet<>(main);
        allowed.addAll(secondary);
        List<CollectorHelperDaemon.Address> result = new ArrayList<>();
        for (CollectorHelperDaemon.Address address : KPI_SOURCES) if (allowed.contains(address)) result.add(address);
        return Collections.unmodifiableList(result);
    }

    private static long nextSequence(long value) { return value == Long.MAX_VALUE ? 1L : value + 1L; }

    private static void sendBinderInvalidation(IBinder listener, long subscriptionId,
                                                String bootId, String generation, long sequence) throws Throwable {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CollectorHelperProtocol.KPI_LISTENER_DESCRIPTOR);
            data.writeLong(subscriptionId);
            data.writeString(bootId);
            data.writeString(generation);
            data.writeLong(sequence);
            if (!listener.transact(CollectorHelperProtocol.KPI_TX_INVALIDATED, data, reply, 0) ||
                reply.readInt() != CollectorHelperProtocol.STATUS_OK) {
                throw new RemoteException("KPI invalidation delivery failed");
            }
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void updateStream(int stream, HelperStreamRuntimeState.StreamView view,
                              List<TelemetryCallbackBatch> displaced) {
        boolean oldDesired = desired[stream];
        boolean oldCapture = capturePermitted[stream];
        boolean desiredChanged = desired[stream] != view.desired;
        boolean plannedEpochChanged = plannedEpochs[stream] != view.epoch;
        boolean nextCapture = view.desired && (view.activeLease || view.autonomyAllowed);
        boolean sourceBreak = (oldDesired && !view.desired) || (oldCapture && !nextCapture);
        boolean transportEpochChanged = epochs[stream] != view.epoch;
        if (sourceBreak) {
            captureEnabled[stream] = false;
            sourceLossLocked(stream, !view.desired ? "explicit_stop" : "capture_ineligible",
                "capture eligibility changed from true to false", wallClock.getAsLong(), nowElapsed(), true, displaced);
        } else if (transportEpochChanged) {
            // Transport claim/pause epochs fence raw batches, but are not source epochs.
            captureEnabled[stream] = false;
            displaced.addAll(drainAllLocked(stream));
        }
        desired[stream] = view.desired;
        plannedEpochs[stream] = view.epoch;
        epochs[stream] = view.epoch;
        capturePermitted[stream] = nextCapture;
        captureEnabled[stream] = capturePermitted[stream];
        if (!oldCapture && nextCapture) scheduleStreamRows(stream, nowElapsed());
    }

    private void sourceLossLocked(
        int stream, String reason, String diagnostic, long wallMs, long elapsedMs,
        boolean emitBoundary, List<TelemetryCallbackBatch> displaced
    ) {
        boolean hadAvailable = false;
        for (AcquisitionState state : acquisition(stream).values()) hadAvailable |= state.sourceAvailable;
        displaced.addAll(drainAllLocked(stream));
        if (emitBoundary && hadAvailable) {
            long sequence = eventSequence[stream]++;
            TelemetryCallbackBatch.SourceBoundary boundary = new TelemetryCallbackBatch.SourceBoundary(
                sequence, TelemetryCallbackBatch.SourceBoundary.SCOPE_STREAM,
                TelemetryCallbackBatch.SourceBoundary.STATE_LOST, 0, 0, 0, wallMs, elapsedMs,
                reason, diagnostic, null, null, null);
            TelemetryCallbackQueue.OfferResult offered = queues[stream].offerBoundary(boundary, epochs[stream]);
            if (offered == TelemetryCallbackQueue.OfferResult.ACCEPTED) {
                TelemetryCallbackBatch batch = drainLocked(stream, false);
                if (batch != null) displaced.add(batch);
            } else {
                host.recordLoss(stream, new TelemetryCallbackQueue.Loss(1L, wallMs, wallMs,
                    "source_lost_boundary_" + offered.name().toLowerCase(java.util.Locale.ROOT)));
            }
        }
        sourceEpoch[stream] = nextEpoch(sourceEpoch[stream]);
        queues[stream] = new TelemetryCallbackQueue(stream, bootId, generation, sourceEpoch[stream]);
        sources(stream).clear();
        callbackWatermarks(stream).clear();
        for (Map.Entry<CollectorHelperDaemon.Address, AcquisitionState> item : acquisition(stream).entrySet()) {
            AcquisitionState state = item.getValue();
            state.sourceAvailable = false;
            state.seedAttempts = 0;
            state.nextSeedReason = seedReason(state);
            state.nextDueElapsedMs = Long.MAX_VALUE;
            scheduleAddress(stream, item.getKey(), Long.MAX_VALUE);
        }
    }

    private void scheduleStreamRows(int stream, long dueElapsedMs) {
        List<CollectorHelperDaemon.Address> rows = stream == CollectorHelperProtocol.STREAM_MAIN
            ? mainRows : secondaryRows;
        for (int ordinal = 0; ordinal < rows.size(); ordinal++) {
            CollectorHelperDaemon.Address row = rows.get(ordinal);
            AcquisitionState state = acquisition(stream).computeIfAbsent(row, unused -> newAcquisition(dueElapsedMs));
            if (!state.sourceAvailable) {
                state.seedAttempts = 0;
                state.nextSeedReason = seedReason(state);
                state.nextDueElapsedMs = dueElapsedMs;
                scheduleAddress(stream, row, dueElapsedMs);
            } else {
                scheduleAfterAvailable(stream, row, state, dueElapsedMs);
            }
        }
    }

    private void markRegistrationUnavailable(int stream) {
        for (AcquisitionState state : acquisition(stream).values()) state.registrationAccepted = false;
    }

    private static boolean sameView(HelperStreamRuntimeState.StreamView left,
                                    HelperStreamRuntimeState.StreamView right) {
        return left != null && right != null && left.desired == right.desired &&
            left.capturePaused == right.capturePaused && left.epoch == right.epoch &&
            left.activeLease == right.activeLease && left.autonomyAllowed == right.autonomyAllowed;
    }

    private boolean canCapture(int stream, NativeKey key) {
        if (!captureEnabled[stream] || !host.captureAllowed(stream)) return false;
        return stream == CollectorHelperProtocol.STREAM_MAIN
            ? mainKeys.contains(key) : secondaryKeys.contains(key);
    }

    private void onCallback(int dev, int fid, int type, int rawBits, byte[] bytes) {
        long wall = wallClock.getAsLong();
        long elapsed = nowElapsed();
        captureKpi(dev, fid, type, rawBits, bytes, wall, elapsed);
        synchronized (lock) {
            if (closed) return;
            callbacksReceived++;
            NativeKey key = new NativeKey(dev, fid);
            String quality = scalarUsable(key, type, rawBits) ? "callback" :
                type == TelemetryCallbackBatch.TYPE_FLOAT && !Float.isFinite(Float.intBitsToFloat(rawBits))
                    ? "non_finite" : scalarTypeMatches(key, type) ? "invalid_sentinel" : "type_mismatch";
            // Both independently stored streams need shared-key changes once periodic GET is gone.
            for (int stream = CollectorHelperProtocol.STREAM_MAIN;
                 stream <= CollectorHelperProtocol.STREAM_SECONDARY; stream++) {
                if (!canCapture(stream, key)) continue;
                long sequence = eventSequence[stream]++;
                for (CollectorHelperDaemon.Address address : scalarAddresses.getOrDefault(key, Collections.emptyList())) {
                    if (addressTypeMatches(address, type)) callbackWatermarks(stream).put(address, sequence);
                }
                if (bytes != null && bytes.length > TelemetryCallbackBatch.MAX_BYTES - 2048) {
                    queues[stream].noteDroppedRecord(sequence, epochs[stream], wall, elapsed,
                        "oversize_callback", "oversize callback payload rejected before raw batching");
                    scheduleFlushLocked(stream, 0L);
                    continue;
                }
                TelemetryCallbackBatch.Event event = new TelemetryCallbackBatch.Event(
                    sequence, dev, fid, type, rawBits, bytes, wall, elapsed, null, quality);
                long reserved = inFlightBytes[stream] + host.liveBytes(stream);
                TelemetryCallbackQueue.OfferResult offered = queues[stream].offerDetailed(event, epochs[stream], reserved);
                if (offered == TelemetryCallbackQueue.OfferResult.FLUSH_EPOCH_FIRST) {
                    queues[stream].noteRejected(event, "epoch_fence");
                }
                scheduleFlushLocked(stream, queues[stream].nextFlushDelayMs(elapsed));
            }
        }
    }

    private void onListenerError(int code, String message) {
        if (closed) return;
        List<TelemetryCallbackBatch> displaced = new ArrayList<>();
        long wall = wallClock.getAsLong();
        long elapsed = nowElapsed();
        synchronized (lock) {
            callbackListenerGeneration = nextEpoch(callbackListenerGeneration);
            String diagnostic = "BYDAuto listener error " + code + ": " + String.valueOf(message);
            sourceLossLocked(CollectorHelperProtocol.STREAM_MAIN, "native_listener_failure", diagnostic,
                wall, elapsed, true, displaced);
            sourceLossLocked(CollectorHelperProtocol.STREAM_SECONDARY, "native_listener_failure", diagnostic,
                wall, elapsed, true, displaced);
            markRegistrationUnavailable(CollectorHelperProtocol.STREAM_MAIN);
            markRegistrationUnavailable(CollectorHelperProtocol.STREAM_SECONDARY);
        }
        boolean kpiChanged = false;
        String kpiDiagnostic = "BYDAuto listener error " + code + ": " + String.valueOf(message);
        synchronized (kpiLock) {
            boolean wasPowerOn = kpiPowerKnown && kpiPowerOn;
            for (CollectorHelperDaemon.Address row : kpiRows) {
                KpiAcquisitionState state = kpiAcquisition.get(row);
                state.registrationAccepted = false;
                if (state.sourceAvailable) {
                    recordKpiLossLocked(row, CollectorHelperProtocol.STATUS_READ_ERROR,
                        expectedNativeType(row), null, null, wall, elapsed, "source_lost",
                        "native_listener_failure", kpiDiagnostic, null, -1L, -1L, true);
                    kpiChanged = true;
                } else {
                    state.inFlight = false;
                    state.inFlightRequestId = -1L;
                }
                if (row.equals(kpiPowerRow)) {
                    state.seedAttempts = 0;
                    state.nextSeedReason = "source_recovery";
                    state.nextDueElapsedMs = elapsed;
                }
            }
            kpiPowerKnown = false;
            kpiPowerOn = false;
            if (wasPowerOn) invalidateKpiFieldsLocked("power_source_unavailable",
                "POWER callback listener failed", wall, elapsed);
        }
        for (TelemetryCallbackBatch batch : displaced) submitBatch(batch);
        if (kpiChanged) scheduleKpiNotification();
        try { streamWorkers[CollectorHelperProtocol.STREAM_MAIN].execute(() -> host.noteError(
            "BYDAuto listener error " + code + ": " + String.valueOf(message))); }
        catch (Throwable ignored) { }
        noteKpiRegistration(CollectorHelperProtocol.KPI_LISTENER_FAILED,
            "BYDAuto listener error " + code + ": " + String.valueOf(message));
        restartListener = true;
        retryReason = "listener_error_" + code;
        scheduleRegistration(RETRY_MS[Math.min(retryIndex++, RETRY_MS.length - 1)], false);
    }

    private void scheduleFlushLocked(int stream, long delayMs) {
        if (closed || delayMs < 0L) return;
        // New callbacks must not turn a blocked full queue's retry into per-event work.
        if (fenceActive[stream] || host.publishingHeld(stream)) delayMs = Math.max(100L, delayMs);
        long deadline = nowElapsed() + delayMs;
        if (flushTasks[stream] != null && flushDeadlines[stream] <= deadline) return;
        if (flushTasks[stream] != null) flushTasks[stream].cancel(false);
        long token = ++flushTokens[stream];
        flushDeadlines[stream] = deadline;
        flushTasks[stream] = streamWorkers[stream].schedule(() -> {
            synchronized (lock) {
                if (token != flushTokens[stream]) return;
                flushTasks[stream] = null;
                flushDeadlines[stream] = Long.MAX_VALUE;
            }
            maintenance(stream);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void maintenance(int stream) {
        if (closed) return;
        boolean retry = false;
        try {
            TelemetryCallbackBatch batch;
            TelemetryCallbackQueue.Loss loss;
            synchronized (lock) {
                batch = fenceActive[stream] || host.publishingHeld(stream)
                    ? null : drainLocked(stream, true);
                loss = queues[stream].takeLoss();
                if (loss != null) queueLossCount += loss.count;
            }
            if (loss != null) host.recordLoss(stream, loss);
            if (batch != null) deliverBatch(batch);
            if (!host.appOwns(stream) && host.liveBytes(stream) > 0L) host.spill(stream);
        } catch (Throwable error) {
            retry = true;
            if (!closed) host.noteError("callback maintenance failed: " + describe(error));
        } finally {
            synchronized (lock) {
                long delay = queues[stream].nextFlushDelayMs(nowElapsed());
                // Timed retries are only for actual pending work or a blocked writer.
                if (retry || (!host.appOwns(stream) && host.liveBytes(stream) > 0L) ||
                    (delay >= 0L && (fenceActive[stream] || host.publishingHeld(stream)))) {
                    delay = 100L;
                }
                scheduleFlushLocked(stream, delay);
            }
        }
    }

    private TelemetryCallbackBatch drainLocked(int stream, boolean dueOnly) {
        TelemetryCallbackBatch batch = dueOnly
            ? queues[stream].drainDue(nowElapsed()) : queues[stream].drain();
        if (batch != null) inFlightBytes[stream] += retainedBytes(batch);
        return batch;
    }

    private List<TelemetryCallbackBatch> drainAllLocked(int stream) {
        List<TelemetryCallbackBatch> batches = new ArrayList<>();
        TelemetryCallbackBatch batch;
        while ((batch = drainLocked(stream, false)) != null) batches.add(batch);
        return batches;
    }

    private void submitBatch(TelemetryCallbackBatch batch) {
        try { streamWorkers[batch.stream].execute(() -> deliverBatch(batch)); }
        catch (Throwable error) {
            synchronized (lock) { inFlightBytes[batch.stream] -= retainedBytes(batch); lock.notifyAll(); }
            synchronized (lock) { queueLossCount += batch.recordCount; }
            host.recordLostBatch(batch, "delivery_queue", error);
            sourceLostAfterDurableLoss(batch.stream, "callback_delivery_queue_loss", describe(error));
        }
    }

    private void deliverBatch(TelemetryCallbackBatch batch) {
        try {
            CallbackSpool.AppendResult result = host.deliver(batch);
            if (result == CallbackSpool.AppendResult.SUCCESS || result == CallbackSpool.AppendResult.DUPLICATE) {
                promote(batch);
            } else {
                host.noteError("callback batch was not durably accepted: " + result);
                sourceLostAfterDurableLoss(batch.stream, "callback_spool_loss", result.name());
            }
        } catch (Throwable error) {
            try { host.recordLostBatch(batch, "callback_delivery_exception", error); }
            catch (Throwable lossError) { error.addSuppressed(lossError); }
            host.noteError("callback delivery failed: " + describe(error));
            sourceLostAfterDurableLoss(batch.stream, "callback_delivery_exception", describe(error));
        } finally {
            synchronized (lock) { inFlightBytes[batch.stream] -= retainedBytes(batch); lock.notifyAll(); }
        }
    }

    private void sourceLostAfterDurableLoss(int stream, String reason, String diagnostic) {
        List<TelemetryCallbackBatch> displaced = new ArrayList<>();
        long now = nowElapsed();
        synchronized (lock) {
            sourceLossLocked(stream, reason, diagnostic, wallClock.getAsLong(), now, false, displaced);
            if (capturePermitted[stream]) scheduleStreamRows(stream, now);
        }
        for (TelemetryCallbackBatch batch : displaced) submitBatch(batch);
    }

    void callbackForTest(int dev, int fid, int type, int rawBits, byte[] bytes) {
        onCallback(dev, fid, type, rawBits, bytes);
    }

    void flushForTest(int stream) {
        TelemetryCallbackBatch batch;
        synchronized (lock) { batch = drainLocked(stream, false); }
        if (batch != null) deliverBatch(batch);
    }

    void forceReconcileForTest(CollectorHelperDaemon.Address address) {
        synchronized (lock) {
            long now = nowElapsed();
            AcquisitionState main = mainAcquisition.get(address);
            if (main != null) main.nextDueElapsedMs = now;
            AcquisitionState secondary = secondaryAcquisition.get(address);
            if (secondary != null) secondary.nextDueElapsedMs = now;
        }
    }

    void listenerErrorForTest() { onListenerError(-1, "test"); }

    void maintenanceForTest() {
        maintenance(CollectorHelperProtocol.STREAM_MAIN);
        maintenance(CollectorHelperProtocol.STREAM_SECONDARY);
    }

    DiagnosticsSnapshot diagnosticsSnapshot() {
        long now = nowElapsed();
        synchronized (lock) {
            Set<NativeKey> promoted = new LinkedHashSet<>();
            Set<NativeKey> fallback = new LinkedHashSet<>();
            addAcquisitionDiagnostics(mainSources, mainAcquisition, promoted, fallback);
            addAcquisitionDiagnostics(secondarySources, secondaryAcquisition, promoted, fallback);
            Set<NativeKey> wanted = desiredUnion(
                desired[CollectorHelperProtocol.STREAM_MAIN], desired[CollectorHelperProtocol.STREAM_SECONDARY]);
            TelemetryCallbackQueue.Snapshot main = queues[CollectorHelperProtocol.STREAM_MAIN].snapshot();
            TelemetryCallbackQueue.Snapshot secondary = queues[CollectorHelperProtocol.STREAM_SECONDARY].snapshot();
            return new DiagnosticsSnapshot(
                platform.acceptedCount(), platform.failedCount(), promoted.size(),
                Math.max(0, wanted.size() - promoted.size()), fallback.size(), callbacksReceived,
                main.retainedBytes, age(now, main.oldestElapsedMs), main.highWaterBytes,
                secondary.retainedBytes, age(now, secondary.oldestElapsedMs), secondary.highWaterBytes,
                queueLossCount, retryReason, initialSeedGetAttempts,
                sourceRecoveryGetAttempts, secondaryExceptionGetAttempts);
        }
    }

    private void promote(TelemetryCallbackBatch batch) {
        synchronized (lock) {
            if (batch.formatVersion() >= TelemetryCallbackBatch.VERSION &&
                batch.sourceEpoch != sourceEpoch[batch.stream]) return;
            List<TelemetryCallbackBatch.RawRecord> records = new ArrayList<>(batch.recordCount);
            records.addAll(batch.events);
            records.addAll(batch.boundaries);
            records.sort(Comparator.comparingLong(TelemetryCallbackBatch.RawRecord::recordSequence));
            for (TelemetryCallbackBatch.RawRecord record : records) {
                if (record instanceof TelemetryCallbackBatch.SourceBoundary) {
                    applyBoundary((TelemetryCallbackBatch.SourceBoundary) record, batch.stream);
                    continue;
                }
                TelemetryCallbackBatch.Event event = (TelemetryCallbackBatch.Event) record;
                NativeKey key = new NativeKey(event.device, event.fid);
                List<CollectorHelperDaemon.Address> rows = scalarAddresses.get(key);
                if (rows == null) continue;
                for (CollectorHelperDaemon.Address row : rows) {
                    if (!addressTypeMatches(row, event.nativeType)) continue;
                    CallbackValueSource source = CallbackValueSource.from(batch, event);
                    if (!addressValueUsable(row, event.nativeType, event.rawBits) ||
                        !source.matches(row.tx, row.dev, row.fid, event.rawBits)) {
                        invalidate(row, batch.stream);
                        continue;
                    }
                    Map<CollectorHelperDaemon.Address, CallbackValueSource> streamSources = sources(batch.stream);
                    CallbackValueSource current = streamSources.get(row);
                    if (current == null || newer(source, current)) {
                        streamSources.put(row, source);
                        AcquisitionState state = acquisition(batch.stream).computeIfAbsent(row, unused -> new AcquisitionState());
                        state.sourceAvailable = true;
                        state.seedAttempts = 0;
                        state.seededOnce = true;
                        state.nextSeedReason = seedReason(state);
                        scheduleAfterAvailable(batch.stream, row, state, nowElapsed());
                    }
                }
            }
        }
    }

    private void applyBoundary(TelemetryCallbackBatch.SourceBoundary boundary, int stream) {
        if (boundary.state == TelemetryCallbackBatch.SourceBoundary.STATE_LOST) {
            if (boundary.scope == TelemetryCallbackBatch.SourceBoundary.SCOPE_STREAM) {
                for (CollectorHelperDaemon.Address row : stream == CollectorHelperProtocol.STREAM_MAIN
                    ? mainRows : secondaryRows) invalidate(row, stream);
            } else if (boundary.scope == TelemetryCallbackBatch.SourceBoundary.SCOPE_NATIVE_KEY) {
                List<CollectorHelperDaemon.Address> rows = scalarAddresses.get(
                    new NativeKey(boundary.device, boundary.fid));
                if (rows != null) for (CollectorHelperDaemon.Address row : rows) {
                    if ((stream == CollectorHelperProtocol.STREAM_MAIN && mainOrdinals.containsKey(row)) ||
                        (stream == CollectorHelperProtocol.STREAM_SECONDARY && secondaryOrdinals.containsKey(row))) {
                        invalidate(row, stream);
                    }
                }
            } else {
                invalidate(new CollectorHelperDaemon.Address(boundary.tx, boundary.device, boundary.fid), stream);
            }
            return;
        }
        if (boundary.scope != TelemetryCallbackBatch.SourceBoundary.SCOPE_ADDRESS ||
            !"get".equals(boundary.seedKind)) return;
        CollectorHelperDaemon.Address row = new CollectorHelperDaemon.Address(
            boundary.tx, boundary.device, boundary.fid);
        AcquisitionState state = acquisition(stream).computeIfAbsent(row, unused -> newAcquisition(nowElapsed()));
        state.sourceAvailable = true;
        state.seededOnce = true;
        state.seedAttempts = 0;
        state.nextSeedReason = seedReason(state);
        scheduleAfterAvailable(stream, row, state, nowElapsed());
    }

    private void invalidate(List<CollectorHelperDaemon.Address> rows, int stream) {
        for (CollectorHelperDaemon.Address row : rows) invalidate(row, stream);
    }

    private void invalidate(CollectorHelperDaemon.Address row, int stream) {
        if (!(stream == CollectorHelperProtocol.STREAM_MAIN
            ? mainOrdinals : secondaryOrdinals).containsKey(row)) return;
        CallbackValueSource removed = sources(stream).remove(row);
        AcquisitionState state = acquisition(stream).computeIfAbsent(row, unused -> newAcquisition(nowElapsed()));
        boolean wasAvailable = state.sourceAvailable || removed != null;
        state.sourceAvailable = false;
        if (wasAvailable) {
            state.seedAttempts = 0;
            state.nextSeedReason = seedReason(state);
            state.nextDueElapsedMs = nowElapsed();
            scheduleAddress(stream, row, state.nextDueElapsedMs);
        }
    }

    private void scheduleRegistration(long delayMs, boolean replacePending) {
        synchronized (registrationLock) {
            if (closed) return;
            if (registrationFuture != null && !registrationFuture.isDone()) {
                if (!replacePending) return;
                registrationFuture.cancel(false);
            }
            registrationFuture = registrationWorker.schedule(() -> {
                synchronized (registrationLock) { registrationFuture = null; }
                try { reconcileRegistration(); }
                catch (Throwable error) {
                    retryReason = bound(describe(error), 512);
                    host.noteError("callback registration failed: " + describe(error));
                    updateKpiRegistrationStatus(error);
                    scheduleRegistration(RETRY_MS[Math.min(retryIndex++, RETRY_MS.length - 1)], false);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void reconcileRegistration() throws Throwable {
        Set<NativeKey> target;
        synchronized (lock) {
            target = new LinkedHashSet<>();
            if (desired[CollectorHelperProtocol.STREAM_MAIN]) target.addAll(mainKeys);
            if (desired[CollectorHelperProtocol.STREAM_SECONDARY]) target.addAll(secondaryKeys);
        }
        synchronized (kpiLock) {
            if (kpiPowerRow != null) target.add(new NativeKey(kpiPowerRow.dev, kpiPowerRow.fid));
            if (kpiPowerKnown && kpiPowerOn) target.addAll(nativeKeys(kpiFieldRows));
        }
        if (restartListener) {
            platform.restartListener();
            restartListener = false;
        }
        try {
            platform.apply(target);
        } finally {
            refreshRegistrationState();
        }
        if (platform.available() && platform.failedCount() > 0) {
            synchronized (lock) { retryReason = "native callback registration rejected one or more keys"; }
            scheduleRegistration(RETRY_MS[Math.min(retryIndex++, RETRY_MS.length - 1)], false);
        } else {
            synchronized (lock) { retryIndex = 0; retryReason = null; }
        }
        updateKpiRegistrationStatus(null);
    }

    private void refreshRegistrationState() {
        List<TelemetryCallbackBatch> displaced = new ArrayList<>();
        int newlyAccepted = 0;
        int newlyRejected = 0;
        long now = nowElapsed();
        long wall = wallClock.getAsLong();
        synchronized (lock) {
            for (int stream : new int[]{CollectorHelperProtocol.STREAM_MAIN, CollectorHelperProtocol.STREAM_SECONDARY}) {
                for (CollectorHelperDaemon.Address row : stream == CollectorHelperProtocol.STREAM_MAIN
                    ? mainRows : secondaryRows) {
                    AcquisitionState state = acquisition(stream).computeIfAbsent(row, unused -> newAcquisition(now));
                    boolean accepted = platform.accepts(new NativeKey(row.dev, row.fid));
                    if (accepted == state.registrationAccepted) continue;
                    boolean wasAccepted = state.registrationAccepted;
                    state.registrationAccepted = accepted;
                    if (accepted) {
                        newlyAccepted++;
                        if (!state.sourceAvailable && capturePermitted[stream]) {
                            state.seedAttempts = 0;
                            state.nextSeedReason = seedReason(state);
                            state.nextDueElapsedMs = now;
                            scheduleAddress(stream, row, now);
                        }
                    } else {
                        newlyRejected++;
                        if (wasAccepted && state.sourceAvailable) {
                            enqueueLostAddressLocked(stream, row, "native_registration_lost",
                                "callback FID is no longer accepted by the native listener", wall, now, displaced);
                            sources(stream).remove(row);
                            state.sourceAvailable = false;
                            state.seedAttempts = 0;
                            state.nextSeedReason = seedReason(state);
                            state.nextDueElapsedMs = now;
                            scheduleAddress(stream, row, now);
                        }
                    }
                }
            }
        }
        for (TelemetryCallbackBatch batch : displaced) submitBatch(batch);
        refreshKpiRegistrationState();
        if (newlyAccepted > 0 || newlyRejected > 0) {
            String message = "accepted=" + platform.acceptedCount() + " failed=" + platform.failedCount() +
                " newly_accepted=" + newlyAccepted + " newly_rejected=" + newlyRejected;
            if (!message.equals(lastRegistrationDiagnostic)) {
                lastRegistrationDiagnostic = message;
                host.noteContext("native_subscription_accepted", message);
            }
        }
    }

    private void updateKpiRegistrationStatus(Throwable failure) {
        int accepted = 0;
        int failed = 0;
        int missing = 0;
        List<CollectorHelperDaemon.Address> required = new ArrayList<>();
        if (KPI_POWER_SOURCE != null) required.add(KPI_POWER_SOURCE);
        synchronized (kpiLock) {
            if (kpiPowerKnown && kpiPowerOn) required.addAll(KPI_FIELD_SOURCES);
        }
        for (CollectorHelperDaemon.Address row : required) {
            if (!kpiRows.contains(row)) { missing++; continue; }
            NativeKey key = new NativeKey(row.dev, row.fid);
            if (platform.accepts(key)) accepted++;
            else if (platform.failed(key)) failed++;
        }
        int status;
        String error;
        if (!platform.available()) {
            status = CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE;
            error = "callback listener platform unavailable";
        } else if (failed > 0 || missing > 0) {
            status = CollectorHelperProtocol.KPI_LISTENER_DEGRADED;
            error = failure == null
                ? "callback registration incomplete: failed=" + failed + " missing_whitelist=" + missing
                : describe(failure);
        } else if (failure != null) {
            status = CollectorHelperProtocol.KPI_LISTENER_FAILED;
            error = describe(failure);
        } else if (accepted == required.size()) {
            status = CollectorHelperProtocol.KPI_LISTENER_READY;
            error = null;
        } else {
            status = CollectorHelperProtocol.KPI_LISTENER_REGISTERING;
            error = null;
        }
        noteKpiRegistration(status, error);
    }

    private void refreshKpiRegistrationState() {
        boolean changed = false;
        long now = nowElapsed();
        long wall = wallClock.getAsLong();
        synchronized (kpiLock) {
            for (CollectorHelperDaemon.Address row : kpiRows) {
                KpiAcquisitionState state = kpiAcquisition.get(row);
                boolean active = row.equals(kpiPowerRow) || (kpiPowerKnown && kpiPowerOn);
                boolean accepted = active && platform.accepts(new NativeKey(row.dev, row.fid));
                boolean wasAccepted = state.registrationAccepted;
                state.registrationAccepted = accepted;
                if (!active) {
                    state.inFlight = false;
                    state.inFlightRequestId = -1L;
                    state.nextDueElapsedMs = Long.MAX_VALUE;
                    continue;
                }
                if (accepted && !wasAccepted && !state.sourceAvailable) {
                    state.seedAttempts = 0;
                    state.nextSeedReason = state.seededOnce ? "source_recovery" : "initial_seed";
                    state.nextDueElapsedMs = now;
                } else if (!accepted && wasAccepted && state.sourceAvailable) {
                    recordKpiLossLocked(row, CollectorHelperProtocol.STATUS_READ_ERROR,
                        expectedNativeType(row), null, null, wall, now,
                        "source_lost", "native_registration_lost",
                        "native callback registration is no longer accepted",
                        null, -1L, -1L, true);
                    if (row.equals(kpiPowerRow)) {
                        boolean wasOn = kpiPowerKnown && kpiPowerOn;
                        kpiPowerKnown = false;
                        kpiPowerOn = false;
                        if (wasOn) invalidateKpiFieldsLocked("power_source_unavailable",
                            "POWER callback registration was lost", wall, now);
                    } else {
                        state.seedAttempts = 0;
                        state.nextSeedReason = "source_recovery";
                        state.nextDueElapsedMs = now;
                    }
                    changed = true;
                }
            }
        }
        if (changed) scheduleKpiNotification();
    }

    private static boolean newer(CallbackValueSource candidate, CallbackValueSource current) {
        if (current == null) return true;
        if (!candidate.bootId.equals(current.bootId) ||
            !candidate.helperGeneration.equals(current.helperGeneration)) return true;
        if (candidate.hasSourceEpoch && current.hasSourceEpoch && candidate.sourceEpoch != current.sourceEpoch) {
            return candidate.sourceEpoch > current.sourceEpoch;
        }
        if (candidate.receivedElapsedMs != current.receivedElapsedMs) {
            return candidate.receivedElapsedMs > current.receivedElapsedMs;
        }
        return candidate.stream == current.stream && candidate.hasSourceEpoch == current.hasSourceEpoch &&
            (!candidate.hasSourceEpoch || candidate.sourceEpoch == current.sourceEpoch) &&
            candidate.eventSequence > current.eventSequence;
    }

    private boolean scalarUsable(NativeKey key, int type, int rawBits) {
        List<CollectorHelperDaemon.Address> rows = scalarAddresses.get(key);
        if (rows == null) return false;
        for (CollectorHelperDaemon.Address row : rows) {
            if (addressValueUsable(row, type, rawBits)) return true;
        }
        return false;
    }

    private boolean scalarTypeMatches(NativeKey key, int type) {
        List<CollectorHelperDaemon.Address> rows = scalarAddresses.get(key);
        if (rows == null) return false;
        for (CollectorHelperDaemon.Address row : rows) {
            if (addressTypeMatches(row, type)) return true;
        }
        return false;
    }

    private static boolean addressTypeMatches(CollectorHelperDaemon.Address row, int nativeType) {
        return (row.tx == CollectorHelperProtocol.AUTO_TX_INT && nativeType == TelemetryCallbackBatch.TYPE_INT) ||
            (row.tx == CollectorHelperProtocol.AUTO_TX_FLOAT && nativeType == TelemetryCallbackBatch.TYPE_FLOAT);
    }

    private static boolean addressValueUsable(
        CollectorHelperDaemon.Address row, int nativeType, int rawBits
    ) {
        if (!addressTypeMatches(row, nativeType)) return false;
        if (nativeType == TelemetryCallbackBatch.TYPE_FLOAT) {
            float value = Float.intBitsToFloat(rawBits);
            if (!Float.isFinite(value) || value == 65535.0f) return false;
        } else if (nativeType == TelemetryCallbackBatch.TYPE_INT &&
            (rawBits == -10013 || rawBits == -10011 || rawBits == 65535 || rawBits == 1_048_575)) {
            return false;
        }
        DirectFidEntry entry = DIRECT_ENTRIES.get(row);
        return entry == null || DirectValueDecoders.INSTANCE.decode(entry, rawBits) != null;
    }

    private static long nextEpoch(long value) { return value == Long.MAX_VALUE ? 1L : value + 1L; }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    static long positiveDelay(long nowElapsedMs, long dueElapsedMs) {
        return dueElapsedMs <= nowElapsedMs ? 1L : dueElapsedMs - nowElapsedMs;
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private boolean validCandidate(int stream, CallbackValueSource source) {
        return source != null && source.stream == stream &&
            (source.hasSourceEpoch ? source.sourceEpoch == sourceEpoch[stream] : source.epoch == epochs[stream]) &&
            bootId.equals(source.bootId) && generation.equals(source.helperGeneration) &&
            source.eventSequence >= 0L;
    }

    private Map<CollectorHelperDaemon.Address, CallbackValueSource> sources(int stream) {
        if (stream == CollectorHelperProtocol.STREAM_MAIN) return mainSources;
        if (stream == CollectorHelperProtocol.STREAM_SECONDARY) return secondarySources;
        throw new IllegalArgumentException("invalid stream: " + stream);
    }

    private Map<CollectorHelperDaemon.Address, Long> callbackWatermarks(int stream) {
        if (stream == CollectorHelperProtocol.STREAM_MAIN) return mainCallbackWatermarks;
        if (stream == CollectorHelperProtocol.STREAM_SECONDARY) return secondaryCallbackWatermarks;
        throw new IllegalArgumentException("invalid stream: " + stream);
    }

    private Map<CollectorHelperDaemon.Address, AcquisitionState> acquisition(int stream) {
        if (stream == CollectorHelperProtocol.STREAM_MAIN) return mainAcquisition;
        if (stream == CollectorHelperProtocol.STREAM_SECONDARY) return secondaryAcquisition;
        throw new IllegalArgumentException("invalid stream: " + stream);
    }

    private static AcquisitionState newAcquisition(long nowElapsedMs) {
        AcquisitionState state = new AcquisitionState();
        state.nextDueElapsedMs = nowElapsedMs;
        state.nextSeedReason = "initial_seed";
        return state;
    }

    private static String seedReason(AcquisitionState state) {
        return state.seededOnce ? "source_recovery" : "initial_seed";
    }

    private static Map<CollectorHelperDaemon.Address, Integer> ordinalIndex(
        List<CollectorHelperDaemon.Address> rows
    ) {
        Map<CollectorHelperDaemon.Address, Integer> result = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < rows.size(); ordinal++) {
            if (result.put(rows.get(ordinal), ordinal) != null) {
                throw new IllegalArgumentException("duplicate telemetry address in source catalog: " + rows.get(ordinal));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static void addAcquisitionDiagnostics(
        Map<CollectorHelperDaemon.Address, CallbackValueSource> sources,
        Map<CollectorHelperDaemon.Address, AcquisitionState> states,
        Set<NativeKey> promoted, Set<NativeKey> fallback
    ) {
        for (Map.Entry<CollectorHelperDaemon.Address, AcquisitionState> item : states.entrySet()) {
            NativeKey key = new NativeKey(item.getKey().dev, item.getKey().fid);
            if (item.getValue().sourceAvailable) promoted.add(key);
            else if (sources.containsKey(item.getKey())) fallback.add(key);
        }
    }

    private static Set<NativeKey> nativeKeys(List<CollectorHelperDaemon.Address> rows) {
        LinkedHashSet<NativeKey> result = new LinkedHashSet<>();
        for (CollectorHelperDaemon.Address row : rows) result.add(new NativeKey(row.dev, row.fid));
        return Collections.unmodifiableSet(result);
    }

    private Set<NativeKey> desiredUnion(boolean main, boolean secondary) {
        LinkedHashSet<NativeKey> result = new LinkedHashSet<>();
        if (main) result.addAll(mainKeys);
        if (secondary) result.addAll(secondaryKeys);
        return result;
    }

    private long nowElapsed() { return elapsedClock.getAsLong(); }

    private static long age(long now, long oldest) {
        return oldest < 0L ? -1L : Math.max(0L, now - oldest);
    }

    private static String bound(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit);
    }

    private static Map<NativeKey, List<CollectorHelperDaemon.Address>> scalarAddresses(
        List<CollectorHelperDaemon.Address> main, List<CollectorHelperDaemon.Address> secondary) {
        LinkedHashMap<NativeKey, List<CollectorHelperDaemon.Address>> result = new LinkedHashMap<>();
        for (CollectorHelperDaemon.Address row : concat(main, secondary)) {
            NativeKey key = new NativeKey(row.dev, row.fid);
            List<CollectorHelperDaemon.Address> values = result.computeIfAbsent(key, unused -> new ArrayList<>());
            if (!values.contains(row)) values.add(row);
        }
        return result;
    }

    private static List<CollectorHelperDaemon.Address> concat(List<CollectorHelperDaemon.Address> a,
                                                               List<CollectorHelperDaemon.Address> b) {
        ArrayList<CollectorHelperDaemon.Address> result = new ArrayList<>(a.size() + b.size());
        result.addAll(a); result.addAll(b); return result;
    }

    private static long retainedBytes(TelemetryCallbackBatch batch) {
        long bytes = 1024L;
        for (TelemetryCallbackBatch.Event event : batch.events) bytes += event.retainedBytes();
        for (TelemetryCallbackBatch.SourceBoundary boundary : batch.boundaries) bytes += boundary.retainedBytes();
        return bytes;
    }

    private static TelemetryCallbackQueue.Loss loss(TelemetryCallbackBatch batch, String reason) {
        return new TelemetryCallbackQueue.Loss(batch.recordCount, batch.firstCaptureWallMs, batch.lastCaptureWallMs, reason);
    }

    private static String describe(Throwable error) {
        while ((error instanceof InvocationTargetException) && error.getCause() != null) error = error.getCause();
        return error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
    }

    @Override public void close() {
        closeAndAwait(5_000L);
    }

    boolean closeAndAwait(long timeoutMs) {
        long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        long started = System.nanoTime();
        try {
            if (!closeLock.tryLock(budget, TimeUnit.NANOSECONDS)) return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            boolean interrupted = false;
            if (!streamWorkersShutdown) {
                closed = true;
                KpiSubscriber kpiToClose;
                synchronized (kpiLock) {
                    kpiToClose = kpiSubscriber;
                    kpiSubscriber = null;
                    kpiMailbox.clear();
                    kpiNotificationPending = false;
                    kpiNotificationSent = false;
                }
                unlinkKpiSubscriber(kpiToClose);
                kpiNotificationWorker.shutdownNow();
                synchronized (registrationLock) {
                    if (registrationFuture != null) registrationFuture.cancel(true);
                }
                // Serialize vendor cleanup behind any in-flight registration. A
                // stuck vendor call must not keep the caller past its deadline or
                // prevent independent raw writers from draining.
                registrationWorker.execute(() -> {
                    try { platform.close(); }
                    catch (Throwable error) { host.noteError("callback close failed: " + describe(error)); }
                });
                registrationWorker.shutdown();

                List<TelemetryCallbackBatch> pending = new ArrayList<>();
                synchronized (lock) {
                    for (int stream = 1; stream <= 2; stream++) {
                        ++flushTokens[stream];
                        if (flushTasks[stream] != null) flushTasks[stream].cancel(false);
                    }
                    captureEnabled[CollectorHelperProtocol.STREAM_MAIN] = false;
                    captureEnabled[CollectorHelperProtocol.STREAM_SECONDARY] = false;
                    fenceActive[CollectorHelperProtocol.STREAM_MAIN] = true;
                    fenceActive[CollectorHelperProtocol.STREAM_SECONDARY] = true;
                    pending.addAll(drainAllLocked(CollectorHelperProtocol.STREAM_MAIN));
                    pending.addAll(drainAllLocked(CollectorHelperProtocol.STREAM_SECONDARY));
                }
                for (TelemetryCallbackBatch batch : pending) submitBatch(batch);
                for (int stream = CollectorHelperProtocol.STREAM_MAIN;
                     stream <= CollectorHelperProtocol.STREAM_SECONDARY; stream++) {
                    streamWorkers[stream].shutdown();
                }
                streamWorkersShutdown = true;
            }

            for (int stream = CollectorHelperProtocol.STREAM_MAIN;
                 stream <= CollectorHelperProtocol.STREAM_SECONDARY; stream++) {
                while (!streamWorkers[stream].isTerminated()) {
                    long remaining = budget - (System.nanoTime() - started);
                    if (remaining <= 0L) break;
                    try { streamWorkers[stream].awaitTermination(remaining, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException error) { interrupted = true; }
                }
            }
            streamWorkersTerminated = streamWorkers[CollectorHelperProtocol.STREAM_MAIN].isTerminated() &&
                streamWorkers[CollectorHelperProtocol.STREAM_SECONDARY].isTerminated();
            long remaining = budget - (System.nanoTime() - started);
            if (remaining > 0L && !registrationWorker.isTerminated()) {
                try { registrationWorker.awaitTermination(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException error) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            // The return value is the transport-close safety gate: a stalled
            // vendor unregistration must not prevent persisting already-drained
            // raw memory slots. Process termination remains the outer gate.
            return streamWorkersTerminated;
        } finally {
            closeLock.unlock();
        }
    }

    boolean streamWorkersTerminated() { return streamWorkersTerminated; }

    boolean registrationWorkerStopped() { return registrationWorker.isTerminated(); }

    private static ScheduledExecutorService newStreamWorker(String streamName) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "byd-helper-callback-" + streamName);
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    interface Listener {
        void changed(int dev, int fid, int type, int rawBits, byte[] bytes);
        void error(int code, String message);
    }

    /** Reflection is isolated here so host tests can exercise registration without vendor classes. */
    static class Platform implements AutoCloseable {
        private final Object manager;
        private final HelperDualStreamRuntime.VendorReadScheduler vendor;
        private final Method enable;
        private final Method disable;
        private final Method register;
        private final Method unregister;
        private final Class<?> listenerType;
        private final Map<Integer, LinkedHashSet<Integer>> accepted = new LinkedHashMap<>();
        private final Set<NativeKey> failed = new LinkedHashSet<>();
        private Listener sink;
        private Object proxy;
        private boolean registered;
        private boolean passFailed;
        private volatile int acceptedCount;
        private volatile int failedCount;
        private volatile int targetCount;

        Platform(Object manager, HelperDualStreamRuntime.VendorReadScheduler vendor) throws Exception {
            this.manager = manager; this.vendor = vendor;
            Class<?> managerType = Class.forName("android.hardware.BYDAutoManager");
            listenerType = Class.forName("android.hardware.BYDAutoManager$OnBYDAutoListener");
            enable = managerType.getMethod("enableDevice", int.class, int[].class);
            disable = managerType.getMethod("disableDevice", int.class);
            register = managerType.getMethod("registerListener", listenerType);
            unregister = managerType.getMethod("unregisterListener", listenerType);
        }

        Platform() { manager = null; vendor = null; enable = disable = register = unregister = null; listenerType = null; }

        void setListener(Listener sink) { this.sink = sink; }

        synchronized void apply(Set<NativeKey> target) throws Throwable {
            targetCount = target.size();
            if (manager == null) { refreshDiagnosticCounts(); return; }
            try {
                passFailed = false;
                failed.retainAll(target);
                if (!target.isEmpty() && !registered) register();
                Map<Integer, LinkedHashSet<Integer>> byDevice = new LinkedHashMap<>();
                List<NativeKey> ordered = new ArrayList<>(target);
                ordered.sort(Comparator.comparingInt((NativeKey key) -> key.dev).thenComparingInt(key -> key.fid));
                for (NativeKey key : ordered) byDevice.computeIfAbsent(key.dev, unused -> new LinkedHashSet<>()).add(key.fid);
                Set<Integer> allDevices = new LinkedHashSet<>(accepted.keySet()); allDevices.addAll(byDevice.keySet());
                for (int dev : allDevices) {
                    LinkedHashSet<Integer> wanted = byDevice.getOrDefault(dev, new LinkedHashSet<>());
                    LinkedHashSet<Integer> have = accepted.computeIfAbsent(dev, unused -> new LinkedHashSet<>());
                    try {
                        if (!wanted.containsAll(have)) resetDevice(dev, wanted);
                        else addDevice(dev, wanted, have);
                    } catch (SecurityException denied) {
                        passFailed = true;
                        for (int fid : wanted) if (!have.contains(fid)) failed.add(new NativeKey(dev, fid));
                    }
                    if (wanted.isEmpty()) accepted.remove(dev);
                }
                if (target.isEmpty() && registered) unregister();
                if (passFailed) throw new IllegalStateException("one or more callback FIDs rejected");
            } finally {
                refreshDiagnosticCounts();
            }
        }

        synchronized void restartListener() throws Throwable {
            if (manager == null) return;
            List<Integer> devices = new ArrayList<>(accepted.keySet());
            accepted.clear();
            acceptedCount = 0;
            if (registered) {
                try { unregister(); }
                catch (Throwable ignored) { registered = false; proxy = null; }
            }
            for (int dev : devices) {
                try { callInt(disable, dev); } catch (Throwable ignored) { }
            }
        }

        private void register() throws Throwable {
            InvocationHandler handler = (instance, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(instance);
                    if ("equals".equals(method.getName())) return instance == args[0];
                    return "BYDCollectorCallbackListener";
                }
                if ("onError".equals(method.getName())) sink.error((Integer) args[0], (String) args[1]);
                else if ("onChanged".equals(method.getName())) {
                    Object value = args[2];
                    if (value instanceof Integer) sink.changed((Integer) args[0], (Integer) args[1],
                        TelemetryCallbackBatch.TYPE_INT, (Integer) value, null);
                    else if (value instanceof Float) sink.changed((Integer) args[0], (Integer) args[1],
                        TelemetryCallbackBatch.TYPE_FLOAT, Float.floatToRawIntBits((Float) value), null);
                    else if (value instanceof byte[]) sink.changed((Integer) args[0], (Integer) args[1],
                        TelemetryCallbackBatch.TYPE_BYTES, 0, (byte[]) value);
                }
                return null;
            };
            proxy = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, handler);
            call(() -> { register.invoke(manager, proxy); return null; });
            registered = true;
        }

        private void unregister() throws Throwable {
            call(() -> { unregister.invoke(manager, proxy); return null; });
            registered = false; proxy = null;
        }

        private void resetDevice(int dev, LinkedHashSet<Integer> wanted) throws Throwable {
            int rc = callInt(disable, dev);
            if (rc != 0) throw new IllegalStateException("disable rejected dev=" + dev + " rc=" + rc);
            accepted.get(dev).clear();
            addDevice(dev, wanted, accepted.get(dev));
        }

        private void addDevice(int dev, LinkedHashSet<Integer> wanted, LinkedHashSet<Integer> have) throws Throwable {
            List<Integer> additions = new ArrayList<>();
            for (int fid : wanted) if (!have.contains(fid)) additions.add(fid);
            for (int offset = 0; offset < additions.size(); offset += MAX_ADDITION) {
                subscribe(dev, additions.subList(offset, Math.min(additions.size(), offset + MAX_ADDITION)), have);
                if (offset + MAX_ADDITION < additions.size()) Thread.sleep(40L);
            }
        }

        private void subscribe(int dev, List<Integer> additions, LinkedHashSet<Integer> have) throws Throwable {
            LinkedHashSet<Integer> requested = new LinkedHashSet<>(have); requested.addAll(additions);
            if (requested.size() > MAX_DEVICE_FIDS) throw new IllegalArgumentException("device subscription exceeds 4096");
            int rc;
            try { rc = callInt(enable, dev, requested.stream().mapToInt(Integer::intValue).toArray()); }
            catch (SecurityException denied) { throw denied; }
            if (rc == 0) {
                have.clear();
                have.addAll(requested);
                for (int fid : requested) failed.remove(new NativeKey(dev, fid));
                return;
            }
            if (rc == PERMISSION_DENIED) throw new SecurityException("enableDevice permission denied dev=" + dev);
            rollback(dev, have);
            if (additions.size() == 1) {
                passFailed = true;
                failed.add(new NativeKey(dev, additions.get(0)));
                return;
            }
            int mid = additions.size() / 2;
            subscribe(dev, additions.subList(0, mid), have);
            subscribe(dev, additions.subList(mid, additions.size()), have);
        }

        private void rollback(int dev, LinkedHashSet<Integer> have) throws Throwable {
            if (callInt(disable, dev) != 0) throw new IllegalStateException("subscription rollback disable failed dev=" + dev);
            if (!have.isEmpty() && callInt(enable, dev, have.stream().mapToInt(Integer::intValue).toArray()) != 0) {
                throw new IllegalStateException("subscription rollback restore failed dev=" + dev);
            }
        }

        private int callInt(Method method, Object... args) throws Throwable {
            return (Integer) call(() -> method.invoke(manager, args));
        }

        private <T> T call(HelperDualStreamRuntime.VendorCall<T> action) throws Throwable {
            try { return vendor.call(false, action); }
            catch (InvocationTargetException error) { throw error.getTargetException(); }
        }

        int acceptedCount() { return acceptedCount; }
        int failedCount() { return failedCount; }
        boolean available() { return manager != null; }
        synchronized boolean accepts(NativeKey key) {
            Set<Integer> device = accepted.get(key.dev);
            return device != null && device.contains(key.fid);
        }
        synchronized boolean failed(NativeKey key) { return failed.contains(key); }

        private void refreshDiagnosticCounts() {
            int total = 0;
            for (Set<Integer> values : accepted.values()) total += values.size();
            acceptedCount = total;
            failedCount = Math.max(failed.size(), Math.max(0, targetCount - total));
        }

        @Override public void close() throws Exception {
            try {
                for (int dev : new ArrayList<>(accepted.keySet())) callInt(disable, dev);
                accepted.clear();
                failed.clear();
                acceptedCount = failedCount = targetCount = 0;
                if (registered) unregister();
            } catch (Exception error) { throw error; }
            catch (Throwable error) { throw new Exception(error); }
        }
    }

    static final class KpiSubscriber {
        final long id;
        final IBinder listener;
        final IBinder.DeathRecipient death;
        KpiSubscriber(long id, IBinder listener, IBinder.DeathRecipient death) {
            this.id = id; this.listener = listener; this.death = death;
        }
    }

    static final class KpiSubscription {
        final int status;
        final long subscriptionId;
        final String bootId;
        final String generation;
        final int listenerStatus;
        final String listenerError;
        final String error;
        KpiSubscription(int status, long subscriptionId, String bootId, String generation,
                        int listenerStatus, String listenerError, String error) {
            this.status = status; this.subscriptionId = subscriptionId;
            this.bootId = bootId; this.generation = generation;
            this.listenerStatus = listenerStatus; this.listenerError = listenerError; this.error = error;
        }
        static KpiSubscription failure(int status, String error) {
            return new KpiSubscription(status, 0L, null, null,
                CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE, null, error);
        }
    }

    static final class KpiActionResult {
        final int status;
        final String error;
        KpiActionResult(int status, String error) { this.status = status; this.error = error; }
    }

    static final class KpiSeedRequest {
        final long requestId;
        final String identity;
        final List<CollectorHelperDaemon.Address> rows;
        final Map<CollectorHelperDaemon.Address, Long> sourceEpochs;
        final Map<CollectorHelperDaemon.Address, Long> callbackWatermarks;
        KpiSeedRequest(long requestId, String identity, List<CollectorHelperDaemon.Address> rows,
                       Map<CollectorHelperDaemon.Address, Long> sourceEpochs,
                       Map<CollectorHelperDaemon.Address, Long> callbackWatermarks) {
            this.requestId = requestId;
            this.identity = identity;
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.sourceEpochs = Collections.unmodifiableMap(new LinkedHashMap<>(sourceEpochs));
            this.callbackWatermarks = Collections.unmodifiableMap(new LinkedHashMap<>(callbackWatermarks));
        }
    }

    static final class KpiValue {
        final CollectorHelperDaemon.Address address;
        final int status;
        final int nativeType;
        final boolean hasRaw;
        final int rawBits;
        final byte[] bytes;
        final long observedWallMs;
        final long observedElapsedMs;
        final long sequence;
        final int sourceKind;
        final long sourceEpoch;
        final long sourceRecordSequence;
        final String quality;
        final String sourceIdentity;
        final long sourceStartWallMs;
        final long sourceStartElapsedMs;
        final String lossReason;
        final String diagnostic;
        KpiValue(CollectorHelperDaemon.Address address, int status, int nativeType, boolean hasRaw,
                 int rawBits, byte[] bytes, long observedWallMs, long observedElapsedMs, long sequence,
                 int sourceKind, long sourceEpoch, long sourceRecordSequence, String quality,
                 String sourceIdentity, long sourceStartWallMs, long sourceStartElapsedMs,
                 String lossReason, String diagnostic) {
            this.address = address; this.status = status; this.nativeType = nativeType;
            this.hasRaw = hasRaw; this.rawBits = rawBits;
            this.bytes = bytes == null ? null : bytes.clone();
            this.observedWallMs = observedWallMs; this.observedElapsedMs = observedElapsedMs;
            this.sequence = sequence;
            this.sourceKind = sourceKind; this.sourceEpoch = sourceEpoch;
            this.sourceRecordSequence = sourceRecordSequence; this.quality = quality;
            this.sourceIdentity = sourceIdentity; this.sourceStartWallMs = sourceStartWallMs;
            this.sourceStartElapsedMs = sourceStartElapsedMs;
            this.lossReason = lossReason; this.diagnostic = diagnostic;
        }
        KpiValue copy() {
            return new KpiValue(address, status, nativeType, hasRaw, rawBits, bytes,
                observedWallMs, observedElapsedMs, sequence, sourceKind, sourceEpoch,
                sourceRecordSequence, quality, sourceIdentity, sourceStartWallMs,
                sourceStartElapsedMs, lossReason, diagnostic);
        }
    }

    static final class KpiSnapshot {
        final int status;
        final long subscriptionId;
        final String bootId;
        final String generation;
        final long sequence;
        final int listenerStatus;
        final String listenerError;
        final List<KpiValue> values;
        final String error;
        KpiSnapshot(int status, long subscriptionId, String bootId, String generation, long sequence,
                    int listenerStatus, String listenerError, List<KpiValue> values, String error) {
            this.status = status; this.subscriptionId = subscriptionId;
            this.bootId = bootId; this.generation = generation; this.sequence = sequence;
            this.listenerStatus = listenerStatus; this.listenerError = listenerError;
            this.values = values; this.error = error;
        }
        static KpiSnapshot failure(long subscriptionId, int status, String error) {
            return new KpiSnapshot(status, subscriptionId, null, null, 0L,
                CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE, null,
                Collections.<KpiValue>emptyList(), error);
        }
    }

    static final class NativeKey {
        final int dev, fid;
        NativeKey(int dev, int fid) { this.dev = dev; this.fid = fid; }
        @Override public boolean equals(Object other) {
            return other instanceof NativeKey && dev == ((NativeKey) other).dev && fid == ((NativeKey) other).fid;
        }
        @Override public int hashCode() { return 31 * dev + fid; }
    }

    static final class DiagnosticsSnapshot {
        final int acceptedNativeKeys;
        final int failedNativeKeys;
        final int promotedKeys;
        final int pollKeys;
        final int fallbackKeys;
        final long callbacksReceived;
        final long mainQueueBytes;
        final long mainQueueOldestAgeMs;
        final long mainQueueHighWaterBytes;
        final long secondaryQueueBytes;
        final long secondaryQueueOldestAgeMs;
        final long secondaryQueueHighWaterBytes;
        final long queueLossCount;
        final String retryReason;
        final long initialSeedGetAttempts;
        final long sourceRecoveryGetAttempts;
        final long secondaryExceptionGetAttempts;

        DiagnosticsSnapshot(int acceptedNativeKeys, int failedNativeKeys, int promotedKeys, int pollKeys,
            int fallbackKeys, long callbacksReceived, long mainQueueBytes, long mainQueueOldestAgeMs,
            long mainQueueHighWaterBytes, long secondaryQueueBytes, long secondaryQueueOldestAgeMs,
            long secondaryQueueHighWaterBytes, long queueLossCount, String retryReason,
            long initialSeedGetAttempts, long sourceRecoveryGetAttempts, long secondaryExceptionGetAttempts) {
            this.acceptedNativeKeys = acceptedNativeKeys;
            this.failedNativeKeys = failedNativeKeys;
            this.promotedKeys = promotedKeys;
            this.pollKeys = pollKeys;
            this.fallbackKeys = fallbackKeys;
            this.callbacksReceived = callbacksReceived;
            this.mainQueueBytes = mainQueueBytes;
            this.mainQueueOldestAgeMs = mainQueueOldestAgeMs;
            this.mainQueueHighWaterBytes = mainQueueHighWaterBytes;
            this.secondaryQueueBytes = secondaryQueueBytes;
            this.secondaryQueueOldestAgeMs = secondaryQueueOldestAgeMs;
            this.secondaryQueueHighWaterBytes = secondaryQueueHighWaterBytes;
            this.queueLossCount = queueLossCount;
            this.retryReason = retryReason;
            this.initialSeedGetAttempts = initialSeedGetAttempts;
            this.sourceRecoveryGetAttempts = sourceRecoveryGetAttempts;
            this.secondaryExceptionGetAttempts = secondaryExceptionGetAttempts;
        }
    }

    private static final class AcquisitionState {
        boolean sourceAvailable;
        boolean seededOnce;
        int seedAttempts;
        boolean registrationAccepted;
        long nextDueElapsedMs = Long.MAX_VALUE;
        long lastPollLogElapsedMs = Long.MIN_VALUE;
        String nextSeedReason = "initial_seed";
    }

    private static final class KpiAcquisitionState {
        boolean sourceAvailable;
        boolean seededOnce;
        boolean registrationAccepted;
        boolean inFlight;
        int seedAttempts;
        long nextDueElapsedMs = Long.MAX_VALUE;
        long sourceEpoch;
        long recordSequence;
        long callbackWatermark;
        long inFlightRequestId = -1L;
        String nextSeedReason = "initial_seed";
    }
}
