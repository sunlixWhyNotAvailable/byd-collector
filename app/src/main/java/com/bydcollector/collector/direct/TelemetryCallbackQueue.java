package com.bydcollector.collector.direct;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded, allocation-only callback-thread handoff. It performs no filesystem or SQL work. */
public final class TelemetryCallbackQueue {
    public static final long MAX_RETAINED_BYTES = 16L * 1024L * 1024L;
    private static final long BATCH_OVERHEAD_BYTES = 1024L;

    private final int stream;
    private final String bootId;
    private final String helperGeneration;
    private final ArrayDeque<TelemetryCallbackBatch.Event> events = new ArrayDeque<>();
    private long retainedBytes;
    private long highWaterBytes;
    private long firstElapsedMs = -1L;
    private long captureEpoch = -1L;
    private long nextBatchSequence;
    private Loss loss;

    public TelemetryCallbackQueue(int stream, String bootId, String helperGeneration) {
        if (stream != CollectorHelperProtocol.STREAM_MAIN && stream != CollectorHelperProtocol.STREAM_SECONDARY) {
            throw new IllegalArgumentException("invalid callback stream");
        }
        if (bootId == null || bootId.isEmpty() || helperGeneration == null || helperGeneration.isEmpty()) {
            throw new IllegalArgumentException("callback identity is required");
        }
        this.stream = stream;
        this.bootId = bootId;
        this.helperGeneration = helperGeneration;
    }

    /** Safe for the vendor callback thread: copy/timestamp happens before this call; this only enqueues. */
    public synchronized boolean offer(TelemetryCallbackBatch.Event event, long epoch) {
        return offerDetailed(event, epoch) == OfferResult.ACCEPTED;
    }

    public synchronized OfferResult offerDetailed(TelemetryCallbackBatch.Event event, long epoch) {
        return offerDetailed(event, epoch, 0L);
    }

    /** Charge retained Binder batches against the same 16 MiB per-stream RAM cap. */
    public synchronized OfferResult offerDetailed(TelemetryCallbackBatch.Event event, long epoch, long reservedTransportBytes) {
        if (event == null) throw new IllegalArgumentException("callback event is required");
        if (epoch < 0) throw new IllegalArgumentException("negative callback epoch");
        if (reservedTransportBytes < 0 || reservedTransportBytes > MAX_RETAINED_BYTES) {
            throw new IllegalArgumentException("invalid callback transport reservation");
        }
        if (!events.isEmpty() && epoch != captureEpoch) {
            return OfferResult.FLUSH_EPOCH_FIRST;
        }
        long bytes = event.retainedBytes();
        long addedOverhead = events.size() % TelemetryCallbackBatch.MAX_EVENTS == 0 ? BATCH_OVERHEAD_BYTES : 0L;
        if (bytes > MAX_RETAINED_BYTES - addedOverhead - reservedTransportBytes ||
            retainedBytes > MAX_RETAINED_BYTES - addedOverhead - reservedTransportBytes - bytes) {
            noteLoss(event.receivedWallMs, "queue_capacity");
            return OfferResult.REJECTED_WITH_LOSS;
        }
        if (!events.isEmpty() && event.sequence <= events.peekLast().sequence) {
            noteLoss(event.receivedWallMs, "non_monotonic_sequence");
            return OfferResult.REJECTED_WITH_LOSS;
        }
        if (events.isEmpty()) { firstElapsedMs = event.receivedElapsedMs; captureEpoch = epoch; }
        events.addLast(event);
        retainedBytes += addedOverhead + bytes;
        highWaterBytes = Math.max(highWaterBytes, retainedBytes);
        return OfferResult.ACCEPTED;
    }

    public synchronized TelemetryCallbackBatch drainDue(long nowElapsedMs) {
        if (events.isEmpty()) return null;
        if (events.size() < TelemetryCallbackBatch.MAX_EVENTS &&
            nowElapsedMs - firstElapsedMs < TelemetryCallbackBatch.FLUSH_MS) return null;
        return drain();
    }

    public synchronized TelemetryCallbackBatch drain() {
        if (events.isEmpty()) return null;
        List<TelemetryCallbackBatch.Event> selected = new ArrayList<>(
            Math.min(events.size(), TelemetryCallbackBatch.MAX_EVENTS));
        java.util.Iterator<TelemetryCallbackBatch.Event> iterator = events.iterator();
        while (iterator.hasNext() && selected.size() < TelemetryCallbackBatch.MAX_EVENTS) selected.add(iterator.next());
        TelemetryCallbackBatch batch = new TelemetryCallbackBatch(
            bootId, helperGeneration, stream, captureEpoch, nextBatchSequence, selected);
        retainedBytes -= BATCH_OVERHEAD_BYTES;
        for (int i = 0; i < selected.size(); i++) retainedBytes -= events.removeFirst().retainedBytes();
        nextBatchSequence++;
        firstElapsedMs = events.isEmpty() ? -1L : events.peekFirst().receivedElapsedMs;
        if (events.isEmpty()) captureEpoch = -1L;
        return batch;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(events.size(), retainedBytes, firstElapsedMs, highWaterBytes, loss);
    }

    public synchronized Loss takeLoss() {
        Loss result = loss;
        loss = null;
        return result;
    }

    synchronized void noteRejected(TelemetryCallbackBatch.Event event, String reason) {
        if (event == null || reason == null || reason.isEmpty()) throw new IllegalArgumentException("invalid rejection");
        noteLoss(event.receivedWallMs, reason);
    }

    synchronized void noteLoss(long wallMs, String reason) {
        if (wallMs < 0 || reason == null || reason.isEmpty()) throw new IllegalArgumentException("invalid loss");
        noteLossInternal(wallMs, reason);
    }

    private void noteLossInternal(long wallMs, String reason) {
        loss = loss == null ? new Loss(1L, wallMs, wallMs, reason)
            : new Loss(loss.count + 1L, Math.min(loss.firstWallMs, wallMs),
                Math.max(loss.lastWallMs, wallMs), loss.reason.equals(reason) ? reason : "multiple");
    }

    public static final class Snapshot {
        public final int eventCount;
        public final long retainedBytes;
        public final long oldestElapsedMs;
        public final long highWaterBytes;
        public final Loss loss;
        Snapshot(int eventCount, long retainedBytes, long oldestElapsedMs, long highWaterBytes, Loss loss) {
            this.eventCount = eventCount;
            this.retainedBytes = retainedBytes;
            this.oldestElapsedMs = oldestElapsedMs;
            this.highWaterBytes = highWaterBytes;
            this.loss = loss;
        }
    }

    public enum OfferResult { ACCEPTED, FLUSH_EPOCH_FIRST, REJECTED_WITH_LOSS }

    public static final class Loss {
        public final long count;
        public final long firstWallMs;
        public final long lastWallMs;
        public final String reason;
        public Loss(long count, long firstWallMs, long lastWallMs, String reason) {
            if (count < 1 || firstWallMs < 0 || lastWallMs < firstWallMs || reason == null || reason.isEmpty()) {
                throw new IllegalArgumentException("invalid callback loss");
            }
            this.count = count;
            this.firstWallMs = firstWallMs;
            this.lastWallMs = lastWallMs;
            this.reason = reason;
        }
    }
}
