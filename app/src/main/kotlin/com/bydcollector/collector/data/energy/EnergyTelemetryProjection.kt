package com.bydcollector.collector.data.energy

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.data.polling.PollSampleSource

object EnergyTelemetryProjection {
    fun receipt(source: PollSampleSource, observedAt: String, readings: List<PollReading>,
                observations: List<NormalizedObservation>): EnergyReceipt {
        val usable = observations.filter { it.quality == NormalizedQuality.OK }.associateBy { it.field.fieldKey }
        val power = readings.firstOrNull { it.rawKey == "bodywork_power_level" }
            ?.descValue?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
        val connected = usable["charge_gun_connected_raw"]?.value?.bool
        // A charging type may remain remembered. Only active, contradictory evidence vetoes the gun gate.
        val external = usable["charger_connected_raw"]?.value?.bool == true ||
            usable["v2l_discharge_active_raw"]?.value?.bool == true ||
            usable["charging_battery_device_state"]?.value?.text == "charging"
        return EnergyReceipt(source.identity, observedAt, EnergyInput(
            source.bootId, source.capturedElapsedMs,
            usable["hv_battery_voltage_v"]?.value?.number,
            usable["charge_current_a"]?.value?.number,
            power?.let { it > 0 }, connected?.not(), external
        ))
    }

    fun observations(snapshot: EnergySnapshot): List<NormalizedObservation> {
        val values = listOf(snapshot.dischargedKwh, snapshot.regeneratedKwh, snapshot.netKwh)
        return NormalizedFieldCatalog.energyFields.zip(values).map { (field, value) ->
            NormalizedObservation(
                field = field,
                value = NormalizedValue(NormalizedValueType.NUMBER, number = value),
                quality = if (value == null) NormalizedQuality.MISSING else NormalizedQuality.OK,
                sourcePollId = null, // Main may have been archived since this durable projection was staged.
                sourceKey = snapshot.sourceIdentity,
                observedAt = snapshot.observedAt,
                reason = when {
                    value == null -> "unavailable"
                    snapshot.energyPartial -> "partial"
                    else -> "complete"
                }
            )
        }
    }
}
