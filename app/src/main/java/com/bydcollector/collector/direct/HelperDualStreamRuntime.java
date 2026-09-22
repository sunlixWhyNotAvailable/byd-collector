package com.bydcollector.collector.direct;

import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Runtime coordinator. Vendor calls stay on the prepared daemon Looper; filesystem work does not. */
final class HelperDualStreamRuntime implements AutoCloseable {
    static final long POLL_INTERVAL_MS = 500L;
    private static final long TICK_MS = 100L;

    private final Handler ownerHandler;
    private final VendorReadScheduler vendor;
    private final HelperStreamRuntimeState state = new HelperStreamRuntimeState();
    private final ThreadPoolExecutor orchestration;
    private final ThreadPoolExecutor persistence;
    private final List<CollectorHelperDaemon.Address> mainRows;
    private final List<CollectorHelperDaemon.Address> secondaryRows;
    private final String mainCatalogVersion;
    private final String secondaryCatalogVersion;
    private final String bootId;
    private final String helperGeneration;
    private final TelemetryWorkerSpool mainSpool;
    private final SecondaryTelemetrySpool secondarySpool;
    private final CollectorHelperDaemon.ScalarReader scalarReader;
    private final CollectorHelperDaemon.NativeReader nativeReader;
    private final HelperWakeLockController wakeLock;
    private final HelperDiagnostics diagnostics;
    private final CallbackSpoolBinder callbackTransport;
    private final HelperCallbackController callbacks;
    private final Object monitor = new Object();
    private final Runnable tick = this::tick;

    private volatile boolean closed;
    private boolean wakeAutonomous;
    private boolean mainInFlight;
    private boolean secondaryInFlight;
    private boolean mainPersisting;
    private boolean secondaryPersisting;
    private long mainSequence;
    private long secondarySequence;
    private String secondaryGapId;
    private long secondaryGapGeneration = -1L;
    private volatile long mainNextAt;
    private volatile long secondaryNextAt;
    private long nextCallbackDiagnosticsAt;
    private long nextErrorLogAt;
    private String lastError;
    private long repeatedErrors;

