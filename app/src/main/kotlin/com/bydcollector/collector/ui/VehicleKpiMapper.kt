package com.bydcollector.collector.ui

import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.StoredNormalizedState
import com.bydcollector.collector.data.energy.RemainingRangeEstimate
import com.bydcollector.collector.data.energy.RemainingRangeResolver
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class VehicleKpiLanguage { UK, EN }

object VehicleKpiMapper {
    fun from(
        rows: List<StoredNormalizedState>,
        language: VehicleKpiLanguage = VehicleKpiLanguage.UK,
        ecMeanKwhPer100Km: Double? = null
    ): VehicleKpis {
        val byKey = rows.associateBy { it.fieldKey }
        val chargePower = byKey.number("battery_charge_power_kw")
        val dischargePower = byKey.number("battery_discharge_power_kw")
        val highestCell = byKey.number("battery_highest_cell_voltage_raw")
        val lowestCell = byKey.number("battery_lowest_cell_voltage_raw")
        val deltaMv = cellDeltaMv(highestCell, lowestCell)

        val charging = chargePower != null && chargePower > 0.05
        val power = if (charging) chargePower else dischargePower
        val range = resolveRange(
            ecMeanKwhPer100Km,
            byKey.number("cumulative_energy_kwh"),
            byKey.number("odometer_km"),
            byKey.number("battery_remaining_energy_kwh"),
            byKey.number("remaining_range_km")
        )
        return VehicleKpis(
            socPercent = byKey.percent("soc"),
            remainingEnergyKwh = formatKwh(byKey.number("battery_remaining_energy_kwh"), language),
            odometerKm = byKey.km("odometer_km", language),
            cabinTempC = byKey.temp("inside_temp_c_raw"),
            perfume1RemainingPercent = byKey.boundedPercent("perfume_1_remaining_percent"),
            perfume2RemainingPercent = byKey.boundedPercent("perfume_2_remaining_percent"),
            perfume3RemainingPercent = byKey.boundedPercent("perfume_3_remaining_percent"),
            sohPercent = byKey.percent("battery_soh_percent"),
            batteryPowerCharging = charging,
            batteryPowerKw = power?.let { formatKw(if (charging) abs(it) else -abs(it), language) } ?: "-",
            remainingRangeKm = range.rangeKm.kmValue(language),
            batteryTempC = byKey.temp("battery_average_temp_raw"),
            cellVoltageDeltaMv = deltaMv?.let { formatMv(it, language) } ?: "-",
            averageConsumptionKwhPer100Km = formatNumber(range.averageKwhPer100Km, 1, language) ?: "-",
            minCellVoltageV = formatElectricalNumber(lowestCell, 3, "V", language) ?: "-",
            maxCellVoltageV = formatElectricalNumber(highestCell, 3, "V", language) ?: "-",
            batteryVoltageV = formatElectricalNumber(byKey.number("hv_battery_voltage_v"), 0, "V", language) ?: "-",
            batteryCurrentA = formatBatteryCurrent(byKey.number("charge_current_a"), language),
            auxVoltageV = formatElectricalNumber(byKey.number("aux_voltage_v"), 1, "V", language) ?: "-",
            acCompressorPowerKw = formatCompressorPower(byKey.number("ac_compressor_power_kw"), language)
        )
    }

