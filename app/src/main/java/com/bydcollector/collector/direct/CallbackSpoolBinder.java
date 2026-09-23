package com.bydcollector.collector.direct;

import android.os.Parcel;
import java.util.concurrent.atomic.AtomicLongArray;

/** Paged callback transport; daemon ownership checks run before dispatch here. */
public final class CallbackSpoolBinder implements AutoCloseable {
    public static final int MAX_REPLY_BYTES = 256 * 1024;
    private final CallbackSpool main;
    private final CallbackSpool secondary;
    private final LiveRecord[] live = new LiveRecord[3];
    private final AtomicLongArray retainedLiveBytes = new AtomicLongArray(3);
    private final Object[] streamLocks = { null, new Object(), new Object() };

    CallbackSpoolBinder() {
        main = CallbackSpool.openMain();
        secondary = CallbackSpool.openSecondary();
    }

    CallbackSpoolBinder(CallbackSpool main, CallbackSpool secondary) {
        if (main == null || secondary == null) throw new IllegalArgumentException("callback spools are required");
        this.main = main; this.secondary = secondary;
    }

    CallbackSpool spool(int stream) {
        if (stream == CollectorHelperProtocol.STREAM_MAIN) return main;
        if (stream == CollectorHelperProtocol.STREAM_SECONDARY) return secondary;
        throw new IllegalArgumentException("invalid callback stream");
    }

    /** Called off the vendor callback thread after queue batching. */
    CallbackSpool.AppendResult deliver(TelemetryCallbackBatch batch, boolean appOwnsStream) {
        synchronized (streamLock(batch.stream)) {
            return deliverLocked(batch, appOwnsStream);
        }
    }

    private CallbackSpool.AppendResult deliverLocked(TelemetryCallbackBatch batch, boolean appOwnsStream) {
        try {
            CallbackSpool target = spool(batch.stream);
            if (live[batch.stream] != null) {
                CallbackSpool.AppendResult spilled = spill(batch.stream);
                if (spilled != CallbackSpool.AppendResult.SUCCESS && spilled != CallbackSpool.AppendResult.DUPLICATE) {
                    TelemetryCallbackBatch.Event first = batch.events.get(0);
                    TelemetryCallbackBatch.Event last = batch.events.get(batch.events.size() - 1);
                    target.recordLoss(batch.events.size(), first.receivedWallMs, last.receivedWallMs, "blocked_behind_live");
                    return CallbackSpool.AppendResult.REJECTED;
                }
            }
            if (appOwnsStream && live[batch.stream] == null && target.oldest() == null) {
                byte[] bytes = batch.encode();
                live[batch.stream] = new LiveRecord(bytes, memoryDescriptor(batch, bytes));
                retainedLiveBytes.set(batch.stream, bytes.length);
                return CallbackSpool.AppendResult.SUCCESS;
            }
            return target.append(batch);
        } catch (Exception error) {
            try {
                TelemetryCallbackBatch.Event first = batch.events.get(0);
                TelemetryCallbackBatch.Event last = batch.events.get(batch.events.size() - 1);
                spool(batch.stream).recordLoss(batch.events.size(), first.receivedWallMs, last.receivedWallMs, "delivery");
            } catch (Exception ignored) { }
            return CallbackSpool.AppendResult.REJECTED;
        }
    }

    CallbackSpool.AppendResult spill(int stream) {
        synchronized (streamLock(stream)) {
            return spillLocked(stream);
        }
    }

    private CallbackSpool.AppendResult spillLocked(int stream) {
        if (live[stream] == null) return CallbackSpool.AppendResult.SUCCESS;
        TelemetryCallbackBatch batch;
        try { batch = TelemetryCallbackBatch.decode(live[stream].bytes); }
        catch (Exception error) { return CallbackSpool.AppendResult.REJECTED; }
        CallbackSpool.AppendResult result = spool(stream).appendRetained(batch);
        if (result == CallbackSpool.AppendResult.SUCCESS || result == CallbackSpool.AppendResult.DUPLICATE) {
            live[stream] = null;
            retainedLiveBytes.set(stream, 0L);
        }
        return result;
    }

    long liveRetainedBytes(int stream) {
        spool(stream); // validates the stream
        return retainedLiveBytes.get(stream);
    }

    void recordLoss(int stream, TelemetryCallbackQueue.Loss loss) {
        // The spool itself serializes its files and shared per-root quota, independently per stream.
        if (loss != null) spool(stream).recordLoss(loss.count, loss.firstWallMs, loss.lastWallMs, loss.reason);
    }

