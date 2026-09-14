package com.bydcollector.collector.service

import com.bydcollector.collector.data.energy.EnergySnapshot
import org.json.JSONObject

/** A persisted projection of the shared energy checkpoint; it never integrates samples. */
data class TelegramEnergyPoint(
    val powerSessionId: String,
    val snapshotId: String,
    val sourceBootId: String,
    val sourceElapsedMs: Long,
    val observedAt: String,
    val dischargedKwh: Double?,
    val regeneratedKwh: Double?,
    val energyCoveredMs: Long,
    val energyUncoveredMs: Long,
    val energyPartial: Boolean
) {
    init {
        require(powerSessionId.isNotBlank())
        require(snapshotId.isNotBlank())
        require(sourceBootId.isNotBlank())
        require(sourceElapsedMs >= 0L)
        require(observedAt.isNotBlank())
        require((dischargedKwh == null) == (regeneratedKwh == null))
        require(listOfNotNull(dischargedKwh, regeneratedKwh).all { it.isFinite() && it >= 0.0 })
        require(energyCoveredMs >= 0L)
        require(energyUncoveredMs >= 0L)
    }

    fun baselineDischargedKwh(): Double? = dischargedKwh ?: knownZero()

    fun baselineRegeneratedKwh(): Double? = regeneratedKwh ?: knownZero()

    fun toJson(): JSONObject = JSONObject().apply {
        put("power_session_id", powerSessionId)
        put("snapshot_id", snapshotId)
        put("source_boot_id", sourceBootId)
        put("source_elapsed_ms", sourceElapsedMs)
        put("observed_at", observedAt)
        put("discharged_kwh", dischargedKwh ?: JSONObject.NULL)
        put("regenerated_kwh", regeneratedKwh ?: JSONObject.NULL)
        put("energy_covered_ms", energyCoveredMs)
        put("energy_uncovered_ms", energyUncoveredMs)
        put("energy_partial", energyPartial)
    }

    private fun knownZero(): Double? = 0.0.takeIf { energyCoveredMs == 0L && !energyPartial }

    companion object {
        fun fromSnapshot(snapshot: EnergySnapshot?): TelegramEnergyPoint? {
            snapshot ?: return null
            val powerSessionId = snapshot.powerSessionId?.takeIf(String::isNotBlank) ?: return null
            return runCatching {
                TelegramEnergyPoint(
                    powerSessionId = powerSessionId,
                    snapshotId = snapshot.snapshotId,
                    sourceBootId = snapshot.sourceBootId,
                    sourceElapsedMs = snapshot.sourceElapsedMs,
                    observedAt = snapshot.observedAt,
                    dischargedKwh = snapshot.dischargedKwh,
                    regeneratedKwh = snapshot.regeneratedKwh,
                    energyCoveredMs = snapshot.energyCoveredMs,
                    energyUncoveredMs = snapshot.energyUncoveredMs,
                    energyPartial = snapshot.energyPartial
                )
            }.getOrNull()
        }

        fun fromJsonOrNull(json: JSONObject?): TelegramEnergyPoint? {
            json ?: return null
            return runCatching {
                TelegramEnergyPoint(
                    powerSessionId = json.getString("power_session_id"),
                    snapshotId = json.getString("snapshot_id"),
                    sourceBootId = json.getString("source_boot_id"),
                    sourceElapsedMs = json.getLong("source_elapsed_ms"),
                    observedAt = json.getString("observed_at"),
                    dischargedKwh = json.finiteDoubleOrNull("discharged_kwh"),
                    regeneratedKwh = json.finiteDoubleOrNull("regenerated_kwh"),
                    energyCoveredMs = json.getLong("energy_covered_ms"),
                    energyUncoveredMs = json.getLong("energy_uncovered_ms"),
                    energyPartial = json.getBoolean("energy_partial")
                )
            }.getOrNull()
        }

        private fun JSONObject.finiteDoubleOrNull(key: String): Double? =
            if (isNull(key)) null else getDouble(key).takeIf(Double::isFinite)
                ?: error("Non-finite Telegram energy value: $key")
    }
}
