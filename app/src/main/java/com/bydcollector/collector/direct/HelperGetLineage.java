package com.bydcollector.collector.direct;

import android.os.Parcel;
import org.json.JSONException;
import org.json.JSONObject;

/** Per-address helper capture lineage for a real GET attempt. */
public final class HelperGetLineage {
    public final String helperBootId;
    public final String helperGeneration;
    public final int stream;
    public final long sourceEpoch;
    public final String readIdentity;
    public final long recordSequence;
    public final long startedWallMs;
    public final long startedElapsedMs;
    public final long completedWallMs;
    public final long completedElapsedMs;
    public final long callbackWatermarkStart;
    public final long callbackWatermarkEnd;
    public final boolean supersededByCallback;

    public HelperGetLineage(String helperBootId, String helperGeneration, int stream, long sourceEpoch,
            String readIdentity, long recordSequence, long startedWallMs, long startedElapsedMs,
            long completedWallMs, long completedElapsedMs, long callbackWatermarkStart,
            long callbackWatermarkEnd, boolean supersededByCallback) {
        if (helperBootId == null || helperBootId.isEmpty() || helperGeneration == null || helperGeneration.isEmpty() ||
            readIdentity == null || readIdentity.isEmpty() || (stream != 1 && stream != 2) || sourceEpoch < 0 ||
            recordSequence < 0 || startedWallMs < 0 || completedWallMs < 0 || startedElapsedMs < 0 ||
            completedElapsedMs < startedElapsedMs || callbackWatermarkStart < -1 || callbackWatermarkEnd < -1) {
            throw new IllegalArgumentException("invalid helper GET lineage");
        }
        this.helperBootId = helperBootId;
        this.helperGeneration = helperGeneration;
        this.stream = stream;
        this.sourceEpoch = sourceEpoch;
        this.readIdentity = readIdentity;
        this.recordSequence = recordSequence;
        this.startedWallMs = startedWallMs;
        this.startedElapsedMs = startedElapsedMs;
        this.completedWallMs = completedWallMs;
        this.completedElapsedMs = completedElapsedMs;
        this.callbackWatermarkStart = callbackWatermarkStart;
        this.callbackWatermarkEnd = callbackWatermarkEnd;
        this.supersededByCallback = supersededByCallback;
    }

    public static void writeNullable(Parcel out, HelperGetLineage value) {
        out.writeInt(value == null ? 0 : 1);
        if (value == null) return;
        out.writeString(value.helperBootId);
        out.writeString(value.helperGeneration);
        out.writeInt(value.stream);
        out.writeLong(value.sourceEpoch);
        out.writeString(value.readIdentity);
        out.writeLong(value.recordSequence);
        out.writeLong(value.startedWallMs);
        out.writeLong(value.startedElapsedMs);
        out.writeLong(value.completedWallMs);
        out.writeLong(value.completedElapsedMs);
        out.writeLong(value.callbackWatermarkStart);
        out.writeLong(value.callbackWatermarkEnd);
        out.writeInt(value.supersededByCallback ? 1 : 0);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
            .put("helper_boot_id", helperBootId)
            .put("helper_generation", helperGeneration)
            .put("stream", stream)
            .put("source_epoch", sourceEpoch)
            .put("read_identity", readIdentity)
            .put("record_sequence", recordSequence)
            .put("started_wall_ms", startedWallMs)
            .put("started_elapsed_ms", startedElapsedMs)
            .put("completed_wall_ms", completedWallMs)
            .put("completed_elapsed_ms", completedElapsedMs)
            .put("callback_watermark_start", callbackWatermarkStart)
            .put("callback_watermark_end", callbackWatermarkEnd)
            .put("superseded_by_callback", supersededByCallback);
    }

    public static HelperGetLineage fromJson(JSONObject json) throws JSONException {
        return new HelperGetLineage(
            json.getString("helper_boot_id"), json.getString("helper_generation"), json.getInt("stream"),
            json.getLong("source_epoch"), json.getString("read_identity"), json.getLong("record_sequence"),
            json.getLong("started_wall_ms"), json.getLong("started_elapsed_ms"),
            json.getLong("completed_wall_ms"), json.getLong("completed_elapsed_ms"),
            json.getLong("callback_watermark_start"), json.getLong("callback_watermark_end"),
            json.getBoolean("superseded_by_callback")
        );
    }

    public static HelperGetLineage readNullable(Parcel in) {
        int present = in.readInt();
        if (present == 0) return null;
        if (present != 1) throw new IllegalArgumentException("invalid helper GET lineage marker");
        String boot = in.readString();
        String generation = in.readString();
        int stream = in.readInt();
        long sourceEpoch = in.readLong();
        String identity = in.readString();
        long recordSequence = in.readLong();
        long startedWall = in.readLong();
        long startedElapsed = in.readLong();
        long completedWall = in.readLong();
        long completedElapsed = in.readLong();
        long watermarkStart = in.readLong();
        long watermarkEnd = in.readLong();
        int superseded = in.readInt();
        if (superseded != 0 && superseded != 1) throw new IllegalArgumentException("invalid superseded marker");
        return new HelperGetLineage(boot, generation, stream, sourceEpoch,
            identity, recordSequence, startedWall, startedElapsed, completedWall, completedElapsed,
            watermarkStart, watermarkEnd, superseded == 1);
    }
}