    fun fromObservations(
        observations: List<NormalizedObservation>,
        language: VehicleKpiLanguage = VehicleKpiLanguage.UK,
        ecMeanKwhPer100Km: Double? = null
    ): VehicleKpis {
        val numbers = currentNumbers(observations)
        val chargePower = numbers["battery_charge_power_kw"]
        val dischargePower = numbers["battery_discharge_power_kw"]
        val deltaMv = cellDeltaMv(
            numbers["battery_highest_cell_voltage_raw"],
            numbers["battery_lowest_cell_voltage_raw"]
        )
        val charging = chargePower != null && chargePower > 0.05
        val power = if (charging) chargePower else dischargePower
        val range = resolveRange(
            ecMeanKwhPer100Km,
            numbers["cumulative_energy_kwh"],
            numbers["odometer_km"],
            numbers["battery_remaining_energy_kwh"],
            numbers["remaining_range_km"]
        )
        return VehicleKpis(
            socPercent = numbers.percentValue("soc"),
            remainingEnergyKwh = formatKwh(numbers["battery_remaining_energy_kwh"], language),
            odometerKm = numbers.kmValue("odometer_km", language),
            cabinTempC = numbers.tempValue("inside_temp_c_raw"),
            perfume1RemainingPercent = numbers.boundedPercentValue("perfume_1_remaining_percent"),
            perfume2RemainingPercent = numbers.boundedPercentValue("perfume_2_remaining_percent"),
            perfume3RemainingPercent = numbers.boundedPercentValue("perfume_3_remaining_percent"),
            sohPercent = numbers.percentValue("battery_soh_percent"),
            batteryPowerCharging = charging,
            batteryPowerKw = power?.let { formatKw(if (charging) abs(it) else -abs(it), language) } ?: "-",
            remainingRangeKm = range.rangeKm.kmValue(language),
            batteryTempC = numbers.tempValue("battery_average_temp_raw"),
            cellVoltageDeltaMv = deltaMv?.let { formatMv(it, language) } ?: "-",
            averageConsumptionKwhPer100Km = formatNumber(range.averageKwhPer100Km, 1, language) ?: "-",
            minCellVoltageV = formatElectricalNumber(numbers["battery_lowest_cell_voltage_raw"], 3, "V", language) ?: "-",
            maxCellVoltageV = formatElectricalNumber(numbers["battery_highest_cell_voltage_raw"], 3, "V", language) ?: "-",
            batteryVoltageV = formatElectricalNumber(numbers["hv_battery_voltage_v"], 0, "V", language) ?: "-",
            batteryCurrentA = formatBatteryCurrent(numbers["charge_current_a"], language),
            auxVoltageV = formatElectricalNumber(numbers["aux_voltage_v"], 1, "V", language) ?: "-",
            acCompressorPowerKw = formatCompressorPower(numbers["ac_compressor_power_kw"], language)
        )
    }

    internal fun rangeEstimateFromObservations(
        observations: List<NormalizedObservation>,
        ecMeanKwhPer100Km: Double?
    ): RemainingRangeEstimate {
        val numbers = currentNumbers(observations)
        return resolveRange(
            ecMeanKwhPer100Km,
            numbers["cumulative_energy_kwh"],
            numbers["odometer_km"],
            numbers["battery_remaining_energy_kwh"],
            numbers["remaining_range_km"]
        )
    }

    private fun currentNumbers(observations: List<NormalizedObservation>): Map<String, Double> = observations
        .asSequence()
        .filter { it.quality == NormalizedQuality.OK }
        .mapNotNull { observation -> observation.value.number?.let { observation.field.fieldKey to it } }
        .toMap()

    private fun resolveRange(
        ecMeanKwhPer100Km: Double?,
        cumulativeEnergyKwh: Double?,
        odometerKm: Double?,
        remainingEnergyKwh: Double?,
        oemRangeKm: Double?
    ): RemainingRangeEstimate = RemainingRangeResolver.resolve(
        ecMeanKwhPer100Km = ecMeanKwhPer100Km,
        cumulativeEnergyKwh = cumulativeEnergyKwh,
        odometerKm = odometerKm,
        remainingEnergyKwh = remainingEnergyKwh,
        oemRangeKm = oemRangeKm
    )

    private fun Map<String, StoredNormalizedState>.number(fieldKey: String): Double? {
        val row = this[fieldKey] ?: return null
        if (row.quality != NormalizedQuality.OK.name) return null
        return row.valueNumber
    }

    private fun Map<String, StoredNormalizedState>.percent(fieldKey: String): String {
        return number(fieldKey)?.roundToInt()?.let { "$it%" } ?: "-"
    }

    private fun Map<String, StoredNormalizedState>.boundedPercent(fieldKey: String): String {
        return number(fieldKey).formatBoundedPercent()
    }

    private fun Map<String, StoredNormalizedState>.km(fieldKey: String, language: VehicleKpiLanguage): String {
        val unit = if (language == VehicleKpiLanguage.UK) "км" else "km"
        return number(fieldKey)?.roundToInt()?.let { "${it.formatInt()} $unit" } ?: "-"
    }

