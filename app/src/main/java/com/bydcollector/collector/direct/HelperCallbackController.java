package com.bydcollector.collector.direct;

import android.os.SystemClock;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.bydcollector.collector.data.direct.DirectFidEntry;
import com.bydcollector.collector.data.direct.DirectFidRegistry;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Owns BYDAuto callback registration, bounded raw batching, and callback-first scalar caching. */
final class HelperCallbackController implements AutoCloseable {
    static final long RECONCILE_MS = 5_000L;
    static final long KPI_RECONCILE_MS = 2_000L;
    private static final Set<CollectorHelperDaemon.Address> KPI_SOURCES = kpiSources();
    static boolean isKpiSource(CollectorHelperDaemon.Address address) { return KPI_SOURCES.contains(address); }
    static final int MAX_ADDITION = 128;
    static final int MAX_DEVICE_FIDS = 4_096;
    static final int PERMISSION_DENIED = -2147482644;
    private static final long[] RETRY_MS = { 1_000L, 2_000L, 5_000L, 10_000L, 30_000L };

    interface Poller { CollectorHelperDaemon.BatchResult poll(List<CollectorHelperDaemon.Address> rows) throws Throwable; }
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
        void noteError(String message);
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
    private final ScheduledExecutorService registrationWorker;
    private final ReentrantLock closeLock = new ReentrantLock();
    private final TelemetryCallbackQueue[] queues = new TelemetryCallbackQueue[3];
    private final long[] eventSequence = new long[3];
    private final long[] minPromotableSequence = new long[3];
    private final long[] inFlightBytes = new long[3];
    private final boolean[] desired = new boolean[3];
    private final boolean[] capturePermitted = new boolean[3];
    private final boolean[] captureEnabled = new boolean[3];
    private final boolean[] fenceActive = new boolean[3];
    private final long[] epochs = new long[3];
    private final long[] plannedEpochs = new long[3];
    private final Set<NativeKey> mainKeys;
    private final Set<NativeKey> secondaryKeys;
    private final Map<NativeKey, List<CollectorHelperDaemon.Address>> scalarAddresses;
    private final List<CollectorHelperDaemon.Address> kpiRows;
    private final Set<NativeKey> kpiKeys;
    private final int missingKpiSourceCount;
    private final Map<CollectorHelperDaemon.Address, KpiValue> kpiMailbox = new LinkedHashMap<>();
    private final ThreadPoolExecutor kpiNotificationWorker;
    private final Map<CollectorHelperDaemon.Address, CacheEntry> cache = new LinkedHashMap<>();
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
    private int kpiListenerStatus = CollectorHelperProtocol.KPI_LISTENER_IDLE;
    private String kpiListenerError;
    private boolean kpiNotificationPending;
    private boolean kpiNotificationQueued;
    private boolean kpiNotificationSent;
    private int retryIndex;
    private long callbacksReceived;
    private long queueLossCount;
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
        this.mainKeys = nativeKeys(mainRows);
        this.secondaryKeys = nativeKeys(secondaryRows);
        this.scalarAddresses = scalarAddresses(mainRows, secondaryRows);
        if (KPI_SOURCES.size() > CollectorHelperProtocol.MAX_KPI_VALUES) {
            throw new IllegalStateException("canonical KPI source count exceeds helper mailbox capacity");
        }
        this.kpiRows = eligibleKpiRows(mainRows, secondaryRows);
        this.kpiKeys = nativeKeys(kpiRows);
        this.missingKpiSourceCount = KPI_SOURCES.size() - kpiRows.size();
        queues[CollectorHelperProtocol.STREAM_MAIN] = new TelemetryCallbackQueue(
            CollectorHelperProtocol.STREAM_MAIN, bootId, generation);
        queues[CollectorHelperProtocol.STREAM_SECONDARY] = new TelemetryCallbackQueue(
            CollectorHelperProtocol.STREAM_SECONDARY, bootId, generation);
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
        for (int stream = CollectorHelperProtocol.STREAM_MAIN;
             stream <= CollectorHelperProtocol.STREAM_SECONDARY; stream++) {
            final int selectedStream = stream;
            streamWorkers[stream].scheduleWithFixedDelay(
                () -> maintenance(selectedStream), 100L, 100L, TimeUnit.MILLISECONDS);
        }
    }

    void updatePlan(HelperStreamRuntimeState.StreamView main, HelperStreamRuntimeState.StreamView secondary) {
        List<TelemetryCallbackBatch> displaced;
        boolean unionChanged;
        synchronized (lock) {
            if (sameView(lastMainView, main) && sameView(lastSecondaryView, secondary)) return;
            displaced = new ArrayList<>();
            boolean oldMain = desired[CollectorHelperProtocol.STREAM_MAIN];
            boolean oldSecondary = desired[CollectorHelperProtocol.STREAM_SECONDARY];
            boolean oldMainCapture = capturePermitted[CollectorHelperProtocol.STREAM_MAIN];
            boolean oldSecondaryCapture = capturePermitted[CollectorHelperProtocol.STREAM_SECONDARY];
            updateStream(CollectorHelperProtocol.STREAM_MAIN, main, displaced);
            updateStream(CollectorHelperProtocol.STREAM_SECONDARY, secondary, displaced);
            boolean desiredChanged = oldMain != desired[CollectorHelperProtocol.STREAM_MAIN] ||
                oldSecondary != desired[CollectorHelperProtocol.STREAM_SECONDARY];
            boolean mainCaptureChanged = oldMainCapture != capturePermitted[CollectorHelperProtocol.STREAM_MAIN];
            boolean secondaryCaptureChanged = oldSecondaryCapture != capturePermitted[CollectorHelperProtocol.STREAM_SECONDARY];
            if (mainCaptureChanged || secondaryCaptureChanged) {
                clearOwnerChanges(oldMainCapture, oldSecondaryCapture,
                    capturePermitted[CollectorHelperProtocol.STREAM_MAIN],
                    capturePermitted[CollectorHelperProtocol.STREAM_SECONDARY]);
            }
            if (mainCaptureChanged || secondaryCaptureChanged) {
                minPromotableSequence[CollectorHelperProtocol.STREAM_MAIN] =
                    eventSequence[CollectorHelperProtocol.STREAM_MAIN];
                minPromotableSequence[CollectorHelperProtocol.STREAM_SECONDARY] =
                    eventSequence[CollectorHelperProtocol.STREAM_SECONDARY];
            }
            unionChanged = desiredChanged && !desiredUnion(oldMain, oldSecondary).equals(
                desiredUnion(desired[CollectorHelperProtocol.STREAM_MAIN], desired[CollectorHelperProtocol.STREAM_SECONDARY]));
            lastMainView = main;
            lastSecondaryView = secondary;
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

    void semanticReset() {
        synchronized (lock) {
            cache.clear();
            minPromotableSequence[CollectorHelperProtocol.STREAM_MAIN] =
                eventSequence[CollectorHelperProtocol.STREAM_MAIN];
            minPromotableSequence[CollectorHelperProtocol.STREAM_SECONDARY] =
                eventSequence[CollectorHelperProtocol.STREAM_SECONDARY];
        }
    }

    CollectorHelperDaemon.BatchResult readHybrid(
        List<CollectorHelperDaemon.Address> rows, Poller poller
    ) throws Throwable {
        long now = nowElapsed();
        List<CollectorHelperDaemon.Address> polls = new ArrayList<>();
        Map<CollectorHelperDaemon.Address, CallbackValueSource> before = new LinkedHashMap<>();
        CollectorHelperDaemon.ReadValue[] merged = new CollectorHelperDaemon.ReadValue[rows.size()];
        synchronized (lock) {
            for (int i = 0; i < rows.size(); i++) {
                CollectorHelperDaemon.Address row = rows.get(i);
                CacheEntry entry = cache.get(row);
                if (entry != null && entry.promoted && !entry.needsSeed && !fastPoll(row) &&
                    now - entry.lastReconcileMs < (KPI_SOURCES.contains(row) ? KPI_RECONCILE_MS : RECONCILE_MS)) {
                    merged[i] = CollectorHelperDaemon.ReadValue.cached(entry.source.rawBits, entry.source);
                } else {
                    polls.add(row);
                    before.put(row, entry == null ? null : entry.source);
                }
            }
        }
        CollectorHelperDaemon.BatchResult polled = polls.isEmpty() ? emptyResult() : poller.poll(polls);
        Map<CollectorHelperDaemon.Address, CollectorHelperDaemon.ReadValue> byAddress = new LinkedHashMap<>();
        for (int i = 0; i < polls.size(); i++) byAddress.put(polls.get(i), polled.values[i]);
        synchronized (lock) {
            for (int i = 0; i < rows.size(); i++) {
                if (merged[i] != null) continue;
                CollectorHelperDaemon.Address row = rows.get(i);
                CollectorHelperDaemon.ReadValue fresh = byAddress.get(row);
                CacheEntry entry = cache.get(row);
                if (entry == null || entry.source == null || fastPoll(row)) { merged[i] = fresh; continue; }
                CallbackValueSource captured = before.get(row);
                if (captured != entry.source && newer(entry.source, captured) && entry.promoted) {
                    merged[i] = CollectorHelperDaemon.ReadValue.cached(entry.source.rawBits, entry.source);
                    continue;
                }
                entry.lastReconcileMs = now;
                if (fresh != null && fresh.status == CollectorHelperProtocol.STATUS_OK && fresh.raw != null &&
                    fresh.raw == entry.source.rawBits) {
                    entry.mismatches = 0;
                    if (!entry.promoted && entry.recoveryPending) entry.promoted = true;
                    entry.recoveryPending = false;
                    entry.needsSeed = false;
                    //This cycle performed a real getter read, so its value remains a fresh poll.
                    //The retained callback source is used again only by later callback-first cycles.
                    merged[i] = fresh;
                } else {
                    entry.mismatches++;
                    if (entry.mismatches >= 2) entry.promoted = false;
                    merged[i] = fresh;
                }
            }
        }
        return new CollectorHelperDaemon.BatchResult(polled.batchStatus, polled.mode, polled.nativeAvailable,
            polled.nativeGroupCount, polled.fallbackGroupCount, polled.fallbackReadCount,
            polled.groupFailureCount, polled.elapsedMs, merged, polled.error);
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
            kpiMailbox.clear();
            kpiListenerStatus = !platform.available()
                ? CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE
                : missingKpiSourceCount > 0
                    ? CollectorHelperProtocol.KPI_LISTENER_DEGRADED
                    : CollectorHelperProtocol.KPI_LISTENER_REGISTERING;
            kpiListenerError = !platform.available() ? "callback listener platform unavailable"
                : missingKpiSourceCount > 0 ? "some canonical KPI sources are absent from the helper whitelist" : null;
            kpiSequence = nextSequence(kpiSequence);
            kpiNotificationPending = true;
            kpiNotificationSent = false;
        }
        unlinkKpiSubscriber(replaced);
        scheduleRegistration(0L, true);
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
            kpiMailbox.clear();
            kpiListenerStatus = CollectorHelperProtocol.KPI_LISTENER_IDLE;
            kpiListenerError = null;
            kpiNotificationPending = false;
            kpiNotificationSent = false;
        }
        unlinkKpiSubscriber(removed);
        scheduleRegistration(0L, true);
        return new KpiActionResult(CollectorHelperProtocol.STATUS_OK, null);
    }

    private void clearKpiSubscription(long subscriptionId) {
        KpiSubscriber removed;
        synchronized (kpiLock) {
            if (kpiSubscriber == null || kpiSubscriber.id != subscriptionId) return;
            removed = kpiSubscriber;
            kpiSubscriber = null;
            kpiMailbox.clear();
            kpiListenerStatus = CollectorHelperProtocol.KPI_LISTENER_IDLE;
            kpiListenerError = null;
            kpiNotificationPending = false;
            kpiNotificationSent = false;
        }
        unlinkKpiSubscriber(removed);
        scheduleRegistration(0L, true);
    }

    private static void unlinkKpiSubscriber(KpiSubscriber subscriber) {
        if (subscriber != null) subscriber.listener.unlinkToDeath(subscriber.death, 0);
    }

    private void captureKpi(int dev, int fid, int type, int rawBits, byte[] bytes,
                            long wall, long elapsed) {
        NativeKey key = new NativeKey(dev, fid);
        if (!kpiKeys.contains(key)) return;
        boolean captured = false;
        synchronized (kpiLock) {
            if (closed || kpiSubscriber == null) return;
            List<CollectorHelperDaemon.Address> rows = scalarAddresses.get(key);
            if (rows == null) return;
            long sequence = nextSequence(kpiSequence);
            for (CollectorHelperDaemon.Address row : rows) {
                if (!KPI_SOURCES.contains(row)) continue;
                KpiValue previous = kpiMailbox.get(row);
                // Concurrent callbacks can acquire this lock out of observation order.
                if (previous != null && elapsed < previous.observedElapsedMs) continue;
                boolean oversize = bytes != null && bytes.length > CollectorHelperProtocol.MAX_KPI_RAW_BYTES;
                kpiMailbox.put(row, new KpiValue(row, oversize ? CollectorHelperProtocol.STATUS_READ_ERROR
                    : CollectorHelperProtocol.STATUS_OK, type, type != TelemetryCallbackBatch.TYPE_BYTES,
                    rawBits, oversize ? null : bytes, wall, elapsed, sequence));
                captured = true;
            }
            if (captured) {
                kpiSequence = sequence;
                kpiNotificationPending = true;
            }
        }
        if (captured) scheduleKpiNotification();
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
            if (kpiSubscriber != null &&
                (kpiListenerStatus != status || !java.util.Objects.equals(kpiListenerError, error))) {
                kpiListenerStatus = status;
                kpiListenerError = error;
                kpiSequence = nextSequence(kpiSequence);
                kpiNotificationPending = true;
                changed = true;
            }
        }
        if (changed) scheduleKpiNotification();
    }

    private static Set<CollectorHelperDaemon.Address> kpiSources() {
        Set<String> keys = NormalizedFieldCatalog.INSTANCE.getKpiSourceKeys();
        Set<CollectorHelperDaemon.Address> rows = new LinkedHashSet<>();
        for (DirectFidEntry entry : DirectFidRegistry.INSTANCE.getEntries()) {
            if (keys.contains(entry.getKey())) {
                rows.add(new CollectorHelperDaemon.Address(entry.getTx(), entry.getDev(), entry.getFid()));
            }
        }
        return Collections.unmodifiableSet(rows);
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
        boolean desiredChanged = desired[stream] != view.desired;
        boolean plannedEpochChanged = plannedEpochs[stream] != view.epoch;
        boolean identityChanged = desiredChanged || (plannedEpochChanged && epochs[stream] != view.epoch);
        if (identityChanged) {
            captureEnabled[stream] = false;
            displaced.addAll(drainAllLocked(stream));
            epochs[stream] = view.epoch;
        }
        desired[stream] = view.desired;
        plannedEpochs[stream] = view.epoch;
        capturePermitted[stream] = view.desired && (view.activeLease || view.autonomyAllowed);
        captureEnabled[stream] = capturePermitted[stream];
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
            int stream = canCapture(CollectorHelperProtocol.STREAM_MAIN, key)
                ? CollectorHelperProtocol.STREAM_MAIN
                : canCapture(CollectorHelperProtocol.STREAM_SECONDARY, key)
                    ? CollectorHelperProtocol.STREAM_SECONDARY : 0;
            if (stream == 0) return;
            long sequence = eventSequence[stream]++;
            if (bytes != null && bytes.length > TelemetryCallbackBatch.MAX_BYTES - 2048) {
                queues[stream].noteLoss(wall, "oversize_callback");
                return;
            }
            TelemetryCallbackBatch.Event event = new TelemetryCallbackBatch.Event(
                sequence, dev, fid, type, rawBits, bytes, wall, elapsed, null,
                scalarUsable(key, type, rawBits) ? "callback" :
                    type == TelemetryCallbackBatch.TYPE_FLOAT && !Float.isFinite(Float.intBitsToFloat(rawBits))
                        ? "non_finite" : "type_mismatch");
            long reserved = inFlightBytes[stream] + host.liveBytes(stream);
            TelemetryCallbackQueue.OfferResult offered = queues[stream].offerDetailed(event, epochs[stream], reserved);
            if (offered == TelemetryCallbackQueue.OfferResult.FLUSH_EPOCH_FIRST) {
                //Plan changes drain under the same lock, so this indicates an internal fencing defect.
                queues[stream].noteRejected(event, "epoch_fence");
            }
        }
    }

    private void onListenerError(int code, String message) {
        if (closed) return;
        synchronized (lock) {
            for (CacheEntry value : cache.values()) { value.promoted = false; value.recoveryPending = false; }
            minPromotableSequence[CollectorHelperProtocol.STREAM_MAIN] =
                eventSequence[CollectorHelperProtocol.STREAM_MAIN];
            minPromotableSequence[CollectorHelperProtocol.STREAM_SECONDARY] =
                eventSequence[CollectorHelperProtocol.STREAM_SECONDARY];
        }
        try { streamWorkers[CollectorHelperProtocol.STREAM_MAIN].execute(() -> host.noteError(
            "BYDAuto listener error " + code + ": " + String.valueOf(message))); }
        catch (Throwable ignored) { }
        noteKpiRegistration(CollectorHelperProtocol.KPI_LISTENER_FAILED,
            "BYDAuto listener error " + code + ": " + String.valueOf(message));
        restartListener = true;
        retryReason = "listener_error_" + code;
        scheduleRegistration(RETRY_MS[Math.min(retryIndex++, RETRY_MS.length - 1)], false);
    }

    private void maintenance(int stream) {
        if (closed) return;
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
            if (!closed) host.noteError("callback maintenance failed: " + describe(error));
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
            synchronized (lock) { queueLossCount += batch.events.size(); }
            host.recordLoss(batch.stream, loss(batch, "delivery_queue"));
        }
    }

    private void deliverBatch(TelemetryCallbackBatch batch) {
        try {
            CallbackSpool.AppendResult result = host.deliver(batch);
            if (result == CallbackSpool.AppendResult.SUCCESS || result == CallbackSpool.AppendResult.DUPLICATE) {
                promote(batch);
            }
        } finally {
            synchronized (lock) { inFlightBytes[batch.stream] -= retainedBytes(batch); lock.notifyAll(); }
        }
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
            CacheEntry entry = cache.get(address);
            if (entry != null) entry.lastReconcileMs = nowElapsed() - RECONCILE_MS;
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
            for (Map.Entry<CollectorHelperDaemon.Address, CacheEntry> item : cache.entrySet()) {
                NativeKey key = new NativeKey(item.getKey().dev, item.getKey().fid);
                CacheEntry value = item.getValue();
                if (value.promoted) promoted.add(key);
                else if (value.source != null || value.recoveryPending) fallback.add(key);
            }
            Set<NativeKey> wanted = desiredUnion(
                desired[CollectorHelperProtocol.STREAM_MAIN], desired[CollectorHelperProtocol.STREAM_SECONDARY]);
            TelemetryCallbackQueue.Snapshot main = queues[CollectorHelperProtocol.STREAM_MAIN].snapshot();
            TelemetryCallbackQueue.Snapshot secondary = queues[CollectorHelperProtocol.STREAM_SECONDARY].snapshot();
            return new DiagnosticsSnapshot(
                platform.acceptedCount(), platform.failedCount(), promoted.size(),
                Math.max(0, wanted.size() - promoted.size()), fallback.size(), callbacksReceived,
                main.retainedBytes, age(now, main.oldestElapsedMs), main.highWaterBytes,
                secondary.retainedBytes, age(now, secondary.oldestElapsedMs), secondary.highWaterBytes,
                queueLossCount, retryReason);
        }
    }

    private void promote(TelemetryCallbackBatch batch) {
        synchronized (lock) {
            for (TelemetryCallbackBatch.Event event : batch.events) {
                if (event.sequence < minPromotableSequence[batch.stream]) continue;
                NativeKey key = new NativeKey(event.device, event.fid);
                List<CollectorHelperDaemon.Address> rows = scalarAddresses.get(key);
                if (rows == null) continue;
                if (event.nativeType == TelemetryCallbackBatch.TYPE_BYTES ||
                    (event.nativeType == TelemetryCallbackBatch.TYPE_FLOAT &&
                        !Float.isFinite(Float.intBitsToFloat(event.rawBits)))) {
                    invalidate(rows);
                    continue;
                }
                CallbackValueSource source = CallbackValueSource.from(batch, event);
                for (CollectorHelperDaemon.Address row : rows) {
                    if (!source.matches(row.tx, row.dev, row.fid, event.rawBits)) {
                        invalidate(row);
                        continue;
                    }
                    CacheEntry entry = cache.computeIfAbsent(row, unused -> new CacheEntry());
                    if (entry.source == null || newer(source, entry.source)) {
                        boolean first = entry.source == null;
                        entry.source = source;
                        if (!fastPoll(row)) {
                            if (!entry.promoted) entry.recoveryPending = true;
                            if (first) { entry.promoted = true; entry.needsSeed = true; }
                        }
                    }
                }
            }
        }
    }

    private void invalidate(List<CollectorHelperDaemon.Address> rows) {
        for (CollectorHelperDaemon.Address row : rows) invalidate(row);
    }

    private void invalidate(CollectorHelperDaemon.Address row) {
        CacheEntry entry = cache.get(row);
        if (entry != null) { entry.promoted = false; entry.recoveryPending = false; }
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
            if (kpiSubscriber != null) target.addAll(kpiKeys);
        }
        if (restartListener) {
            platform.restartListener();
            restartListener = false;
        }
        platform.apply(target);
        synchronized (lock) { retryIndex = 0; retryReason = null; }
        updateKpiRegistrationStatus(null);
    }

    private void updateKpiRegistrationStatus(Throwable failure) {
        int accepted = 0;
        int failed = 0;
        for (NativeKey key : kpiKeys) {
            if (platform.accepts(key)) accepted++;
            else if (platform.failed(key)) failed++;
        }
        int status;
        String error;
        if (!platform.available()) {
            status = CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE;
            error = "callback listener platform unavailable";
        } else if (failed > 0 || missingKpiSourceCount > 0) {
            status = CollectorHelperProtocol.KPI_LISTENER_DEGRADED;
            error = failure == null
                ? "callback registration incomplete: failed=" + failed + " missing_whitelist=" + missingKpiSourceCount
                : describe(failure);
        } else if (failure != null) {
            status = CollectorHelperProtocol.KPI_LISTENER_FAILED;
            error = describe(failure);
        } else if (accepted == kpiKeys.size()) {
            status = CollectorHelperProtocol.KPI_LISTENER_READY;
            error = null;
        } else {
            status = CollectorHelperProtocol.KPI_LISTENER_REGISTERING;
            error = null;
        }
        noteKpiRegistration(status, error);
    }

    private void clearOwnerChanges(boolean oldMain, boolean oldSecondary, boolean newMain, boolean newSecondary) {
        cache.entrySet().removeIf(entry -> {
            NativeKey key = new NativeKey(entry.getKey().dev, entry.getKey().fid);
            return owner(key, oldMain, oldSecondary) != owner(key, newMain, newSecondary);
        });
    }

    private int owner(NativeKey key, boolean main, boolean secondary) {
        if (main && mainKeys.contains(key)) return CollectorHelperProtocol.STREAM_MAIN;
        if (secondary && secondaryKeys.contains(key)) return CollectorHelperProtocol.STREAM_SECONDARY;
        return 0;
    }

    private static boolean newer(CallbackValueSource candidate, CallbackValueSource current) {
        if (current == null) return true;
        if (!candidate.bootId.equals(current.bootId) ||
            !candidate.helperGeneration.equals(current.helperGeneration)) return true;
        if (candidate.receivedElapsedMs != current.receivedElapsedMs) {
            return candidate.receivedElapsedMs > current.receivedElapsedMs;
        }
        return candidate.stream == current.stream && candidate.epoch == current.epoch &&
            candidate.eventSequence > current.eventSequence;
    }

    private boolean scalarUsable(NativeKey key, int type, int rawBits) {
        if (type == TelemetryCallbackBatch.TYPE_FLOAT && !Float.isFinite(Float.intBitsToFloat(rawBits))) return false;
        List<CollectorHelperDaemon.Address> rows = scalarAddresses.get(key);
        if (rows == null) return false;
        for (CollectorHelperDaemon.Address row : rows) {
            if ((row.tx == CollectorHelperProtocol.AUTO_TX_INT && type == TelemetryCallbackBatch.TYPE_INT) ||
                (row.tx == CollectorHelperProtocol.AUTO_TX_FLOAT && type == TelemetryCallbackBatch.TYPE_FLOAT)) return true;
        }
        return false;
    }

    private static long nextEpoch(long value) { return value == Long.MAX_VALUE ? 1L : value + 1L; }

    static boolean fastPoll(CollectorHelperDaemon.Address row) {
        return (row.dev == 1001 && row.fid == 315621418) ||
            (row.dev == 1011 && row.fid == 555745336) ||
            (row.dev == 1014 && row.fid == 1145045040);
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

    private static CollectorHelperDaemon.BatchResult emptyResult() {
        return new CollectorHelperDaemon.BatchResult(CollectorHelperProtocol.STATUS_OK,
            CollectorHelperProtocol.MODE_NATIVE, true, 0, 0, 0, 0, 0L,
            new CollectorHelperDaemon.ReadValue[0], null);
    }

    private static long retainedBytes(TelemetryCallbackBatch batch) {
        long bytes = 1024L;
        for (TelemetryCallbackBatch.Event event : batch.events) bytes += event.retainedBytes();
        return bytes;
    }

    private static TelemetryCallbackQueue.Loss loss(TelemetryCallbackBatch batch, String reason) {
        TelemetryCallbackBatch.Event first = batch.events.get(0), last = batch.events.get(batch.events.size() - 1);
        return new TelemetryCallbackQueue.Loss(batch.events.size(), first.receivedWallMs, last.receivedWallMs, reason);
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

    private static ScheduledExecutorService newStreamWorker(String streamName) {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "byd-helper-callback-" + streamName);
            thread.setDaemon(true);
            return thread;
        });
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

        void apply(Set<NativeKey> target) throws Throwable {
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

        void restartListener() throws Throwable {
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
        boolean accepts(NativeKey key) {
            Set<Integer> device = accepted.get(key.dev);
            return device != null && device.contains(key.fid);
        }
        boolean failed(NativeKey key) { return failed.contains(key); }

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
        KpiValue(CollectorHelperDaemon.Address address, int status, int nativeType, boolean hasRaw,
                 int rawBits, byte[] bytes, long observedWallMs, long observedElapsedMs, long sequence) {
            this.address = address; this.status = status; this.nativeType = nativeType;
            this.hasRaw = hasRaw; this.rawBits = rawBits;
            this.bytes = bytes == null ? null : bytes.clone();
            this.observedWallMs = observedWallMs; this.observedElapsedMs = observedElapsedMs;
            this.sequence = sequence;
        }
        KpiValue copy() {
            return new KpiValue(address, status, nativeType, hasRaw, rawBits, bytes,
                observedWallMs, observedElapsedMs, sequence);
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

        DiagnosticsSnapshot(int acceptedNativeKeys, int failedNativeKeys, int promotedKeys, int pollKeys,
            int fallbackKeys, long callbacksReceived, long mainQueueBytes, long mainQueueOldestAgeMs,
            long mainQueueHighWaterBytes, long secondaryQueueBytes, long secondaryQueueOldestAgeMs,
            long secondaryQueueHighWaterBytes, long queueLossCount, String retryReason) {
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
        }
    }

    private static final class CacheEntry {
        CallbackValueSource source;
        boolean promoted;
        boolean recoveryPending;
        boolean needsSeed;
        int mismatches;
        long lastReconcileMs;
    }
}
