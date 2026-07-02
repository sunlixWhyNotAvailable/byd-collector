package com.bydcollector.collector.ui

import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.StoredNormalizedState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class VehicleKpiLanguage { UK, EN }

object VehicleKpiMapper {
    fun from(
        rows: List<StoredNormalizedState>,
        language: VehicleKpiLanguage = VehicleKpiLanguage.UK
    ): VehicleKpis {
        val byKey = rows.associateBy { it.fieldKey }
        val chargePower = byKey.number("battery_charge_power_kw")
        val dischargePower = byKey.number("battery_discharge_power_kw")
        val highestCell = byKey.number("battery_highest_cell_voltage_raw")
        val lowestCell = byKey.number("battery_lowest_cell_voltage_raw")
        val deltaMv = cellDeltaMv(highestCell, lowestCell)

        val charging = chargePower != null && chargePower > 0.05
        val power = if (charging) chargePower else dischargePower
        return VehicleKpis(
            socPercent = byKey.percent("soc"),
            odometerKm = byKey.km("odometer_km", language),
            cabinTempC = byKey.temp("inside_temp_c_raw"),
            sohPercent = byKey.percent("battery_soh_percent"),
            batteryPowerLabelUk = if (charging) "Заряджання" else "Розряджання",
            batteryPowerLabelEn = if (charging) "Charging" else "Discharging",
            batteryPowerKw = power?.let { formatKw(if (charging) abs(it) else -abs(it), language) } ?: "-",
            remainingRangeKm = byKey.km("remaining_range_km", language),
            batteryTempC = byKey.temp("battery_average_temp_raw"),
            cellVoltageDeltaMv = deltaMv?.let { formatMv(it, language) } ?: "-"
        )
    }

    private fun Map<String, StoredNormalizedState>.number(fieldKey: String): Double? {
        val row = this[fieldKey] ?: return null
        if (row.quality != NormalizedQuality.OK.name) return null
        return row.valueNumber
    }

    private fun Map<String, StoredNormalizedState>.percent(fieldKey: String): String {
        return number(fieldKey)?.roundToInt()?.let { "$it%" } ?: "-"
    }

    private fun Map<String, StoredNormalizedState>.km(fieldKey: String, language: VehicleKpiLanguage): String {
        val unit = if (language == VehicleKpiLanguage.UK) "км" else "km"
        return number(fieldKey)?.roundToInt()?.let { "${it.formatInt()} $unit" } ?: "-"
    }

    private fun Map<String, StoredNormalizedState>.temp(fieldKey: String): String {
        return number(fieldKey)?.roundToInt()?.let { "$it C" } ?: "-"
    }

    private fun formatKw(value: Double, language: VehicleKpiLanguage): String {
        val rounded = String.format(Locale.US, "%.1f", value)
        val unit = if (language == VehicleKpiLanguage.UK) "кВт" else "kW"
        return "$rounded $unit"
    }

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