    private fun Map<String, StoredNormalizedState>.temp(fieldKey: String): String {
        return number(fieldKey)?.roundToInt()?.let { "$it °C" } ?: "-"
    }

    private fun Map<String, Double>.percentValue(fieldKey: String): String {
        return this[fieldKey]?.roundToInt()?.let { "$it%" } ?: "-"
    }

    private fun Map<String, Double>.boundedPercentValue(fieldKey: String): String {
        return this[fieldKey].formatBoundedPercent()
    }

    private fun Double?.formatBoundedPercent(): String {
        return this
            ?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?.roundToInt()
            ?.let { "$it%" }
            ?: "-"
    }

    private fun Map<String, Double>.kmValue(fieldKey: String, language: VehicleKpiLanguage): String {
        val unit = if (language == VehicleKpiLanguage.UK) "км" else "km"
        return this[fieldKey]?.roundToInt()?.let { "${it.formatInt()} $unit" } ?: "-"
    }

    private fun Double?.kmValue(language: VehicleKpiLanguage): String {
        val unit = if (language == VehicleKpiLanguage.UK) "км" else "km"
        return this?.roundToInt()?.let { "${it.formatInt()} $unit" } ?: "-"
    }

    private fun Map<String, Double>.tempValue(fieldKey: String): String {
        return this[fieldKey]?.roundToInt()?.let { "$it °C" } ?: "-"
    }

    private fun formatKw(value: Double, language: VehicleKpiLanguage): String {
        val rounded = String.format(Locale.US, "%.1f", value)
        val unit = if (language == VehicleKpiLanguage.UK) "кВт" else "kW"
        return "$rounded $unit"
    }

    private fun formatBatteryCurrent(value: Double?, language: VehicleKpiLanguage): String {
        // Vehicle telemetry is positive for discharge; only the presentation reverses it.
        val current = value?.takeIf { it.isFinite() }
            ?.let { if (abs(it) < 0.05) 0.0 else -it } ?: return "-"
        val text = formatElectricalNumber(current, 1, "A", language, allowNegative = true) ?: return "-"
        return if (current > 0.0) "+$text" else text
    }

    private fun formatCompressorPower(value: Double?, language: VehicleKpiLanguage): String {
        val consumption = value?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { if (it < 0.005) 0.0 else -it } ?: return "-"
        return formatElectricalNumber(consumption, 2, "kW", language, allowNegative = true) ?: "-"
    }

    private fun formatElectricalNumber(
        value: Double?,
        decimals: Int,
        unit: String,
        language: VehicleKpiLanguage,
        allowNegative: Boolean = false
    ): String? = formatNumber(value?.takeIf { allowNegative || it >= 0.0 }, decimals, language)
        ?.let {
            val localizedUnit = if (language == VehicleKpiLanguage.UK) when (unit) {
                "V" -> "В"
                "A" -> "А"
                "kW" -> "кВт"
                else -> unit
            } else unit
            "$it $localizedUnit"
        }

    private fun formatNumber(value: Double?, decimals: Int, language: VehicleKpiLanguage): String? = value
        ?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.US, "%.${decimals}f", it) }
        ?.let { formatted -> if (language == VehicleKpiLanguage.UK) formatted.replace('.', ',') else formatted }

    private fun formatKwh(value: Double?, language: VehicleKpiLanguage): String = value
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { String.format(Locale.US, "%.1f", it).let { text ->
            if (language == VehicleKpiLanguage.UK) text.replace('.', ',') else text
        } }
        ?: "-"

    private fun formatMv(value: Int, language: VehicleKpiLanguage): String {
        val unit = if (language == VehicleKpiLanguage.UK) "мВ" else "mV"
        return "$value $unit"
    }

    private fun cellDeltaMv(highestCell: Double?, lowestCell: Double?): Int? {
        if (highestCell == null || lowestCell == null) return null
        val delta = highestCell - lowestCell
        if (delta < 0.0 || delta > 1.0) return null
        return (delta * 1000.0).roundToInt()
    }

    private fun Int.formatInt(): String {
        return toString().reversed().chunked(3).joinToString(" ").reversed()
    }
}
