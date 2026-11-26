package com.bydcollector.collector.data.input

import com.bydcollector.collector.data.energy.EnergyPendingProjection
import com.bydcollector.collector.data.energy.EnergySnapshot
import com.bydcollector.collector.data.energy.EnergyStateCodec
import com.bydcollector.collector.data.normalized.*
import com.bydcollector.collector.service.TripBusinessResult
import com.bydcollector.collector.service.toJson
import org.json.JSONArray
import org.json.JSONObject

/** One bounded cross-database handoff, retained until Main and Telegram acknowledge it. */
internal data class BusinessPageEntry(
    val inputId: Long,
    val sourceIdentity: String,
    val energy: EnergySnapshot?,
    val projectEnergy: Boolean,
    val trip: TripBusinessResult?
)

internal data class BusinessPage(
    val generation: String,
    val mergeBootId: String,
    val entries: List<BusinessPageEntry>,
    val locationObservations: List<NormalizedObservation> = emptyList()
) {
    init {
        require(generation.isNotBlank() && mergeBootId.isNotBlank())
        require(entries.size in 1..64 && entries.all { it.inputId > 0 && it.sourceIdentity.isNotBlank() })
        require(entries.zipWithNext().all { (a, b) -> a.inputId < b.inputId })
        require(entries.all { !it.projectEnergy || it.energy != null })
    }
    val key: String get() = "$generation:${entries.first().inputId}:${entries.last().inputId}"

    fun requireReferences(refs: List<BusinessInputRef>) {
        check(refs.map { it.id to it.sourceIdentity } == entries.map { it.inputId to it.sourceIdentity }) {
            "Business page $key no longer matches its durable input references"
        }
    }

    fun encode(): String = JSONObject().put("version", 2).put("generation", generation)
        .put("mergeBoot", mergeBootId).put("entries", JSONArray().apply {
            entries.forEach { entry -> put(JSONObject().put("id", entry.inputId).put("source", entry.sourceIdentity)
                .put("energy", entry.energy?.let { EnergyStateCodec.encodeProjection(EnergyPendingProjection(it)) })
                .put("project", entry.projectEnergy).put("trip", entry.trip?.let { trip ->
                    JSONObject().put("consumed", trip.consumed).put("prior", trip.priorCompletionWatermark)
                        .put("watermark", trip.completionWatermark).put("session", trip.powerSessionId)
                        .put("on", trip.confirmedPowerOn).put("closed", trip.closedPowerSession)
                })) }
        }).put("location", JSONArray().apply {
            locationObservations.forEach { value ->
                require(value.field.category == NormalizedCategory.LOCATION && value.sourceStamp == null)
                put(JSONObject().put("field", value.field.fieldKey).put("text", value.value.text)
                    .put("number", value.value.number).put("bool", value.value.bool).put("quality", value.quality.name)
                    .put("poll", value.sourcePollId).put("key", value.sourceKey).put("at", value.observedAt)
                    .put("reason", value.reason))
            }
        }).toString()

    companion object {
        fun sourceEvidence(deltas: List<BusinessInputDelta>): String = JSONArray().apply {
            deltas.forEach { delta ->
                val ref = delta.ref
                put(JSONObject().put("id", ref.id).put("identity", ref.sourceIdentity)
                    .put("boot", ref.bootId).put("kind", ref.kind.storageValue).put("origin", ref.origin.storageValue)
                    .put("generation", ref.generation).put("sequence", ref.sequence).put("epoch", ref.epoch)
                    .put("wall_ms", ref.acquiredWallMs).put("elapsed_ms", ref.acquiredElapsedMs)
                    .put("poll", ref.pollId).put("ok", ref.ok).put("quality", ref.quality)
                    .put("error_category", ref.errorCategory).put("error", ref.errorMessage)
                    .put("readings", JSONArray().apply {
                        delta.readings.forEach { reading ->
                            put(JSONObject().put("key", reading.rawKey).put("raw", reading.rawValue ?: JSONObject.NULL)
                                .put("desc", reading.descValue ?: JSONObject.NULL).apply {
                                    reading.callbackSource?.let { callback ->
                                        val bytes = java.io.ByteArrayOutputStream()
                                        java.io.DataOutputStream(bytes).use {
                                            com.bydcollector.collector.direct.CallbackValueSource.writeNullable(it, callback, true)
                                        }
                                        put("callback_source_v4", java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()))
                                    }
                                    reading.getLineage?.let { lineage ->
                                        put("get", JSONObject().put("boot", lineage.helperBootId)
                                            .put("generation", lineage.helperGeneration).put("stream", lineage.stream)
                                            .put("epoch", lineage.sourceEpoch).put("identity", lineage.readIdentity)
                                            .put("sequence", lineage.recordSequence).put("start_wall", lineage.startedWallMs)
                                            .put("start_elapsed", lineage.startedElapsedMs).put("end_wall", lineage.completedWallMs)
                                            .put("end_elapsed", lineage.completedElapsedMs)
                                            .put("watermark_start", lineage.callbackWatermarkStart)
                                            .put("watermark_end", lineage.callbackWatermarkEnd)
                                            .put("superseded", lineage.supersededByCallback))
                                    }
                                })
                        }
                    }).put("continuity", JSONObject().apply {
                        delta.continuityUpdates.forEach { (key, value) ->
                            put(key, JSONObject().put("state", value.state.storageValue).put("stamp", value.stamp.toJson())
                                .put("seed_kind", value.seedKind).put("seed_identity", value.seedIdentity)
                                .put("seed_sequence", value.seedRecordSequence).put("seed_valid", value.seedValid)
                                .put("reason", value.reason).put("diagnostic", value.diagnostic))
                        }
                    }))
            }
        }.toString()

        fun decode(encoded: String): BusinessPage {
            val json = JSONObject(encoded)
            require(json.getInt("version") in 1..2) { "Unsupported business page version" }
            val values = json.getJSONArray("entries")
            require(values.length() in 1..64)
            val locations = json.getJSONArray("location")
            return BusinessPage(json.getString("generation"), json.getString("mergeBoot"),
                (0 until values.length()).map { index ->
                    val value = values.getJSONObject(index)
                    val trip = value.optJSONObject("trip")
                    BusinessPageEntry(value.getLong("id"), value.getString("source"),
                        value.stringOrNull("energy")?.let { EnergyStateCodec.decodeProjection(it).snapshot },
                        value.getBoolean("project"), trip?.let {
                            TripBusinessResult(it.getBoolean("consumed"), it.getLong("prior"), it.getLong("watermark"),
                                it.stringOrNull("session"), it.getBoolean("on"), it.getBoolean("closed"))
                        })
                }, (0 until locations.length()).map { index ->
                    val value = locations.getJSONObject(index)
                    val field = NormalizedFieldCatalog.fields.single { it.fieldKey == value.getString("field") }
                    require(field.category == NormalizedCategory.LOCATION)
                    NormalizedObservation(field, NormalizedValue(field.valueType, value.stringOrNull("text"),
                        if (value.isNull("number")) null else value.getDouble("number"),
                        if (value.isNull("bool")) null else value.getBoolean("bool")),
                        NormalizedQuality.valueOf(value.getString("quality")),
                        if (value.isNull("poll")) null else value.getLong("poll"), value.stringOrNull("key"),
                        value.getString("at"), value.stringOrNull("reason"))
                })
        }
        private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    }
}
