package com.bydcollector.collector.direct;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Native callback evidence. Delivery (live/replay) is deliberately not part of its identity. */
public final class TelemetryCallbackBatch {
    private static final int MAGIC = 0x42594342;
    public static final int VERSION = 1;
    public static final int TYPE_INT = 1;
    public static final int TYPE_FLOAT = 2;
    public static final int TYPE_BYTES = 3;
    public static final int MAX_EVENTS = 512;
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final long FLUSH_MS = 500L;
    public final String bootId;
    public final String helperGeneration;
    public final int stream;
    public final long epoch;
    public final long batchSequence;
    public final List<Event> events;

    public TelemetryCallbackBatch(String bootId, String helperGeneration, int stream,
                                  long epoch, long batchSequence, List<Event> events) {
        this.bootId = checkedText(bootId, 256, "boot ID");
        this.helperGeneration = checkedText(helperGeneration, 256, "helper generation");
        if (stream != 1 && stream != 2) throw new IllegalArgumentException("invalid callback stream");
        if (epoch < 0 || batchSequence < 0) throw new IllegalArgumentException("negative callback identity");
        if (events == null || events.isEmpty() || events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException("invalid callback event count");
        }
        long previous = -1;
        long budget = 1024;
        for (Event event : events) {
            Objects.requireNonNull(event, "callback event");
            if (event.sequence <= previous) throw new IllegalArgumentException("unordered callback events");
            previous = event.sequence;
            budget += event.retainedBytes();
        }
        if (budget > MAX_BYTES) throw new IllegalArgumentException("callback batch exceeds byte cap");
        this.stream = stream;
        this.epoch = epoch;
        this.batchSequence = batchSequence;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }

    public String identity() {
        return "callback:" + component(bootId) + ":" + component(helperGeneration) + ":" +
            stream + ":" + epoch + ":" + batchSequence;
    }

    public String eventIdentity(Event event) {
        return "callback:" + component(bootId) + ":" + component(helperGeneration) + ":" +
            stream + ":" + epoch + ":" + event.sequence;
    }

    private static String component(String text) { return text.length() + ":" + text; }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            writeText(output, bootId);
            writeText(output, helperGeneration);
            output.writeInt(stream);
            output.writeLong(epoch);
            output.writeLong(batchSequence);
            output.writeInt(events.size());
            for (Event event : events) {
                output.writeLong(event.sequence);
                output.writeInt(event.device);
                output.writeInt(event.fid);
                output.writeInt(event.nativeType);
                output.writeInt(event.rawBits);
                output.writeLong(event.receivedWallMs);
                output.writeLong(event.receivedElapsedMs);
                output.writeBoolean(event.sourceWallMs != null);
                if (event.sourceWallMs != null) output.writeLong(event.sourceWallMs);
                writeText(output, event.quality);
                output.writeInt(event.payload == null ? -1 : event.payload.length);
                if (event.payload != null) output.write(event.payload);
            }
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("callback batch exceeds byte cap");
        return bytes.toByteArray();
    }

    public static TelemetryCallbackBatch decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("invalid callback payload size");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("unsupported callback format");
            }
            String boot = readText(input, 256);
            String generation = readText(input, 256);
            int stream = input.readInt();
            long epoch = input.readLong();
            long sequence = input.readLong();
            int count = input.readInt();
            if (count < 1 || count > MAX_EVENTS) throw new IOException("invalid callback count");
            List<Event> events = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                long eventSequence = input.readLong();
                int device = input.readInt();
                int fid = input.readInt();
                int nativeType = input.readInt();
                int rawBits = input.readInt();
                long wall = input.readLong();
                long elapsed = input.readLong();
                int sourceMarker = input.readUnsignedByte();
                if (sourceMarker > 1) throw new IOException("invalid callback clock marker");
                Long sourceWall = sourceMarker == 1 ? input.readLong() : null;
                String quality = readText(input, 128);
                int length = input.readInt();
                if (length < -1 || length > input.available()) throw new IOException("invalid callback byte length");
                byte[] payload = length < 0 ? null : new byte[length];
                if (payload != null) input.readFully(payload);
                events.add(new Event(eventSequence, device, fid, nativeType, rawBits, payload,
                    wall, elapsed, sourceWall, quality));
            }
            if (input.available() != 0) throw new IOException("trailing callback payload");
            return new TelemetryCallbackBatch(boot, generation, stream, epoch, sequence, events);
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid callback evidence: " + error.getMessage(), error);
        }
    }

    public static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] chars = new char[hash.length * 2];
            char[] hex = "0123456789abcdef".toCharArray();
            for (int i = 0; i < hash.length; i++) {
                chars[i * 2] = hex[(hash[i] & 0xff) >>> 4];
                chars[i * 2 + 1] = hex[hash[i] & 15];
            }
            return new String(chars);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String checkedText(String text, int maxBytes, String field) {
        if (text == null || text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > maxBytes ||
            text.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid " + field);
        return text;
    }

    private static void writeText(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input, int maxBytes) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maxBytes || length > input.available()) throw new IOException("invalid callback text length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        String result = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, result.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("invalid callback UTF-8");
        }
        return result;
    }

    public static final class Event {
        public final long sequence;
        public final int device;
        public final int fid;
        public final int nativeType;
        /** Integer value or exact IEEE754 float bits, never a float converted to an integer. */
        public final int rawBits;
        private final byte[] payload;
        public final long receivedWallMs;
        public final long receivedElapsedMs;
        public final Long sourceWallMs;
        public final String quality;

        public Event(long sequence, int device, int fid, int nativeType, int rawBits, byte[] payload,
                     long receivedWallMs, long receivedElapsedMs, Long sourceWallMs, String quality) {
            if (sequence < 0 || receivedWallMs < 0 || receivedElapsedMs < 0 ||
                (sourceWallMs != null && sourceWallMs < 0)) throw new IllegalArgumentException("invalid callback clocks/sequence");
            if (nativeType < TYPE_INT || nativeType > TYPE_BYTES) throw new IllegalArgumentException("unknown native type");
            if ((nativeType == TYPE_BYTES) != (payload != null)) throw new IllegalArgumentException("callback native payload mismatch");
            if (payload != null && payload.length > MAX_BYTES - 2048) throw new IllegalArgumentException("oversize callback");
            this.sequence = sequence;
            this.device = device;
            this.fid = fid;
            this.nativeType = nativeType;
            this.rawBits = rawBits;
            this.payload = payload == null ? null : payload.clone();
            this.receivedWallMs = receivedWallMs;
            this.receivedElapsedMs = receivedElapsedMs;
            this.sourceWallMs = sourceWallMs;
            this.quality = checkedText(quality, 128, "callback quality");
        }

        public byte[] rawBytes() { return payload == null ? null : payload.clone(); }
        public int payloadBytes() { return payload == null ? 0 : payload.length; }
        public int retainedBytes() { return 256 + payloadBytes(); }
    }
}