    boolean onTransact(int code, int stream, Parcel data, Parcel reply) {
        if (reply == null) return true;
        try {
            synchronized (streamLock(stream)) {
                return transactLocked(code, stream, data, reply);
            }
        } catch (Exception error) {
            writeUnavailable(code, reply, error instanceof IllegalArgumentException
                ? CollectorHelperProtocol.STATUS_INVALID_REQUEST : CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE,
                errorText(error));
            return true;
        }
    }

    private boolean transactLocked(int code, int stream, Parcel data, Parcel reply) throws Exception {
            CallbackSpool spool = spool(stream);
            if (code == CollectorHelperProtocol.TX_CALLBACK_STATUS) {
                CallbackSpool.Status status = spool.status();
                reply.writeInt(CollectorHelperProtocol.STATUS_OK);
                reply.writeLong(status.footprintBytes);
                reply.writeInt(status.readyBatches + (live[stream] == null ? 0 : 1));
                reply.writeInt(status.quarantinedFiles);
                writeLoss(reply, status.loss);
                reply.writeString(null);
                return true;
            }
            CallbackSpool.Descriptor descriptor = readDescriptor(data);
            if (descriptor != null && descriptor.stream != stream) {
                throw new IllegalArgumentException("callback descriptor stream mismatch");
            }
            if (code == CollectorHelperProtocol.TX_CALLBACK_PENDING_PAGE) {
                long offset = data.readLong();
                int limit = data.readInt();
                if (offset < 0 || limit < 1 || limit > CallbackSpool.MAX_SLICE_BYTES ||
                    (descriptor == null && offset != 0L)) throw new IllegalArgumentException("invalid callback page");
                if (descriptor == null) descriptor = live[stream] == null ? spool.oldest() : live[stream].descriptor;
                byte[] bytes = descriptor == null ? new byte[0] : isLive(stream, descriptor)
                    ? liveSlice(live[stream], offset, limit) : spool.readSlice(descriptor, offset, limit);
                reply.writeInt(CollectorHelperProtocol.STATUS_OK);
                reply.writeString(null);
                writeDescriptor(reply, descriptor);
                reply.writeLong(offset);
                reply.writeByteArray(bytes);
                return true;
            }
            if (code == CollectorHelperProtocol.TX_CALLBACK_ACK) {
                if (descriptor == null) throw new IllegalArgumentException("callback descriptor required");
                CallbackSpool.AckResult result;
                if (isLive(stream, descriptor)) {
                    live[stream] = null;
                    retainedLiveBytes.set(stream, 0L);
                    result = CallbackSpool.AckResult.RELEASED;
                }
                else result = spool.acknowledge(descriptor);
                reply.writeInt(result == CallbackSpool.AckResult.REJECTED
                    ? CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE : CollectorHelperProtocol.STATUS_OK);
                reply.writeInt(result == CallbackSpool.AckResult.RELEASED ? 1 : 0);
                reply.writeString(result == CallbackSpool.AckResult.REJECTED ? "callback ACK rejected" : null);
                return true;
            }
            if (code == CollectorHelperProtocol.TX_CALLBACK_QUARANTINE) {
                if (descriptor == null) throw new IllegalArgumentException("callback descriptor required");
                String reason = data.readString();
                if (reason == null || reason.isEmpty() || reason.length() > 512) throw new IllegalArgumentException("invalid callback quarantine reason");
                int affected;
                if (isLive(stream, descriptor)) {
                    LiveRecord record = live[stream];
                    TelemetryCallbackBatch batch = TelemetryCallbackBatch.decode(record.bytes);
                    spool.quarantineMemory(record.bytes, batch);
                    live[stream] = null;
                    retainedLiveBytes.set(stream, 0L);
                    affected = 1;
                } else affected = spool.quarantine(descriptor, reason);
                reply.writeInt(CollectorHelperProtocol.STATUS_OK); reply.writeInt(affected); reply.writeString(null);
                return true;
            }
            return false;
    }

    private Object streamLock(int stream) {
        spool(stream); // Validate before array access; protocol failures remain typed.
        return streamLocks[stream];
    }

