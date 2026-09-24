package com.bydcollector.collector.ui

import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.data.normalized.StoredNormalizedState
import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleKpiMapperTest {
    @Test
    fun formatsObservedRemainingEnergyWithoutEstimatingFromSoc() {
        val observed = observation("battery_remaining_energy_kwh", 53.4)
        assertEquals("53,4 kWh", VehicleKpiMapper.fromObservations(listOf(observed)).remainingEnergyKwh)
        assertEquals("53.4 kWh", VehicleKpiMapper.fromObservations(listOf(observed), VehicleKpiLanguage.EN).remainingEnergyKwh)
        assertEquals("-", VehicleKpiMapper.fromObservations(listOf(observation(
            "battery_remaining_energy_kwh", 53.4, NormalizedQuality.INVALID
        ))).remainingEnergyKwh)
    }

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

        assertEquals(false, kpis.batteryPowerCharging)
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

        assertEquals(false, kpis.batteryPowerCharging)
        assertEquals("-35.8 kW", kpis.batteryPowerKw)
    }

    @Test
    fun mapsTemperatureWithDegreeSymbol() {
        val kpis = VehicleKpiMapper.from(
            listOf(
                state("inside_temp_c_raw", 22.0),
                state("battery_average_temp_raw", 28.0)
            )
        )

        assertEquals("22 °C", kpis.cabinTempC)
        assertEquals("28 °C", kpis.batteryTempC)
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

    @Test
    fun mapsDirectlyFromTheCurrentPollAndFailsClosedPerField() {
        val kpis = VehicleKpiMapper.fromObservations(
            observations = listOf(
                observation("soc", 74.0),
                observation("odometer_km", 12_345.0),
                observation("inside_temp_c_raw", 99.0, NormalizedQuality.MISSING)
            ),
            language = VehicleKpiLanguage.EN
        )

        assertEquals("74%", kpis.socPercent)
        assertEquals("12 345 km", kpis.odometerKm)
        assertEquals("-", kpis.cabinTempC)
    }

    @Test
    fun mapsAllPerfumeSlotsFromStoredStateIncludingZeroAndBounds() {
        val kpis = VehicleKpiMapper.from(
            rows = listOf(
                state("perfume_1_remaining_percent", 0.0),
                state("perfume_2_remaining_percent", 49.6),
                state("perfume_3_remaining_percent", 100.0)
            ),
            language = VehicleKpiLanguage.UK
        )

        assertEquals("0%", kpis.perfume1RemainingPercent)
        assertEquals("50%", kpis.perfume2RemainingPercent)
        assertEquals("100%", kpis.perfume3RemainingPercent)
    }

    @Test
    fun mapsAllPerfumeSlotsFromCurrentObservations() {
        val kpis = VehicleKpiMapper.fromObservations(
            observations = listOf(
                observation("perfume_1_remaining_percent", 12.4),
                observation("perfume_2_remaining_percent", 65.0),
                observation("perfume_3_remaining_percent", 99.5)
            ),
            language = VehicleKpiLanguage.EN
        )

        assertEquals("12%", kpis.perfume1RemainingPercent)
        assertEquals("65%", kpis.perfume2RemainingPercent)
        assertEquals("100%", kpis.perfume3RemainingPercent)
    }

    @Test
    fun storedPerfumeValuesFailClosedWhenMissingStaleOrOutOfRange() {
        val missing = VehicleKpiMapper.from(emptyList())
        val invalid = VehicleKpiMapper.from(
            listOf(
                state("perfume_1_remaining_percent", 25.0, NormalizedQuality.STALE),
                state("perfume_2_remaining_percent", -0.1),
                state("perfume_3_remaining_percent", 100.1)
            )
        )

        assertEquals("-", missing.perfume1RemainingPercent)
        assertEquals("-", missing.perfume2RemainingPercent)
        assertEquals("-", missing.perfume3RemainingPercent)
        assertEquals("-", invalid.perfume1RemainingPercent)
        assertEquals("-", invalid.perfume2RemainingPercent)
        assertEquals("-", invalid.perfume3RemainingPercent)
    }

    @Test
    fun currentPerfumeValuesFailClosedWhenNonFiniteOrInvalid() {
        val kpis = VehicleKpiMapper.fromObservations(
            observations = listOf(
                observation("perfume_1_remaining_percent", Double.NaN),
                observation("perfume_2_remaining_percent", Double.POSITIVE_INFINITY),
                observation("perfume_3_remaining_percent", 40.0, NormalizedQuality.INVALID)
            )
        )

        assertEquals("-", kpis.perfume1RemainingPercent)
        assertEquals("-", kpis.perfume2RemainingPercent)
        assertEquals("-", kpis.perfume3RemainingPercent)
    }

    private fun state(
        fieldKey: String,
        valueNumber: Double,
        quality: NormalizedQuality = NormalizedQuality.OK
    ): StoredNormalizedState {
        return StoredNormalizedState(
            fieldKey = fieldKey,
            category = "battery",
            valueType = "NUMBER",
            valueText = null,
            valueNumber = valueNumber,
            valueBool = null,
            quality = quality.name,
            unit = null,
            sourcePollId = 1L,
            sourceKeys = fieldKey,
            observedAt = "2026-06-19T12:00:00+03:00",
            changedAt = "2026-06-19T12:00:00+03:00"
        )
    }

    private fun observation(
        fieldKey: String,
        valueNumber: Double,
        quality: NormalizedQuality = NormalizedQuality.OK
    ): NormalizedObservation {
        val field = NormalizedFieldCatalog.fields.first { it.fieldKey == fieldKey }
        return NormalizedObservation(
            field = field,
            value = NormalizedValue(NormalizedValueType.NUMBER, number = valueNumber),
            quality = quality,
            sourcePollId = 1L,
            sourceKey = field.sourceKeys.firstOrNull(),
            observedAt = "2026-06-19T12:00:00+03:00"
        )
    }
}
