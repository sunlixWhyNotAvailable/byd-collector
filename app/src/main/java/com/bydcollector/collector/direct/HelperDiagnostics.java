package com.bydcollector.collector.direct;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

//Nonblocking diagnostic aggregation. Telemetry callers only update memory and offer to a bounded queue.
final class HelperDiagnostics implements AutoCloseable, TelemetryWorkerSpool.DiagnosticListener {
    static final int QUEUE_CAPACITY = 128;
    static final long SUMMARY_INTERVAL_MS = 30_000L;
    static final int MAX_BOOTSTRAP_CHUNK_BYTES = 4 * 1024;
    static final int MAX_EVENT_CHARS = 64;
    static final int MAX_MESSAGE_CHARS = 512;

    interface Sink {
        void persist(String jsonLine, String snapshotJson) throws Exception;
        void persistBootstrap(byte[] chunk) throws Exception;
    }

    interface Clock {
        long wallTimeMs();
        long elapsedTimeMs();
    }

    private static final int SIGNAL_CHANGE = 1;
    private static final int SIGNAL_IMMEDIATE = 2;
    private static final int SIGNAL_BOOTSTRAP = 3;

    private final String bootId;
    private final int pid;
    private final String helperGeneration;
    private final long intervalStartedWallMs;
    private final long intervalStartedElapsedMs;
    private final long capBytes;
    private final Sink sink;
    private final Clock clock;
    private final long summaryIntervalMs;
    private final ArrayBlockingQueue<Signal> queue;
    private final Thread writer;
    private final Object stateLock = new Object();
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicLong currentBytes = new AtomicLong(-1L);
    private final AtomicInteger pendingReadyRecords = new AtomicInteger(-1);
    private final AtomicLong observationPeakBytes = new AtomicLong(-1L);
    private final AtomicLong successfulAppends = new AtomicLong();
    private final AtomicLong duplicateRefusals = new AtomicLong();
    private final AtomicLong capRefusals = new AtomicLong();
    private final AtomicLong capSkippedPollCycles = new AtomicLong();
    private final AtomicLong persistenceFailures = new AtomicLong();
    private final AtomicLong ackReleasedRecords = new AtomicLong();
    private final AtomicLong ackReleasedBytes = new AtomicLong();
    private final AtomicLong ackNotFound = new AtomicLong();
    private final AtomicLong ackFailures = new AtomicLong();
    private final AtomicLong quarantinedRecords = new AtomicLong();
    private final AtomicLong diagnosticQueueDrops = new AtomicLong();
    private final AtomicLong diagnosticDiskFailures = new AtomicLong();
    private final AtomicReference<String> collectionMode = new AtomicReference<String>("starting");
    private final AtomicBoolean capReached = new AtomicBoolean(false);
    private final AtomicReference<String> lastErrorKey = new AtomicReference<String>();
    private final AtomicLong repeatedErrorCount = new AtomicLong();
    private long stateRevision;

    static HelperDiagnostics open(
        String bootId,
        int pid,
        String helperGeneration,
        long capBytes,
        TelemetryWorkerSpool.Footprint initialFootprint
    ) {
        return new HelperDiagnostics(
            bootId,
            pid,
            helperGeneration,
            capBytes,
            initialFootprint,
            new HelperDiagnosticFileStore(new File(HelperDiagnosticFileStore.DIRECTORY_PATH)),
            new Clock() {
                @Override public long wallTimeMs() { return System.currentTimeMillis(); }
                @Override public long elapsedTimeMs() { return android.os.SystemClock.elapsedRealtime(); }
            },
            QUEUE_CAPACITY
        );
    }

    HelperDiagnostics(
        String bootId,
        int pid,
        String helperGeneration,
        long capBytes,
        TelemetryWorkerSpool.Footprint initialFootprint,
        Sink sink,
        Clock clock,
        int queueCapacity
    ) {
        this(bootId, pid, helperGeneration, capBytes, initialFootprint, sink, clock, queueCapacity, SUMMARY_INTERVAL_MS);
    }

