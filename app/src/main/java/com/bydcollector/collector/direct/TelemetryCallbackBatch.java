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
    public static final int VERSION = 2;
    public static final int LEGACY_VERSION = 1;
    public static final int TYPE_INT = 1;
    public static final int TYPE_FLOAT = 2;
    public static final int TYPE_BYTES = 3;
    public static final int MAX_EVENTS = 512;
    public static final int MAX_RECORDS = 512;
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final long FLUSH_MS = 500L;
    public final String bootId;
    public final String helperGeneration;
    public final int stream;
    /** APP-owned delivery fence retained for transport identity and legacy decoding. */
    public final long epoch;
    public final long batchSequence;
    /** Helper-owned source continuity generation, independent of APP lease changes. */
    public final long sourceEpoch;
    public final List<Event> events;
    public final List<SourceBoundary> boundaries;
    public final int recordCount;
    public final long firstRecordSequence;
    public final long lastRecordSequence;
    public final long firstCaptureWallMs;
    public final long lastCaptureWallMs;
    public final long firstCaptureElapsedMs;
    public final long lastCaptureElapsedMs;
    private final int formatVersion;

    public TelemetryCallbackBatch(String bootId, String helperGeneration, int stream,
                                  long epoch, long batchSequence, List<Event> events) {
        this(bootId, helperGeneration, stream, epoch, batchSequence, epoch, events,
            Collections.emptyList(), VERSION);
    }

    public TelemetryCallbackBatch(String bootId, String helperGeneration, int stream,
                                  long epoch, long batchSequence, long sourceEpoch,
                                  List<Event> events, List<SourceBoundary> boundaries) {
        this(bootId, helperGeneration, stream, epoch, batchSequence, sourceEpoch, events, boundaries, VERSION);
    }

    private TelemetryCallbackBatch(String bootId, String helperGeneration, int stream,
                                   long epoch, long batchSequence, long sourceEpoch,
                                   List<Event> events, List<SourceBoundary> boundaries, int formatVersion) {
        this.bootId = checkedText(bootId, 256, "boot ID");
        this.helperGeneration = checkedText(helperGeneration, 256, "helper generation");
        if (stream != 1 && stream != 2) throw new IllegalArgumentException("invalid callback stream");
        if (epoch < 0 || batchSequence < 0 || sourceEpoch < 0) throw new IllegalArgumentException("negative callback identity");
        if (formatVersion != VERSION && formatVersion != LEGACY_VERSION) throw new IllegalArgumentException("invalid callback version");
        if (events == null || boundaries == null || events.size() + boundaries.size() < 1 ||
            events.size() > MAX_EVENTS || events.size() + boundaries.size() > MAX_RECORDS ||
            (formatVersion == LEGACY_VERSION && (!boundaries.isEmpty() || events.isEmpty()))) {
            throw new IllegalArgumentException("invalid callback record count");
        }
        long budget = 1024;
        for (Event event : events) {
            Objects.requireNonNull(event, "callback event");
            budget += event.retainedBytes();
        }
        for (SourceBoundary boundary : boundaries) {
            Objects.requireNonNull(boundary, "callback boundary");
            budget += boundary.retainedBytes();
        }
        List<RawRecord> ordered = new ArrayList<>(events.size() + boundaries.size());
        validateMonotonic(events, "callback events");
        validateMonotonic(boundaries, "callback boundaries");
        ordered.addAll(events);
        ordered.addAll(boundaries);
        ordered.sort((left, right) -> Long.compare(left.recordSequence(), right.recordSequence()));
        long previous = -1;
        for (RawRecord record : ordered) {
            if (record.recordSequence() <= previous) throw new IllegalArgumentException("unordered callback records");
            previous = record.recordSequence();
        }
        if (budget > MAX_BYTES) throw new IllegalArgumentException("callback batch exceeds byte cap");
        this.stream = stream;
        this.epoch = epoch;
        this.batchSequence = batchSequence;
        this.sourceEpoch = sourceEpoch;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.boundaries = Collections.unmodifiableList(new ArrayList<>(boundaries));
        this.recordCount = ordered.size();
        RawRecord first = ordered.get(0), last = ordered.get(ordered.size() - 1);
        this.firstRecordSequence = first.recordSequence();
        this.lastRecordSequence = last.recordSequence();
        this.firstCaptureWallMs = first.captureWallMs();
        this.lastCaptureWallMs = last.captureWallMs();
        this.firstCaptureElapsedMs = first.captureElapsedMs();
        this.lastCaptureElapsedMs = last.captureElapsedMs();
        this.formatVersion = formatVersion;
    }

    private static void validateMonotonic(List<? extends RawRecord> records, String name) {
        long previous = -1L;
        for (RawRecord record : records) {
            Objects.requireNonNull(record, name);
            if (record.recordSequence() <= previous) throw new IllegalArgumentException("unordered " + name);
            previous = record.recordSequence();
        }
    }

    public String identity() {
        return "callback:" + component(bootId) + ":" + component(helperGeneration) + ":" +
            stream + ":" + epoch + ":" + batchSequence;
    }

    public String eventIdentity(Event event) {
        long identityEpoch = formatVersion == LEGACY_VERSION ? epoch : sourceEpoch;
        return "callback:" + component(bootId) + ":" + component(helperGeneration) + ":" +
            stream + ":" + identityEpoch + ":" + event.sequence;
    }

    public int formatVersion() { return formatVersion; }

    private static String component(String text) { return text.length() + ":" + text; }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(formatVersion);
            writeText(output, bootId);
            writeText(output, helperGeneration);
            output.writeInt(stream);
            output.writeLong(epoch);
            output.writeLong(batchSequence);
            if (formatVersion == VERSION) {
                output.writeLong(sourceEpoch);
                output.writeInt(recordCount);
                output.writeLong(firstRecordSequence);
                output.writeLong(lastRecordSequence);
                output.writeLong(firstCaptureWallMs);
                output.writeLong(lastCaptureWallMs);
                output.writeLong(firstCaptureElapsedMs);
                output.writeLong(lastCaptureElapsedMs);
            }
            output.writeInt(events.size());
            for (Event event : events) {
                writeEvent(output, event);
            }
            if (formatVersion == VERSION) {
                output.writeInt(boundaries.size());
                for (SourceBoundary boundary : boundaries) writeBoundary(output, boundary);
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
            if (input.readInt() != MAGIC) {
                throw new IOException("unsupported callback format");
            }
            int version = input.readInt();
            if (version != VERSION && version != LEGACY_VERSION) throw new IOException("unsupported callback format");
            String boot = readText(input, 256);
            String generation = readText(input, 256);
            int stream = input.readInt();
            long epoch = input.readLong();
            long sequence = input.readLong();
            long sourceEpoch = epoch;
            int recordCount = -1;
            long firstRecordSequence = -1L, lastRecordSequence = -1L;
            long firstCaptureWallMs = -1L, lastCaptureWallMs = -1L;
            long firstCaptureElapsedMs = -1L, lastCaptureElapsedMs = -1L;
            if (version == VERSION) {
                sourceEpoch = input.readLong();
                recordCount = input.readInt();
                firstRecordSequence = input.readLong();
                lastRecordSequence = input.readLong();
                firstCaptureWallMs = input.readLong();
                lastCaptureWallMs = input.readLong();
                firstCaptureElapsedMs = input.readLong();
                lastCaptureElapsedMs = input.readLong();
                if (recordCount < 1 || recordCount > MAX_RECORDS) throw new IOException("invalid callback record count");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_EVENTS || (version == LEGACY_VERSION && count < 1)) {
                throw new IOException("invalid callback count");
            }
            List<Event> events = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                events.add(readEvent(input));
            }
            List<SourceBoundary> boundaries = new ArrayList<>();
            if (version == VERSION) {
                int boundaryCount = input.readInt();
                if (boundaryCount < 0 || boundaryCount > MAX_RECORDS || count + boundaryCount != recordCount) {
                    throw new IOException("invalid callback boundary count");
                }
                for (int index = 0; index < boundaryCount; index++) boundaries.add(readBoundary(input));
            }
            if (input.available() != 0) throw new IOException("trailing callback payload");
            TelemetryCallbackBatch result = new TelemetryCallbackBatch(
                boot, generation, stream, epoch, sequence, sourceEpoch, events, boundaries, version);
            if (version == VERSION && (result.recordCount != recordCount ||
                result.firstRecordSequence != firstRecordSequence || result.lastRecordSequence != lastRecordSequence ||
                result.firstCaptureWallMs != firstCaptureWallMs || result.lastCaptureWallMs != lastCaptureWallMs ||
                result.firstCaptureElapsedMs != firstCaptureElapsedMs || result.lastCaptureElapsedMs != lastCaptureElapsedMs)) {
                throw new IOException("callback record summary mismatch");
            }
            return result;
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid callback evidence: " + error.getMessage(), error);
        }
    }

    private static void writeEvent(DataOutputStream output, Event event) throws IOException {
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

    private static Event readEvent(DataInputStream input) throws IOException {
        long sequence = input.readLong();
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
        return new Event(sequence, device, fid, nativeType, rawBits, payload, wall, elapsed, sourceWall, quality);
    }

    private static void writeBoundary(DataOutputStream output, SourceBoundary boundary) throws IOException {
        output.writeLong(boundary.recordSequence);
        output.writeInt(boundary.scope);
        output.writeInt(boundary.state);
        output.writeInt(boundary.device);
        output.writeInt(boundary.fid);
        output.writeInt(boundary.tx);
        output.writeLong(boundary.captureWallMs);
        output.writeLong(boundary.captureElapsedMs);
        writeText(output, boundary.reason);
        writeNullableText(output, boundary.diagnostic);
        writeNullableText(output, boundary.seedKind);
        writeNullableText(output, boundary.seedIdentity);
        output.writeLong(boundary.seedRecordSequence == null ? -1L : boundary.seedRecordSequence);
    }

    private static SourceBoundary readBoundary(DataInputStream input) throws IOException {
        long sequence = input.readLong();
        int scope = input.readInt();
        int state = input.readInt();
        int device = input.readInt();
        int fid = input.readInt();
        int tx = input.readInt();
        long wall = input.readLong();
        long elapsed = input.readLong();
        String reason = readText(input, 512);
        String diagnostic = readNullableText(input, 2048);
        String seedKind = readNullableText(input, 32);
        String seedIdentity = readNullableText(input, 512);
        long seedSequence = input.readLong();
        return new SourceBoundary(sequence, scope, state, device, fid, tx, wall, elapsed,
            reason, diagnostic, seedKind, seedIdentity, seedSequence < 0 ? null : seedSequence);
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

    private static void writeNullableText(DataOutputStream output, String text) throws IOException {
        output.writeBoolean(text != null);
        if (text != null) writeText(output, text);
    }

    private static String readNullableText(DataInputStream input, int maxBytes) throws IOException {
        int marker = input.readUnsignedByte();
        if (marker > 1) throw new IOException("invalid nullable text marker");
        return marker == 1 ? readText(input, maxBytes) : null;
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

    public interface RawRecord {
        long recordSequence();
        long captureWallMs();
        long captureElapsedMs();
        int retainedBytes();
    }

    public static final class Event implements RawRecord {
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
        @Override public long recordSequence() { return sequence; }
        @Override public long captureWallMs() { return receivedWallMs; }
        @Override public long captureElapsedMs() { return receivedElapsedMs; }
    }

    /** Source availability evidence; it carries no synthetic telemetry value. */
    public static final class SourceBoundary implements RawRecord {
        public static final int SCOPE_ADDRESS = 1;
        public static final int SCOPE_NATIVE_KEY = 2;
        public static final int SCOPE_STREAM = 3;
        public static final int STATE_AVAILABLE = 1;
        public static final int STATE_LOST = 2;
        public final long recordSequence;
        public final int scope;
        public final int state;
        public final int device;
        public final int fid;
        public final int tx;
        public final long captureWallMs;
        public final long captureElapsedMs;
        public final String reason;
        public final String diagnostic;
        public final String seedKind;
        public final String seedIdentity;
        public final Long seedRecordSequence;

        public SourceBoundary(long recordSequence, int scope, int state, int device, int fid, int tx,
                              long captureWallMs, long captureElapsedMs, String reason,
                              String diagnostic, String seedKind, String seedIdentity, Long seedRecordSequence) {
            if (recordSequence < 0 || captureWallMs < 0 || captureElapsedMs < 0) {
                throw new IllegalArgumentException("invalid boundary sequence/clocks");
            }
            if (scope < SCOPE_ADDRESS || scope > SCOPE_STREAM ||
                (state != STATE_AVAILABLE && state != STATE_LOST)) throw new IllegalArgumentException("invalid boundary scope/state");
            if ((scope == SCOPE_STREAM && (device != 0 || fid != 0 || tx != 0)) ||
                (scope == SCOPE_NATIVE_KEY && tx != 0) ||
                (scope == SCOPE_ADDRESS && tx < 0)) throw new IllegalArgumentException("boundary scope does not match its address");
            if (state == STATE_AVAILABLE && (seedKind == null || seedKind.isBlank() || seedIdentity == null ||
                seedIdentity.isBlank() || seedRecordSequence == null || seedRecordSequence < 0L ||
                seedRecordSequence >= recordSequence)) {
                throw new IllegalArgumentException("available boundary must reference an earlier real seed");
            }
            if (state == STATE_LOST && (seedKind != null || seedIdentity != null || seedRecordSequence != null)) {
                throw new IllegalArgumentException("lost boundary cannot carry a seed");
            }
            this.recordSequence = recordSequence;
            this.scope = scope;
            this.state = state;
            this.device = device;
            this.fid = fid;
            this.tx = tx;
            this.captureWallMs = captureWallMs;
            this.captureElapsedMs = captureElapsedMs;
            this.reason = checkedText(reason, 512, "boundary reason");
            this.diagnostic = diagnostic == null ? null : checkedText(diagnostic, 2048, "boundary diagnostic");
            this.seedKind = seedKind == null ? null : checkedText(seedKind, 32, "boundary seed kind");
            this.seedIdentity = seedIdentity == null ? null : checkedText(seedIdentity, 512, "boundary seed identity");
            this.seedRecordSequence = seedRecordSequence;
        }

        @Override public long recordSequence() { return recordSequence; }
        @Override public long captureWallMs() { return captureWallMs; }
        @Override public long captureElapsedMs() { return captureElapsedMs; }
        @Override public int retainedBytes() {
            return 96 + textBytes(reason) + textBytes(diagnostic) +
                textBytes(seedKind) + textBytes(seedIdentity);
        }
    }

    private static int textBytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
