package com.bydcollector.collector.service

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.*
import com.bydcollector.collector.ui.VehicleKpiMapper
import org.junit.Assert.*
import org.junit.Test

class KpiFreshnessTest {
    @Test fun `expiry evidence is source specific rate limited and cannot revive stale values`() {
        val state = KpiFreshness("boot", 3_000)
        state.accept(listOf(observation("soc", 80.0, 10_000)), 10_100)
        assertFalse(state.expiryDiagnostics(10_100)!!.contains("field=soc "))
        assertNull(state.expiryDiagnostics(13_100))
        assertTrue(state.freshObservations(13_100).isEmpty())
        state.accept(listOf(observation("soc", 10.0, 9_000)), 40_100)
        val detail = state.expiryDiagnostics(40_100)!!
        assertTrue(detail.contains("field=soc "))
        assertTrue(detail.contains("reason=source_age age_ms=30100 last_rejection=source_age"))
        state.accept(listOf(observation("soc", 79.0, 40_200)), 40_200)
        state.freshObservations(43_200)
        state.freshObservations(43_201) // Same expired source is counted once.
        state.accept(listOf(observation("soc", 78.0, 70_000)), 70_000)
        assertTrue(state.expiryDiagnostics(70_100)!!.contains("reason=recovered_after_source_age age_ms=100 last_rejection=null expiries=1"))
        state.clear()
        assertTrue(state.expiryDiagnostics(70_101)!!.contains("reason=not_observed"))
    }

    @Test fun `pending and old replay cannot extend a real observation deadline`() {
        val state = KpiFreshness("boot", 3_000)
        assertTrue(state.accept(listOf(observation("soc", 80.0, 10_000)), 10_100))
        assertEquals(2_900L, state.remainingMs(10_100))
        assertFalse(state.accept(listOf(observation("soc", 10.0, 9_000)), 11_000))
        assertEquals("80%", VehicleKpiMapper.fromObservations(state.freshObservations(12_000)).socPercent)
        assertEquals(1_000L, state.remainingMs(12_000))
        assertEquals(0L, state.remainingMs(13_000))
        assertFalse(state.accept(listOf(observation("soc", 80.0, 10_000)), 13_000))
        assertTrue(state.accept(listOf(observation("soc", 79.0, 13_000)), 13_100))
    }

    @Test fun `other boot and future time are not fresh and stop clears retained age`() {
        val state = KpiFreshness("boot", 3_000)
        assertFalse(state.accept(listOf(observation("soc", 80.0, 1_000, "previous")), 1_001))
        assertFalse(state.accept(listOf(observation("soc", 80.0, 2_000)), 1_001))
        assertTrue(state.accept(listOf(observation("soc", 80.0, 1_000)), 1_001))
        state.clear()
        assertEquals(0L, state.remainingMs(1_002))
        assertTrue(state.freshObservations(1_002).isEmpty())
    }

    @Test fun `fresh sparse field never refreshes older battery and range values`() {
        val state = KpiFreshness("boot", 3_000)
        state.accept(listOf(observation("soc", 80.0, 10_000),
            observation("remaining_range_km", 400.0, 10_500)), 10_600)
        state.accept(listOf(observation("inside_temp_c_raw", 22.0, 12_500)), 12_600)
        assertEquals(400L, state.remainingMs(12_600))
        val firstExpiry = VehicleKpiMapper.fromObservations(state.freshObservations(13_000))
        assertEquals("-", firstExpiry.socPercent)
        assertEquals("400 км", firstExpiry.remainingRangeKm)
        assertEquals("22 °C", firstExpiry.cabinTempC)
        assertEquals(500L, state.remainingMs(13_000))
        val secondExpiry = VehicleKpiMapper.fromObservations(state.freshObservations(13_500))
        assertEquals("-", secondExpiry.remainingRangeKm)
        assertEquals("22 °C", secondExpiry.cabinTempC)
        assertFalse(state.accept(listOf(observation("speed_kmh", 20.0, 13_500)), 13_500))
    }

    @Test fun `composite power uses oldest contributor and rejects mixed boots`() {
        val field = NormalizedFieldCatalog.fields.single { it.fieldKey == "battery_discharge_power_kw" }
        fun input(key: String, number: String, elapsed: Long, boot: String = "boot") =
            NormalizedSourceInput(PollReading(key, number, number), stamp(elapsed, boot), 1)
        val inputs = mapOf(
            "charging_charge_battery_volt" to input("charging_charge_battery_volt", "400", 10_000),
            "charging_charge_current" to input("charging_charge_current", "10", 12_000)
        )
        val normalizer = VehicleStateNormalizer(catalog = listOf(field))
        val observation = normalizer.normalizeSparse(inputs, setOf("charging_charge_current")).single()
        assertEquals(10_000L, observation.sourceStamp?.elapsedMs)
        val state = KpiFreshness("boot", 3_000)
        assertTrue(state.accept(listOf(observation), 12_100))
        assertEquals("-", VehicleKpiMapper.fromObservations(state.freshObservations(13_000)).batteryPowerKw)
        val mixed = normalizer.normalizeSparse(inputs + ("charging_charge_battery_volt" to
            input("charging_charge_battery_volt", "400", 12_000, "previous")), inputs.keys).single()
        assertNull(mixed.sourceStamp)
        assertFalse(state.accept(listOf(mixed), 12_100))
    }

    private fun observation(key: String, number: Double, elapsed: Long, boot: String = "boot") =
        NormalizedObservation(NormalizedFieldCatalog.fields.single { it.fieldKey == key },
            NormalizedValue(NormalizedValueType.NUMBER, number = number), NormalizedQuality.OK,
            null, null, "2026-09-23T00:00:00Z", sourceStamp = stamp(elapsed, boot))

    private fun stamp(elapsed: Long, boot: String) = NormalizedSourceStamp(
        NormalizedSourceKind.CALLBACK, "$boot-$elapsed", boot, "helper", elapsed, elapsed, elapsed)
}
