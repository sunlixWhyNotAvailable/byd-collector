package com.bydcollector.collector.ui

import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.StoredNormalizedState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object VehicleKpiMapper {
    fun from(rows: List<StoredNormalizedState>): VehicleKpis {
        val byKey = rows.associateBy { it.fieldKey }
        val power = byKey.number("charge_power_kw")
        val highestCell = byKey.number("battery_highest_cell_voltage_raw")
        val lowestCell = byKey.number("battery_lowest_cell_voltage_raw")
        val deltaMv = cellDeltaMv(highestCell, lowestCell)

        val charging = power != null && power > 0.05
        return VehicleKpis(
            socPercent = byKey.percent("soc"),
            odometerKm = byKey.km("odometer_km"),
            cabinTempC = byKey.temp("inside_temp_c_raw"),
            sohPercent = byKey.percent("battery_soh_percent"),
            batteryPowerLabelUk = if (charging) "Заряджання" else "Розряджання",
            batteryPowerLabelEn = if (charging) "Charging" else "Discharging",
            batteryPowerKw = power?.let { formatKw(if (charging) abs(it) else -abs(it)) } ?: "-",
            remainingRangeKm = byKey.km("remaining_range_km"),
            batteryTempC = byKey.temp("battery_average_temp_raw"),
            cellVoltageDeltaMv = deltaMv
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

    private fun Map<String, StoredNormalizedState>.km(fieldKey: String): String {
        return number(fieldKey)?.roundToInt()?.let { "${it.formatInt()} км" } ?: "-"
    }

    private fun Map<String, StoredNormalizedState>.temp(fieldKey: String): String {
        return number(fieldKey)?.roundToInt()?.let { "$it C" } ?: "-"
    }

    private fun formatKw(value: Double): String {
        val rounded = String.format(Locale.US, "%.1f", value)
        return "$rounded кВт"
    }

    private fun cellDeltaMv(highestCell: Double?, lowestCell: Double?): String {
        if (highestCell == null || lowestCell == null) return "-"
        val delta = highestCell - lowestCell
        if (delta < 0.0 || delta > 1.0) return "-"
        return "${(delta * 1000.0).roundToInt()} мВ"
    }

    private fun Int.formatInt(): String {
        return toString().reversed().chunked(3).joinToString(" ").reversed()
    }
}
