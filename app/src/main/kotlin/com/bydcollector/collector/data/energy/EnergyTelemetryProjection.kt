package com.bydcollector.collector.data.energy

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.data.input.*
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer

object EnergyTelemetryProjection {
    /** Proof concerns the state before this journal event, not two valid endpoints. */
    fun intervalContinuity(previous: Map<String, NormalizedSourceInput>,
        continuity: Map<String, BusinessInputContinuity>, delta: BusinessInputDelta,
        normalizer: VehicleStateNormalizer): EnergyIntervalContinuity? {
        val keys = REQUIRED_KEYS + OPTIONAL_VETO_FIELDS.keys.filter { it in previous }
        if (keys.none { it in continuity || delta.sourceInputs[it]?.stamp?.sourceEpoch != null ||
                it in delta.continuityUpdates }) return null
        val unproven = EnergyIntervalContinuity(null)
        val boot = delta.ref.bootId ?: return unproven
        val elapsed = delta.ref.acquiredElapsedMs ?: return unproven
        val proven = BusinessInputProjection.continuousSources(previous, continuity, boot, elapsed)
        if (keys.any { it !in proven }) return unproven
        val veto = normalizer.normalizeSparse(previous, OPTIONAL_VETO_FIELDS.keys.intersect(previous.keys))
            .associateBy { it.field.fieldKey }
        if (OPTIONAL_VETO_FIELDS.any { (raw, field) -> raw in previous && veto[field]?.quality != NormalizedQuality.OK }) return unproven
        for (key in keys) {
            val old = previous.getValue(key).stamp
            val next = delta.sourceInputs[key]?.stamp ?: delta.continuityUpdates[key]?.stamp ?: continue
            if (old.bootId != next.bootId || old.generatorId != next.generatorId ||
                old.sourceEpoch == null || old.sourceEpoch != next.sourceEpoch) return unproven
            val loss = delta.continuityUpdates[key]?.takeIf { it.state == BusinessContinuityState.LOST }
            // APP-observed death and registration failures do not establish when the source stopped.
            if (loss != null && delta.callbackEvent == null && loss.reason !in EXACT_LOSS_REASONS) return unproven
        }
        return EnergyIntervalContinuity(keys.maxOf { proven.getValue(it) })
    }

    private val REQUIRED_KEYS = setOf("charging_charge_battery_volt", "charging_charge_current",
        "bodywork_power_level", "charging_1009_876609586_5")
    private val OPTIONAL_VETO_FIELDS = mapOf("charging_1009_89128973_5" to "charger_connected_raw",
        "charging_charging_vtov_discharge_status" to "v2l_discharge_active_raw",
        "charging_1009_876609560_5" to "charging_battery_device_state")
    private val EXACT_LOSS_REASONS = setOf("explicit_stop", "capture_ineligible", "consumer_rejected")

    fun receipt(source: PollSampleSource, observedAt: String, readings: List<PollReading>,
                observations: List<NormalizedObservation>, continuity: EnergyIntervalContinuity? = null): EnergyReceipt {
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
            power?.let { it > 0 }, connected?.not(), external, continuity
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
