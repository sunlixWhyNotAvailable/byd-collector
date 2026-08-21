package com.bydcollector.collector.direct;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Base64;

//helper-owned durable raw spool; the app acknowledges a sample only after its own transaction commits
final class TelemetryWorkerSpool implements AutoCloseable {
    static final String SPOOL_DIRECTORY_PATH = "/data/local/tmp/bydcollector_telemetry_spool";
    static final int RECORD_VERSION = 1;
    static final long MAX_SPOOL_BYTES = 128L * 1024L * 1024L;
    private static final String READY_SUFFIX = ".ready";
    private static final String TMP_SUFFIX = ".tmp";
    private static final String BAD_SUFFIX = ".bad";
    private static final SampleValidator ACCEPT_ALL = sample -> { };

    private final File directory;
    private final long maxBytes;
    private boolean closed;

    static TelemetryWorkerSpool open() {
        return open(new File(SPOOL_DIRECTORY_PATH), MAX_SPOOL_BYTES);
    }

    //package-private seam for deterministic filesystem tests
    static TelemetryWorkerSpool openForTest(File directory, long maxBytes) {
        return open(directory, maxBytes);
    }

    private static TelemetryWorkerSpool open(File directory, long maxBytes) {
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IllegalStateException("cannot create telemetry worker spool directory: " + directory);
        }
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        return new TelemetryWorkerSpool(directory, maxBytes);
    }

    private TelemetryWorkerSpool(File directory, long maxBytes) {
        this.directory = directory;
        this.maxBytes = maxBytes;
    }

    synchronized boolean append(Sample sample) {
        ensureOpen();
        if (sample == null) throw new IllegalArgumentException("sample is required");
        File ready = readyFile(sample.identity);
        File temporary = temporaryFile(sample.identity);
        if (ready.exists() || temporary.exists()) return false;

        byte[] payload;
        try {
            payload = encode(sample).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("cannot encode telemetry worker sample", error);
        }
        long footprint = footprintBytes();
        if (footprint > maxBytes || payload.length > maxBytes - footprint) return false;
        try {
            writeDurably(temporary, payload);
            Files.move(temporary.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException error) {
            //A .tmp is never visible to pending(); remove only this failed write.
            if (temporary.isFile()) temporary.delete();
            throw new IllegalStateException("cannot persist telemetry worker sample", error);
        }
    }

    synchronized boolean canAppend() {
        ensureOpen();
        return footprintBytes() < maxBytes;
    }

    synchronized List<Sample> pending(int limit) {
        return pending(limit, ACCEPT_ALL);
    }

    synchronized List<Sample> pending(int limit, SampleValidator validator) {
        ensureOpen();
        if (validator == null) throw new IllegalArgumentException("sample validator is required");
        if (limit < 1 || limit > CollectorHelperProtocol.MAX_PENDING_WORKER_SAMPLES) {
            throw new IllegalArgumentException("invalid pending sample limit: " + limit);
        }
        File[] files = directory.listFiles((dir, name) -> name.endsWith(READY_SUFFIX));
        if (files == null) throw new IllegalStateException("cannot list telemetry worker spool: " + directory);
        List<PendingRecord> records = new ArrayList<PendingRecord>();
        for (File file : files) {
            try {
                Sample sample = decode(readBytes(file));
                if (!file.equals(readyFile(sample.identity))) throw new IllegalArgumentException("record filename does not match identity");
                validator.validate(sample);
                records.add(new PendingRecord(file, sample));
            } catch (Exception error) {
                quarantine(file);
            }
        }
        Collections.sort(records, new Comparator<PendingRecord>() {
            @Override public int compare(PendingRecord left, PendingRecord right) {
                int result = compareLong(left.sample.capturedWallMs, right.sample.capturedWallMs);
                if (result != 0) return result;
                result = compareLong(left.sample.capturedElapsedMs, right.sample.capturedElapsedMs);
                if (result != 0) return result;
                result = left.sample.identity.bootId.compareTo(right.sample.identity.bootId);
                if (result != 0) return result;
                result = left.sample.identity.helperGeneration.compareTo(right.sample.identity.helperGeneration);
                if (result != 0) return result;
                return compareLong(left.sample.identity.pollSequence, right.sample.identity.pollSequence);
            }
        });
        List<Sample> result = new ArrayList<Sample>(Math.min(limit, records.size()));
        for (int index = 0; index < records.size() && index < limit; index++) {
            result.add(records.get(index).sample);
        }
        return result;
    }

    synchronized int acknowledge(TelemetryWorkerSampleIdentity identity, long acknowledgedAtMs) {
        ensureOpen();
        if (identity == null) throw new IllegalArgumentException("identity is required");
        if (acknowledgedAtMs < 0) throw new IllegalArgumentException("acknowledgedAtMs must be non-negative");
        File ready = readyFile(identity);
        if (!ready.isFile()) return 0;
        try {
            Sample sample = decode(readBytes(ready));
            if (!identity.equals(sample.identity)) return 0;
        } catch (Exception error) {
            return 0;
        }
        return ready.delete() ? 1 : 0;
    }

    @Override public synchronized void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("telemetry worker spool is closed");
    }

    private File readyFile(TelemetryWorkerSampleIdentity identity) {
        return new File(directory, fileStem(identity) + READY_SUFFIX);
    }

    private File temporaryFile(TelemetryWorkerSampleIdentity identity) {
        return new File(directory, fileStem(identity) + TMP_SUFFIX);
    }

    private static String fileStem(TelemetryWorkerSampleIdentity identity) {
        return "v" + RECORD_VERSION + "_" + encodePart(identity.bootId) + "_" +
            encodePart(identity.helperGeneration) + "_" + identity.pollSequence;
    }

    private static String encodePart(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private long footprintBytes() {
        File[] files = directory.listFiles();
        if (files == null) throw new IllegalStateException("cannot list telemetry worker spool: " + directory);
        long total = 0L;
        for (File file : files) {
            if (!file.isFile()) continue;
            long length = file.length();
            if (Long.MAX_VALUE - total < length) return Long.MAX_VALUE;
            total += length;
        }
        return total;
    }

    private static void writeDurably(File file, byte[] payload) throws IOException {
        FileOutputStream output = new FileOutputStream(file, false);
        try {
            output.write(payload);
            output.flush();
            FileDescriptor descriptor = output.getFD();
            descriptor.sync();
        } finally {
            output.close();
        }
    }

    private static byte[] readBytes(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE, file.length()));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static JSONObject encode(Sample sample) throws Exception {
        JSONObject json = new JSONObject();
        json.put("record_version", RECORD_VERSION);
        json.put("identity", new JSONObject()
            .put("boot_id", sample.identity.bootId)
            .put("helper_generation", sample.identity.helperGeneration)
            .put("poll_sequence", sample.identity.pollSequence));
        json.put("catalog_version", sample.catalogVersion);
        json.put("captured_wall_ms", sample.capturedWallMs);
        json.put("captured_elapsed_ms", sample.capturedElapsedMs);
        json.put("poll_elapsed_ms", sample.pollElapsedMs);
        json.put("batch_status", sample.batchStatus);
        json.put("batch_mode", sample.batchMode);
        json.put("native_available", sample.nativeAvailable);
        json.put("group_failure_count", sample.groupFailureCount);
        json.put("field_count", sample.values.size());
        json.put("error", sample.error == null ? JSONObject.NULL : sample.error);
        JSONArray values = new JSONArray();
        for (Value value : sample.values) {
            values.put(new JSONObject()
                .put("field_index", value.fieldIndex)
                .put("tx", value.tx)
                .put("dev", value.dev)
                .put("fid", value.fid)
                .put("status", value.status)
                .put("raw", value.raw == null ? JSONObject.NULL : value.raw)
                .put("error", value.error == null ? JSONObject.NULL : value.error));
        }
        json.put("values", values);
        return json;
    }

    private static Sample decode(byte[] bytes) throws Exception {
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (json.getInt("record_version") != RECORD_VERSION) {
            throw new IllegalArgumentException("unsupported telemetry worker record version");
        }
        JSONObject identity = json.getJSONObject("identity");
        TelemetryWorkerSampleIdentity sampleIdentity = new TelemetryWorkerSampleIdentity(
            identity.getString("boot_id"),
            identity.getString("helper_generation"),
            identity.getLong("poll_sequence")
        );
        JSONArray encodedValues = json.getJSONArray("values");
        int fieldCount = json.getInt("field_count");
        if (fieldCount != encodedValues.length()) throw new IllegalArgumentException("field count mismatch");
        List<Value> values = new ArrayList<Value>(encodedValues.length());
        for (int index = 0; index < encodedValues.length(); index++) {
            JSONObject value = encodedValues.getJSONObject(index);
            values.add(new Value(
                value.getInt("field_index"),
                value.getInt("tx"),
                value.getInt("dev"),
                value.getInt("fid"),
                value.getInt("status"),
                nullableInteger(value, "raw"),
                nullableString(value, "error")
            ));
        }
        return new Sample(
            sampleIdentity,
            json.getString("catalog_version"),
            json.getLong("captured_wall_ms"),
            json.getLong("captured_elapsed_ms"),
            json.getLong("poll_elapsed_ms"),
            json.getInt("batch_status"),
            json.getInt("batch_mode"),
            json.getBoolean("native_available"),
            json.getInt("group_failure_count"),
            nullableString(json, "error"),
            values
        );
    }

    private static Integer nullableInteger(JSONObject json, String key) throws Exception {
        if (!json.has(key) || json.isNull(key)) return null;
        return json.getInt(key);
    }

    private static String nullableString(JSONObject json, String key) throws Exception {
        if (!json.has(key) || json.isNull(key)) return null;
        return json.getString(key);
    }

    private void quarantine(File file) {
        File bad = new File(file.getPath() + BAD_SUFFIX);
        int suffix = 1;
        while (bad.exists()) bad = new File(file.getPath() + BAD_SUFFIX + "." + suffix++);
        if (!file.renameTo(bad)) {
            System.err.println("WARN: cannot quarantine malformed telemetry worker record: " + file);
        }
    }

    private static int compareLong(long left, long right) {
        return left < right ? -1 : left == right ? 0 : 1;
    }

    private static final class PendingRecord {
        final File file;
        final Sample sample;

        PendingRecord(File file, Sample sample) {
            this.file = file;
            this.sample = sample;
        }
    }

    interface SampleValidator {
        void validate(Sample sample);
    }

    static final class Sample {
        final TelemetryWorkerSampleIdentity identity;
        final String catalogVersion;
        final long capturedWallMs;
        final long capturedElapsedMs;
        final long pollElapsedMs;
        final int batchStatus;
        final int batchMode;
        final boolean nativeAvailable;
        final int groupFailureCount;
        final String error;
        final List<Value> values;

        Sample(
            TelemetryWorkerSampleIdentity identity,
            String catalogVersion,
            long capturedWallMs,
            long capturedElapsedMs,
            long pollElapsedMs,
            int batchStatus,
            int batchMode,
            boolean nativeAvailable,
            int groupFailureCount,
            String error,
            List<Value> values
        ) {
            if (identity == null) throw new IllegalArgumentException("identity is required");
            if (catalogVersion == null || catalogVersion.trim().isEmpty()) {
                throw new IllegalArgumentException("catalogVersion must not be blank");
            }
            if (capturedElapsedMs < 0 || pollElapsedMs < 0) {
                throw new IllegalArgumentException("elapsed times must be non-negative");
            }
            if (groupFailureCount < 0) throw new IllegalArgumentException("groupFailureCount must be non-negative");
            if (values == null || values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
            if (values.size() > CollectorHelperProtocol.MAX_WORKER_FIELD_COUNT) {
                throw new IllegalArgumentException("too many worker fields: " + values.size());
            }
            Set<Integer> indexes = new HashSet<Integer>();
            for (int index = 0; index < values.size(); index++) {
                Value value = values.get(index);
                if (value == null) throw new IllegalArgumentException("values must not contain null");
                if (value.fieldIndex != index) throw new IllegalArgumentException("values must be in fieldIndex order");
                if (!indexes.add(value.fieldIndex)) throw new IllegalArgumentException("duplicate fieldIndex: " + value.fieldIndex);
            }
            for (int index = 0; index < values.size(); index++) {
                if (!indexes.contains(index)) throw new IllegalArgumentException("missing fieldIndex: " + index);
            }
            this.identity = identity;
            this.catalogVersion = catalogVersion;
            this.capturedWallMs = capturedWallMs;
            this.capturedElapsedMs = capturedElapsedMs;
            this.pollElapsedMs = pollElapsedMs;
            this.batchStatus = batchStatus;
            this.batchMode = batchMode;
            this.nativeAvailable = nativeAvailable;
            this.groupFailureCount = groupFailureCount;
            this.error = error;
            this.values = Collections.unmodifiableList(new ArrayList<Value>(values));
        }
    }

    static final class Value {
        final int fieldIndex;
        final int tx;
        final int dev;
        final int fid;
        final int status;
        final Integer raw;
        final String error;

        Value(int fieldIndex, int tx, int dev, int fid, int status, Integer raw, String error) {
            if (fieldIndex < 0) throw new IllegalArgumentException("fieldIndex must be non-negative");
            if (tx != CollectorHelperProtocol.AUTO_TX_INT && tx != CollectorHelperProtocol.AUTO_TX_FLOAT) {
                throw new IllegalArgumentException("unsupported read transaction: " + tx);
            }
            this.fieldIndex = fieldIndex;
            this.tx = tx;
            this.dev = dev;
            this.fid = fid;
            this.status = status;
            this.raw = raw;
            this.error = error;
        }
    }
}
