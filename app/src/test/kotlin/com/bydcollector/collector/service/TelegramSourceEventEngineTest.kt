package com.bydcollector.collector.service

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.telegram.TelegramEventType
import com.bydcollector.collector.telegram.TelegramTemplateLanguage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramSourceEventEngineTest {
    private val normalizer = VehicleStateNormalizer()
    private val config = TelegramEventConfig(
        enabledEvents = TelegramEventType.entries.toSet(),
        chargeStepPercent = 5,
        lowVoltageThreshold = 12.0,
        unavailableDelayMs = 60_000L,
        tripEndDelayMs = 10_000L,
        language = TelegramTemplateLanguage.EN
    )

    @Test
    fun repeatedSourceCannotConfirmCableButFreshSourceAfterRestartCan() {
        val engine = TelegramEventEngine()
        poll(engine, 1, "gun-1", listOf(gun("2"))) // initial connected baseline
        poll(engine, 2, "gun-2", listOf(gun("1")))
        assertEquals(1, engine.state.chargeGunCandidateCount)
        val unrelated = poll(engine, 3, "soc-between", listOf(soc("49.0")))
        assertTrue(unrelated.events.isEmpty())
        assertEquals(1, engine.state.chargeGunCandidateCount)

        val restarted = TelegramEventEngine(TelegramEventState.fromJson(engine.state.toJson()))
        val repeated = poll(restarted, 4, "gun-2", listOf(gun("1")))
        assertEquals(1, repeated.sourceOrderStats?.repeated)
        assertEquals(1, restarted.state.chargeGunCandidateCount)
        assertTrue(repeated.events.isEmpty())

        val confirmed = poll(restarted, 5, "gun-3", listOf(gun("1")))
        val event = confirmed.events.single { it.type == TelegramEventType.CHARGE_GUN_DISCONNECTED }
        assertEquals(5_000L, event.occurredAtMs)
        assertNotNull(event.sourceIdentityHash)
        assertEquals(false, restarted.state.chargeGunConnected)

        poll(restarted, 6, "gun-4", listOf(gun("2")))
        val reconnected = poll(restarted, 7, "gun-5", listOf(gun("2")))
            .events.single { it.type == TelegramEventType.CHARGE_GUN_CONNECTED }
        assertFalse(reconnected.dedupeKey == event.dedupeKey)
    }

    @Test
    fun unrelatedSocCannotAdvanceGearOrPrimaryPowerConfirmation() {
        val gearEngine = TelegramEventEngine()
        poll(gearEngine, 1, "gear-1", listOf(gear("1"), odometer("100.0")))
        poll(gearEngine, 2, "gear-2", listOf(gear("4")))
        assertEquals(1, gearEngine.state.gearCandidateCount)
        poll(gearEngine, 3, "soc-only", listOf(soc("51.0")))
        assertEquals(1, gearEngine.state.gearCandidateCount)
        assertEquals("P", gearEngine.state.gear)
        poll(gearEngine, 4, "gear-3", listOf(gear("4")))
        assertEquals("D", gearEngine.state.gear)
        assertNotNull(gearEngine.state.tripId)

        val chargeEngine = TelegramEventEngine()
        poll(chargeEngine, 1, "power-1", listOf(gun("2"), voltage("640.0"), current("0.0")))
        poll(chargeEngine, 2, "power-2", listOf(current("-10.0")))
        assertEquals(1, chargeEngine.state.chargingActiveCandidateCount)
        val unrelated = poll(chargeEngine, 3, "soc-only-2", listOf(soc("52.0")))
        assertTrue(unrelated.events.isEmpty())
        assertEquals(1, chargeEngine.state.chargingActiveCandidateCount)
        assertNull(chargeEngine.state.chargingSessionId)
    }

    @Test
    fun freshCurrentWithCachedVoltageConfirmsPowerAtFreshContributorTime() {
        val engine = TelegramEventEngine()
        poll(engine, 1, "power-1", listOf(gun("1"), voltage("640.0"), current("0.0")))
        poll(engine, 2, "power-idle-1", listOf(gun("1")))
        poll(engine, 3, "power-idle-2", listOf(gun("1")))
        poll(engine, 4, "power-2", listOf(gun("2"), current("-10.0")), processingAtMs = 40_000L)
        val confirmed = poll(engine, 5, "power-3", listOf(current("-11.0")), processingAtMs = 50_000L)

        val started = confirmed.events.single { it.type == TelegramEventType.CHARGING_STARTED }
        assertEquals(5_000L, started.occurredAtMs)
        assertTrue(confirmed.state.lastSuccessfulPollAtMs == 50_000L)
        assertNotNull(started.sourceIdentityHash)
        assertTrue(engine.state.chargingActive == true)
    }

    @Test
    fun repeatedAndUnrelatedSamplesCannotConfirmFullCharge() {
        val engine = TelegramEventEngine()
        poll(engine, 1, "full-1", listOf(gun("2"), voltage("640.0"), current("-10.0"), soc("99.6")))
        poll(engine, 2, "full-power-1", listOf(current("-11.0")))
        poll(engine, 3, "full-power-2", listOf(current("-12.0")))
        assertNotNull(engine.state.chargingSessionId)

        val firstSoc = poll(engine, 4, "full-soc-1", listOf(soc("99.6")))
        assertTrue(firstSoc.events.isEmpty())
        assertEquals(1, engine.state.fullCandidateCount)
        poll(engine, 5, "full-soc-1", listOf(soc("99.6")))
        assertEquals(1, engine.state.fullCandidateCount)
        val unrelatedPower = poll(engine, 6, "full-power-3", listOf(current("-13.0")))
        assertTrue(unrelatedPower.events.isEmpty())
        assertEquals(1, engine.state.fullCandidateCount)

        val full = poll(engine, 7, "full-soc-2", listOf(soc("99.7")))
            .events.single { it.type == TelegramEventType.CHARGED_TO_100 }
        assertEquals(7_000L, full.occurredAtMs)
    }

    @Test
    fun lowVoltageOnlyUsesFreshVoltageAndTripSummaryKeepsParkedTime() {
        val voltageEngine = TelegramEventEngine()
        poll(voltageEngine, 1, "voltage-1", listOf(auxVoltage("11.5")))
        poll(voltageEngine, 2, "voltage-2", listOf(auxVoltage("11.4")))
        val unrelated = poll(voltageEngine, 70, "voltage-soc", listOf(soc("50.0")))
        assertTrue(unrelated.events.isEmpty())
        assertFalse(voltageEngine.state.lowVoltageSent)
        val lowVoltage = poll(voltageEngine, 71, "voltage-3", listOf(auxVoltage("11.3")))
            .events.single { it.type == TelegramEventType.LOW_12V_VOLTAGE }
        assertEquals(71_000L, lowVoltage.occurredAtMs)

        val tripEngine = TelegramEventEngine()
        poll(tripEngine, 1, "trip-1", listOf(gear("1"), odometer("1000"), soc("50.0"), tripEnergy("1.0")))
        poll(tripEngine, 2, "trip-2", listOf(gear("4")))
        poll(tripEngine, 3, "trip-3", listOf(gear("4")))
        poll(tripEngine, 4, "trip-4", listOf(gear("1")))
        poll(tripEngine, 5, "trip-5", listOf(gear("1"), odometer("1010"), tripEnergy("1.2")))
        assertEquals(5_000L, tripEngine.state.tripEndedAtMs)
        val summary = tripEngine.onTick(config, true, null, nowMs = 30_000L)
            .events.single { it.type == TelegramEventType.TRIP_SUMMARY }
        assertEquals(5_000L, summary.occurredAtMs)
    }

    @Test
    fun replayingUncommittedCandidateProducesSameSemanticCableKey() {
        val engine = TelegramEventEngine()
        poll(engine, 1, "retry-gun-1", listOf(gun("2")))
        poll(engine, 2, "retry-gun-2", listOf(gun("1")))
        val beforeLastConfirmation = TelegramEventState.fromJson(engine.state.toJson())
        val first = poll(engine, 3, "retry-gun-3", listOf(gun("1")))
            .events.single { it.type == TelegramEventType.CHARGE_GUN_DISCONNECTED }

        val restored = TelegramEventEngine(beforeLastConfirmation)
        val retried = poll(restored, 3, "retry-gun-3", listOf(gun("1")))
            .events.single { it.type == TelegramEventType.CHARGE_GUN_DISCONNECTED }
        assertEquals(first.dedupeKey, retried.dedupeKey)
    }

    @Test
    fun replayCanProcessDataWithoutHealingLiveTelemetryOutage() {
        val before = TelegramEventState(
            initialized = true,
            lastSuccessfulPollAtMs = 500L,
            telemetryExpectedSinceMs = 100L,
            telemetryOutageSent = true
        )
        val engine = TelegramEventEngine(before)
        poll(engine, 1, "replay-1", listOf(soc("60.0")), origin = PollOrigin.REPLAY)
        assertEquals(500L, engine.state.lastSuccessfulPollAtMs)
        assertEquals(100L, engine.state.telemetryExpectedSinceMs)
        assertTrue(engine.state.telemetryOutageSent)
    }

    @Test
    fun legacyStateSeedsSourceCursorsWithoutUsingSeedAsConfirmation() {
        val engine = TelegramEventEngine(TelegramEventState(initialized = true, chargeGunConnected = true))
        poll(engine, 1, "legacy-seed", listOf(gun("1")))
        assertNull(engine.state.chargeGunCandidate)
        assertEquals(1, engine.state.rawSourceInputs.size)
        poll(engine, 2, "legacy-next", listOf(gun("1")))
        assertEquals(1, engine.state.chargeGunCandidateCount)
    }

    private fun poll(
        engine: TelegramEventEngine,
        sequence: Long,
        identity: String,
        readings: List<PollReading>,
        origin: PollOrigin = PollOrigin.LIVE,
        processingAtMs: Long? = null
    ): TelegramEventResult {
        val atMs = sequence * 1_000L
        val timestamp = Instant.ofEpochMilli(atMs).toString()
        return engine.onSourcePoll(
            pollId = sequence,
            timestamp = timestamp,
            source = PollSampleSource(
                identity = identity,
                bootId = "test-boot",
                capturedElapsedMs = atMs,
                generatorId = "test-generator",
                sequence = sequence
            ),
            readings = readings,
            origin = origin,
            currentBootId = "test-boot",
            config = config,
            nowMs = processingAtMs ?: atMs,
            normalizer = normalizer
        )
    }

    private fun gun(raw: String) = PollReading("charging_1009_876609586_5", raw)
    private fun gear(raw: String) = PollReading("gearbox_1011_555745336_5", raw)
    private fun soc(decoded: String) = PollReading("statistic_1014_1145045040_5", "0", decoded)
    private fun odometer(raw: String) = PollReading("statistic_1014_1246765072_5", raw)
    private fun voltage(decoded: String) = PollReading("charging_charge_battery_volt", "0", decoded)
    private fun current(decoded: String) = PollReading("charging_charge_current", "0", decoded)
    private fun auxVoltage(decoded: String) = PollReading("ota_battery_voltage", "0", decoded)
    private fun tripEnergy(raw: String) = PollReading("statistic_statistic_this_trip_total_elec_consumption", raw)
}
