package com.bydcollector.collector.direct;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

//helper-owned durable raw spool; the app acknowledges a sample only after its own transaction commits
final class TelemetryWorkerSpool implements AutoCloseable {
    static final String DATABASE_PATH = "/data/local/tmp/bydcollector_telemetry_worker.db";
    static final int SCHEMA_VERSION = 1;
    static final String SAMPLE_TABLE = "telemetry_worker_samples";
    static final String VALUE_TABLE = "telemetry_worker_values";

    static final String CREATE_SAMPLE_TABLE =
        "CREATE TABLE " + SAMPLE_TABLE + " (" +
            "boot_id TEXT NOT NULL," +
            "helper_generation TEXT NOT NULL," +
            "poll_sequence INTEGER NOT NULL CHECK(poll_sequence >= 0)," +
            "catalog_version TEXT NOT NULL," +
            "captured_wall_ms INTEGER NOT NULL," +
            "captured_elapsed_ms INTEGER NOT NULL CHECK(captured_elapsed_ms >= 0)," +
            "poll_elapsed_ms INTEGER NOT NULL CHECK(poll_elapsed_ms >= 0)," +
            "batch_status INTEGER NOT NULL," +
            "batch_mode INTEGER NOT NULL," +
            "native_available INTEGER NOT NULL CHECK(native_available IN (0,1))," +
            "group_failure_count INTEGER NOT NULL CHECK(group_failure_count >= 0)," +
            "field_count INTEGER NOT NULL CHECK(field_count > 0)," +
            "error TEXT," +
            "acknowledged_at_ms INTEGER," +
            "PRIMARY KEY(boot_id, helper_generation, poll_sequence)" +
        ") WITHOUT ROWID";

    static final String CREATE_VALUE_TABLE =
        "CREATE TABLE " + VALUE_TABLE + " (" +
            "boot_id TEXT NOT NULL," +
            "helper_generation TEXT NOT NULL," +
            "poll_sequence INTEGER NOT NULL," +
            "field_index INTEGER NOT NULL CHECK(field_index >= 0)," +
            "tx INTEGER NOT NULL CHECK(tx IN (5,7))," +
            "dev INTEGER NOT NULL," +
            "fid INTEGER NOT NULL," +
            "status INTEGER NOT NULL," +
            "raw INTEGER," +
            "error TEXT," +
            "PRIMARY KEY(boot_id, helper_generation, poll_sequence, field_index)," +
            "FOREIGN KEY(boot_id, helper_generation, poll_sequence) REFERENCES " + SAMPLE_TABLE +
                "(boot_id, helper_generation, poll_sequence) ON DELETE CASCADE" +
        ") WITHOUT ROWID";

    private final SQLiteDatabase database;

    static TelemetryWorkerSpool open() {
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(DATABASE_PATH, null);
        try {
            database.setForeignKeyConstraintsEnabled(true);
            database.enableWriteAheadLogging();
            int version = (int) DatabaseUtils.longForQuery(database, "PRAGMA user_version", null);
            if (version == 0) createSchema(database);
            else if (version != SCHEMA_VERSION) {
                throw new IllegalStateException("unsupported telemetry worker spool schema: " + version);
            }
            return new TelemetryWorkerSpool(database);
        } catch (RuntimeException error) {
            database.close();
            throw error;
        }
    }

    private TelemetryWorkerSpool(SQLiteDatabase database) {
        this.database = database;
    }