    static void writeUnavailable(int code, Parcel reply, int status, String error) {
        if (reply == null) return;
        if (code == CollectorHelperProtocol.TX_CALLBACK_PENDING_PAGE) {
            reply.writeInt(status); reply.writeString(error); writeDescriptor(reply, null);
            reply.writeLong(0L); reply.writeByteArray(new byte[0]);
        } else if (code == CollectorHelperProtocol.TX_CALLBACK_ACK || code == CollectorHelperProtocol.TX_CALLBACK_QUARANTINE) {
            reply.writeInt(status); reply.writeInt(0); reply.writeString(error);
        } else {
            reply.writeInt(status); reply.writeLong(0L); reply.writeInt(0); reply.writeInt(0);
            writeLoss(reply, null); reply.writeString(error);
        }
    }

    public static void writeDescriptor(Parcel parcel, CallbackSpool.Descriptor value) {
        parcel.writeInt(value == null ? 0 : 1);
        if (value == null) return;
        parcel.writeLong(value.spoolOrder); parcel.writeInt(value.stream); parcel.writeString(value.identity); parcel.writeString(value.fileName);
        parcel.writeLong(value.length); parcel.writeString(value.sha256); parcel.writeString(value.bootId);
        parcel.writeString(value.helperGeneration); parcel.writeLong(value.epoch); parcel.writeLong(value.batchSequence);
    }

    public static CallbackSpool.Descriptor readDescriptor(Parcel parcel) {
        int present = parcel.readInt();
        if (present == 0) return null;
        if (present != 1) throw new IllegalArgumentException("invalid callback descriptor marker");
        long order = parcel.readLong(); int stream = parcel.readInt(); String identity = parcel.readString(); String file = parcel.readString();
        long length = parcel.readLong(); String sha = parcel.readString(); String boot = parcel.readString();
        String generation = parcel.readString(); long epoch = parcel.readLong(); long sequence = parcel.readLong();
        if (length < 1 || length > TelemetryCallbackBatch.MAX_BYTES || identity == null || file == null || sha == null ||
            boot == null || generation == null) throw new IllegalArgumentException("invalid callback descriptor");
        if (order < -1L) throw new IllegalArgumentException("invalid callback spool order");
        return new CallbackSpool.Descriptor(order, stream, identity, file, length, sha, boot, generation, epoch, sequence);
    }

    private static void writeLoss(Parcel parcel, CallbackSpool.Loss loss) {
        parcel.writeInt(loss == null ? 0 : 1);
        if (loss != null) { parcel.writeLong(loss.count); parcel.writeLong(loss.firstWallMs); parcel.writeLong(loss.lastWallMs); parcel.writeString(loss.reason); }
    }

    private static String errorText(Exception error) {
        String value = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private boolean isLive(int stream, CallbackSpool.Descriptor descriptor) {
        return live[stream] != null && live[stream].descriptor.equals(descriptor);
    }

    private static byte[] liveSlice(LiveRecord record, long offset, int limit) {
        if (offset < 0 || offset > record.bytes.length) throw new IllegalArgumentException("callback offset exceeds memory batch");
        int count = (int) Math.min((long) limit, record.bytes.length - offset);
        return java.util.Arrays.copyOfRange(record.bytes, (int) offset, (int) offset + count);
    }

    private static CallbackSpool.Descriptor memoryDescriptor(TelemetryCallbackBatch batch, byte[] bytes) {
        return new CallbackSpool.Descriptor(-1L, batch.stream, batch.identity(), "memory.cbready", bytes.length,
            TelemetryCallbackBatch.digest(bytes), batch.bootId, batch.helperGeneration, batch.epoch, batch.batchSequence);
    }

    private static final class LiveRecord {
        final byte[] bytes; final CallbackSpool.Descriptor descriptor;
        LiveRecord(byte[] bytes, CallbackSpool.Descriptor descriptor) {
            this.bytes = bytes; this.descriptor = descriptor;
        }
    }

    @Override public void close() {
        for (int stream = 1; stream <= 2; stream++) {
            synchronized (streamLock(stream)) {
                closeStream(stream);
                spool(stream).close();
            }
        }
    }

    private void closeStream(int stream) {
        LiveRecord retained = live[stream];
        if (retained == null) return;
        CallbackSpool.AppendResult result = spill(stream);
        if (result == CallbackSpool.AppendResult.SUCCESS || result == CallbackSpool.AppendResult.DUPLICATE) return;
        try {
            TelemetryCallbackBatch batch = TelemetryCallbackBatch.decode(retained.bytes);
            TelemetryCallbackBatch.Event first = batch.events.get(0);
            TelemetryCallbackBatch.Event last = batch.events.get(batch.events.size() - 1);
            spool(stream).recordLoss(batch.events.size(), first.receivedWallMs, last.receivedWallMs, "shutdown_spill");
        } catch (Exception ignored) { }
    }
}
