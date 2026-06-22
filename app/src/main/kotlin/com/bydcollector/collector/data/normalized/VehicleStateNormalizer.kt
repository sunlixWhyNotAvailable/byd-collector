package com.bydcollector.collector.data.normalized

import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.local.PollReading
import java.util.Locale

class VehicleStateNormalizer(
    private val catalog: List<NormalizedFieldDefinition> = NormalizedFieldCatalog.fields,
    private val directFloatSourceKeys: Set<String> = DirectFidRegistry.entries
        .filter { it.tx == DirectFidRegistry.TX_GET_FLOAT }
        .map { it.key }
        .toSet()
) {
    fun normalize(
        pollId: Long,
        observedAt: String,
        readings: List<PollReading>
    ): List<NormalizedObservation> {
        val byKey = readings.associateBy { it.rawKey }
        return catalog.map { field ->
            normalizeField(field, byKey, pollId, observedAt)
        }
    }

    private fun normalizeField(
        field: NormalizedFieldDefinition,
        byKey: Map<String, PollReading>,
        pollId: Long,
        observedAt: String
    ): NormalizedObservation {
        if (field.normalizerId in DERIVED_HV_POWER_NORMALIZERS) {
            val (quality, value) = when (field.normalizerId) {
                "derived_signed_hv_power_kw" -> normalizeDerivedHvPower(byKey, split = null)
                "derived_hv_charge_power_kw" -> normalizeDerivedHvPower(byKey, split = "charge")
                "derived_hv_discharge_power_kw" -> normalizeDerivedHvPower(byKey, split = "discharge")
                else -> NormalizedQuality.UNSUPPORTED to emptyValue(field.valueType)
            }
            return observation(
                field = field,
                pollId = pollId,
                observedAt = observedAt,
                sourceKey = DERIVED_HV_POWER_SOURCE_KEY,
                quality = quality,
                value = value
            )
        }

        var firstPresentObservation: NormalizedObservation? = null

        for (sourceKey in field.sourceKeys) {
            if (!byKey.containsKey(sourceKey)) {
                continue
            }

            val (quality, value) = normalizeValue(field, byKey[sourceKey])
            val candidate = observation(
                field = field,
                pollId = pollId,
                observedAt = observedAt,
                sourceKey = sourceKey,
                quality = quality,
                value = value
            )
            if (quality == NormalizedQuality.OK) {
                return candidate
            }
            if (firstPresentObservation == null) {
                firstPresentObservation = candidate
            }
        }

        return firstPresentObservation ?: observation(
            field = field,
            pollId = pollId,
            observedAt = observedAt,
            sourceKey = null,
            quality = NormalizedQuality.MISSING,
            value = emptyValue(field.valueType)
        )
    }

    private fun normalizeValue(
        field: NormalizedFieldDefinition,
        reading: PollReading?
    ): Pair<NormalizedQuality, NormalizedValue> {
        if (reading == null) {
            return NormalizedQuality.MISSING to emptyValue(field.valueType)
        }
        return when (field.normalizerId) {
            "percent_0_100" -> normalizeNumber(field, rawOnly(reading)) { it in 0.0..100.0 }
            "number_non_negative" -> normalizeNumber(field, rawOnly(reading)) { it >= 0.0 }
            "number_raw" -> normalizeNumber(field, rawOnly(reading)) { true }
            "decoded_percent_0_100" -> normalizeDecodedNumber(field, reading) { it in 0.0..100.0 }
            "decoded_speed_kmh" -> normalizeDecodedNumber(field, reading) { it in 0.0..350.0 }
            "decoded_current_a" -> normalizeDecodedNumber(field, reading) { it in -1000.0..1000.0 }
            "decoded_voltage_v" -> normalizeDecodedNumber(field, reading) { it in 0.0..1000.0 }
            "decoded_number_non_negative" -> normalizeDecodedNumber(field, reading) { it >= 0.0 }
            "decoded_number_deci_non_negative" -> normalizeDecodedNumber(field, reading, scale = 0.1) { it >= 0.0 }
            "decoded_number_raw" -> normalizeDecodedNumber(field, reading) { true }
            "raw_number_deci_non_negative" -> normalizeNumber(field, rawOnly(reading), scale = 0.1) { it >= 0.0 }
            "raw_number_milli_non_negative" -> normalizeNumber(field, rawOnly(reading), scale = 0.001) { it >= 0.0 }
            "raw_number_kpa_non_negative" -> normalizeNumber(field, rawOnly(reading)) { it in 0.0..1000.0 }
            "raw_temperature_c" -> normalizeNumber(field, rawOnly(reading)) { it in -50.0..100.0 }
            "raw_temp_c_offset_40" -> normalizeNumber(field, rawOnly(reading), offset = -40.0) { it in -50.0..100.0 }
            "zero_closed_nonzero_open",
            "zero_false_nonzero_true",
            "nonzero_true" -> normalizeBoolean(field, rawOnly(reading))
            "door_lock_state_locked" -> normalizeDoorLockState(rawOnly(reading))
            "decoded_bool_nonzero_true",
            "decoded_zero_false_nonzero_true" -> normalizeBoolean(field, decodedPreferred(reading))
            else -> NormalizedQuality.UNSUPPORTED to emptyValue(field.valueType)
        }
    }

    private fun normalizeDerivedHvPower(
        byKey: Map<String, PollReading>,
        split: String?
    ): Pair<NormalizedQuality, NormalizedValue> {
        val voltageReading = byKey[HV_BATTERY_VOLTAGE_SOURCE_KEY]
        val currentReading = byKey[HV_BATTERY_CURRENT_SOURCE_KEY]
        if (voltageReading == null || currentReading == null) {
            return NormalizedQuality.MISSING to emptyValue(NormalizedValueType.NUMBER)
        }
        if (hasRawWithoutDecodedFloat(voltageReading) || hasRawWithoutDecodedFloat(currentReading)) {
            return NormalizedQuality.INVALID to emptyValue(NormalizedValueType.NUMBER)
        }

        val voltage = parseNumber(decodedPreferred(voltageReading))
        val current = parseNumber(decodedPreferred(currentReading))
        if (voltage == null || current == null) {
            return NormalizedQuality.INVALID to emptyValue(NormalizedValueType.NUMBER)
        }

        val signed = stableDouble(voltage * current / 1000.0)
        val number = when (split) {
            "charge" -> maxOf(0.0, -signed)
            "discharge" -> maxOf(0.0, signed)
            else -> signed
        }
        return NormalizedQuality.OK to NormalizedValue(
            type = NormalizedValueType.NUMBER,
            number = stableDouble(number)
        )
    }

    private fun normalizeDoorLockState(rawValue: String?): Pair<NormalizedQuality, NormalizedValue> {
        if (rawValue == null) {
            return NormalizedQuality.MISSING to emptyValue(NormalizedValueType.BOOLEAN)
        }
        val number = parseNumber(rawValue)
        val state = number?.toInt()
        if (number == null || state == null || number != state.toDouble()) {
            return NormalizedQuality.INVALID to emptyValue(NormalizedValueType.BOOLEAN)
        }
        return when (state) {
            1 -> NormalizedQuality.OK to NormalizedValue(
                type = NormalizedValueType.BOOLEAN,
                bool = false
            )
            2 -> NormalizedQuality.OK to NormalizedValue(
                type = NormalizedValueType.BOOLEAN,
                bool = true
            )
            0 -> NormalizedQuality.MISSING to emptyValue(NormalizedValueType.BOOLEAN)
            else -> NormalizedQuality.INVALID to emptyValue(NormalizedValueType.BOOLEAN)
        }
    }

    private fun normalizeDecodedNumber(
        field: NormalizedFieldDefinition,
        reading: PollReading?,
        scale: Double = 1.0,
        isValid: (Double) -> Boolean
    ): Pair<NormalizedQuality, NormalizedValue> {
        if (hasRawWithoutDecodedFloat(reading)) {
            return NormalizedQuality.INVALID to emptyValue(field.valueType)
        }
        return normalizeNumber(field, decodedPreferred(reading), scale = scale, isValid = isValid)
    }

    private fun decodedPreferred(reading: PollReading?): String? {
        return reading?.descValue?.trim()?.takeIf { it.isNotEmpty() }
            ?: reading?.rawValue?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun rawOnly(reading: PollReading?): String? {
        return reading?.rawValue
    }

    private fun hasRawWithoutDecodedFloat(reading: PollReading?): Boolean {
        if (reading == null) return false
        val hasRaw = reading.rawValue?.trim()?.isNotEmpty() == true
        val hasDecoded = reading.descValue?.trim()?.isNotEmpty() == true
        return hasRaw && !hasDecoded && reading.rawKey in directFloatSourceKeys
    }

    private fun normalizeNumber(
        field: NormalizedFieldDefinition,
        rawValue: String?,
        scale: Double = 1.0,
        offset: Double = 0.0,
        isValid: (Double) -> Boolean
    ): Pair<NormalizedQuality, NormalizedValue> {
        if (rawValue == null) {
            return NormalizedQuality.MISSING to emptyValue(field.valueType)
        }
        val number = parseNumber(rawValue)?.let { stableDouble(it * scale + offset) }
        if (number == null || !isValid(number)) {
            return NormalizedQuality.INVALID to emptyValue(field.valueType)
        }
        return NormalizedQuality.OK to NormalizedValue(
            type = NormalizedValueType.NUMBER,
            number = stableDouble(number)
        )
    }

    private fun normalizeBoolean(
        field: NormalizedFieldDefinition,
        rawValue: String?
    ): Pair<NormalizedQuality, NormalizedValue> {
        if (rawValue == null) {
            return NormalizedQuality.MISSING to emptyValue(field.valueType)
        }
        val number = parseNumber(rawValue)
        if (number == null) {
            return NormalizedQuality.INVALID to emptyValue(field.valueType)
        }
        return NormalizedQuality.OK to NormalizedValue(
            type = NormalizedValueType.BOOLEAN,
            bool = number != 0.0
        )
    }

    private fun parseNumber(rawValue: String?): Double? {
        val parsed = rawValue
            ?.trim()
            ?.replace(',', '.')
            ?.takeIf { it.isNotEmpty() }
            ?.toDoubleOrNull()
            ?: return null
        return parsed.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    private fun stableDouble(value: Double): Double {
        return String.format(Locale.US, "%.6f", value)
            .trimEnd('0')
            .trimEnd('.')
            .toDouble()
    }

    private fun observation(
        field: NormalizedFieldDefinition,
        pollId: Long,
        observedAt: String,
        sourceKey: String?,
        quality: NormalizedQuality,
        value: NormalizedValue
    ): NormalizedObservation {
        return NormalizedObservation(
            field = field,
            value = value,
            quality = quality,
            sourcePollId = pollId,
            sourceKey = sourceKey,
            observedAt = observedAt
        )
    }

    private fun emptyValue(type: NormalizedValueType): NormalizedValue {
        return NormalizedValue(type = type)
    }

    private companion object {
        const val HV_BATTERY_VOLTAGE_SOURCE_KEY = "charging_charge_battery_volt"
        const val HV_BATTERY_CURRENT_SOURCE_KEY = "charging_charging_charge_current_not_convert"
        const val DERIVED_HV_POWER_SOURCE_KEY =
            "$HV_BATTERY_VOLTAGE_SOURCE_KEY+$HV_BATTERY_CURRENT_SOURCE_KEY"
        val DERIVED_HV_POWER_NORMALIZERS = setOf(
            "derived_signed_hv_power_kw",
            "derived_hv_charge_power_kw",
            "derived_hv_discharge_power_kw"
        )
    }
}