    void append(Sample sample) {
        database.beginTransaction();
        try {
            database.insertOrThrow(SAMPLE_TABLE, null, sampleValues(sample));
            for (Value value : sample.values) {
                database.insertOrThrow(VALUE_TABLE, null, valueValues(sample.identity, value));
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    List<Sample> pending(int limit) {
        if (limit < 1 || limit > CollectorHelperProtocol.MAX_PENDING_WORKER_SAMPLES) {
            throw new IllegalArgumentException("invalid pending sample limit: " + limit);
        }
        List<Sample> samples = new ArrayList<Sample>();
        database.beginTransactionNonExclusive();
        try {
            List<SampleHeader> headers = pendingHeaders(limit);
            for (SampleHeader header : headers) {
                List<Value> values = values(header.identity);
                if (values.size() != header.fieldCount) {
                    throw new IllegalStateException(
                        "worker sample field count mismatch: expected=" + header.fieldCount + " actual=" + values.size()
                    );
                }
                samples.add(header.toSample(values));
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return samples;
    }

    int acknowledge(TelemetryWorkerSampleIdentity identity, long acknowledgedAtMs) {
        if (acknowledgedAtMs < 0) throw new IllegalArgumentException("acknowledgedAtMs must be non-negative");
        ContentValues values = new ContentValues();
        values.put("acknowledged_at_ms", acknowledgedAtMs);
        return database.update(
            SAMPLE_TABLE,
            values,
            "boot_id=? AND helper_generation=? AND poll_sequence=?",
            identityArgs(identity)
        );
    }

    @Override public void close() {
        database.close();
    }

    private static void createSchema(SQLiteDatabase database) {
        database.beginTransaction();
        try {
            database.execSQL(CREATE_SAMPLE_TABLE);
            database.execSQL(CREATE_VALUE_TABLE);
            database.execSQL("PRAGMA user_version=" + SCHEMA_VERSION);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private List<SampleHeader> pendingHeaders(int limit) {
        List<SampleHeader> headers = new ArrayList<SampleHeader>();
        try (Cursor cursor = database.query(
            SAMPLE_TABLE,
            new String[] {
                "boot_id", "helper_generation", "poll_sequence", "catalog_version",
                "captured_wall_ms", "captured_elapsed_ms", "poll_elapsed_ms",
                "batch_status", "batch_mode", "native_available", "group_failure_count",
                "field_count", "error"
            },
            "acknowledged_at_ms IS NULL",
            null,
            null,
            null,
            "captured_wall_ms, captured_elapsed_ms, boot_id, helper_generation, poll_sequence",
            Integer.toString(limit)
        )) {
            while (cursor.moveToNext()) {
                headers.add(new SampleHeader(
                    new TelemetryWorkerSampleIdentity(cursor.getString(0), cursor.getString(1), cursor.getLong(2)),
                    cursor.getString(3),
                    cursor.getLong(4),
                    cursor.getLong(5),
                    cursor.getLong(6),
                    cursor.getInt(7),
                    cursor.getInt(8),
                    cursor.getInt(9) == 1,
                    cursor.getInt(10),
                    cursor.getInt(11),
                    cursor.isNull(12) ? null : cursor.getString(12)
                ));
            }
        }
        return headers;
    }

    private List<Value> values(TelemetryWorkerSampleIdentity identity) {
        List<Value> values = new ArrayList<Value>();
        try (Cursor cursor = database.query(
            VALUE_TABLE,
            new String[] {"field_index", "tx", "dev", "fid", "status", "raw", "error"},
            "boot_id=? AND helper_generation=? AND poll_sequence=?",
            identityArgs(identity),
            null,
            null,
            "field_index"
        )) {
            while (cursor.moveToNext()) {
                values.add(new Value(
                    cursor.getInt(0),
                    cursor.getInt(1),
                    cursor.getInt(2),
                    cursor.getInt(3),
                    cursor.getInt(4),
                    cursor.isNull(5) ? null : cursor.getInt(5),
                    cursor.isNull(6) ? null : cursor.getString(6)
                ));
            }
        }
        return values;
    }

    private static ContentValues sampleValues(Sample sample) {
        ContentValues values = identityValues(sample.identity);
        values.put("catalog_version", sample.catalogVersion);
        values.put("captured_wall_ms", sample.capturedWallMs);
        values.put("captured_elapsed_ms", sample.capturedElapsedMs);
        values.put("poll_elapsed_ms", sample.pollElapsedMs);
        values.put("batch_status", sample.batchStatus);
        values.put("batch_mode", sample.batchMode);
        values.put("native_available", sample.nativeAvailable ? 1 : 0);
        values.put("group_failure_count", sample.groupFailureCount);
        values.put("field_count", sample.values.size());
        values.put("error", sample.error);
        return values;
    }

    private static ContentValues valueValues(TelemetryWorkerSampleIdentity identity, Value value) {
        ContentValues values = identityValues(identity);
        values.put("field_index", value.fieldIndex);
        values.put("tx", value.tx);
        values.put("dev", value.dev);
        values.put("fid", value.fid);
        values.put("status", value.status);
        if (value.raw == null) values.putNull("raw");
        else values.put("raw", value.raw);
        values.put("error", value.error);
        return values;
    }

    private static ContentValues identityValues(TelemetryWorkerSampleIdentity identity) {
        ContentValues values = new ContentValues();
        values.put("boot_id", identity.bootId);
        values.put("helper_generation", identity.helperGeneration);
        values.put("poll_sequence", identity.pollSequence);
        return values;
    }

    private static String[] identityArgs(TelemetryWorkerSampleIdentity identity) {
        return new String[] {
            identity.bootId,
            identity.helperGeneration,
            Long.toString(identity.pollSequence)
        };
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
            for (Value value : values) {
                if (value == null) throw new IllegalArgumentException("values must not contain null");
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

    private static final class SampleHeader {
        final TelemetryWorkerSampleIdentity identity;
        final String catalogVersion;
        final long capturedWallMs;
        final long capturedElapsedMs;
        final long pollElapsedMs;
        final int batchStatus;
        final int batchMode;
        final boolean nativeAvailable;
        final int groupFailureCount;
        final int fieldCount;
        final String error;

        SampleHeader(
            TelemetryWorkerSampleIdentity identity,
            String catalogVersion,
            long capturedWallMs,
            long capturedElapsedMs,
            long pollElapsedMs,
            int batchStatus,
            int batchMode,
            boolean nativeAvailable,
            int groupFailureCount,
            int fieldCount,
            String error
        ) {
            this.identity = identity;
            this.catalogVersion = catalogVersion;
            this.capturedWallMs = capturedWallMs;
            this.capturedElapsedMs = capturedElapsedMs;
            this.pollElapsedMs = pollElapsedMs;
            this.batchStatus = batchStatus;
            this.batchMode = batchMode;
            this.nativeAvailable = nativeAvailable;
            this.groupFailureCount = groupFailureCount;
            this.fieldCount = fieldCount;
            this.error = error;
        }

        Sample toSample(List<Value> values) {
            return new Sample(
                identity,
                catalogVersion,
                capturedWallMs,
                capturedElapsedMs,
                pollElapsedMs,
                batchStatus,
                batchMode,
                nativeAvailable,
                groupFailureCount,
                error,
                values
            );
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
