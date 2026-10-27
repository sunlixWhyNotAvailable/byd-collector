package com.bydcollector.collector.direct;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded, allocation-only callback-thread handoff. It performs no filesystem or SQL work. */
public final class TelemetryCallbackQueue {
    public static final long MAX_RETAINED_BYTES = 16L * 1024L * 1024L;
    private static final long BATCH_OVERHEAD_BYTES = 1024L;
    private static final long LOSS_BOUNDARY_RESERVE_BYTES = 2048L;

    private final int stream;
    private final String bootId;
    private final String helperGeneration;
    private final long sourceEpoch;
    private final ArrayDeque<TelemetryCallbackBatch.RawRecord> records = new ArrayDeque<>();
    private long retainedBytes;
    private long highWaterBytes;
    private long firstElapsedMs = -1L;
    private long captureEpoch = -1L;
    private long nextBatchSequence;
    private Loss loss;

    public TelemetryCallbackQueue(int stream, String bootId, String helperGeneration) {
        this(stream, bootId, helperGeneration, 0L);
    }

    public TelemetryCallbackQueue(int stream, String bootId, String helperGeneration, long sourceEpoch) {
        if (stream != CollectorHelperProtocol.STREAM_MAIN && stream != CollectorHelperProtocol.STREAM_SECONDARY) {
            throw new IllegalArgumentException("invalid callback stream");
        }
        if (bootId == null || bootId.isEmpty() || helperGeneration == null || helperGeneration.isEmpty() || sourceEpoch < 0L) {
            throw new IllegalArgumentException("callback identity is required");
        }
        this.stream = stream;
        this.bootId = bootId;
        this.helperGeneration = helperGeneration;
        this.sourceEpoch = sourceEpoch;
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
        if (!records.isEmpty() && epoch != captureEpoch) {
            return OfferResult.FLUSH_EPOCH_FIRST;
        }
        long bytes = event.retainedBytes();
        long addedOverhead = records.size() % TelemetryCallbackBatch.MAX_RECORDS == 0 ? BATCH_OVERHEAD_BYTES : 0L;
        long ordinaryLimit = MAX_RETAINED_BYTES - LOSS_BOUNDARY_RESERVE_BYTES;
        if (bytes > ordinaryLimit - addedOverhead - reservedTransportBytes ||
            retainedBytes > ordinaryLimit - addedOverhead - reservedTransportBytes - bytes) {
            noteLoss(event.receivedWallMs, "queue_capacity");
            noteLostBoundary(event.sequence, event.receivedWallMs, event.receivedElapsedMs, epoch,
                "queue_capacity", "callback record rejected by retained-byte limit");
            return OfferResult.REJECTED_WITH_LOSS;
        }
        if (!records.isEmpty() && event.sequence <= records.peekLast().recordSequence()) {
            noteLoss(event.receivedWallMs, "non_monotonic_sequence");
            return OfferResult.REJECTED_WITH_LOSS;
        }
        if (records.isEmpty()) { firstElapsedMs = event.receivedElapsedMs; captureEpoch = epoch; }
        records.addLast(event);
        retainedBytes += addedOverhead + bytes;
        highWaterBytes = Math.max(highWaterBytes, retainedBytes);
        return OfferResult.ACCEPTED;
    }

    /** Enqueues typed continuity evidence without creating a telemetry event. */
    public synchronized OfferResult offerBoundary(TelemetryCallbackBatch.SourceBoundary boundary, long epoch) {
        if (boundary == null || epoch < 0L) throw new IllegalArgumentException("invalid callback boundary");
        if (!records.isEmpty() && epoch != captureEpoch) return OfferResult.FLUSH_EPOCH_FIRST;
        long lastSequence = records.isEmpty() ? -1L : records.peekLast().recordSequence();
        if (boundary.recordSequence <= lastSequence) {
            noteLoss(boundary.captureWallMs, "non_monotonic_sequence");
            noteLostBoundary(lastSequence + 1L, boundary.captureWallMs, boundary.captureElapsedMs, epoch,
                "non_monotonic_sequence", "continuity boundary arrived behind retained records");
            return OfferResult.REJECTED_WITH_LOSS;
        }
        long bytes = boundary.retainedBytes();
        long addedOverhead = records.size() % TelemetryCallbackBatch.MAX_RECORDS == 0 ? BATCH_OVERHEAD_BYTES : 0L;
        if (bytes > MAX_RETAINED_BYTES - addedOverhead || retainedBytes > MAX_RETAINED_BYTES - addedOverhead - bytes) {
            noteLoss(boundary.captureWallMs, "boundary_capacity");
            noteLostBoundary(boundary.recordSequence, boundary.captureWallMs, boundary.captureElapsedMs, epoch,
                "boundary_capacity", "callback boundary rejected by retained-byte limit");
            return OfferResult.REJECTED_WITH_LOSS;
        }
        if (records.isEmpty()) { firstElapsedMs = boundary.captureElapsedMs; captureEpoch = epoch; }
        records.addLast(boundary);
        retainedBytes += addedOverhead + bytes;
        highWaterBytes = Math.max(highWaterBytes, retainedBytes);
        return OfferResult.ACCEPTED;
    }