    HelperDiagnostics(
        String bootId,
        int pid,
        String helperGeneration,
        long capBytes,
        TelemetryWorkerSpool.Footprint initialFootprint,
        Sink sink,
        Clock clock,
        int queueCapacity,
        long summaryIntervalMs
    ) {
        if (bootId == null || helperGeneration == null) throw new IllegalArgumentException("helper identity is required");
        if (capBytes < 1L || queueCapacity < 1 || summaryIntervalMs < 1L) {
            throw new IllegalArgumentException("diagnostic bounds must be positive");
        }
        if (sink == null || clock == null) throw new IllegalArgumentException("diagnostic dependencies are required");
        this.bootId = bootId;
        this.pid = pid;
        this.helperGeneration = helperGeneration;
        this.capBytes = capBytes;
        this.sink = sink;
        this.clock = clock;
        this.summaryIntervalMs = summaryIntervalMs;
        this.queue = new ArrayBlockingQueue<Signal>(queueCapacity);
        this.intervalStartedWallMs = clock.wallTimeMs();
        this.intervalStartedElapsedMs = clock.elapsedTimeMs();
        if (initialFootprint != null) {
            synchronized (stateLock) {
                applyObservationLocked(initialFootprint);
                markChangedLocked();
            }
        }
        this.writer = new Thread(this::writerLoop, "bydcollector-helper-diagnostics");
        this.writer.setDaemon(true);
        this.writer.start();
        offerImmediate("helper_start", null);
        if (initialFootprint != null && initialFootprint.bytes >= capBytes) {
            offerImmediate("spool_cap_reached", null);
        }
    }

    @Override public void onObservation(
        TelemetryWorkerSpool.Footprint footprint,
        boolean capacityBlocked
    ) {
        Signal transition;
        boolean changed;
        synchronized (stateLock) {
            changed = currentBytes.get() != footprint.bytes ||
                pendingReadyRecords.get() != footprint.pendingReadyRecords;
            String event = applyObservationLocked(footprint, capacityBlocked);
            if (changed) markChangedLocked();
            transition = event == null ? null : immediateSignalLocked(event, null);
        }
        if (transition != null) offer(transition);
        else if (changed) offer(new Signal(SIGNAL_CHANGE, null, (String) null));
    }

    @Override public void onAppend(TelemetryWorkerSpool.AppendResult result, TelemetryWorkerSpool.Footprint footprint) {
        Signal transition = null;
        synchronized (stateLock) {
            if (result == TelemetryWorkerSpool.AppendResult.SUCCESS) successfulAppends.incrementAndGet();
            else if (result == TelemetryWorkerSpool.AppendResult.DUPLICATE) duplicateRefusals.incrementAndGet();
            else capRefusals.incrementAndGet();
            if (footprint != null) {
                String event = applyObservationLocked(
                    footprint,
                    result == TelemetryWorkerSpool.AppendResult.CAP_REACHED
                );
                if (event != null) transition = immediateSignalLocked(event, null);
            }
            markChangedLocked();
        }
        if (transition != null) offer(transition);
        else offer(new Signal(SIGNAL_CHANGE, null, (String) null));
    }

    @Override public void onPersistenceFailure(String operation, Throwable error) {
        synchronized (stateLock) {
            persistenceFailures.incrementAndGet();
            markChangedLocked();
        }
        error(operation + ": " + describe(error));
    }

    @Override public void onAcknowledge(TelemetryWorkerSpool.AckResult result) {
        Signal transition = null;
        synchronized (stateLock) {
            if (result.status == TelemetryWorkerSpool.AckStatus.RELEASED) {
                ackReleasedRecords.addAndGet(result.releasedRecords);
                ackReleasedBytes.addAndGet(result.releasedBytes);
                String event = subtractCurrentLocked(result.releasedBytes, result.releasedRecords);
                if (event != null) transition = immediateSignalLocked(event, null);
            } else if (result.status == TelemetryWorkerSpool.AckStatus.NOT_FOUND) {
                ackNotFound.incrementAndGet();
            } else {
                ackFailures.incrementAndGet();
            }
            markChangedLocked();
        }
        if (transition != null) offer(transition);
        else offer(new Signal(SIGNAL_CHANGE, null, (String) null));
    }

