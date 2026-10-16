package com.bydcollector.collector.ui

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.StoredNormalizedState
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.assertEquals

class VehicleKpiMapperTest {
    private val normalizer = VehicleStateNormalizer(NormalizedFieldCatalog.kpiFields)

    @Test
    fun normalizedElectricalKpisMapAcrossLocalesAndPathsAndSerialize() {
        val observations = normalize(
            reading("charging_charge_battery_volt", "704", "704.2"),
            reading("charging_charge_current", "4.2", "4.2"),
            reading("ota_battery_voltage", "13.8", "13.84"),
            reading("statistic_highest_battery_voltage", "3303"),
            reading("statistic_lowest_battery_voltage", "3301"),
            reading("power_battery_remain_electricity", "54.3", "54.3")
        )
        val uk = VehicleKpiMapper.fromObservations(observations, VehicleKpiLanguage.UK, 17.84)
        val en = VehicleKpiMapper.fromObservations(observations, VehicleKpiLanguage.EN, 17.84)

        assertEquals("3,301 В", uk.minCellVoltageV)
        assertEquals("3,303 В", uk.maxCellVoltageV)
        assertEquals("704 В", uk.batteryVoltageV)
        assertEquals("4,2 А", uk.batteryCurrentA)
        assertEquals("13,8 В", uk.auxVoltageV)
        assertEquals("17,8", uk.averageConsumptionKwhPer100Km)
        assertEquals("-3.0 кВт", uk.batteryPowerKw)
        assertEquals("3.301 V", en.minCellVoltageV)
        assertEquals("3.303 V", en.maxCellVoltageV)
        assertEquals("704 V", en.batteryVoltageV)
        assertEquals("4.2 A", en.batteryCurrentA)
        assertEquals("13.8 V", en.auxVoltageV)
        assertEquals("17.8", en.averageConsumptionKwhPer100Km)

        val storedRows = listOf(
            stored("battery_lowest_cell_voltage_raw", 3.301),
            stored("battery_highest_cell_voltage_raw", 3.303),
            stored("hv_battery_voltage_v", 704.2),
            stored("charge_current_a", 4.2),
            stored("aux_voltage_v", 13.84),
            stored("battery_remaining_energy_kwh", 54.3)
        )
        val storedUk = VehicleKpiMapper.from(storedRows, VehicleKpiLanguage.UK, 17.84)
        assertEquals("3,301 В", storedUk.minCellVoltageV)
        assertEquals("3,303 В", storedUk.maxCellVoltageV)
        assertEquals("704 В", storedUk.batteryVoltageV)
        assertEquals("4,2 А", storedUk.batteryCurrentA)
        assertEquals("13,8 В", storedUk.auxVoltageV)
        assertEquals("17,8", storedUk.averageConsumptionKwhPer100Km)
        val storedEn = VehicleKpiMapper.from(storedRows, VehicleKpiLanguage.EN, 17.84)
        assertEquals("3.301 V", storedEn.minCellVoltageV)
        assertEquals("3.303 V", storedEn.maxCellVoltageV)
        assertEquals("704 V", storedEn.batteryVoltageV)
        assertEquals("4.2 A", storedEn.batteryCurrentA)
        assertEquals("13.8 V", storedEn.auxVoltageV)
        assertEquals("17.8", storedEn.averageConsumptionKwhPer100Km)

        val invalid = normalize(
            reading("charging_charge_battery_volt", "704", "NaN"),
            reading("charging_charge_current", "1", "Infinity"),
            reading("ota_battery_voltage", "1", "-Infinity"),
            reading("statistic_highest_battery_voltage", "-1"),
            reading("statistic_lowest_battery_voltage", "NaN")
        )
        assertElectricalDashes(VehicleKpiMapper.fromObservations(invalid, VehicleKpiLanguage.UK))
        assertElectricalDashes(VehicleKpiMapper.from(
            listOf(
                stored("battery_lowest_cell_voltage_raw", null, NormalizedQuality.INVALID),
                stored("battery_highest_cell_voltage_raw", null, NormalizedQuality.INVALID),
                stored("hv_battery_voltage_v", null, NormalizedQuality.INVALID),
                stored("charge_current_a", null, NormalizedQuality.INVALID),
                stored("aux_voltage_v", null, NormalizedQuality.MISSING)
            ),
            VehicleKpiLanguage.UK
        ))

        val serialized = ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(uk) }
            bytes.toByteArray()
        }
        val restored = ObjectInputStream(ByteArrayInputStream(serialized)).use {
            it.readObject() as VehicleKpis
        }
        assertEquals(uk, restored)
    }

    @Test
    fun rangeAverageUsesLifetimeMeanAndIsAbsentForOemFallback() {
        val lifetime = normalize(
            reading("statistic_total_elec_consumption", "200", "200"),
            reading("statistic_1014_1246765072_5", "10000"),
            reading("power_battery_remain_electricity", "50", "50"),
            reading("statistic_elec_driving_range_yun", "280")
        )
        val lifetimeKpis = VehicleKpiMapper.fromObservations(lifetime, VehicleKpiLanguage.EN)
        assertEquals("20.0", lifetimeKpis.averageConsumptionKwhPer100Km)
        assertEquals("250 km", lifetimeKpis.remainingRangeKm)

        val oem = normalize(
            reading("power_battery_remain_electricity", "50", "50"),
            reading("statistic_elec_driving_range_yun", "260")
        )
        val oemKpis = VehicleKpiMapper.fromObservations(oem, VehicleKpiLanguage.UK)
        assertEquals("260 км", oemKpis.remainingRangeKm)
        assertEquals("-", oemKpis.averageConsumptionKwhPer100Km)
    }

    @Test
    fun validZeroAndNegativeCurrentKeepTheirValuesAndPolarity() {
        val zero = normalize(
            reading("charging_charge_battery_volt", "0", "0"),
            reading("charging_charge_current", "0", "0"),
            reading("ota_battery_voltage", "0", "0"),
            reading("statistic_highest_battery_voltage", "0"),
            reading("statistic_lowest_battery_voltage", "0")
        )
        val zeroKpis = VehicleKpiMapper.fromObservations(zero, VehicleKpiLanguage.UK)
        assertEquals("0,000 В", zeroKpis.minCellVoltageV)
        assertEquals("0,000 В", zeroKpis.maxCellVoltageV)
        assertEquals("0 В", zeroKpis.batteryVoltageV)
        assertEquals("0,0 А", zeroKpis.batteryCurrentA)
        assertEquals("0,0 В", zeroKpis.auxVoltageV)

        val charging = normalize(
            reading("charging_charge_battery_volt", "704", "704"),
            reading("charging_charge_current", "1", "-0.7")
        )
        val chargingKpis = VehicleKpiMapper.fromObservations(charging, VehicleKpiLanguage.EN)
        assertEquals("-0.7 A", chargingKpis.batteryCurrentA)
        assertEquals("0.5 kW", chargingKpis.batteryPowerKw)
    }

    @Test
    fun absentReadingsMapToDashes() {
        assertElectricalDashes(VehicleKpiMapper.fromObservations(normalize(), VehicleKpiLanguage.EN))
    }

    private fun normalize(vararg readings: PollReading) = normalizer.normalize(
        pollId = 1L,
        observedAt = "2026-10-16T12:00:00Z",
        readings = readings.toList()
    )

    private fun reading(key: String, raw: String, description: String? = null) =
        PollReading(rawKey = key, rawValue = raw, descValue = description)

    private fun stored(
        fieldKey: String,
        value: Double?,
        quality: NormalizedQuality = NormalizedQuality.OK
    ) = StoredNormalizedState(
        fieldKey = fieldKey,
        category = "battery",
        valueType = "NUMBER",
        valueText = null,
        valueNumber = value,
        valueBool = null,
        quality = quality.name,
        unit = null,
        sourcePollId = null,
        sourceKeys = "",
        observedAt = "2026-10-16T12:00:00Z",
        changedAt = "2026-10-16T12:00:00Z"
    )

    private fun assertElectricalDashes(kpis: VehicleKpis) {
        assertEquals("-", kpis.minCellVoltageV)
        assertEquals("-", kpis.maxCellVoltageV)
        assertEquals("-", kpis.batteryVoltageV)
        assertEquals("-", kpis.batteryCurrentA)
        assertEquals("-", kpis.auxVoltageV)
    }
}
