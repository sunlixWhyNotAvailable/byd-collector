package com.bydcollector.collector.ui

import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.StoredNormalizedState
import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleKpiMapperTest {
    @Test
    fun mapsSohAndCellVoltageDeltaFromNormalizedState() {
        val kpis = VehicleKpiMapper.from(
            listOf(
                state("battery_soh_percent", 95.0),
                state("battery_highest_cell_voltage_raw", 3.314),
                state("battery_lowest_cell_voltage_raw", 3.312)
            )
        )

        assertEquals("95%", kpis.sohPercent)
        assertEquals("2 мВ", kpis.cellVoltageDeltaMv)
    }

    @Test
    fun mapsBatteryDischargePowerFromCurrentNormalizedSplit() {
        val kpis = VehicleKpiMapper.from(
            listOf(
                state("battery_charge_power_kw", 0.0),
                state("battery_discharge_power_kw", 35.8)
            )
        )

        assertEquals("Розряджання", kpis.batteryPowerLabelUk)
        assertEquals("Discharging", kpis.batteryPowerLabelEn)
        assertEquals("-35.8 кВт", kpis.batteryPowerKw)
    }

    @Test
    fun mapsBatteryDischargePowerUnitsForEnglish() {
        val kpis = VehicleKpiMapper.from(
            rows = listOf(
                state("battery_charge_power_kw", 0.0),
                state("battery_discharge_power_kw", 35.8)
            ),
            language = VehicleKpiLanguage.EN
        )

        assertEquals("Discharging", kpis.batteryPowerLabelEn)
        assertEquals("-35.8 kW", kpis.batteryPowerKw)
    }

    @Test
    fun mapsDistanceAndCellVoltageUnitsForEnglish() {
        val kpis = VehicleKpiMapper.from(
            rows = listOf(
                state("odometer_km", 1234.0),
                state("battery_highest_cell_voltage_raw", 3.314),
                state("battery_lowest_cell_voltage_raw", 3.312)
            ),
            language = VehicleKpiLanguage.EN
        )

        assertEquals("1 234 km", kpis.odometerKm)
        assertEquals("2 mV", kpis.cellVoltageDeltaMv)
    }

    @Test
    fun missingOrInvalidBatteryHealthValuesFailClosed() {
        val missing = VehicleKpiMapper.from(emptyList())
        val invalidDelta = VehicleKpiMapper.from(
            listOf(
                state("battery_soh_percent", 95.0),
                state("battery_highest_cell_voltage_raw", 3.310),
                state("battery_lowest_cell_voltage_raw", 3.312)
            )
        )

        assertEquals("-", missing.sohPercent)
        assertEquals("-", missing.cellVoltageDeltaMv)
        assertEquals("-", invalidDelta.cellVoltageDeltaMv)
    }

    private fun state(fieldKey: String, valueNumber: Double): StoredNormalizedState {
        return StoredNormalizedState(
            fieldKey = fieldKey,
            category = "battery",
            valueType = "NUMBER",
            valueText = null,
            valueNumber = valueNumber,
            valueBool = null,
            quality = NormalizedQuality.OK.name,
            unit = null,
            sourcePollId = 1L,
            sourceKeys = fieldKey,
            observedAt = "2026-06-19T12:00:00+03:00",
            changedAt = "2026-06-19T12:00:00+03:00"
        )
    }
}
