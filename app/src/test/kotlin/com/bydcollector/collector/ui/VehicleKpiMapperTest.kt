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