    HelperDualStreamRuntime(
        Handler ownerHandler,
        List<CollectorHelperDaemon.Address> mainRows,
        List<CollectorHelperDaemon.Address> secondaryRows,
        String mainCatalogVersion,
        String secondaryCatalogVersion,
        String bootId,
        String helperGeneration,
        TelemetryWorkerSpool mainSpool,
        SecondaryTelemetrySpool secondarySpool,
        CollectorHelperDaemon.ScalarReader scalarReader,
        CollectorHelperDaemon.NativeReader nativeReader,
        CallbackSpoolBinder callbackTransport,
        HelperWakeLockController wakeLock,
        HelperDiagnostics diagnostics
    ) {
        this.ownerHandler = ownerHandler;
        this.vendor = new VendorReadScheduler(ownerHandler);
        this.mainRows = mainRows;
        this.secondaryRows = secondaryRows;
        this.mainCatalogVersion = mainCatalogVersion;
        this.secondaryCatalogVersion = secondaryCatalogVersion;
        this.bootId = bootId;
        this.helperGeneration = helperGeneration;
        this.mainSpool = mainSpool;
        this.secondarySpool = secondarySpool;
        this.scalarReader = scalarReader;
        this.nativeReader = nativeReader;
        this.callbackTransport = callbackTransport;
        this.wakeLock = wakeLock;
        this.diagnostics = diagnostics;
        this.orchestration = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(4),
            runnable -> {
                Thread thread = new Thread(runnable, "byd-helper-read-orchestration");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.persistence = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(4),
            runnable -> {
                Thread thread = new Thread(runnable, "byd-helper-persistence");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        HelperCallbackController.Platform callbackPlatform;
        try {
            Object manager = nativeReader instanceof CollectorHelperDaemon.NativeArrayReader
                ? ((CollectorHelperDaemon.NativeArrayReader) nativeReader).manager() : null;
            callbackPlatform = manager == null
                ? new HelperCallbackController.Platform()
                : new HelperCallbackController.Platform(manager, vendor);
        } catch (Throwable error) {
            callbackPlatform = new HelperCallbackController.Platform();
            noteError("callback platform unavailable: " + describe(error));
        }
        this.callbacks = new HelperCallbackController(bootId, helperGeneration, mainRows, secondaryRows,
            callbackPlatform, new HelperCallbackController.Host() {
                @Override public boolean appOwns(int stream) { return callbackUsesLiveMemory(stream); }
                @Override public boolean publishingHeld(int stream) {
                    return state.callbackPublishingHeld(stream, SystemClock.elapsedRealtime());
                }
                @Override public long liveBytes(int stream) { return callbackLiveRetainedBytes(callbackTransport, stream); }
                @Override public CallbackSpool.AppendResult deliver(TelemetryCallbackBatch batch) {
                    return deliverCallbackBatch(callbackTransport, batch);
                }
                @Override public CallbackSpool.AppendResult spill(int stream) { return callbackTransport.spill(stream); }
                @Override public void recordLoss(int stream, TelemetryCallbackQueue.Loss loss) {
                    recordCallbackLoss(callbackTransport, stream, loss);
                }
                @Override public void noteError(String message) { HelperDualStreamRuntime.this.noteError(message); }
            });
    }

    void start() {
        refreshCallbackPlan();
        ownerHandler.removeCallbacks(tick);
        ownerHandler.post(tick);
    }

    HelperStreamRuntimeState.ControlResult control(
        int action, String nonce, long token, int stream, long epoch, int value
    ) {
        long now = SystemClock.elapsedRealtime();
        if (action == CollectorHelperProtocol.CONTROL_CLAIM) {
            long previousToken = state.snapshot(0, now).controllerToken;
            HelperStreamRuntimeState.ControlResult result = state.claim(nonce, value, now);
            if (result.status == CollectorHelperProtocol.STATUS_OK) {
                if (previousToken != 0L && previousToken != result.controllerToken) callbacks.semanticReset();
                refreshCallbackPlan();
            }
            signalChanged();
            return result;
        }
        if (action == CollectorHelperProtocol.CONTROL_SET_DESIRED) {
            HelperStreamRuntimeState.ControlResult result = state.setDesired(token, stream, epoch, value, now);
            if (result.status == CollectorHelperProtocol.STATUS_OK) {
                refreshCallbackPlan();
                if (value == 0) callbacks.quiesce(stream, 5_000L);
            }
            signalChanged();
            return result;
        }
        if (action == CollectorHelperProtocol.CONTROL_RENEW) {
            HelperStreamRuntimeState.ControlResult result = state.renew(token, stream, epoch, now);
            signalChanged();
            return result;
        }
        if (action == CollectorHelperProtocol.CONTROL_PAUSE_FENCE) {
            HelperStreamRuntimeState.ControlResult begun = state.beginPause(token, stream, epoch, now);
            if (begun.status != CollectorHelperProtocol.STATUS_OK) return begun;
            refreshCallbackPlan();
            if (!callbacks.quiesce(stream, 5_000L)) {
                state.cancelPause(token, stream, epoch);
                refreshCallbackPlan();
                return state.fenceFailure(token, stream, epoch, SystemClock.elapsedRealtime(),
                    "callback pause fence interrupted");
            }
            signalChanged();
            if (!awaitSettled(stream, token, epoch)) {
                state.cancelPause(token, stream, epoch);
                refreshCallbackPlan();
                return state.fenceFailure(
                    token, stream, epoch, SystemClock.elapsedRealtime(), "pause fence interrupted");
            }
            HelperStreamRuntimeState.ControlResult result =
                state.completePause(token, stream, epoch, SystemClock.elapsedRealtime());
            refreshCallbackPlan();
            return result;
        }
        if (action == CollectorHelperProtocol.CONTROL_RESUME) {
            HelperStreamRuntimeState.ControlResult result = state.resume(token, stream, epoch, now);
            if (result.status == CollectorHelperProtocol.STATUS_OK) refreshCallbackPlan();
            signalChanged();
            return result;
        }
        return new HelperStreamRuntimeState.ControlResult(
            CollectorHelperProtocol.STATUS_INVALID_REQUEST,
            state.snapshot(0, now).controllerToken,
            state.snapshot(0, now).mainEpoch,
            state.snapshot(0, now).secondaryEpoch,
            0L,
            "invalid control action"
        );
    }

    CollectorHelperDaemon.BatchResult readMain(
        long token, long epoch, List<CollectorHelperDaemon.Address> rows
    ) {
        long now = SystemClock.elapsedRealtime();
        int access = state.authorizeLive(token, CollectorHelperProtocol.STREAM_MAIN, epoch, now);
        if (access != CollectorHelperProtocol.STATUS_OK) return rejected(rows.size(), access, "main stream unavailable");
        synchronized (monitor) {
            if (mainInFlight || mainPersisting) {
                return CollectorHelperDaemon.BatchResult.replayPending(rows.size(),
                    "app-gap spool or persistence pending; replay before live read");
            }
            mainInFlight = true;
        }
        if (hasMainBacklog()) {
            finish(CollectorHelperProtocol.STREAM_MAIN, false);
            return CollectorHelperDaemon.BatchResult.replayPending(rows.size(),
                "app-gap spool pending; replay before live read");
        }
        long generation = state.workGeneration(CollectorHelperProtocol.STREAM_MAIN, now);
        try {
            return vendor.call(true, () -> {
                int current = state.authorizeLive(token, CollectorHelperProtocol.STREAM_MAIN, epoch,
                    SystemClock.elapsedRealtime());
                if (current != CollectorHelperProtocol.STATUS_OK) {
                    return rejected(rows.size(), current, "main read canceled");
                }
                if (generation != state.workGeneration(CollectorHelperProtocol.STREAM_MAIN,
                    SystemClock.elapsedRealtime())) {
                    return rejected(rows.size(), CollectorHelperProtocol.STATUS_REPLAY_PENDING,
                        "main read canceled");
                }
                return callbacks.readHybrid(rows,
                    selected -> CollectorHelperDaemon.BatchEngine.run(selected, scalarReader, nativeReader));
            });
        } catch (Throwable error) {
            return rejected(rows.size(), CollectorHelperProtocol.STATUS_READ_ERROR, describe(error));
        } finally {
            finish(CollectorHelperProtocol.STREAM_MAIN, false);
        }
    }

    CollectorHelperDaemon.ReadValue readDiagnostic(CollectorHelperDaemon.Address address) {
        try {
            return vendor.call(true, () -> scalarReader.read(address));
        } catch (Throwable error) {
            return CollectorHelperDaemon.ReadValue.error(
                CollectorHelperProtocol.STATUS_READ_ERROR, describe(error));
        }
    }

    CollectorHelperDaemon.BatchResult readSecondary(long token, long epoch, String catalogVersion) {
        if (!secondaryCatalogVersion.equals(catalogVersion)) {
            return rejected(secondaryRows.size(), CollectorHelperProtocol.STATUS_INVALID_REQUEST,
                "secondary catalog mismatch");
        }
        long now = SystemClock.elapsedRealtime();
        int access = state.authorizeLive(token, CollectorHelperProtocol.STREAM_SECONDARY, epoch, now);
        if (access != CollectorHelperProtocol.STATUS_OK) {
            return rejected(secondaryRows.size(), access, "secondary stream unavailable");
        }
        synchronized (monitor) {
            if (secondaryInFlight || secondaryPersisting) {
                return rejected(secondaryRows.size(), CollectorHelperProtocol.STATUS_REPLAY_PENDING,
                    "secondary gap spool or persistence pending");
            }
            secondaryInFlight = true;
        }
        if (hasSecondaryBacklog()) {
            finish(CollectorHelperProtocol.STREAM_SECONDARY, false);
            return rejected(secondaryRows.size(), CollectorHelperProtocol.STATUS_REPLAY_PENDING,
                "secondary gap spool pending");
        }
        long generation = state.workGeneration(CollectorHelperProtocol.STREAM_SECONDARY, now);
        try {
            return readSecondaryChunks(() ->
                state.authorizeLive(token, CollectorHelperProtocol.STREAM_SECONDARY, epoch,
                    SystemClock.elapsedRealtime()) == CollectorHelperProtocol.STATUS_OK &&
                generation == state.workGeneration(CollectorHelperProtocol.STREAM_SECONDARY,
                    SystemClock.elapsedRealtime()));
        } catch (Canceled error) {
            return rejected(secondaryRows.size(), CollectorHelperProtocol.STATUS_REPLAY_PENDING, error.getMessage());
        } catch (Throwable error) {
            return rejected(secondaryRows.size(), CollectorHelperProtocol.STATUS_READ_ERROR, describe(error));
        } finally {
            finish(CollectorHelperProtocol.STREAM_SECONDARY, false);
        }
    }

    boolean replayAllowed(int stream) {
        return state.replayAllowed(stream, SystemClock.elapsedRealtime());
    }

    int authorizeCallbackTransport(long token, int stream, long epoch) {
        int status = state.authorizeReplay(token, stream, epoch, SystemClock.elapsedRealtime());
        if (status != CollectorHelperProtocol.STATUS_OK) return status;
        return barrierPending(stream) ? CollectorHelperProtocol.STATUS_REPLAY_PENDING : CollectorHelperProtocol.STATUS_OK;
    }

    /** Passive STEP2 ownership seam used by the future callback listener; it performs no capture. */
    boolean callbackUsesLiveMemory(int stream) {
        return state.replayAllowed(stream, SystemClock.elapsedRealtime());
    }

    /** Passive STEP2 ownership seam: an expired APP lease spills through that stream's existing root. */
    boolean callbackUsesGapSpool(int stream) {
        return state.fallbackAllowed(stream, SystemClock.elapsedRealtime());
    }

    CallbackSpool.AppendResult deliverCallbackBatch(CallbackSpoolBinder transport, TelemetryCallbackBatch batch) {
        if (transport == null || batch == null) throw new IllegalArgumentException("callback delivery is required");
        return transport.deliver(batch, callbackUsesLiveMemory(batch.stream));
    }

    void recordCallbackLoss(CallbackSpoolBinder transport, int stream, TelemetryCallbackQueue.Loss loss) {
        if (transport == null) throw new IllegalArgumentException("callback transport is required");
        transport.recordLoss(stream, loss);
    }

    long callbackLiveRetainedBytes(CallbackSpoolBinder transport, int stream) {
        if (transport == null) throw new IllegalArgumentException("callback transport is required");
        return transport.liveRetainedBytes(stream);
    }

    boolean barrierPending(int stream) {
        synchronized (monitor) {
            return stream == CollectorHelperProtocol.STREAM_MAIN
                ? mainInFlight || mainPersisting || callbacks.busy(stream)
                : secondaryInFlight || secondaryPersisting || callbacks.busy(stream);
        }
    }

    private void tick() {
        if (closed) return;
        long now = SystemClock.elapsedRealtime();
        if (now >= nextCallbackDiagnosticsAt) {
            diagnostics.callback(callbacks.diagnosticsSnapshot());
            nextCallbackDiagnosticsAt = saturatedAdd(now, 1_000L);
        }
        boolean mainFallback = mainSpool != null && state.fallbackAllowed(CollectorHelperProtocol.STREAM_MAIN, now);
        boolean secondaryFallback = secondarySpool != null &&
            state.fallbackAllowed(CollectorHelperProtocol.STREAM_SECONDARY, now);
        maintainWakeLock(mainFallback || secondaryFallback);
        if (mainFallback && now >= mainNextAt) submitFallback(CollectorHelperProtocol.STREAM_MAIN, now);
        if (secondaryFallback && now >= secondaryNextAt) submitFallback(CollectorHelperProtocol.STREAM_SECONDARY, now);
        ownerHandler.postDelayed(tick, TICK_MS);
    }

    private void submitFallback(int stream, long now) {
        synchronized (monitor) {
            if (closed || (stream == CollectorHelperProtocol.STREAM_MAIN ? mainInFlight : secondaryInFlight)) return;
            if (stream == CollectorHelperProtocol.STREAM_MAIN) mainInFlight = true;
            else secondaryInFlight = true;
        }
        long generation = state.workGeneration(stream, now);
        try {
            orchestration.execute(() -> runFallback(stream, generation));
        } catch (RejectedExecutionException error) {
            finish(stream, false);
            noteError("runtime queue full for stream " + stream);
        }
    }

    private void runFallback(int stream, long generation) {
        long started = SystemClock.elapsedRealtime();
        long capturedWallMs = System.currentTimeMillis();
        try {
            if (!fallbackCurrent(stream, generation)) return;
            if (stream == CollectorHelperProtocol.STREAM_MAIN) {
                if (!mainSpool.canAppend()) {
                    diagnostics.capSkippedPollCycle();
                    noteError("main app-gap spool cap reached");
                    return;
                }
                CollectorHelperDaemon.BatchResult result = vendor.call(true, () -> {
                    if (!fallbackCurrent(stream, generation)) throw new Canceled("main fallback canceled");
                    return callbacks.readHybrid(mainRows,
                        selected -> CollectorHelperDaemon.BatchEngine.run(selected, scalarReader, nativeReader));
                });
                if (!fallbackCurrent(stream, generation)) return;
                setPersisting(stream, true);
                long sequence = mainSequence++;
                runPersistence(() -> {
                    TelemetryWorkerSpool.AppendResult appended = mainSpool.append(CollectorHelperDaemon.workerSample(
                        new TelemetryWorkerSampleIdentity(bootId, helperGeneration, sequence),
                        mainCatalogVersion, capturedWallMs, started, mainRows, result));
                    if (appended != TelemetryWorkerSpool.AppendResult.SUCCESS) {
                        throw new IllegalStateException("main spool append rejected: " + appended);
                    }
                });
                setPersisting(stream, false);
            } else {
                startSecondaryGapIfNeeded(generation);
                CollectorHelperDaemon.BatchResult result = readSecondaryChunks(() -> fallbackCurrent(stream, generation));
                if (!fallbackCurrent(stream, generation)) return;
                setPersisting(stream, true);
                runPersistence(() -> secondarySpool.append(secondaryCycle(result, capturedWallMs, started)));
                setPersisting(stream, false);
            }
        } catch (Canceled ignored) {
            // A claim, renewal, stop, pause, or resume canceled queued fallback work.
        } catch (Throwable error) {
            noteError("fallback stream " + stream + " failed: " + describe(error));
        } finally {
            setPersisting(stream, false);
            long ended = SystemClock.elapsedRealtime();
            if (stream == CollectorHelperProtocol.STREAM_MAIN) {
                mainNextAt = Math.max(ended, started + POLL_INTERVAL_MS);
            } else {
                long elapsed = Math.max(0L, ended - started);
                secondaryNextAt = elapsed < POLL_INTERVAL_MS
                    ? started + POLL_INTERVAL_MS
                    : saturatedAdd(ended, Math.min(elapsed, 30_000L));
            }
            finish(stream, false);
        }
    }

    private CollectorHelperDaemon.BatchResult readSecondaryChunks(CheckCurrent current) throws Throwable {
        long startedNanos = System.nanoTime();
        CollectorHelperDaemon.ReadValue[] values = new CollectorHelperDaemon.ReadValue[secondaryRows.size()];
        int nativeGroups = 0;
        int fallbackGroups = 0;
        int fallbackReads = 0;
        int groupFailures = 0;
        String firstError = null;
        for (int offset = 0; offset < secondaryRows.size(); offset += CollectorHelperProtocol.SECONDARY_CHUNK_SIZE) {
            if (!current.ok()) throw new Canceled("secondary read canceled");
            int end = Math.min(secondaryRows.size(), offset + CollectorHelperProtocol.SECONDARY_CHUNK_SIZE);
            final int chunkOffset = offset;
            List<CollectorHelperDaemon.Address> chunk = secondaryRows.subList(offset, end);
            CollectorHelperDaemon.BatchResult result = callbacks.readHybrid(chunk, selected ->
                vendor.call(false, () -> {
                    if (!current.ok()) throw new Canceled("secondary read canceled");
                    return CollectorHelperDaemon.BatchEngine.run(selected, scalarReader, nativeReader);
                }));
            System.arraycopy(result.values, 0, values, chunkOffset, result.values.length);
            nativeGroups += result.nativeGroupCount;
            fallbackGroups += result.fallbackGroupCount;
            fallbackReads += result.fallbackReadCount;
            groupFailures += result.groupFailureCount;
            if (firstError == null) firstError = result.error;
        }
        boolean nativeAvailable = nativeReader.isAvailable();
        int mode = !nativeAvailable ? CollectorHelperProtocol.MODE_SCALAR_FALLBACK
            : fallbackGroups == 0 ? CollectorHelperProtocol.MODE_NATIVE
            : CollectorHelperProtocol.MODE_NATIVE_WITH_FALLBACK;
        return new CollectorHelperDaemon.BatchResult(
            CollectorHelperProtocol.STATUS_OK, mode, nativeAvailable, nativeGroups,
            fallbackGroups, fallbackReads, groupFailures,
            (System.nanoTime() - startedNanos) / 1_000_000L, values,
            nativeAvailable ? firstError : nativeReader.unavailableReason());
    }

    private SecondaryTelemetrySpool.Cycle secondaryCycle(
        CollectorHelperDaemon.BatchResult result, long capturedWallMs, long capturedElapsedMs
    ) {
        List<SecondaryTelemetrySpool.Value> values = new ArrayList<>(result.values.length);
        for (int ordinal = 0; ordinal < result.values.length; ordinal++) {
            CollectorHelperDaemon.ReadValue value = result.values[ordinal];
            values.add(new SecondaryTelemetrySpool.Value(
                ordinal, value.status, value.raw != null, value.raw, value.error, value.callbackSource != null));
        }
        return new SecondaryTelemetrySpool.Cycle(
            new SecondaryTelemetrySpool.CycleIdentity(
                bootId, helperGeneration, secondaryGapId, secondarySequence++),
            secondaryCatalogVersion, capturedWallMs, capturedElapsedMs,
            result.elapsedMs, result.batchStatus, result.mode, result.nativeAvailable,
            result.nativeGroupCount, result.fallbackGroupCount, result.fallbackReadCount,
            result.groupFailureCount, result.error, values);
    }

    private void startSecondaryGapIfNeeded(long generation) {
        if (secondaryGapGeneration != generation) {
            secondaryGapGeneration = generation;
            secondaryGapId = UUID.randomUUID().toString();
            secondarySequence = 0L;
        }
    }

    private boolean fallbackCurrent(int stream, long generation) {
        long now = SystemClock.elapsedRealtime();
        return generation == state.workGeneration(stream, now) && state.fallbackAllowed(stream, now);
    }

    private boolean awaitSettled(int stream, long token, long epoch) {
        synchronized (monitor) {
            for (;;) {
                long now = SystemClock.elapsedRealtime();
                int access = state.authorizeLive(token, stream, epoch, now);
                if (access == CollectorHelperProtocol.STATUS_LEASE_EXPIRED ||
                    access == CollectorHelperProtocol.STATUS_STALE_TOKEN) return false;
                boolean busy = stream == CollectorHelperProtocol.STREAM_MAIN
                    ? mainInFlight || mainPersisting : secondaryInFlight || secondaryPersisting;
                busy = busy || callbacks.busy(stream);
                if (!busy && !vendor.hasQueued(stream == CollectorHelperProtocol.STREAM_MAIN)) return true;
                try {
                    monitor.wait(100L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    private void signalChanged() {
        synchronized (monitor) { monitor.notifyAll(); }
        ownerHandler.removeCallbacks(tick);
        ownerHandler.post(tick);
    }

    private void refreshCallbackPlan() {
        long now = SystemClock.elapsedRealtime();
        callbacks.updatePlan(
            state.streamView(CollectorHelperProtocol.STREAM_MAIN, now),
            state.streamView(CollectorHelperProtocol.STREAM_SECONDARY, now));
    }

    private void finish(int stream, boolean persistenceOnly) {
        synchronized (monitor) {
            if (stream == CollectorHelperProtocol.STREAM_MAIN) {
                if (persistenceOnly) mainPersisting = false; else mainInFlight = false;
            } else {
                if (persistenceOnly) secondaryPersisting = false; else secondaryInFlight = false;
            }
            monitor.notifyAll();
        }
    }

    private void setPersisting(int stream, boolean value) {
        synchronized (monitor) {
            if (stream == CollectorHelperProtocol.STREAM_MAIN) mainPersisting = value;
            else secondaryPersisting = value;
            monitor.notifyAll();
        }
    }

    private boolean hasMainBacklog() {
        if (mainSpool == null) return false;
        try { return !mainSpool.pending(1, sample -> { }).isEmpty(); }
        catch (Throwable error) {
            noteError("main callback backlog check failed: " + android.util.Log.getStackTraceString(error));
            return true;
        }
    }

    private boolean hasSecondaryBacklog() {
        if (secondarySpool == null) return false;
        try { return secondarySpool.oldest() != null; }
        catch (Throwable error) {
            noteError("secondary callback backlog check failed: " + android.util.Log.getStackTraceString(error));
            return true;
        }
    }

    private void maintainWakeLock(boolean autonomous) {
        if (autonomous && !wakeAutonomous) {
            wakeAutonomous = true;
            wakeLock.enterAutonomousMode();
        } else if (!autonomous && wakeAutonomous) {
            wakeAutonomous = false;
            wakeLock.exitAutonomousMode();
        } else if (autonomous) {
            wakeLock.maintain();
        }
    }

    private void noteError(String message) {
        try { diagnostics.error(message); } catch (Throwable ignored) { }
        synchronized (monitor) {
            long now = SystemClock.elapsedRealtime();
            if (message.equals(lastError) && now < nextErrorLogAt) {
                repeatedErrors++;
                return;
            }
            String suffix = repeatedErrors == 0L ? "" : " (" + repeatedErrors + " similar failures suppressed)";
            CollectorHelperDaemon.log("WARN: " + message + suffix);
            lastError = message;
            repeatedErrors = 0L;
            nextErrorLogAt = saturatedAdd(now, 30_000L);
        }
    }

    private void runPersistence(PersistenceCall call) throws Throwable {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            persistence.execute(() -> {
                try { call.run(); } catch (Throwable error) { failure.set(error); }
                finally { done.countDown(); }
            });
        } catch (RejectedExecutionException error) {
            throw new IllegalStateException("persistence queue full", error);
        }
        done.await();
        if (failure.get() != null) throw failure.get();
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static CollectorHelperDaemon.BatchResult rejected(int count, int status, String error) {
        CollectorHelperDaemon.ReadValue[] values = new CollectorHelperDaemon.ReadValue[count];
        for (int i = 0; i < count; i++) values[i] = CollectorHelperDaemon.ReadValue.error(status, error);
        return new CollectorHelperDaemon.BatchResult(
            status, CollectorHelperProtocol.MODE_REJECTED, false, 0, 0, 0, 0, 0L, values, error);
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String text = current.getClass().getSimpleName() + ": " +
            (current.getMessage() == null ? "no message" : current.getMessage());
        return text.length() <= 512 ? text : text.substring(0, 512);
    }

    @Override public void close() {
        closed = true;
        ownerHandler.removeCallbacks(tick);
        callbacks.close();
        vendor.close();
        orchestration.shutdownNow();
        persistence.shutdownNow();
        maintainWakeLock(false);
        synchronized (monitor) { monitor.notifyAll(); }
    }

    private interface CheckCurrent { boolean ok(); }
    interface VendorCall<T> { T run() throws Throwable; }
    private interface PersistenceCall { void run() throws Throwable; }
    private static final class Canceled extends Exception { Canceled(String message) { super(message); } }

    /** Two-level owner-Looper queue: every pending Main call runs before the next secondary chunk. */
    static final class VendorReadScheduler implements AutoCloseable {
        private final Consumer<Runnable> ownerPoster;
        private final ArrayDeque<Task<?>> high = new ArrayDeque<>();
        private final ArrayDeque<Task<?>> low = new ArrayDeque<>();
        private boolean posted;
        private boolean closed;

        VendorReadScheduler(Handler handler) { this(handler::post); }

        VendorReadScheduler(Consumer<Runnable> ownerPoster) { this.ownerPoster = ownerPoster; }

        void runForTest(boolean mainPriority, Runnable runnable) throws Throwable {
            call(mainPriority, () -> { runnable.run(); return null; });
        }

        <T> T call(boolean mainPriority, VendorCall<T> call) throws Throwable {
            Task<T> task = new Task<>(call);
            synchronized (this) {
                if (closed) throw new IllegalStateException("vendor scheduler closed");
                (mainPriority ? high : low).addLast(task);
                if (!posted) {
                    posted = true;
                    ownerPoster.accept(this::drainOne);
                }
            }
            task.done.await();
            if (task.error != null) throw task.error;
            return task.result;
        }

        synchronized boolean hasQueued(boolean mainPriority) {
            return !(mainPriority ? high : low).isEmpty();
        }

        private void drainOne() {
            Task<?> task;
            synchronized (this) {
                task = !high.isEmpty() ? high.removeFirst() : low.pollFirst();
                if (task == null) { posted = false; return; }
            }
            task.run();
            synchronized (this) {
                if (high.isEmpty() && low.isEmpty()) posted = false;
                else ownerPoster.accept(this::drainOne);
            }
        }

        @Override public synchronized void close() {
            closed = true;
            List<Task<?>> pending = new ArrayList<>();
            pending.addAll(high);
            pending.addAll(low);
            high.clear();
            low.clear();
            for (Task<?> task : pending) task.cancel();
        }

        private static final class Task<T> {
            final VendorCall<T> call;
            final CountDownLatch done = new CountDownLatch(1);
            T result;
            Throwable error;
            Task(VendorCall<T> call) { this.call = call; }
            void run() {
                try { result = call.run(); } catch (Throwable failure) { error = failure; }
                finally { done.countDown(); }
            }
            void cancel() { error = new IllegalStateException("vendor scheduler closed"); done.countDown(); }
        }
    }
}
