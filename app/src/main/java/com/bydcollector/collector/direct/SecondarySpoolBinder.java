package com.bydcollector.collector.direct;

import android.os.Parcel;

/** Passive spool transport. The daemon validates caller UID and interface before dispatch. */
public final class SecondarySpoolBinder implements AutoCloseable {
    public static final int MAX_REPLY_BYTES = 256 * 1024;
    private final SecondaryTelemetrySpool spool;
    private final String openError;

    SecondarySpoolBinder() {
        SecondaryTelemetrySpool opened = null;
        String failure = null;
        try {
            opened = SecondaryTelemetrySpool.open();
        } catch (Exception error) {
            failure = errorText(error);
        }
        spool = opened;
        openError = failure;
    }

    SecondaryTelemetrySpool spool() { return spool; }
    String openError() { return openError; }

    boolean onTransact(int code, Parcel data, Parcel reply) {
        boolean page = code == CollectorHelperProtocol.TX_SECONDARY_PENDING_PAGE;
        if (!page && code != CollectorHelperProtocol.TX_SECONDARY_ACK &&
            code != CollectorHelperProtocol.TX_SECONDARY_QUARANTINE) return false;
        // ACK/quarantine are synchronous, never fire-and-forget mutations.
        if (reply == null) return true;
        try {
            if (spool == null) throw new IllegalStateException(openError == null ? "secondary spool unavailable" : openError);
            SecondaryTelemetrySpool.Descriptor descriptor = readDescriptor(data);
            if (page) {
                long offset = data.readLong();
                int limit = data.readInt();
                if (offset < 0 || limit < 1 || limit > SecondaryTelemetrySpool.MAX_SLICE_BYTES ||
                    (descriptor == null && offset != 0)) throw new IllegalArgumentException("invalid secondary page request");
                if (descriptor == null) descriptor = spool.oldest();
                byte[] bytes = descriptor == null ? new byte[0] : spool.readSlice(descriptor, offset, limit);
                writePage(reply, CollectorHelperProtocol.STATUS_OK, descriptor, offset, bytes, null);
            } else {
                if (descriptor == null) throw new IllegalArgumentException("secondary descriptor is required");
                if (code == CollectorHelperProtocol.TX_SECONDARY_ACK) {
                    SecondaryTelemetrySpool.AckResult result = spool.acknowledge(descriptor);
                    boolean ok = result != SecondaryTelemetrySpool.AckResult.REJECTED;
                    // NOT_FOUND is an idempotent ACK: the importer already has its durable receipt.
                    writeAction(reply, ok ? CollectorHelperProtocol.STATUS_OK : CollectorHelperProtocol.STATUS_READ_ERROR,
                        result == SecondaryTelemetrySpool.AckResult.RELEASED ? 1 : 0, ok ? null : "secondary ACK rejected");
                } else {
                    String reason = data.readString();
                    if (reason == null || reason.length() > 512) throw new IllegalArgumentException("invalid quarantine reason");
                    writeAction(reply, CollectorHelperProtocol.STATUS_OK, spool.quarantine(descriptor, reason), null);
                }
            }
        } catch (Exception error) {
            int status = error instanceof IllegalArgumentException
                ? CollectorHelperProtocol.STATUS_INVALID_REQUEST : CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE;
            if (page) writePage(reply, status, null, 0, new byte[0], errorText(error));
            else writeAction(reply, status, 0, errorText(error));
        }
        return true;
    }

    static void writeUnavailable(int code, Parcel reply, int status, String error) {
        if (reply == null) return;
        if (code == CollectorHelperProtocol.TX_SECONDARY_PENDING_PAGE) {
            writePage(reply, status, null, 0L, new byte[0], error);
        } else {
            writeAction(reply, status, 0, error);
        }
    }

    void writeStatus(Parcel reply, boolean inFlight) {
        if (reply == null) return;
        try {
            if (spool == null) throw new IllegalStateException(
                openError == null ? "secondary spool unavailable" : openError);
            SecondaryTelemetrySpool.Status status = spool.status();
            reply.writeInt(CollectorHelperProtocol.STATUS_OK);
            reply.writeInt(status.readyRecords);
            reply.writeInt(inFlight ? 1 : 0);
            reply.writeString(null);
        } catch (Exception error) {
            reply.writeInt(CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE);
            reply.writeInt(0);
            reply.writeInt(inFlight ? 1 : 0);
            reply.writeString(errorText(error));
        }
    }

    public static void writeDescriptor(Parcel parcel, SecondaryTelemetrySpool.Descriptor descriptor) {
        parcel.writeInt(descriptor == null ? 0 : 1);
        if (descriptor == null) return;
        parcel.writeLong(descriptor.spoolOrder);
        parcel.writeString(descriptor.identity.bootId);
        parcel.writeString(descriptor.identity.helperGeneration);
        parcel.writeString(descriptor.identity.gapId);
        parcel.writeLong(descriptor.identity.sequence);
        parcel.writeString(descriptor.kind.name());
        parcel.writeLong(descriptor.capturedWallMs);
        parcel.writeLong(descriptor.capturedElapsedMs);
        parcel.writeString(descriptor.fileName);
        parcel.writeLong(descriptor.length);
        parcel.writeString(descriptor.sha256);
    }

    public static SecondaryTelemetrySpool.Descriptor readDescriptor(Parcel parcel) {
        int present = parcel.readInt();
        if (present == 0) return null;
        if (present != 1) throw new IllegalArgumentException("invalid secondary descriptor marker");
        long order = parcel.readLong();
        SecondaryTelemetrySpool.CycleIdentity identity = new SecondaryTelemetrySpool.CycleIdentity(
            parcel.readString(), parcel.readString(), parcel.readString(), parcel.readLong());
        SecondaryTelemetrySpool.Kind kind = SecondaryTelemetrySpool.Kind.valueOf(parcel.readString());
        long wall = parcel.readLong();
        long elapsed = parcel.readLong();
        String file = parcel.readString();
        long length = parcel.readLong();
        String digest = parcel.readString();
        if (length > SecondaryTelemetrySpool.MAX_SPOOL_BYTES) throw new IllegalArgumentException("secondary record exceeds quota");
        return new SecondaryTelemetrySpool.Descriptor(order, identity, kind, wall, elapsed, file, length, digest);
    }

    private static void writePage(Parcel reply, int status, SecondaryTelemetrySpool.Descriptor descriptor,
        long offset, byte[] bytes, String error) {
        // Identifiers are <=256 chars, error <=512; fixed header stays well below the remaining 32 KiB.
        if (bytes.length > SecondaryTelemetrySpool.MAX_SLICE_BYTES) throw new IllegalArgumentException("oversize page");
        reply.writeInt(status);
        reply.writeString(error);
        writeDescriptor(reply, descriptor);
        reply.writeLong(offset);
        reply.writeByteArray(bytes);
    }

    private static void writeAction(Parcel reply, int status, int affected, String error) {
        reply.writeInt(status);
        reply.writeInt(affected);
        reply.writeString(error);
    }

    private static String errorText(Exception error) {
        String text = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        return text.length() <= 512 ? text : text.substring(0, 512);
    }

    @Override public void close() { if (spool != null) spool.close(); }
}