    private void noteLostBoundary(long sequence, long wallMs, long elapsedMs, long epoch, String reason, String diagnostic) {
        if (sequence < 0L || records.size() >= TelemetryCallbackBatch.MAX_RECORDS + 1) return;
        long lastSequence = records.isEmpty() ? -1L : records.peekLast().recordSequence();
        if (sequence <= lastSequence) return;
        TelemetryCallbackBatch.SourceBoundary boundary = new TelemetryCallbackBatch.SourceBoundary(
            sequence, TelemetryCallbackBatch.SourceBoundary.SCOPE_STREAM,
            TelemetryCallbackBatch.SourceBoundary.STATE_LOST, 0, 0, 0, wallMs, elapsedMs,
            reason, diagnostic, null, null, null);
        if (records.isEmpty()) { firstElapsedMs = elapsedMs; captureEpoch = epoch; }
        records.addLast(boundary);
        retainedBytes += boundary.retainedBytes();
        highWaterBytes = Math.max(highWaterBytes, retainedBytes);
    }

    synchronized void noteDroppedRecord(
        long sequence, long epoch, long wallMs, long elapsedMs, String reason, String diagnostic
    ) {
        if (sequence < 0L || epoch < 0L || wallMs < 0L || elapsedMs < 0L ||
            reason == null || reason.isEmpty() || diagnostic == null || diagnostic.isEmpty()) {
            throw new IllegalArgumentException("invalid dropped callback record");
        }
        noteLoss(wallMs, reason);
        long lastSequence = records.isEmpty() ? -1L : records.peekLast().recordSequence();
        long orderedSequence = sequence;
        if (orderedSequence <= lastSequence) {
            if (lastSequence == Long.MAX_VALUE) return;
            orderedSequence = lastSequence + 1L;
        }
        noteLostBoundary(orderedSequence, wallMs, elapsedMs, epoch, reason, diagnostic);
    }

    public synchronized TelemetryCallbackBatch drainDue(long nowElapsedMs) {
        if (records.isEmpty()) return null;
        if (records.size() < TelemetryCallbackBatch.MAX_RECORDS &&
            nowElapsedMs - firstElapsedMs < TelemetryCallbackBatch.FLUSH_MS) return null;
        return drain();
    }

    /** No timer is needed for an empty queue without pending loss metadata. */
    synchronized long nextFlushDelayMs(long nowElapsedMs) {
        if (loss != null || records.size() >= TelemetryCallbackBatch.MAX_RECORDS) return 0L;
        if (records.isEmpty()) return -1L;
        return Math.max(0L, TelemetryCallbackBatch.FLUSH_MS - (nowElapsedMs - firstElapsedMs));
    }

    public synchronized TelemetryCallbackBatch drain() {
        if (records.isEmpty()) return null;
        List<TelemetryCallbackBatch.Event> selectedEvents = new ArrayList<>();
        List<TelemetryCallbackBatch.SourceBoundary> selectedBoundaries = new ArrayList<>();
        List<TelemetryCallbackBatch.RawRecord> selected = new ArrayList<>(Math.min(records.size(), TelemetryCallbackBatch.MAX_RECORDS));
        java.util.Iterator<TelemetryCallbackBatch.RawRecord> iterator = records.iterator();
        while (iterator.hasNext() && selected.size() < TelemetryCallbackBatch.MAX_RECORDS) {
            TelemetryCallbackBatch.RawRecord record = iterator.next();
            selected.add(record);
            if (record instanceof TelemetryCallbackBatch.Event) selectedEvents.add((TelemetryCallbackBatch.Event) record);
            else selectedBoundaries.add((TelemetryCallbackBatch.SourceBoundary) record);
        }
        TelemetryCallbackBatch batch = new TelemetryCallbackBatch(
            bootId, helperGeneration, stream, captureEpoch, nextBatchSequence, sourceEpoch,
            selectedEvents, selectedBoundaries);
        retainedBytes -= BATCH_OVERHEAD_BYTES;
        for (int i = 0; i < selected.size(); i++) retainedBytes -= records.removeFirst().retainedBytes();
        nextBatchSequence++;
        firstElapsedMs = records.isEmpty() ? -1L : records.peekFirst().captureElapsedMs();
        if (records.isEmpty()) captureEpoch = -1L;
        return batch;
    }

    public synchronized Snapshot snapshot() {
        int eventCount = 0, boundaryCount = 0;
        for (TelemetryCallbackBatch.RawRecord record : records) {
            if (record instanceof TelemetryCallbackBatch.Event) eventCount++;
            else boundaryCount++;
        }
        return new Snapshot(eventCount, boundaryCount, retainedBytes, firstElapsedMs, highWaterBytes, loss);
    }

    public synchronized Loss takeLoss() {
        Loss result = loss;
        loss = null;
        return result;
    }

    synchronized void noteRejected(TelemetryCallbackBatch.Event event, String reason) {
        if (event == null || reason == null || reason.isEmpty()) throw new IllegalArgumentException("invalid rejection");
        noteLoss(event.receivedWallMs, reason);
        noteLostBoundary(event.sequence, event.receivedWallMs, event.receivedElapsedMs, captureEpoch < 0L ? 0L : captureEpoch,
            reason, "callback record rejected before spool delivery");
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
        public final int boundaryCount;
        public final int recordCount;
        public final long retainedBytes;
        public final long oldestElapsedMs;
        public final long highWaterBytes;
        public final Loss loss;
        Snapshot(int eventCount, int boundaryCount, long retainedBytes, long oldestElapsedMs, long highWaterBytes, Loss loss) {
            this.eventCount = eventCount;
            this.boundaryCount = boundaryCount;
            this.recordCount = eventCount + boundaryCount;
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
