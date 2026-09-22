package com.bydcollector.collector.direct;

import android.os.Parcel;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Immutable acquisition provenance of a scalar reused from the callback cache, never a fresh getter. */
public final class CallbackValueSource {
    public final String bootId;
    public final String helperGeneration;
    public final int stream;
    public final long epoch;
    public final long eventSequence;
    public final int device;
    public final int fid;
    public final int nativeType;
    public final int rawBits;
    public final long receivedWallMs;
    public final long receivedElapsedMs;
    public final Long sourceWallMs;
    public final String quality;

    public CallbackValueSource(String bootId, String helperGeneration, int stream, long epoch,
        long eventSequence, int device, int fid, int nativeType, int rawBits,
        long receivedWallMs, long receivedElapsedMs, Long sourceWallMs, String quality) {
        if (!validText(bootId, 256) || !validText(helperGeneration, 256) || !validText(quality, 128) ||
            (stream != 1 && stream != 2) || epoch < 0 || eventSequence < 0 ||
            (nativeType != TelemetryCallbackBatch.TYPE_INT && nativeType != TelemetryCallbackBatch.TYPE_FLOAT) ||
            receivedWallMs < 0 || receivedElapsedMs < 0 || (sourceWallMs != null && sourceWallMs < 0)) {
            throw new IllegalArgumentException("invalid callback value source");
        }
        this.bootId = bootId;
        this.helperGeneration = helperGeneration;
        this.stream = stream;
        this.epoch = epoch;
        this.eventSequence = eventSequence;
        this.device = device;
        this.fid = fid;
        this.nativeType = nativeType;
        this.rawBits = rawBits;
        this.receivedWallMs = receivedWallMs;
        this.receivedElapsedMs = receivedElapsedMs;
        this.sourceWallMs = sourceWallMs;
        this.quality = quality;
    }

    public static CallbackValueSource from(TelemetryCallbackBatch batch, TelemetryCallbackBatch.Event event) {
        return new CallbackValueSource(batch.bootId, batch.helperGeneration, batch.stream, batch.epoch,
            event.sequence, event.device, event.fid, event.nativeType, event.rawBits,
            event.receivedWallMs, event.receivedElapsedMs, event.sourceWallMs, event.quality);
    }

    public boolean matches(int tx, int dev, int field, int raw) {
        return device == dev && fid == field && rawBits == raw &&
            ((tx == CollectorHelperProtocol.AUTO_TX_INT && nativeType == TelemetryCallbackBatch.TYPE_INT) ||
             (tx == CollectorHelperProtocol.AUTO_TX_FLOAT && nativeType == TelemetryCallbackBatch.TYPE_FLOAT));
    }

    public static void writeNullable(DataOutput out, CallbackValueSource value) throws IOException {
        out.writeByte(value == null ? 0 : 1);
        if (value == null) return;
        out.writeUTF(value.bootId); out.writeUTF(value.helperGeneration); out.writeInt(value.stream);
        out.writeLong(value.epoch); out.writeLong(value.eventSequence);
        out.writeInt(value.device); out.writeInt(value.fid); out.writeInt(value.nativeType); out.writeInt(value.rawBits);
        out.writeLong(value.receivedWallMs); out.writeLong(value.receivedElapsedMs);
        out.writeByte(value.sourceWallMs == null ? 0 : 1);
        if (value.sourceWallMs != null) out.writeLong(value.sourceWallMs);
        out.writeUTF(value.quality);
    }

    public static CallbackValueSource readNullable(DataInput in) throws IOException {
        int present = in.readUnsignedByte();
        if (present == 0) return null;
        if (present != 1) throw new IOException("invalid callback source marker");
        String boot = in.readUTF(), generation = in.readUTF(); int stream = in.readInt();
        long epoch = in.readLong(), sequence = in.readLong();
        int device = in.readInt(), fid = in.readInt(), type = in.readInt(), raw = in.readInt();
        long wall = in.readLong(), elapsed = in.readLong();
        int hasSource = in.readUnsignedByte();
        if (hasSource != 0 && hasSource != 1) throw new IOException("invalid callback source time marker");
        Long source = hasSource == 0 ? null : in.readLong();
        try {
            return new CallbackValueSource(boot, generation, stream, epoch, sequence, device, fid, type,
                raw, wall, elapsed, source, in.readUTF());
        } catch (IllegalArgumentException invalid) { throw new IOException("invalid callback source", invalid); }
    }

    public static void writeNullable(Parcel out, CallbackValueSource value) {
        out.writeInt(value == null ? 0 : 1);
        if (value == null) return;
        out.writeString(value.bootId); out.writeString(value.helperGeneration); out.writeInt(value.stream);
        out.writeLong(value.epoch); out.writeLong(value.eventSequence);
        out.writeInt(value.device); out.writeInt(value.fid); out.writeInt(value.nativeType); out.writeInt(value.rawBits);
        out.writeLong(value.receivedWallMs); out.writeLong(value.receivedElapsedMs);
        out.writeInt(value.sourceWallMs == null ? 0 : 1);
        if (value.sourceWallMs != null) out.writeLong(value.sourceWallMs);
        out.writeString(value.quality);
    }

    public static CallbackValueSource readNullable(Parcel in) {
        int present = in.readInt();
        if (present == 0) return null;
        if (present != 1) throw new IllegalArgumentException("invalid callback source marker");
        String boot = in.readString(), generation = in.readString(); int stream = in.readInt();
        long epoch = in.readLong(), sequence = in.readLong();
        int device = in.readInt(), fid = in.readInt(), type = in.readInt(), raw = in.readInt();
        long wall = in.readLong(), elapsed = in.readLong();
        int hasSource = in.readInt();
        if (hasSource != 0 && hasSource != 1) throw new IllegalArgumentException("invalid callback source time marker");
        Long source = hasSource == 0 ? null : in.readLong();
        return new CallbackValueSource(boot, generation, stream, epoch, sequence, device, fid, type,
            raw, wall, elapsed, source, in.readString());
    }

    private static boolean validText(String value, int max) {
        return value != null && !value.isEmpty() && value.length() <= max;
    }
}
