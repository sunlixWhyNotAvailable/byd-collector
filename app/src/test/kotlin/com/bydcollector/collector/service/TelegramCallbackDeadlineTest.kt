package com.bydcollector.collector.service

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.direct.CallbackValueSource
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.telegram.TelegramEventType
import com.bydcollector.collector.telegram.TelegramTemplateLanguage
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramCallbackDeadlineTest {
    private val config = TelegramEventConfig(TelegramEventType.entries.toSet(), 5, 12.0,
        60_000L, 30_000L, language = TelegramTemplateLanguage.EN)
    private val normalizer = VehicleStateNormalizer()
    private val base = 1_797_000_000_000L

    private fun input(engine: TelegramEventEngine, elapsed: Long, vararg values: Pair<String, String>) =
        engine.onSourcePoll(elapsed, Instant.ofEpochMilli(base + elapsed).toString(),
            PollSampleSource("sample:$elapsed", "boot", elapsed, "source", elapsed),
            values.mapIndexed { index, value ->
                val entry = DirectFidRegistry.entries.first { it.key == value.first }
                val callback = if (elapsed == 0L) null else CallbackValueSource(
                    "boot", "source", 1, 1L, 1L, elapsed * 100 + index,
                    entry.dev, entry.fid, if (entry.tx == 7) 2 else 1, value.second.toInt(),
                    base + elapsed, elapsed, null, "callback")
                PollReading(value.first, value.second,
                    if (value.first in setOf(VOLTAGE, CURRENT)) Float.fromBits(value.second.toInt()).toString() else value.second,
                    callback)
            }, PollOrigin.REPLAY,
            "boot", config, base + 1_000_000L, normalizer, nowElapsedMs = 1_000_000L)

    private fun tick(engine: TelegramEventEngine, elapsed: Long) =
        engine.onDetectorTimer(config, base + elapsed, "boot", elapsed, normalizer)

    @Test fun odometerDistanceUsesCachedStartWithoutGpsAndSurvivesStateReload() {
        var engine = TelegramEventEngine()
        input(engine, 0, GEAR to "1", ODOMETER to "1000", SOC to "72")
        input(engine, 100, GEAR to "4")
        engine = TelegramEventEngine(TelegramEventState.fromJson(engine.state.toJson()))
        input(engine, 1_000, GEAR to "1")
        // Exactly at the report deadline: this real input must update distance before rendering.
        val result = input(engine, 31_000, ODOMETER to "1008")
        val report = result.events.single { it.type == TelegramEventType.TRIP_SUMMARY }
        assertEquals("0.8", report.variables["trip_distance_km"])
        assertEquals("0.8", report.variables["total_distance_km"])
        assertTrue(tick(engine, 32_000).events.none { it.type == TelegramEventType.TRIP_SUMMARY })
    }

    @Test fun lateStartKeepsKnownDistanceButDoesNotTurnIncompleteTotalIntoZero() {
        var engine = TelegramEventEngine()
        input(engine, 0, GEAR to "1", SOC to "72")
        input(engine, 100, GEAR to "4")
        input(engine, 500, ODOMETER to "1000")
        input(engine, 1_000, ODOMETER to "1008", SOC to "70")
        engine = TelegramEventEngine(TelegramEventState.fromJson(engine.state.toJson()))
        assertEquals(0.8, engine.state.tripDistance.knownKm!!, 0.00001)
        assertNull(engine.state.tripDistance.completeKm)
        input(engine, 2_000, GEAR to "1")
        val first = tick(engine, 32_000).events.single { it.type == TelegramEventType.TRIP_SUMMARY }
        assertEquals("n/a", first.variables["total_distance_km"])
        assertEquals("n/a", first.variables["total_net_kwh_per_100km"])
        assertEquals(0.8, engine.state.bootTotalDistanceKm, 0.00001)
        input(engine, 33_000, GEAR to "4")
        input(engine, 34_000, ODOMETER to "1018")
        input(engine, 35_000, GEAR to "1")
        val second = tick(engine, 65_000).events.single { it.type == TelegramEventType.TRIP_SUMMARY }
        assertEquals("1", second.variables["trip_distance_km"])
        assertEquals("n/a", second.variables["total_distance_km"])
        assertEquals(1.8, engine.state.bootTotalDistanceKm, 0.00001)
        val legacy = org.json.JSONObject(engine.state.toJson()).apply {
            remove("tripDistance"); remove("bootTotalDistanceComplete")
        }
        val restored = TelegramEventState.fromJson(legacy.toString())
        assertFalse(restored.bootTotalDistanceComplete)
        assertEquals(engine.state.bootTotalDistanceKm, restored.bootTotalDistanceKm)
    }

    @Test fun delayedPreDepartureEvidenceRecoversStartButCounterResetRemainsInvalid() {
        val engine = TelegramEventEngine()
        input(engine, 0, GEAR to "1", SOC to "72")
        input(engine, 100, GEAR to "4")
        // Delivery is late, but the source captured this value before departure.
        input(engine, 50, ODOMETER to "1000")
        input(engine, 1_000, ODOMETER to "1008")
        assertEquals(0.8, engine.state.tripDistance.completeKm!!, 0.00001)
        input(engine, 2_000, ODOMETER to "10", SOC to "70")
        input(engine, 3_000, ODOMETER to "1010")
        assertEquals("odometer_reset", engine.state.tripDistance.error)
        val report = engine.onPowerOffConfirmed(config, TelegramPowerOffSnapshot(soc = 70.0), nowMs = base + 4_000).events
            .single { it.type == TelegramEventType.TRIP_SUMMARY }
        assertEquals("n/a", report.variables["trip_distance_km"])
        assertEquals("n/a", report.variables["total_distance_km"])
    }

    @Test fun validUnchangedOdometerStaysZeroAtPowerOff() {
        val engine = TelegramEventEngine()
        input(engine, 0, GEAR to "1", ODOMETER to "1000", SOC to "72")
        input(engine, 100, GEAR to "4")
        input(engine, 1_000, SOC to "70")
        val report = engine.onPowerOffConfirmed(config, TelegramPowerOffSnapshot(soc = 70.0), nowMs = base + 2_000).events
            .single { it.type == TelegramEventType.TRIP_SUMMARY }
        assertEquals("0", report.variables["total_distance_km"])
    }

    @Test fun completionWithNewerOdometerUpdatesTheFinalDistance() {
        val engine = TelegramEventEngine()
        input(engine, 0, GEAR to "1", ODOMETER to "1000", SOC to "72")
        input(engine, 100, GEAR to "4")
        val report = engine.onPowerOffConfirmed(config,
            TelegramPowerOffSnapshot(odometerKm = 100.8, soc = 72.0), nowMs = base + 2_000).events
            .single { it.type == TelegramEventType.TRIP_SUMMARY }
        assertEquals("0.8", report.variables["total_distance_km"])
    }

    @Test fun completionDoesNotRevalidateAnOdometerAfterAnInvalidSourceValue() {
        val engine = TelegramEventEngine()
        input(engine, 0, GEAR to "1", ODOMETER to "1000", SOC to "72")
        input(engine, 100, GEAR to "4")
        input(engine, 1_000, ODOMETER to "-10011", SOC to "70")
        val report = engine.onPowerOffConfirmed(config,
            TelegramPowerOffSnapshot(odometerKm = 100.0, soc = 70.0), nowMs = base + 2_000).events
            .single { it.type == TelegramEventType.TRIP_SUMMARY }
        assertEquals("n/a", report.variables["total_distance_km"])
    }

    @Test fun unreadableStateBaselinesCurrentConditionsWithoutReplayingOldNotifications() {
        assertFails { TelegramEventState.fromJson("{broken") }
        val recovered = TelegramEventState.recoverJson("{broken")
        val engine = TelegramEventEngine(TelegramEventState.fromJson(recovered.toJson()))
        assertTrue(input(engine, 0, GUN to "2", SOC to "100", BMS to "1",
            VOLTAGE to 11f.toRawBits().toString()).events.isEmpty())
        assertTrue(tick(engine, 61_000L).events.isEmpty())
        input(engine, 62_000L, GUN to "1")
        assertTrue(tick(engine, 65_000L).events.any { it.type == TelegramEventType.CHARGE_GUN_DISCONNECTED })
    }

    @Test fun singleCableAndChargingEdgesSurviveAppRestartAndConfirmWithoutMoreSamples() {
        val engine = TelegramEventEngine()
        input(engine, 0, GUN to "1")
        input(engine, 10, SOC to "50", BMS to "1")
        val candidate = input(engine, 100, GUN to "2")
        assertTrue(candidate.events.isEmpty())
        val restored = TelegramEventEngine(TelegramEventState.fromJson(candidate.state.toJson()))
        assertTrue(tick(restored, 1099).events.isEmpty())
        val confirmed = tick(restored, 1100)
        assertEquals(setOf(TelegramEventType.CHARGE_GUN_CONNECTED, TelegramEventType.CHARGING_STARTED),
            confirmed.events.map { it.type }.toSet())
        assertTrue(tick(restored, 5000).events.isEmpty())
        input(restored, 6000, SOC to "100")
        assertEquals(listOf(TelegramEventType.CHARGED_TO_100), tick(restored, 7000).events.map { it.type })
        assertTrue(tick(restored, 8000).events.isEmpty())
    }

    @Test fun invalidCableCancelsCandidateAndNewValidValueGetsItsOwnSecond() {
        val engine = TelegramEventEngine()
        input(engine, 0, GUN to "1")
        input(engine, 100, GUN to "2")
        input(engine, 500, GUN to "-10011")
        assertTrue(tick(engine, 1500).events.isEmpty())
        input(engine, 2000, GUN to "2")
        assertTrue(tick(engine, 2999).events.isEmpty())
        assertEquals(listOf(TelegramEventType.CHARGE_GUN_CONNECTED), tick(engine, 3000).events.map { it.type })
    }

    @Test fun briefDisconnectIsCancelledAndPersistentDisconnectSurvivesRestart() {
        val engine = TelegramEventEngine()
        input(engine, 0, GUN to "2")
        input(engine, 100, GUN to "1")
        assertTrue(tick(engine, 1100).events.isEmpty())
        assertTrue(input(engine, 1101, GUN to "2").events.isEmpty())
        assertTrue(tick(engine, 4101).events.isEmpty())
        val candidate = input(engine, 5000, GUN to "1")
        val restored = TelegramEventEngine(TelegramEventState.fromJson(candidate.state.toJson()))
        assertTrue(tick(restored, 7999).events.isEmpty())
        assertEquals(listOf(TelegramEventType.CHARGE_GUN_DISCONNECTED), tick(restored, 8000).events.map { it.type })
        assertTrue(tick(restored, 9000).events.isEmpty())
    }

    @Test fun legacyDisconnectDeadlineIsExtendedOnceFromItsOriginalStart() {
        val engine = TelegramEventEngine()
        input(engine, 0, GUN to "2")
        val candidate = input(engine, 100, GUN to "1")
        val legacy = org.json.JSONObject(candidate.state.toJson())
        legacy.getJSONObject("detectorTiming").getJSONObject("pending").getJSONObject("gun").apply {
            remove("duration")
            put("elapsed", 1100L)
        }
        val restored = TelegramEventEngine(TelegramEventState.fromJson(legacy.toString()))
        assertTrue(tick(restored, 1100).events.isEmpty())
        val reopened = TelegramEventEngine(TelegramEventState.fromJson(restored.state.toJson()))
        assertTrue(tick(reopened, 3099).events.isEmpty())
        assertEquals(listOf(TelegramEventType.CHARGE_GUN_DISCONNECTED), tick(reopened, 3100).events.map { it.type })
    }

    @Test fun lateSocSeedsProgressAndQuietLowVoltageHasARealDeadline() {
        val engine = TelegramEventEngine()
        input(engine, 0, GUN to "2", BMS to "1")
        input(engine, 100, SOC to "50", VOLTAGE to 11.5f.toRawBits().toString())
        assertEquals(listOf(TelegramEventType.CHARGING_PROGRESS), input(engine, 200, SOC to "55").events.map { it.type })
        assertTrue(tick(engine, 60099).events.isEmpty())
        assertEquals(listOf(TelegramEventType.LOW_12V_VOLTAGE), tick(engine, 60100).events.map { it.type })
        assertTrue(tick(engine, 120100).events.isEmpty())
    }

    @Test fun replayedShortParkingDoesNotEndTripUsingHostTime() {
        val engine = TelegramEventEngine()
        input(engine, 0, GEAR to "1", SOC to "80")
        val moving = input(engine, 100, GEAR to "4")
        val leg = assertNotNull(moving.state.tripId)
        engine.bindTripPowerSession(leg, "power-session")
        input(engine, 200, SOC to "78")
        input(engine, 1000, GEAR to "1")
        val resumed = input(engine, 2000, GEAR to "4")
        assertTrue(resumed.events.isEmpty())
        assertEquals(leg, resumed.state.tripId)
        input(engine, 3000, GEAR to "1")
        val parked = tick(engine, 33000)
        assertEquals(listOf(TelegramEventType.TRIP_SUMMARY), parked.events.map { it.type })
        assertNull(parked.state.tripId)
        val reset = engine.reset()
        val restored = TelegramEventState.fromJson(reset.toJson())
        assertEquals("power-session", restored.detectorTiming.reportedPowerSessionId)
        assertTrue(restored.tripSummaryCreatedInPowerSession)
        assertTrue(restored.detectorTiming.pending.isEmpty())
    }

    @Test fun quietLowChargingPowerStopsOnlyAfterSixtyContinuousSeconds() {
        val engine = TelegramEventEngine()
        input(engine, 0, GUN to "1")
        input(engine, 100, GUN to "2", BMS to "0", "charging_charge_battery_volt" to "400",
            CURRENT to (-50f).toRawBits().toString())
        assertTrue(tick(engine, 1100).events.any { it.type == TelegramEventType.CHARGING_STARTED })
        input(engine, 2000, CURRENT to 0f.toRawBits().toString())
        assertTrue(tick(engine, 61999).events.isEmpty())
        assertEquals(listOf(TelegramEventType.CHARGING_STOPPED), tick(engine, 62000).events.map { it.type })
        assertTrue(tick(engine, 120000).events.isEmpty())
    }

    companion object {
        private const val GUN = "charging_1009_876609586_5"
        private const val BMS = "charging_1009_876609560_5"
        private const val SOC = "statistic_1014_1145045040_5"
        private const val GEAR = "gearbox_1011_555745336_5"
        private const val ODOMETER = "statistic_1014_1246765072_5"
        private const val VOLTAGE = "ota_battery_voltage"
        private const val CURRENT = "charging_charge_current"
    }
}