    @Override public void onQuarantine(long recordBytes) {
        Signal event;
        synchronized (stateLock) {
            quarantinedRecords.incrementAndGet();
            int pending = pendingReadyRecords.get();
            if (pending > 0) pendingReadyRecords.set(pending - 1);
            markChangedLocked();
            event = immediateSignalLocked("spool_quarantine", null);
        }
        offer(event);
    }

    void capSkippedPollCycle() {
        synchronized (stateLock) {
            capSkippedPollCycles.incrementAndGet();
            markChangedLocked();
        }
        offer(new Signal(SIGNAL_CHANGE, null, (String) null));
    }

    void mode(String mode) {
        String boundedMode = bound(mode, MAX_EVENT_CHARS);
        String previous;
        Signal event = null;
        synchronized (stateLock) {
            previous = collectionMode.getAndSet(boundedMode);
            if (!boundedMode.equals(previous)) {
                markChangedLocked();
                event = immediateSignalLocked("mode_" + boundedMode, null);
            }
        }
        if (event != null) offer(event);
    }

    void context(String event, String message) {
        offerImmediate(bound(event, MAX_EVENT_CHARS), boundNullable(message, MAX_MESSAGE_CHARS));
    }

    void error(String message) {
        String key = bound(message == null ? "unknown" : message, MAX_MESSAGE_CHARS);
        boolean repeated;
        Signal event = null;
        synchronized (stateLock) {
            String previous = lastErrorKey.getAndSet(key);
            repeated = key.equals(previous);
            if (repeated) repeatedErrorCount.incrementAndGet();
            markChangedLocked();
            if (!repeated) event = immediateSignalLocked("error", key);
        }
        if (event != null) offer(event);
        else offer(new Signal(SIGNAL_CHANGE, null, (String) null));
    }

    void enqueueBootstrap(byte[] chunk) {
        if (chunk == null || chunk.length == 0) return;
        int length = Math.min(chunk.length, MAX_BOOTSTRAP_CHUNK_BYTES);
        byte[] bounded = new byte[length];
        System.arraycopy(chunk, 0, bounded, 0, length);
        offer(new Signal(SIGNAL_BOOTSTRAP, null, bounded));
    }

    HelperDiagnosticSnapshot snapshotForTest() {
        return snapshot();
    }

    long repeatedErrorCountForTest() {
        synchronized (stateLock) { return repeatedErrorCount.get(); }
    }

