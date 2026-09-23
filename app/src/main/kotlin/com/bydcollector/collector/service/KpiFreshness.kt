package com.bydcollector.collector.service

import com.bydcollector.collector.data.normalized.NormalizedObservation

/** Main-thread-owned per-field source age; sparse events never refresh other fields. */
internal class KpiFreshness(private val bootId: String, private val maxAgeMs: Long) {
    private val fields = mutableMapOf<String, NormalizedObservation>()
    private val kpiKeys = setOf("soc", "odometer_km", "inside_temp_c_raw",
        "perfume_1_remaining_percent", "perfume_2_remaining_percent", "perfume_3_remaining_percent",
        "battery_soh_percent", "battery_charge_power_kw", "battery_discharge_power_kw",
        "remaining_range_km", "battery_average_temp_raw",
        "battery_highest_cell_voltage_raw", "battery_lowest_cell_voltage_raw")

    private fun isRecent(sourceBootId: String, sourceElapsedMs: Long, nowMs: Long): Boolean =
        sourceBootId == bootId && sourceElapsedMs >= 0 && sourceElapsedMs <= nowMs &&
            nowMs - sourceElapsedMs < maxAgeMs

    fun accept(observations: List<NormalizedObservation>, nowMs: Long): Boolean {
        var changed = false
        observations.forEach { observation ->
            val key = observation.field.fieldKey
            if (key !in kpiKeys) return@forEach
            val source = observation.sourceStamp ?: return@forEach
            if (!isRecent(source.bootId, source.elapsedMs, nowMs) ||
                fields[key]?.sourceStamp?.let { source.elapsedMs < it.elapsedMs } == true) return@forEach
            fields[key] = observation
            changed = true
        }
        return changed
    }

    fun freshObservations(nowMs: Long): List<NormalizedObservation> = fields.values.filter {
        val source = checkNotNull(it.sourceStamp)
        isRecent(source.bootId, source.elapsedMs, nowMs)
    }

    /** Next individual expiry, not the newest field's deadline. */
    fun remainingMs(nowMs: Long): Long = freshObservations(nowMs)
        .minOfOrNull { maxAgeMs - (nowMs - checkNotNull(it.sourceStamp).elapsedMs) } ?: 0L

    fun clear() { fields.clear() }
}
