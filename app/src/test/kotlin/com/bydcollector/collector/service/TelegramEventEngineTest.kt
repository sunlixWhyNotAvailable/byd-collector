package com.bydcollector.collector.service

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedFieldDefinition
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.telegram.TelegramEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TelegramEventEngineTest {
    private val config = TelegramEventConfig(
        enabledEvents = TelegramEventType.entries.toSet(),
        chargeStepPercent = 5,
        lowVoltageThreshold = 12.0,
        unavailableDelayMs = 60_000L,
        tripEndDelayMs = 10_000L
    )

    @Test
    fun primaryChargingEvidenceIsConfirmedAndProgressUsesLatestAbsoluteThreshold() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0, soc = 63.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0, soc = 63.0), config, 500L)
        assertTrue(engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 8.0, soc = 63.0), config, 1_000L).events.isEmpty())
        val started = engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 8.0, soc = 63.0), config, 1_500L)
        assertTrue(started.events.any { it.type == TelegramEventType.CHARGING_STARTED })
        assertEquals("8", started.events.single { it.type == TelegramEventType.CHARGING_STARTED }.variables["battery_power_kw"])

        assertTrue(engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 8.0, soc = 64.0), config, 1_750L).events.isEmpty())
        val progress = engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 8.0, soc = 72.0), config, 2_000L)
        val progressEvent = progress.events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertTrue(progressEvent.dedupeKey.endsWith(":progress:70"))
    }

    @Test
    fun chargingProgressSeparatesPersistedStepAndSessionDeltas() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0, soc = 63.0, remainingEnergy = 30.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0, soc = 63.0, remainingEnergy = 30.0), config, 500L)
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 8.0, soc = 63.0, remainingEnergy = 30.0), config, 1_000L)
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 8.0, soc = 63.0, remainingEnergy = 30.0), config, 1_500L)

        val first = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 8.0, soc = 72.0, remainingEnergy = 34.0),
            config,
            2_000L
        ).events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertEquals("9", first.variables["charge_step_added_percent"])
        assertEquals("4", first.variables["charge_step_added_kwh"])
        assertEquals("9", first.variables["charge_added_percent"])
        assertEquals("4", first.variables["charge_added_kwh"])

        val restarted = TelegramEventEngine(TelegramEventState.fromJson(engine.state.toJson()))
        restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 8.0, soc = 76.0, remainingEnergy = 35.0),
            config,
            2_500L
        )
        val second = restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 8.0, soc = 76.0, remainingEnergy = 35.0),
            config,
            3_000L
        ).events.single { it.type == TelegramEventType.CHARGING_PROGRESS }

        assertEquals("4", second.variables["charge_step_added_percent"])
        assertEquals("1", second.variables["charge_step_added_kwh"])
        assertEquals("13", second.variables["charge_added_percent"])
        assertEquals("5", second.variables["charge_added_kwh"])
    }

    @Test
    fun missingOrInvalidChargingStepBaselinesRenderAsNotAvailable() {
        val running = startedChargingEngine()
        val legacyState = running.state.copy(
            chargingProgressBaselineSoc = null,
            chargingProgressBaselineEnergyKwh = 40.0
        )
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(legacyState.toJson()))

        restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 6.0, soc = 56.0, remainingEnergy = 39.0),
            config,
            2_000L
        )
        val progress = restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 6.0, soc = 56.0, remainingEnergy = 39.0),
            config,
            2_500L
        ).events.single { it.type == TelegramEventType.CHARGING_PROGRESS }

        assertEquals("n/a", progress.variables["charge_step_added_percent"])
        assertEquals("n/a", progress.variables["charge_step_added_kwh"])
        assertEquals("n/a", progress.variables["charge_added_kwh"])
    }

    @Test
    fun coldActiveBaselineNeedsTwoSamplesAndDoesNotSendRetroactiveStart() {
        val engine = TelegramEventEngine()

        assertTrue(engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 7.0), config, 0L).events.isEmpty())
        val confirmed = engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 7.0), config, 500L)

        assertTrue(confirmed.events.isEmpty())
        assertEquals(true, confirmed.state.chargingActive)
        assertNotNull(confirmed.state.chargingSessionId)
    }

    @Test
    fun semanticStateIsFallbackOnlyWhenPrimaryEvidenceIsIncomplete() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(charging = "ready", chargeGun = false, chargePower = 0.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(charging = "ready", chargeGun = false, chargePower = 0.0), config, 500L)

        engine.onSuccessfulPoll(snapshot(charging = "charging", chargeGun = null, chargePower = null), config, 1_000L)
        val fallbackStart = engine.onSuccessfulPoll(snapshot(charging = "charging", chargeGun = null, chargePower = null), config, 1_500L)
        assertTrue(fallbackStart.events.any { it.type == TelegramEventType.CHARGING_STARTED })
        assertEquals("semantic_fallback", fallbackStart.state.chargingEvidenceSource)

        engine.onSuccessfulPoll(snapshot(charging = "charging", chargeGun = false, chargePower = 9.0), config, 2_000L)
        val primaryStop = engine.onSuccessfulPoll(snapshot(charging = "charging", chargeGun = false, chargePower = 9.0), config, 2_500L)
        assertTrue(primaryStop.events.any { it.type == TelegramEventType.CHARGING_STOPPED })
        assertEquals("primary_disconnected", primaryStop.state.chargingEvidenceSource)
    }

    @Test
    fun lowPowerStopRequiresContinuousMinuteAndRecoveryResetsTimer() {
        val engine = startedChargingEngine()

        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0), config, 2_000L)
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0), config, 40_000L)
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0), config, 41_000L)
        assertTrue(engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0), config, 100_999L).events.isEmpty())
        val stopped = engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0), config, 101_000L)

        assertEquals(TelegramEventType.CHARGING_STOPPED, stopped.events.single().type)
        assertEquals(false, stopped.state.chargingActive)
        assertNull(stopped.state.chargingSessionId)
    }

    @Test
    fun unknownChargingEvidencePreservesStateAndBreaksLowPowerContinuity() {
        val engine = startedChargingEngine()
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0), config, 2_000L)
        val unknownProgress = engine.onSuccessfulPoll(
            snapshot(charging = null, chargeGun = null, chargePower = null, soc = 55.0),
            config,
            30_000L
        )
        val unknownFull = engine.onSuccessfulPoll(
            snapshot(charging = null, chargeGun = null, chargePower = null, soc = 99.6),
            config,
            30_500L
        )

        val resumedLowPower = engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0), config, 70_000L)

        assertTrue(unknownProgress.events.isEmpty())
        assertTrue(unknownFull.events.isEmpty())
        assertTrue(resumedLowPower.events.isEmpty())
        assertEquals(true, resumedLowPower.state.chargingActive)
        assertEquals(70_000L, resumedLowPower.state.chargingLowPowerSinceMs)
    }

    @Test
    fun unknownChargingEvidenceBreaksFullConfirmation() {
        val engine = startedChargingEngine()

        val firstFull = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6),
            config,
            2_000L
        )
        val unknown = engine.onSuccessfulPoll(
            snapshot(charging = null, chargeGun = null, chargePower = null, soc = 99.6),
            config,
            2_500L
        )
        val firstAfterUnknown = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6),
            config,
            3_000L
        )
        val confirmed = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6),
            config,
            3_500L
        )

        assertFalse(firstFull.events.any { it.type == TelegramEventType.CHARGED_TO_100 })
        assertTrue(unknown.events.isEmpty())
        assertFalse(firstAfterUnknown.events.any { it.type == TelegramEventType.CHARGED_TO_100 })
        assertEquals(TelegramEventType.CHARGED_TO_100, confirmed.events.single().type)
    }

    @Test
    fun processRestartDoesNotTreatAnUnobservedGapAsContinuousLowPower() {
        val running = startedChargingEngine()
        running.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0), config, 2_000L)
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(running.state.toJson()))

        val firstAfterRestart = restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 0.0),
            config,
            100_000L
        )
        val confirmedAfterRestart = restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 0.0),
            config,
            100_500L
        )
        val beforeFullWindow = restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 0.0),
            config,
            159_999L
        )
        val stopped = restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 0.0),
            config,
            160_000L
        )

        assertTrue(firstAfterRestart.events.isEmpty())
        assertNull(firstAfterRestart.state.chargingActive)
        assertTrue(confirmedAfterRestart.events.isEmpty())
        assertNull(confirmedAfterRestart.state.chargingActive)
        assertNotNull(confirmedAfterRestart.state.chargingSessionId)
        assertEquals(100_000L, confirmedAfterRestart.state.chargingLowPowerSinceMs)
        assertTrue(beforeFullWindow.events.isEmpty())
        assertEquals(TelegramEventType.CHARGING_STOPPED, stopped.events.single().type)
        assertEquals(false, stopped.state.chargingActive)
        assertNull(stopped.state.chargingSessionId)
    }

    @Test
    fun restartReconfirmsAnActiveSessionWithoutRetroactiveStart() {
        val running = startedChargingEngine()
        val sessionId = running.state.chargingSessionId
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(running.state.toJson()))

        assertTrue(restarted.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0), config, 10_000L).events.isEmpty())
        val confirmed = restarted.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0), config, 10_500L)

        assertTrue(confirmed.events.isEmpty())
        assertEquals(true, confirmed.state.chargingActive)
        assertEquals(sessionId, confirmed.state.chargingSessionId)
    }

    @Test
    fun rolledBackDurabilityCommitReplaysTheTransitionExactlyOnce() {
        val chargingOnly = config.copy(enabledEvents = setOf(TelegramEventType.CHARGING_STARTED))
        val baseline = TelegramEventEngine().also { engine ->
            engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0), chargingOnly, 0L)
            engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0), chargingOnly, 500L)
        }
        val journal = FakeTelegramJournal(baseline.state.toJson())

        val candidate = baseline.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0), chargingOnly, 1_000L)
        journal.commit(candidate.events, candidate.state.toJson(), failBeforeCommit = false)
        val firstAttempt = baseline.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0), chargingOnly, 1_500L)
        assertFailsWith<IllegalStateException> {
            journal.commit(firstAttempt.events, firstAttempt.state.toJson(), failBeforeCommit = true)
        }

        val restarted = TelegramEventEngine(TelegramEventState.fromJson(journal.runtimeState))
        val replay = restarted.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0), chargingOnly, 2_000L)
        journal.commit(replay.events, replay.state.toJson(), failBeforeCommit = false)

        assertTrue(journal.outbox.single().dedupeKey != firstAttempt.events.single().dedupeKey)
        assertEquals(replay.state.toJson(), journal.runtimeState)
        val afterCommit = TelegramEventEngine(TelegramEventState.fromJson(journal.runtimeState))
        assertTrue(afterCommit.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0), chargingOnly, 2_000L).events.isEmpty())
        assertTrue(afterCommit.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0), chargingOnly, 2_500L).events.isEmpty())
        assertEquals(1, journal.outbox.size)
    }

    @Test
    fun naturalFullClosesSessionWithoutAStoppedEvent() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 7.0, soc = 98.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 7.0, soc = 98.0), config, 500L)
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6), config, 1_000L)
        val full = engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6), config, 1_500L)

        assertEquals(TelegramEventType.CHARGED_TO_100, full.events.single().type)
        assertNull(full.state.chargingSessionId)

        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0, soc = 99.6), config, 2_000L)
        val inactive = engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0, soc = 99.6), config, 62_000L)
        assertFalse(inactive.events.any { it.type == TelegramEventType.CHARGING_STOPPED })
    }

    @Test
    fun restartAtAnAlreadyReportedFullBaselineDoesNotDuplicateTheFullEvent() {
        val running = TelegramEventEngine()
        running.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 7.0, soc = 99.6), config, 0L)
        running.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 7.0, soc = 99.6), config, 500L)
        running.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6), config, 1_000L)
        running.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6), config, 1_500L)
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(running.state.toJson()))

        restarted.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6), config, 10_000L)
        restarted.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6), config, 10_500L)
        val confirmedFull = restarted.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6), config, 11_000L)

        assertTrue(confirmedFull.events.isEmpty())
        assertNull(confirmedFull.state.chargingSessionId)
    }

    @Test
    fun fullChargeFallsBackToProgress100WhenFullEventIsDisabled() {
        val engine = TelegramEventEngine()
        val progressOnly = config.copy(
            enabledEvents = setOf(TelegramEventType.CHARGING_PROGRESS),
            chargeStepPercent = 6
        )
        engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0, soc = 95.0), progressOnly, 0L)
        engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0, soc = 95.0), progressOnly, 500L)
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 7.0, soc = 95.0), progressOnly, 1_000L)
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 7.0, soc = 95.0), progressOnly, 1_500L)
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6), progressOnly, 2_000L)
        val full = engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6), progressOnly, 2_500L)

        assertEquals(TelegramEventType.CHARGING_PROGRESS, full.events.single().type)
        assertTrue(full.events.single().dedupeKey.endsWith(":progress:100"))
    }

    @Test
    fun rawReadyStateStillDetectsRepresentativeAcAndDcCharging() {
        listOf("2" to -11.0, "3" to -120.0).forEach { (gunRaw, current) ->
            val engine = TelegramEventEngine()
            engine.onSuccessfulPoll(normalizedSnapshot("1", 0.0), config, 0L)
            engine.onSuccessfulPoll(normalizedSnapshot("1", 0.0), config, 500L)
            engine.onSuccessfulPoll(normalizedSnapshot(gunRaw, current), config, 1_000L)
            val result = engine.onSuccessfulPoll(normalizedSnapshot(gunRaw, current), config, 1_500L)
            val started = result.events.single { it.type == TelegramEventType.CHARGING_STARTED }

            assertEquals("ready", result.state.charging)
            assertTrue(started.variables.getValue("battery_power_kw").toDouble() > 0.0)
        }
    }

    @Test
    fun lowVoltageRequiresOneMinuteAndHysteresisRearmsIt() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(auxVoltage = 12.5), config, 0L)
        engine.onSuccessfulPoll(snapshot(auxVoltage = 11.9), config, 1_000L)
        assertTrue(engine.onSuccessfulPoll(snapshot(auxVoltage = 11.8), config, 60_999L).events.isEmpty())
        assertEquals(
            TelegramEventType.LOW_12V_VOLTAGE,
            engine.onSuccessfulPoll(snapshot(auxVoltage = 11.8), config, 61_000L).events.single().type
        )
        engine.onSuccessfulPoll(snapshot(auxVoltage = 12.2), config, 62_000L)
        assertTrue(engine.state.lowVoltageSent)
        engine.onSuccessfulPoll(snapshot(auxVoltage = 12.3), config, 62_500L)
        assertFalse(engine.state.lowVoltageSent)
    }

    @Test
    fun tripFinalizesOnExactTickWithoutAnotherSuccessfulPoll() {
        val engine = pendingTripEngine()
        assertEquals(12_500L, engine.onTick(config, false, null, 12_499L).nextWakeAtMs)
        assertTrue(engine.onTick(config, false, null, 12_499L).events.isEmpty())

        val completed = engine.onTick(config, mainCollectionExpected = false, lastError = null, nowMs = 12_500L)
        val summary = completed.events.single()

        assertEquals(TelegramEventType.TRIP_SUMMARY, summary.type)
        assertEquals("1", summary.variables["trip_distance_km"])
        assertEquals("1.5", summary.variables["trip_energy_kwh"])
        assertEquals("1", summary.variables["total_distance_km"])
        assertEquals("2.5", summary.variables["total_energy_kwh"])
        assertNull(completed.nextWakeAtMs)
        assertNull(completed.state.tripId)
    }

    @Test
    fun confirmedPowerOffBypassesParkDelayAndAppendsLocationOnlyWhenOptedIn() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 1_000L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 2_000L)

        val location = TelegramLocationSnapshot(50.0, 30.0, "12:00", "2 s", "osm", "google", "apple")
        val result = engine.onPowerOffConfirmed(
            config.copy(sendLocation = true),
            TelegramPowerOffSnapshot(101.0, 49.0, 2.5),
            location,
            3_000L
        )

        assertEquals(TelegramEventType.TRIP_SUMMARY, result.events.single().type)
        assertTrue(result.events.single().textSuffix!!.contains("50.0, 30.0"))
        assertNull(result.state.tripId)
        val noLocation = TelegramEventEngine()
        noLocation.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 0L)
        noLocation.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 1_000L)
        noLocation.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 2_000L)
        assertNull(noLocation.onPowerOffConfirmed(config, TelegramPowerOffSnapshot(101.0, 49.0, 2.5), location, 3_000L).events.single().textSuffix)
    }

    @Test
    fun pendingTripRecoveryWaitsForFreshParkGearBeforeSending() {
        val pending = pendingTripEngine().state
        val restoredState = TelegramEventState.fromJson(pending.toJson())
        assertEquals(101.0, restoredState.tripEndOdometerKm)
        assertEquals(49.0, restoredState.tripEndSoc)
        assertEquals(2.5, restoredState.tripEndEnergyKwh)

        val restarted = TelegramEventEngine(restoredState)
        val waiting = restarted.onTick(config, mainCollectionExpected = false, lastError = null, nowMs = 20_000L)
        restarted.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0), config, 20_500L)
        val freshPark = restarted.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0), config, 21_000L)
        val completed = restarted.onTick(config, mainCollectionExpected = false, lastError = null, nowMs = 21_000L)

        assertTrue(waiting.events.isEmpty())
        assertEquals(50_000L, waiting.nextWakeAtMs)
        assertEquals(12_500L, freshPark.nextWakeAtMs)
        assertEquals(TelegramEventType.TRIP_SUMMARY, completed.events.single().type)
        assertNull(completed.state.tripId)
    }

    @Test
    fun recoveredParkedTripFinalizesBeforeNewBootCounterReset() {
        val persisted = pendingTripEngine().state.copy(
            bootTotalDistanceKm = 4.0,
            bootTotalDurationMs = 60_000L,
            lastTripEnergyCounterKwh = 2.5
        )
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(persisted.toJson()))

        val firstNewBootPoll = restarted.onSuccessfulPoll(
            snapshot(gear = "P", odometer = 101.0, soc = 49.0, tripEnergy = 0.1),
            config,
            20_500L
        )
        restarted.onSuccessfulPoll(
            snapshot(gear = "P", odometer = 101.0, soc = 49.0, tripEnergy = 0.1),
            config,
            21_000L
        )
        val completed = restarted.onTick(config, mainCollectionExpected = false, lastError = null, nowMs = 21_000L)
        val summary = completed.events.single()

        assertTrue(firstNewBootPoll.events.isEmpty())
        assertNotNull(firstNewBootPoll.state.tripId)
        assertEquals("1", summary.variables["trip_distance_km"])
        assertEquals("1.5", summary.variables["trip_energy_kwh"])
        assertEquals("5", summary.variables["total_distance_km"])
        assertNull(completed.state.tripId)
        assertEquals(0.0, completed.state.bootTotalDistanceKm)
        assertEquals(0L, completed.state.bootTotalDurationMs)
        assertEquals(0.1, completed.state.lastTripEnergyCounterKwh)
    }

    @Test
    fun pendingTripRecoveryUsesBoundedGraceOnlyWhenTelemetryNeverArrives() {
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(pendingTripEngine().state.toJson()))

        assertTrue(restarted.onTick(config, false, null, 20_000L).events.isEmpty())
        assertTrue(restarted.onTick(config, false, null, 49_999L).events.isEmpty())
        val completed = restarted.onTick(config, false, null, 50_000L)

        assertEquals(TelegramEventType.TRIP_SUMMARY, completed.events.single().type)
        assertNull(completed.state.tripId)
    }

    @Test
    fun pendingTripRecoveryDoesNotSendAfterFreshDrivingGear() {
        val previousTripId = pendingTripEngine().state.tripId
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(pendingTripEngine().state.toJson()))

        restarted.onTick(config, false, null, 20_000L)
        restarted.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.0), config, 20_500L)
        val driving = restarted.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.1), config, 21_000L)
        val laterTick = restarted.onTick(config, false, null, 50_000L)

        assertTrue(driving.events.isEmpty())
        assertNotNull(driving.state.tripId)
        assertTrue(driving.state.tripId != previousTripId)
        assertTrue(laterTick.events.isEmpty())
    }

    @Test
    fun recoveredParkedTripWithNewBootCounterStillSuppressesAfterFreshDrivingGear() {
        val persisted = pendingTripEngine().state.copy(
            bootTotalDistanceKm = 4.0,
            bootTotalDurationMs = 60_000L,
            lastTripEnergyCounterKwh = 2.5
        )
        val previousTripId = persisted.tripId
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(persisted.toJson()))

        restarted.onSuccessfulPoll(
            snapshot(gear = "D", odometer = 101.0, soc = 49.0, tripEnergy = 0.1),
            config,
            20_500L
        )
        val driving = restarted.onSuccessfulPoll(
            snapshot(gear = "D", odometer = 101.1, soc = 49.0, tripEnergy = 0.1),
            config,
            21_000L
        )
        val laterTick = restarted.onTick(config, false, null, 50_000L)

        assertTrue(driving.events.isEmpty())
        assertNotNull(driving.state.tripId)
        assertTrue(driving.state.tripId != previousTripId)
        assertEquals(0.0, driving.state.bootTotalDistanceKm)
        assertEquals(0L, driving.state.bootTotalDurationMs)
        assertEquals(0.1, driving.state.lastTripEnergyCounterKwh)
        assertTrue(laterTick.events.isEmpty())
    }

    @Test
    fun tripSummaryUsesCounterDeltaAndAccumulatesFinalizedSessions() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0, soc = 50.0, tripEnergy = 10.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 10.0), config, 1_000L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 10.0), config, 2_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0, soc = 49.0, tripEnergy = 10.5), config, 62_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0, soc = 49.0, tripEnergy = 10.5), config, 63_000L)
        val first = engine.onTick(config, false, null, 73_000L).events.single()

        assertEquals("1", first.variables["trip_distance_km"])
        assertEquals("0.5", first.variables["trip_energy_kwh"])
        assertEquals("1", first.variables["total_distance_km"])
        assertEquals("10.5", first.variables["total_energy_kwh"])
        assertEquals("0:01", first.variables["trip_duration"])
        assertEquals("0:01", first.variables["total_duration"])

        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.0, soc = 49.0, tripEnergy = 10.5), config, 74_000L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.0, soc = 49.0, tripEnergy = 10.5), config, 75_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 103.0, soc = 48.5, tripEnergy = 11.2), config, 195_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 103.0, soc = 48.5, tripEnergy = 11.2), config, 196_000L)
        val second = engine.onTick(config, false, null, 206_000L).events.single()

        assertEquals("2", second.variables["trip_distance_km"])
        assertEquals("0.7", second.variables["trip_energy_kwh"])
        assertEquals("3", second.variables["total_distance_km"])
        assertEquals("11.2", second.variables["total_energy_kwh"])
        assertEquals("0:03", second.variables["total_duration"])
    }

    @Test
    fun exactPointOneTripIsSuppressedButOnePercentSocAlwaysQualifies() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 500L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 1_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.1, soc = 49.5, tripEnergy = 1.1), config, 2_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.1, soc = 49.5, tripEnergy = 1.1), config, 2_500L)
        assertTrue(engine.onTick(config, false, null, 12_500L).events.isEmpty())

        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.1, soc = 49.5, tripEnergy = 1.1), config, 13_000L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.1, soc = 49.5, tripEnergy = 1.1), config, 13_500L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.1, soc = 48.5, tripEnergy = 1.1), config, 14_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.1, soc = 48.5, tripEnergy = 1.1), config, 14_500L)

        val qualified = engine.onTick(config, false, null, 24_500L).events.single()
        assertEquals("0", qualified.variables["trip_distance_km"])
        assertEquals("0", qualified.variables["trip_energy_kwh"])
        assertEquals("49.5", qualified.variables["soc_start"])
        assertEquals("48.5", qualified.variables["soc_end"])
    }

    @Test
    fun meaningfulTripEnergyCounterDecreaseResetsTotalsAndStaleTrip() {
        val restored = TelegramEventState(
            initialized = true,
            gear = "D",
            tripId = "stale",
            tripStartedAtMs = 1_000L,
            tripStartEnergyKwh = 9.0,
            bootTotalDistanceKm = 5.0,
            bootTotalDurationMs = 60_000L,
            lastTripEnergyCounterKwh = 10.0
        )
        val engine = TelegramEventEngine(TelegramEventState.fromJson(restored.toJson()))

        val reset = engine.onSuccessfulPoll(snapshot(gear = "D", tripEnergy = 9.89), config, 3_000L)

        assertNull(reset.state.tripId)
        assertEquals(0.0, reset.state.bootTotalDistanceKm)
        assertEquals(0L, reset.state.bootTotalDurationMs)
        assertEquals(9.89, reset.state.lastTripEnergyCounterKwh)
    }

    @Test
    fun leavingParkBeforeDeadlineCancelsPendingSummaryAndContinuesSameTrip() {
        val engine = pendingTripEngine()
        val tripId = engine.state.tripId

        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.0), config, 5_000L)
        val resumed = engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.1), config, 5_500L)

        assertTrue(resumed.events.isEmpty())
        assertEquals(tripId, resumed.state.tripId)
        assertNull(resumed.state.tripParkedSinceMs)
        assertNull(resumed.nextWakeAtMs)
    }

    @Test
    fun newTripAfterMissedExpiredDeadlineReplacesOldTripWithoutStaleSummary() {
        val engine = pendingTripEngine()
        val previousTripId = engine.state.tripId

        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.0), config, 20_000L)
        val restarted = engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.1), config, 20_500L)

        assertTrue(restarted.events.isEmpty())
        assertNotNull(restarted.state.tripId)
        assertTrue(restarted.state.tripId != previousTripId)
    }

    @Test
    fun telemetryOutageCanBeReportedBeforeTheFirstSuccessfulPoll() {
        val engine = TelegramEventEngine()

        assertTrue(engine.onTick(config, mainCollectionExpected = true, lastError = "offline", nowMs = 1_000L).events.isEmpty())
        assertTrue(engine.onTick(config, mainCollectionExpected = true, lastError = "offline", nowMs = 60_999L).events.isEmpty())
        assertEquals(
            TelegramEventType.TELEMETRY_UNAVAILABLE,
            engine.onTick(config, mainCollectionExpected = true, lastError = "offline", nowMs = 61_000L).events.single().type
        )
    }

    private fun startedChargingEngine(): TelegramEventEngine {
        return TelegramEventEngine().also { engine ->
            engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0), config, 0L)
            engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0), config, 500L)
            engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0), config, 1_000L)
            engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0), config, 1_500L)
        }
    }

    private fun pendingTripEngine(): TelegramEventEngine {
        return TelegramEventEngine().also { engine ->
            engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 0L)
            engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 500L)
            engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 1_000L)
            engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0, soc = 49.0), config, 2_000L)
            engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0, soc = 49.0), config, 2_500L)
        }
    }

    private class FakeTelegramJournal(initialState: String) {
        var runtimeState: String = initialState
            private set
        val outbox = mutableListOf<TelegramDetectedEvent>()

        fun commit(events: List<TelegramDetectedEvent>, state: String, failBeforeCommit: Boolean) {
            val stagedOutbox = outbox + events
            if (failBeforeCommit) error("simulated transaction rollback")
            outbox.clear()
            outbox += stagedOutbox
            runtimeState = state
        }
    }

    private fun snapshot(
        charging: String? = "ready",
        soc: Double? = 50.0,
        auxVoltage: Double? = 12.5,
        gear: String? = "P",
        odometer: Double? = 100.0,
        tripEnergy: Double? = 2.5,
        remainingEnergy: Double? = 40.0,
        chargePower: Double? = 0.0,
        chargeGun: Boolean? = false
    ): List<NormalizedObservation> = buildList {
        charging?.let { add(text(NormalizedFieldCatalog.chargingState, it)) }
        soc?.let { add(number(NormalizedFieldCatalog.soc, it)) }
        remainingEnergy?.let { add(number(NormalizedFieldCatalog.batteryRemainingEnergy, it)) }
        chargePower?.let { add(number(NormalizedFieldCatalog.batteryChargePower, it)) }
        auxVoltage?.let { add(number(NormalizedFieldCatalog.auxVoltage, it)) }
        odometer?.let { add(number(NormalizedFieldCatalog.odometerKm, it)) }
        tripEnergy?.let { add(number(NormalizedFieldCatalog.tripEnergy, it)) }
        add(number(NormalizedFieldCatalog.remainingRangeKm, 300.0))
        gear?.let { add(text(NormalizedFieldCatalog.gearAutoMode, it)) }
        chargeGun?.let { add(bool(NormalizedFieldCatalog.chargeGunConnected, it)) }
    }

    private fun normalizedSnapshot(gunRaw: String, current: Double): List<NormalizedObservation> {
        return VehicleStateNormalizer().normalize(
            pollId = 1L,
            observedAt = "2026-07-22T00:00:00Z",
            readings = listOf(
                PollReading("charging_1009_1231032336_5", "0"),
                PollReading("charging_1009_876609586_5", gunRaw),
                PollReading("charging_charge_battery_volt", "0", "640.0"),
                PollReading("charging_charge_current", "0", current.toString()),
                PollReading("statistic_1014_1145045040_5", "0", "50.0"),
                PollReading("gearbox_1011_555745336_5", "1")
            )
        )
    }

    private fun number(field: NormalizedFieldDefinition, value: Double) = observation(
        field,
        NormalizedValue(NormalizedValueType.NUMBER, number = value)
    )

    private fun text(field: NormalizedFieldDefinition, value: String) = observation(
        field,
        NormalizedValue(NormalizedValueType.TEXT, text = value)
    )

    private fun bool(field: NormalizedFieldDefinition, value: Boolean) = observation(
        field,
        NormalizedValue(NormalizedValueType.BOOLEAN, bool = value)
    )

    private fun observation(field: NormalizedFieldDefinition, value: NormalizedValue) = NormalizedObservation(
        field = field,
        value = value,
        quality = NormalizedQuality.OK,
        sourcePollId = 1L,
        sourceKey = field.sourceKeys.firstOrNull(),
        observedAt = "2026-07-22T00:00:00Z"
    )
}