    @Override public synchronized void close() {
        if (closing.get()) return;
        Signal stop;
        synchronized (stateLock) {
            collectionMode.set("stopped");
            markChangedLocked();
            stop = immediateSignalLocked("helper_stop", null);
        }
        offerClosing(stop);
        closing.set(true);
        try {
            writer.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String applyObservationLocked(TelemetryWorkerSpool.Footprint footprint) {
        return applyObservationLocked(footprint, footprint.bytes >= capBytes);
    }

    private String applyObservationLocked(
        TelemetryWorkerSpool.Footprint footprint,
        boolean capacityBlocked
    ) {
        currentBytes.set(footprint.bytes);
        pendingReadyRecords.set(footprint.pendingReadyRecords);
        updatePeakLocked(footprint.bytes);
        boolean nowCapped = capacityBlocked || footprint.bytes >= capBytes;
        boolean wasCapped = capReached.getAndSet(nowCapped);
        return nowCapped == wasCapped ? null : nowCapped ? "spool_cap_reached" : "spool_cap_released";
    }

    private void updatePeakLocked(long candidate) {
        long previous = observationPeakBytes.get();
        if (candidate > previous) observationPeakBytes.set(candidate);
    }

    private String subtractCurrentLocked(long bytes, int records) {
        long current = currentBytes.get();
        if (current >= 0L) currentBytes.set(Math.max(0L, current - bytes));
        int pending = pendingReadyRecords.get();
        if (pending >= 0) pendingReadyRecords.set(Math.max(0, pending - records));
        if (capReached.get() && currentBytes.get() >= 0L && currentBytes.get() < capBytes) {
            capReached.set(false);
            return "spool_cap_released";
        }
        return null;
    }

    private void offer(Signal signal) {
        if (closing.get()) return;
        if (!queue.offer(signal)) recordQueueDrop();
    }

    private void offerClosing(Signal signal) {
        if (!queue.offer(signal)) {
            recordQueueDrop();
            queue.poll();
            queue.offer(signal);
        }
    }

    private void writerLoop() {
        long lastSummaryElapsedMs = intervalStartedElapsedMs;
        long nextWriteAttemptElapsedMs = intervalStartedElapsedMs;
        long lastPersistedRevision = -1L;
        Signal deferredSignal = null;
        while (!closing.get() || !queue.isEmpty()) {
            Signal activeSignal = null;
            try {
                long nowElapsed = clock.elapsedTimeMs();
                if (!closing.get() && nowElapsed < nextWriteAttemptElapsedMs) {
                    Thread.sleep(Math.min(1_000L, Math.max(1L, nextWriteAttemptElapsedMs - nowElapsed)));
                    continue;
                }
                boolean dirty = currentRevision() != lastPersistedRevision;
                long untilSummary = dirty
                    ? Math.max(1L, summaryIntervalMs - (nowElapsed - lastSummaryElapsedMs))
                    : 1_000L;
                Signal signal;
                if (deferredSignal != null) {
                    signal = deferredSignal;
                    deferredSignal = null;
                } else {
                    signal = queue.poll(Math.min(1_000L, untilSummary), TimeUnit.MILLISECONDS);
                }
                activeSignal = signal;
                if (signal != null) {
                    if (signal.type == SIGNAL_BOOTSTRAP) {
                        persistBootstrap(signal.bytes);
                    } else if (signal.type == SIGNAL_IMMEDIATE) {
                        lastPersistedRevision = persist(signal);
                    }
                }
                nowElapsed = clock.elapsedTimeMs();
                dirty = currentRevision() != lastPersistedRevision;
                if (
                    dirty &&
                    nowElapsed - lastSummaryElapsedMs >= summaryIntervalMs
                ) {
                    lastPersistedRevision = persist("summary", null);
                    lastSummaryElapsedMs = nowElapsed;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable error) {
                recordDiskFailure();
                if (activeSignal != null && activeSignal.type != SIGNAL_CHANGE && !closing.get()) {
                    if (deferredSignal == null) deferredSignal = activeSignal;
                    else recordQueueDrop();
                }
                nextWriteAttemptElapsedMs = clock.elapsedTimeMs() + summaryIntervalMs;
            }
        }
    }

    private long persist(String event, String message) throws Exception {
        CurrentCapture current = captureCurrent();
        JSONObject entry = current.snapshot.toJson(current.wallMs, current.elapsedMs);
        entry.put("event", event);
        entry.put("message", message == null ? JSONObject.NULL : message);
        sink.persist(
            entry.toString(),
            current.snapshot.toJson(current.wallMs, current.elapsedMs).toString()
        );
        return current.revision;
    }

    private long persist(Signal signal) throws Exception {
        JSONObject entry = signal.snapshot.toJson(signal.wallMs, signal.elapsedMs);
        entry.put("event", signal.event);
        entry.put("message", signal.message == null ? JSONObject.NULL : signal.message);
        CurrentCapture current = captureCurrent();
        sink.persist(
            entry.toString(),
            current.snapshot.toJson(current.wallMs, current.elapsedMs).toString()
        );
        return current.revision;
    }

    private void persistBootstrap(byte[] chunk) throws Exception {
        sink.persistBootstrap(chunk);
    }

    private HelperDiagnosticSnapshot snapshot() {
        synchronized (stateLock) {
            return snapshotLocked();
        }
    }

    private CurrentCapture captureCurrent() {
        synchronized (stateLock) {
            return new CurrentCapture(
                snapshotLocked(),
                stateRevision,
                clock.wallTimeMs(),
                clock.elapsedTimeMs()
            );
        }
    }

    private long currentRevision() {
        synchronized (stateLock) { return stateRevision; }
    }

    private void markChangedLocked() {
        stateRevision++;
    }

    private void recordQueueDrop() {
        synchronized (stateLock) {
            diagnosticQueueDrops.incrementAndGet();
            markChangedLocked();
        }
    }

    private void recordDiskFailure() {
        synchronized (stateLock) {
            diagnosticDiskFailures.incrementAndGet();
            markChangedLocked();
        }
    }

    private HelperDiagnosticSnapshot snapshotLocked() {
            long bytes = currentBytes.get();
            int pending = pendingReadyRecords.get();
            long peak = observationPeakBytes.get();
            return new HelperDiagnosticSnapshot(
                bootId, pid, helperGeneration, intervalStartedWallMs, intervalStartedElapsedMs,
                bytes < 0L ? null : bytes,
                pending < 0 ? null : pending,
                capBytes,
                peak < 0L ? null : peak,
                null,
                successfulAppends.get(), duplicateRefusals.get(), capRefusals.get(), capSkippedPollCycles.get(),
                persistenceFailures.get(), ackReleasedRecords.get(), ackReleasedBytes.get(), ackNotFound.get(),
                ackFailures.get(), quarantinedRecords.get(), diagnosticQueueDrops.get(), diagnosticDiskFailures.get(),
                lastErrorKey.get(), repeatedErrorCount.get(), collectionMode.get(), capReached.get()
            );
    }

    private void offerImmediate(String event, String message) {
        Signal signal;
        synchronized (stateLock) { signal = immediateSignalLocked(event, message); }
        offer(signal);
    }

    private Signal immediateSignalLocked(String event, String message) {
        return new Signal(
            bound(event, MAX_EVENT_CHARS),
            boundNullable(message, MAX_MESSAGE_CHARS),
            clock.wallTimeMs(),
            clock.elapsedTimeMs(),
            snapshotLocked()
        );
    }

    private static String bound(String value, int maxChars) {
        if (value == null || value.isEmpty()) return "unknown";
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static String boundNullable(String value, int maxChars) {
        if (value == null) return null;
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static String describe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class Signal {
        final int type;
        final String event;
        final String message;
        final byte[] bytes;
        final long wallMs;
        final long elapsedMs;
        final HelperDiagnosticSnapshot snapshot;

        Signal(int type, String event, String message) {
            this.type = type;
            this.event = event;
            this.message = message;
            this.bytes = null;
            this.wallMs = 0L;
            this.elapsedMs = 0L;
            this.snapshot = null;
        }

        Signal(int type, String event, byte[] bytes) {
            this.type = type;
            this.event = event;
            this.message = null;
            this.bytes = bytes;
            this.wallMs = 0L;
            this.elapsedMs = 0L;
            this.snapshot = null;
        }

        Signal(String event, String message, long wallMs, long elapsedMs, HelperDiagnosticSnapshot snapshot) {
            this.type = SIGNAL_IMMEDIATE;
            this.event = event;
            this.message = message;
            this.bytes = null;
            this.wallMs = wallMs;
            this.elapsedMs = elapsedMs;
            this.snapshot = snapshot;
        }
    }

    private static final class CurrentCapture {
        final HelperDiagnosticSnapshot snapshot;
        final long revision;
        final long wallMs;
        final long elapsedMs;

        CurrentCapture(
            HelperDiagnosticSnapshot snapshot,
            long revision,
            long wallMs,
            long elapsedMs
        ) {
            this.snapshot = snapshot;
            this.revision = revision;
            this.wallMs = wallMs;
            this.elapsedMs = elapsedMs;
        }
    }
}
