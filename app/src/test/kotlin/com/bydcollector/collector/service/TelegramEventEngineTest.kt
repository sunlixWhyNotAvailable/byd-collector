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
import com.bydcollector.collector.telegram.TelegramNavigatorMask
import com.bydcollector.collector.telegram.TelegramTemplateLanguage
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
        val second = restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 8.0, soc = 76.0, remainingEnergy = 35.0),
            config,
            2_500L
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

        val progress = restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 6.0, soc = 56.0, remainingEnergy = 39.0),
            config,
            2_000L
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
    fun bmsChargingStartsFromKnownInactiveAtLowPowerAndKeepsFirstSampleBaseline() {
        val engine = knownInactiveChargingEngine()

        val candidate = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = null, bmsState = "charging", soc = 60.0, remainingEnergy = 30.0),
            config,
            1_000L
        )
        val restored = TelegramEventEngine(TelegramEventState.fromJson(candidate.state.toJson()))
        val started = restored.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 0.0, bmsState = "charging", soc = 61.0, remainingEnergy = 31.0),
            config,
            1_500L
        )

        assertTrue(candidate.events.none { it.type == TelegramEventType.CHARGING_STOPPED })
        assertEquals("bms_charging", candidate.state.chargingActiveCandidateSource)
        assertEquals(
            TelegramEventType.CHARGING_STARTED,
            started.events.single { it.type == TelegramEventType.CHARGING_STARTED }.type
        )
        assertEquals(true, started.state.chargingActive)
        assertEquals("bms_charging", started.state.chargingEvidenceSource)
        assertEquals(1_000L, started.state.chargingStartedAtMs)
        assertEquals(60.0, started.state.chargingStartSoc)
        assertEquals(30.0, started.state.chargingStartEnergyKwh)
    }

    @Test
    fun bmsChargingColdAttachIsSilentAndExplicitDisconnectWins() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0, bmsState = "charging"), config, 0L)
        val attached = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 0.0, bmsState = "charging"),
            config,
            500L
        )

        assertTrue(attached.events.isEmpty())
        assertNotNull(attached.state.chargingSessionId)
        engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 8.0, bmsState = "charging"), config, 1_000L)
        val stopped = engine.onSuccessfulPoll(
            snapshot(chargeGun = false, chargePower = 8.0, bmsState = "charging"),
            config,
            1_500L
        )

        assertEquals(
            TelegramEventType.CHARGING_STOPPED,
            stopped.events.single { it.type == TelegramEventType.CHARGING_STOPPED }.type
        )
        assertEquals("primary_disconnected", stopped.state.chargingEvidenceSource)
    }

    @Test
    fun bmsChargingKeepsAnActiveSessionThroughLowPowerLongerThanFallbackTimeout() {
        val engine = startedBmsChargingEngine()

        val stillCharging = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 0.0, bmsState = "charging"),
            config,
            120_000L
        )

        assertTrue(stillCharging.events.none { it.type == TelegramEventType.CHARGING_STOPPED })
        assertEquals(true, stillCharging.state.chargingActive)
        assertNull(stillCharging.state.chargingLowPowerSinceMs)
    }

    @Test
    fun bmsFinishedLowPowerStopsAfterTwoPollsWithoutWaitingForFallbackTimeout() {
        val engine = startedBmsChargingEngine()
        val sessionId = engine.state.chargingSessionId

        val candidate = engine.onSuccessfulPoll(
            snapshot(chargeGun = null, chargePower = 0.49, bmsState = "finished", soc = 70.0),
            config,
            2_000L
        )
        val stopped = engine.onSuccessfulPoll(
            snapshot(chargeGun = null, chargePower = 0.49, bmsState = "finished", soc = 70.0),
            config,
            2_500L
        )

        assertTrue(candidate.events.none { it.type == TelegramEventType.CHARGING_STOPPED })
        assertEquals("bms_finished", candidate.state.chargingActiveCandidateSource)
        assertEquals(
            "$sessionId:stopped",
            stopped.events.single { it.type == TelegramEventType.CHARGING_STOPPED }.dedupeKey
        )
        assertNull(stopped.state.chargingSessionId)
        assertEquals(false, stopped.state.chargingActive)
    }

    @Test
    fun bmsFinishedAtThresholdUsesPowerFallbackAndLatchesOneConflictEpisode() {
        val engine = startedBmsChargingEngine()

        val conflict = engine.onSuccessfulPoll(
            snapshot(chargeGun = null, chargePower = 0.5, bmsState = "finished"),
            config,
            2_000L
        )
        val persisted = TelegramEventState.fromJson(conflict.state.toJson())
        val restarted = TelegramEventEngine(persisted)
        val repeated = restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 6.0, bmsState = "finished"),
            config,
            2_500L
        )
        val fallback = restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 6.0, bmsState = "finished"),
            config,
            2_750L
        )
        val reset = TelegramEventEngine(TelegramEventState.fromJson(fallback.state.toJson())).onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 6.0, bmsState = "charging"),
            config,
            3_000L
        )

        assertTrue(conflict.events.none { it.type == TelegramEventType.CHARGING_STOPPED })
        assertEquals(true, conflict.state.chargingActive)
        assertTrue(persisted.bmsFinishHighPowerConflictActive)
        assertTrue(repeated.state.bmsFinishHighPowerConflictActive)
        assertEquals("primary_power", fallback.state.chargingEvidenceSource)
        assertTrue(fallback.events.none { it.type == TelegramEventType.CHARGING_STOPPED })
        assertFalse(reset.state.bmsFinishHighPowerConflictActive)
    }

    @Test
    fun explicitDisconnectDoesNotLatchBmsFinishPowerConflict() {
        val engine = startedBmsChargingEngine()

        val candidate = engine.onSuccessfulPoll(
            snapshot(chargeGun = false, chargePower = 6.0, bmsState = "finished"),
            config,
            2_000L
        )
        val stopped = engine.onSuccessfulPoll(
            snapshot(chargeGun = false, chargePower = 6.0, bmsState = "finished"),
            config,
            2_500L
        )

        assertFalse(candidate.state.bmsFinishHighPowerConflictActive)
        assertEquals("primary_disconnected", candidate.state.chargingActiveCandidateSource)
        assertFalse(stopped.state.bmsFinishHighPowerConflictActive)
        assertEquals(1, stopped.events.count { it.type == TelegramEventType.CHARGING_STOPPED })
    }

    @Test
    fun chargingConfirmationCannotMixBmsFallbackDisconnectOrEvidenceGaps() {
        val engine = knownInactiveChargingEngine()

        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0, bmsState = "charging"), config, 1_000L)
        val sourceChanged = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 6.0, bmsState = "ready"),
            config,
            1_500L
        )
        val gap = engine.onSuccessfulPoll(snapshot(chargeGun = null, chargePower = null), config, 2_000L)
        val disconnected = engine.onSuccessfulPoll(
            snapshot(chargeGun = false, chargePower = 6.0, bmsState = "charging"),
            config,
            2_500L
        )
        val fresh = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 0.0, bmsState = "charging"),
            config,
            3_000L
        )

        assertEquals(1, sourceChanged.state.chargingActiveCandidateCount)
        assertEquals("primary_power", sourceChanged.state.chargingActiveCandidateSource)
        assertNull(gap.state.chargingActiveCandidateSource)
        assertEquals(0, disconnected.state.chargingActiveCandidateCount)
        assertNull(disconnected.state.chargingActiveCandidateSource)
        assertEquals(1, fresh.state.chargingActiveCandidateCount)
        assertTrue(listOf(sourceChanged, gap, disconnected, fresh).all { result ->
            result.events.none { it.type == TelegramEventType.CHARGING_STARTED }
        })
    }

    @Test
    fun missingNamedAndUntrustedBmsStatesUseExistingGunPowerFallback() {
        val fallbackStates = listOf<String?>(
            null,
            "ready",
            "discharg",
            "charg_terminate",
            "breakdown_c10",
            "breakdown_charging_gun",
            "breakdown_charger",
            "breakdown_ac",
            "schedule",
            "discharg_cbu",
            "timeout",
            "discharg_finish",
            "charging_pause"
        )
        fallbackStates.forEach { bmsState ->
            val engine = knownInactiveChargingEngine()
            engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0, bmsState = bmsState), config, 1_000L)
            val started = engine.onSuccessfulPoll(
                snapshot(chargeGun = true, chargePower = 6.0, bmsState = bmsState),
                config,
                1_500L
            )
            assertEquals(
                TelegramEventType.CHARGING_STARTED,
                started.events.single { it.type == TelegramEventType.CHARGING_STARTED }.type,
                bmsState
            )
            assertEquals("primary_power", started.state.chargingEvidenceSource, bmsState)
        }

        val readyLowPower = knownInactiveChargingEngine().also { engine ->
            engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0, bmsState = "ready"), config, 1_000L)
        }.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 0.0, bmsState = "ready"), config, 1_500L)
        assertTrue(readyLowPower.events.none { it.type == TelegramEventType.CHARGING_STARTED })
        assertEquals(false, readyLowPower.state.chargingActive)

        NormalizedQuality.entries.filter { it != NormalizedQuality.OK }.forEach { quality ->
            val engine = knownInactiveChargingEngine()
            val untrustedBms = text(NormalizedFieldCatalog.chargingBatteryDeviceState, "charging")
                .copy(quality = quality)
            engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 6.0) + untrustedBms, config, 1_000L)
            val started = engine.onSuccessfulPoll(
                snapshot(chargeGun = true, chargePower = 6.0) + untrustedBms,
                config,
                1_500L
            )
            assertEquals(
                TelegramEventType.CHARGING_STARTED,
                started.events.single { it.type == TelegramEventType.CHARGING_STARTED }.type,
                quality.name
            )
            assertEquals("primary_power", started.state.chargingEvidenceSource, quality.name)
        }
    }

    @Test
    fun missingPrimaryEvidenceDoesNotUseRetiredSemanticStateFallback() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(chargeGun = null, chargePower = null), config, 0L)
        val unknown = engine.onSuccessfulPoll(snapshot(chargeGun = null, chargePower = null), config, 500L)
        assertTrue(unknown.events.none { it.type == TelegramEventType.CHARGING_STARTED })
        assertEquals(null, unknown.state.chargingActive)
        assertEquals(null, unknown.state.chargingEvidenceSource)
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
            snapshot(chargeGun = null, chargePower = null, soc = 55.0),
            config,
            30_000L
        )
        val unknownFull = engine.onSuccessfulPoll(
            snapshot(chargeGun = null, chargePower = null, soc = 99.6),
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
            snapshot(chargeGun = null, chargePower = null, soc = 99.6),
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
    fun simultaneousBmsFinishAndFullConfirmationEmitsOnlyFullAndRetainsFullLatch() {
        val engine = startedBmsChargingEngine(soc = 98.0, remainingEnergy = 30.0)
        val sessionId = engine.state.chargingSessionId

        engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 0.49, bmsState = "finished", soc = 99.6, remainingEnergy = 40.0),
            config,
            2_000L
        )
        val finished = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 0.49, bmsState = "finished", soc = 99.6, remainingEnergy = 40.0),
            config,
            2_500L
        )

        assertEquals(listOf(TelegramEventType.CHARGED_TO_100), finished.events.map { it.type })
        assertEquals("$sessionId:full", finished.events.single().dedupeKey)
        assertTrue(finished.state.fullSent)
        assertNull(finished.state.chargingSessionId)
        assertNull(finished.state.chargingStartSoc)
        assertNull(finished.state.chargingProgressBaselineSoc)
    }

    @Test
    fun restoredSessionCountsBothSimultaneousBmsFinishAndFullSamplesBeforeStopping() {
        val running = startedBmsChargingEngine(soc = 98.0, remainingEnergy = 30.0)
        val sessionId = running.state.chargingSessionId
        val restored = TelegramEventEngine(TelegramEventState.fromJson(running.state.toJson()))

        val first = restored.onSuccessfulPoll(
            snapshot(chargeGun = null, chargePower = 0.49, bmsState = "finished", soc = 99.6, remainingEnergy = 40.0),
            config,
            10_000L
        )
        val finished = restored.onSuccessfulPoll(
            snapshot(chargeGun = null, chargePower = 0.49, bmsState = "finished", soc = 99.6, remainingEnergy = 40.0),
            config,
            10_500L
        )

        assertTrue(first.events.none { it.type == TelegramEventType.CHARGING_STOPPED })
        assertEquals(1, first.state.fullCandidateCount)
        assertEquals(listOf(TelegramEventType.CHARGED_TO_100), finished.events.map { it.type })
        val full = finished.events.single()
        assertEquals("$sessionId:full", full.dedupeKey)
        assertEquals("1.6", full.variables["charge_added_percent"])
        assertEquals("10", full.variables["charge_added_kwh"])
        assertTrue(finished.state.fullSent)
        assertNull(finished.state.chargingSessionId)
    }

    @Test
    fun bmsFinishWithLowOrMissingSocStopsWithoutFullAndPreservesStopVariables() {
        listOf<Double?>(70.0, null).forEach { soc ->
            val engine = startedBmsChargingEngine(soc = 60.0, remainingEnergy = 30.0)
            engine.onSuccessfulPoll(
                snapshot(chargeGun = true, chargePower = 0.49, bmsState = "finished", soc = soc),
                config,
                2_000L
            )
            val stopped = engine.onSuccessfulPoll(
                snapshot(chargeGun = true, chargePower = 0.49, bmsState = "finished", soc = soc),
                config,
                2_500L
            )

            assertEquals(
                1,
                stopped.events.count { it.type == TelegramEventType.CHARGING_STOPPED },
                soc.toString()
            )
            assertEquals(
                if (soc == null) "n/a" else "10",
                stopped.events.single { it.type == TelegramEventType.CHARGING_STOPPED }.variables["charge_added_percent"],
                soc.toString()
            )
            assertFalse(stopped.state.fullSent, soc.toString())
        }
    }

    @Test
    fun restoredActiveSessionUsesTwoBmsFinishPollsAndOriginalBaselinesForStop() {
        val running = startedBmsChargingEngine(soc = 60.0, remainingEnergy = 30.0)
        val sessionId = running.state.chargingSessionId
        val startedAt = running.state.chargingStartedAtMs
        val restored = TelegramEventEngine(TelegramEventState.fromJson(running.state.toJson()))

        val candidate = restored.onSuccessfulPoll(
            snapshot(chargeGun = null, chargePower = 0.49, bmsState = "finished", soc = 70.0, remainingEnergy = 35.0),
            config,
            10_000L
        )
        val stopped = restored.onSuccessfulPoll(
            snapshot(chargeGun = null, chargePower = 0.49, bmsState = "finished", soc = 70.0, remainingEnergy = 35.0),
            config,
            10_500L
        )

        assertTrue(candidate.events.none { it.type == TelegramEventType.CHARGING_STOPPED })
        assertEquals(sessionId, candidate.state.chargingSessionId)
        assertEquals(startedAt, candidate.state.chargingStartedAtMs)
        val stoppedEvent = stopped.events.single { it.type == TelegramEventType.CHARGING_STOPPED }
        assertEquals("10", stoppedEvent.variables["charge_added_percent"])
        assertEquals("5", stoppedEvent.variables["charge_added_kwh"])
    }

    @Test
    fun fullChargeUsesPersistedSessionBaselineAndUnwrappedPaddedDuration() {
        val english = config.copy(language = TelegramTemplateLanguage.EN)
        val engine = TelegramEventEngine()
        val day = 24L * 60L * 60L * 1_000L
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 7.0, soc = 50.0, remainingEnergy = 20.0), english, 0L)
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 7.0, soc = 50.0, remainingEnergy = 20.0), english, 500L)
        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 7.0, soc = 50.0, remainingEnergy = 20.0), english, 1_000L)
        assertEquals(0L, engine.state.chargingStartedAtMs)
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(engine.state.toJson()))
        restarted.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6, remainingEnergy = 30.0), english, day + 1_000L)
        val full = restarted.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6, remainingEnergy = 30.0),
            english,
            day + 7L * 60L * 1_000L + 1_000L
        ).events.single()

        assertEquals(TelegramEventType.CHARGED_TO_100, full.type)
        assertEquals("49.6", full.variables["charge_added_percent"])
        assertEquals("10", full.variables["charge_added_kwh"])
        assertEquals("24:07", full.variables["charge_duration_hhmm"])
        val localTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        assertEquals(localTime.format(java.util.Date(0L)), full.variables["charge_start_time"])
        assertEquals(localTime.format(java.util.Date(day + 7L * 60L * 1_000L + 1_000L)), full.variables["charge_end_time"])
    }

    @Test
    fun coldAttachUsesFirstObservedActiveSampleAsChargeBaseline() {
        val engine = TelegramEventEngine()
        val firstObservedAtMs = 59_500L
        engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 7.0, soc = 60.0, remainingEnergy = 40.0),
            config,
            firstObservedAtMs
        )
        val attached = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 7.0, soc = 61.0, remainingEnergy = 41.0),
            config,
            60_500L
        )
        assertTrue(attached.events.isEmpty())
        assertEquals(firstObservedAtMs, attached.state.chargingStartedAtMs)
        assertEquals(60.0, attached.state.chargingStartSoc)
        assertEquals(40.0, attached.state.chargingStartEnergyKwh)

        engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6, remainingEnergy = 50.0), config, 61_000L)
        val full = engine.onSuccessfulPoll(snapshot(chargeGun = true, chargePower = 4.0, soc = 99.6, remainingEnergy = 50.0), config, 61_500L)
            .events.single()
        assertEquals(TelegramEventType.CHARGED_TO_100, full.type)
        assertEquals("39.6", full.variables["charge_added_percent"])
        assertEquals("10", full.variables["charge_added_kwh"])
    }

    @Test
    fun rejectedActiveCandidateDoesNotLeakItsChargeBaseline() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 7.0, soc = 60.0, remainingEnergy = 40.0),
            config,
            0L
        )
        engine.onSuccessfulPoll(
            snapshot(chargeGun = false, chargePower = 0.0, soc = 60.0, remainingEnergy = 40.0),
            config,
            500L
        )
        engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 7.0, soc = 70.0, remainingEnergy = 45.0),
            config,
            1_000L
        )
        val attached = engine.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 7.0, soc = 71.0, remainingEnergy = 46.0),
            config,
            1_500L
        )

        assertEquals(1_000L, attached.state.chargingStartedAtMs)
        assertEquals(70.0, attached.state.chargingStartSoc)
        assertEquals(45.0, attached.state.chargingStartEnergyKwh)
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

            assertNull(result.state.charging)
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
        assertEquals("150", summary.variables["trip_avg_kwh_per_100km"])
        assertEquals("1", summary.variables["total_distance_km"])
        assertEquals("1.5", summary.variables["total_energy_kwh"])
        assertEquals("150", summary.variables["total_avg_kwh_per_100km"])
        assertNull(completed.nextWakeAtMs)
        assertNull(completed.state.tripId)
    }

    @Test
    fun confirmedPowerOffBypassesParkDelayAndAppendsLocationOnlyWhenOptedIn() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 1_000L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 2_000L)

        val location = TelegramLocationSnapshot(50.0, 30.0, "12:00", 2L, "osm", "google", "apple", "waze")
        val result = engine.onPowerOffConfirmed(
            config.copy(sendLocation = true, navigatorMask = TelegramNavigatorMask.ALL),
            TelegramPowerOffSnapshot(101.0, 49.0, 2.5),
            location,
            3_000L
        )

        val summary = result.events.single()
        assertEquals(TelegramEventType.TRIP_SUMMARY, summary.type)
        assertEquals("1.5", summary.variables["trip_energy_kwh"])
        assertEquals("150", summary.variables["trip_avg_kwh_per_100km"])
        assertEquals("1.5", summary.variables["total_energy_kwh"])
        assertEquals("150", summary.variables["total_avg_kwh_per_100km"])
        assertEquals("\nGoogle: google\nWaze: waze\nApple: apple\nOSM: osm", summary.textSuffix)
        assertNull(result.state.tripId)
        assertEquals(0.0, result.state.bootTotalEnergyKwh)
        assertNull(result.state.lastTripEnergyCounterKwh)
        val noLocation = TelegramEventEngine()
        noLocation.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 0L)
        noLocation.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 1_000L)
        noLocation.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 50.0, tripEnergy = 1.0), config, 2_000L)
        assertNull(noLocation.onPowerOffConfirmed(config, TelegramPowerOffSnapshot(101.0, 49.0, 2.5), location, 3_000L).events.single().textSuffix)
    }

    @Test
    fun chargingProgressUsesLocalizedStepAndSessionDurations() {
        val started = startedChargingEngine()
        val uk = started.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 6.0, soc = 55.0),
            config,
            19_501_500L
        ).events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertEquals("5год 25хв", uk.variables["charge_step_duration"])
        assertEquals("5год 25хв", uk.variables["charge_duration"])

        val english = startedChargingEngine()
        val en = english.onSuccessfulPoll(
            snapshot(chargeGun = true, chargePower = 6.0, soc = 55.0),
            config.copy(language = TelegramTemplateLanguage.EN),
            19_501_500L
        ).events.single { it.type == TelegramEventType.CHARGING_PROGRESS }
        assertEquals("5h 25m", en.variables["charge_step_duration"])
        assertEquals("5h 25m", en.variables["charge_duration"])
    }

    @Test
    fun locationUsesOnlySelectedNavigatorLinksAndZeroMaskOmitsSuffix() {
        val engine = pendingTripEngine()
        val location = TelegramLocationSnapshot(
            50.0,
            30.0,
            "12:00",
            2L,
            "osm",
            "google",
            "apple",
            "waze"
        )
        val selected = engine.onPowerOffConfirmed(
            config.copy(sendLocation = true, navigatorMask = TelegramNavigatorMask.GOOGLE or TelegramNavigatorMask.WAZE or TelegramNavigatorMask.OSM),
            TelegramPowerOffSnapshot(101.0, 49.0, 2.5),
            location,
            3_000L
        ).events.single().textSuffix!!
        assertTrue(selected.indexOf("Google: google") < selected.indexOf("Waze: waze"))
        assertTrue(selected.indexOf("Waze: waze") < selected.indexOf("OSM: osm"))
        assertFalse(selected.contains("Apple: apple"))
        assertFalse(selected.contains("50.0, 30.0"))
        assertFalse(selected.contains("Локація"))
        assertFalse(selected.contains("Зафіксовано"))
        assertFalse(selected.contains("вік"))

        val noLinksEngine = pendingTripEngine()
        val noLinks = noLinksEngine.onPowerOffConfirmed(
            config.copy(sendLocation = true, navigatorMask = 0),
            TelegramPowerOffSnapshot(101.0, 49.0, 2.5),
            location,
            3_000L
        ).events.single().textSuffix
        assertNull(noLinks)

        val noFix = pendingTripEngine().onPowerOffConfirmed(
            config.copy(sendLocation = true),
            TelegramPowerOffSnapshot(101.0, 49.0, 2.5),
            location = null,
            nowMs = 3_000L
        ).events.single()
        assertEquals(TelegramEventType.TRIP_SUMMARY, noFix.type)
        assertNull(noFix.textSuffix)
    }

    @Test
    fun restoredParkedTripFinalizesImmediatelyAndRecoveryIsIdempotent() {
        val pending = pendingTripEngine().state
        val restoredState = TelegramEventState.fromJson(pending.toJson())
        assertEquals(101.0, restoredState.tripEndOdometerKm)
        assertEquals(49.0, restoredState.tripEndSoc)
        assertEquals(2.5, restoredState.tripEndEnergyKwh)

        val restarted = TelegramEventEngine(restoredState)
        val recovered = restarted.recoverPendingTrip(config, 20_000L)
        val summary = recovered.events.single()

        assertEquals(TelegramEventType.TRIP_SUMMARY, summary.type)
        assertEquals("1", summary.variables["trip_distance_km"])
        assertEquals("1.5", summary.variables["trip_energy_kwh"])
        assertNull(recovered.state.tripId)
        assertTrue(restarted.recoverPendingTrip(config, 20_500L).events.isEmpty())
    }

    @Test
    fun oneStopBuiltInSummaryOmitsIdenticalOverallBlockAndPersistsLocationMarker() {
        val engine = pendingTripEngine()
        val tripId = engine.state.tripId

        val finalized = engine.onTick(config, false, null, 20_000L)
        val summary = finalized.events.single()

        assertTrue(summary.omitOverall)
        assertEquals(tripId, finalized.state.pendingPowerOffLocationTripId)
        assertFalse(finalized.state.pendingPowerOffLocationSummaryDelivered)
        assertTrue(finalized.state.hasDeferredStorageWork())

        val restarted = TelegramEventEngine(TelegramEventState.fromJson(finalized.state.toJson()))
        assertFalse(restarted.state.pendingPowerOffLocationSummaryDelivered)
        assertNull(restarted.markTripSummaryDelivered("wrong:summary", 20_500L))
        assertEquals(
            tripId,
            restarted.markTripSummaryDelivered("$tripId:summary", 20_500L)?.pendingPowerOffLocationTripId
        )
        assertTrue(restarted.state.pendingPowerOffLocationSummaryDelivered)
        val location = TelegramLocationSnapshot(50.0, 30.0, "12:00", 0L, "osm", "google", "apple", "waze")
        val followUp = restarted.onPowerOffConfirmed(
            config.copy(sendLocation = true, navigatorMask = TelegramNavigatorMask.GOOGLE),
            location = location,
            nowMs = 21_000L
        )

        assertEquals("$tripId:location", followUp.events.single().dedupeKey)
        assertTrue(followUp.events.single().locationOnly)
        assertEquals("Google: google", followUp.events.single().textSuffix)
        assertNull(followUp.events.single().waitsForSummaryKey)
        assertEquals("ready", followUp.locationEligibilityReason)
        assertNull(followUp.state.pendingPowerOffLocationTripId)
        assertFalse(followUp.state.pendingPowerOffLocationSummaryDelivered)
        assertTrue(restarted.onPowerOffConfirmed(
            config.copy(sendLocation = true, navigatorMask = TelegramNavigatorMask.GOOGLE),
            location = location,
            nowMs = 22_000L
        ).events.isEmpty())
    }

    @Test
    fun actualPowerOffQueuesLocationUntilPersistedSummaryDeliveryProof() {
        val engine = pendingTripEngine()
        val tripId = engine.state.tripId
        engine.onTick(config, false, null, 20_000L)

        val result = engine.onPowerOffConfirmed(
            config.copy(sendLocation = true, navigatorMask = TelegramNavigatorMask.GOOGLE),
            location = TelegramLocationSnapshot(50.0, 30.0, "12:00", 0L, "osm", "google", "apple"),
            nowMs = 21_000L
        )

        assertEquals("$tripId:location", result.events.single().dedupeKey)
        assertTrue(result.events.single().locationOnly)
        assertEquals("$tripId:summary", result.events.single().waitsForSummaryKey)
        assertEquals("waiting_summary", result.locationEligibilityReason)
        assertNull(result.state.pendingPowerOffLocationTripId)
        assertFalse(result.state.pendingPowerOffLocationSummaryDelivered)
        assertNull(engine.markTripSummaryDelivered("$tripId:summary", 21_500L))
    }

    @Test
    fun frozenPowerOffLocationSurvivesRestartAndNewTripWithoutRegeneration() {
        val engine = pendingTripEngine()
        val tripId = engine.state.tripId
        engine.onTick(config, false, null, 20_000L)
        val frozen = engine.onPowerOffConfirmed(
            config.copy(sendLocation = true, navigatorMask = TelegramNavigatorMask.GOOGLE),
            location = TelegramLocationSnapshot(50.0, 30.0, "12:00", 0L, "osm", "google", "apple"),
            nowMs = 21_000L
        ).events.single()

        val restarted = TelegramEventEngine(TelegramEventState.fromJson(engine.state.toJson()))
        restarted.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, soc = 48.0, tripEnergy = 0.0), config, 22_000L)
        val newTrip = restarted.onSuccessfulPoll(
            snapshot(gear = "D", odometer = 102.0, soc = 48.0, tripEnergy = 0.0),
            config,
            22_500L
        )

        assertEquals("$tripId:location", frozen.dedupeKey)
        assertEquals("$tripId:summary", frozen.waitsForSummaryKey)
        assertTrue(restarted.state.tripId != null && restarted.state.tripId != tripId)
        assertNull(restarted.state.pendingPowerOffLocationTripId)
        assertTrue(newTrip.events.none { it.dedupeKey == frozen.dedupeKey })
    }

    @Test
    fun locationEligibilityReportsSummaryDisabledBeforeOtherGates() {
        val engine = pendingTripEngine()
        engine.onTick(config, false, null, 20_000L)
        val result = engine.onPowerOffConfirmed(
            config.copy(
                enabledEvents = config.enabledEvents - TelegramEventType.TRIP_SUMMARY,
                sendLocation = false,
                navigatorMask = 0
            ),
            location = TelegramLocationSnapshot(50.0, 30.0, "12:00", 0L, "osm", "google", "apple"),
            nowMs = 21_000L
        )

        assertEquals("summary_disabled", result.locationEligibilityReason)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun actualPowerOffClearsDeferredLocationMarkerWhenNoValidLocationCanBeSent() {
        val engine = pendingTripEngine()
        engine.onTick(config, false, null, 20_000L)
        val location = TelegramLocationSnapshot(Double.NaN, 30.0, "12:00", 0L, "osm", "google", "apple")

        val noFix = engine.onPowerOffConfirmed(
            config.copy(sendLocation = true, navigatorMask = TelegramNavigatorMask.GOOGLE),
            location = location,
            nowMs = 21_000L
        )
        assertTrue(noFix.events.isEmpty())
        assertEquals("invalid_fix", noFix.locationEligibilityReason)
        assertNull(noFix.state.pendingPowerOffLocationTripId)
        assertTrue(engine.onPowerOffConfirmed(
            config.copy(sendLocation = true, navigatorMask = TelegramNavigatorMask.GOOGLE),
            location = TelegramLocationSnapshot(50.0, 30.0, "12:01", 0L, "osm", "google", "apple"),
            nowMs = 22_000L
        ).events.isEmpty())
    }

    @Test
    fun recoveredParkedTripFinalizesBeforeFreshDrivingAndNewCounter() {
        val persisted = pendingTripEngine().state.copy(
            bootTotalDistanceKm = 4.0,
            bootTotalDurationMs = 60_000L,
            lastTripEnergyCounterKwh = 2.5
        )
        val previousTripId = persisted.tripId
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(persisted.toJson()))

        val recovered = restarted.recoverPendingTrip(config, 20_000L)
        val summary = recovered.events.single()
        val firstNewBootPoll = restarted.onSuccessfulPoll(
            snapshot(gear = "D", odometer = 101.0, soc = 49.0, tripEnergy = 0.1),
            config,
            20_500L
        )
        val driving = restarted.onSuccessfulPoll(
            snapshot(gear = "D", odometer = 101.1, soc = 49.0, tripEnergy = 0.1),
            config,
            21_000L
        )

        assertTrue(firstNewBootPoll.events.isEmpty())
        assertNull(firstNewBootPoll.state.tripId)
        assertEquals("1", summary.variables["trip_distance_km"])
        assertEquals("1.5", summary.variables["trip_energy_kwh"])
        assertEquals("150", summary.variables["trip_avg_kwh_per_100km"])
        assertEquals("5", summary.variables["total_distance_km"])
        assertEquals("1.5", summary.variables["total_energy_kwh"])
        assertEquals("30", summary.variables["total_avg_kwh_per_100km"])
        assertTrue(driving.events.isEmpty())
        assertNotNull(driving.state.tripId)
        assertTrue(driving.state.tripId != previousTripId)
        assertEquals(5.0, driving.state.bootTotalDistanceKm)
        assertEquals(1.5, driving.state.bootTotalEnergyKwh)
        assertEquals(61_500L, driving.state.bootTotalDurationMs)
        assertEquals(0.1, driving.state.lastTripEnergyCounterKwh)
    }

    @Test
    fun recoveryLeavesAnActiveNonParkedTripOpen() {
        val active = TelegramEventEngine().also { engine ->
            engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0), config, 0L)
            engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0), config, 500L)
            engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.1), config, 1_000L)
        }
        val tripId = active.state.tripId

        val recovered = TelegramEventEngine(TelegramEventState.fromJson(active.state.toJson()))
            .recoverPendingTrip(config, 20_000L)

        assertTrue(recovered.events.isEmpty())
        assertEquals(tripId, recovered.state.tripId)
    }

    @Test
    fun tripSummaryUsesCounterDeltaAndAccumulatesFinalizedSessions() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0, soc = 56.0, tripEnergy = 10.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 56.0, tripEnergy = 10.0), config, 1_000L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 56.0, tripEnergy = 10.0), config, 2_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0, soc = 54.0, tripEnergy = 10.5), config, 62_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0, soc = 54.0, tripEnergy = 10.5), config, 63_000L)
        val first = engine.onTick(config, false, null, 73_000L).events.single()

        assertEquals("1", first.variables["trip_distance_km"])
        assertEquals("0.5", first.variables["trip_energy_kwh"])
        assertEquals("50", first.variables["trip_avg_kwh_per_100km"])
        assertEquals("1", first.variables["total_distance_km"])
        assertEquals("0.5", first.variables["total_energy_kwh"])
        assertEquals("50", first.variables["total_avg_kwh_per_100km"])
        assertEquals("0:01", first.variables["trip_duration"])
        assertEquals("0:01", first.variables["total_duration"])
        assertEquals("56", first.variables["soc_start"])
        assertEquals("54", first.variables["soc_end"])
        assertEquals("56", first.variables["total_soc_start"])
        assertEquals("54", first.variables["total_soc_end"])
        assertTrue(first.omitOverall)

        val restarted = TelegramEventEngine(TelegramEventState.fromJson(engine.state.toJson()))
        restarted.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0, soc = 53.0, tripEnergy = 10.5), config, 74_000L)
        restarted.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.0, soc = 53.0, tripEnergy = 10.5), config, 75_000L)
        restarted.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.0, soc = 53.0, tripEnergy = 10.5), config, 76_000L)
        val second = restarted.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(103.0, 52.0, 11.2),
            nowMs = 196_000L
        ).events.single()

        assertEquals("2", second.variables["trip_distance_km"])
        assertEquals("0.7", second.variables["trip_energy_kwh"])
        assertEquals("35", second.variables["trip_avg_kwh_per_100km"])
        assertEquals("3", second.variables["total_distance_km"])
        assertEquals("1.2", second.variables["total_energy_kwh"])
        assertEquals("40", second.variables["total_avg_kwh_per_100km"])
        assertEquals("0:03", second.variables["total_duration"])
        assertEquals("53", second.variables["soc_start"])
        assertEquals("52", second.variables["soc_end"])
        assertEquals("56", second.variables["total_soc_start"])
        assertEquals("52", second.variables["total_soc_end"])
        assertFalse(second.omitOverall)
        assertEquals(0.0, restarted.state.bootTotalEnergyKwh)
        assertNull(restarted.state.bootStartSoc)
        assertNull(restarted.state.bootEndSoc)
    }

    @Test
    fun parkedSocUpdatesOverallButDoesNotRewriteTheCompletedDrive() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0, soc = 56.0, tripEnergy = 10.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 56.0, tripEnergy = 10.0), config, 1_000L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, soc = 56.0, tripEnergy = 10.0), config, 2_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0, soc = 54.0, tripEnergy = 10.5), config, 62_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0, soc = 54.0, tripEnergy = 10.5), config, 63_000L)

        val summary = engine.onSuccessfulPoll(
            snapshot(gear = "P", odometer = 101.0, soc = 53.0, tripEnergy = 10.5),
            config,
            73_000L
        ).events.single()

        assertEquals("56", summary.variables["soc_start"])
        assertEquals("54", summary.variables["soc_end"])
        assertEquals("56", summary.variables["total_soc_start"])
        assertEquals("53", summary.variables["total_soc_end"])
        assertFalse(summary.omitOverall)
    }

    @Test
    fun firstMovingPollWithoutSocBackfillsOverallStartFromTheFirstLaterValidSample() {
        val engine = TelegramEventEngine()

        engine.onSuccessfulPoll(snapshot(gear = "D", soc = null, odometer = 100.0, tripEnergy = 2.5), config, 0L)
        assertNull(engine.state.bootStartSoc)

        val later = engine.onSuccessfulPoll(
            snapshot(gear = "D", soc = 63.0, odometer = 100.0, tripEnergy = 2.5),
            config,
            500L
        )

        assertEquals(63.0, later.state.bootStartSoc)
        assertEquals(63.0, later.state.bootEndSoc)
    }

    @Test
    fun laterSocDoesNotBackfillAnAccumulatedLegacySession() {
        val engine = TelegramEventEngine(
            TelegramEventState(
                initialized = true,
                gear = "D",
                tripId = "legacy",
                bootTotalDistanceKm = 2.0
            )
        )

        val result = engine.onSuccessfulPoll(snapshot(gear = "D", soc = 63.0), config, 500L)

        assertNull(result.state.bootStartSoc)
        assertEquals(63.0, result.state.bootEndSoc)
    }

    @Test
    fun legacyFirstActiveTripRecoversExactOverallSocButPriorTotalsDoNotFabricateIt() {
        val firstTrip = TelegramEventEngine(
            TelegramEventState(
                initialized = true,
                gear = "D",
                tripId = "first",
                tripStartSoc = 70.0,
                tripEndSoc = 69.0
            )
        )
        val laterTrip = TelegramEventEngine(
            TelegramEventState(
                initialized = true,
                gear = "D",
                tripId = "later",
                tripStartSoc = 65.0,
                tripEndSoc = 64.0,
                bootTotalDistanceKm = 10.0
            )
        )

        assertEquals(70.0, firstTrip.state.bootStartSoc)
        assertEquals(69.0, firstTrip.state.bootEndSoc)
        assertNull(laterTrip.state.bootStartSoc)
        assertEquals(64.0, laterTrip.state.bootEndSoc)
    }

    @Test
    fun shortParkAndCounterResetContinueOneTripWithoutLosingEnergy() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0, tripEnergy = 10.0), config, 0L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, tripEnergy = 10.0), config, 500L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, tripEnergy = 10.0), config, 1_000L)
        val tripId = engine.state.tripId
        val progressed = engine.onSuccessfulPoll(
            snapshot(gear = "D", odometer = 101.0, tripEnergy = 10.5),
            config,
            2_000L
        )
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0, tripEnergy = 10.5), config, 3_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 101.0, tripEnergy = 10.5), config, 3_500L)
        engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.0, tripEnergy = 0.2), config, 4_000L)
        val resumed = engine.onSuccessfulPoll(
            snapshot(gear = "D", odometer = 101.0, tripEnergy = 0.2),
            config,
            4_500L
        )
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.0, tripEnergy = 0.9), config, 20_000L)
        engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.0, tripEnergy = 0.9), config, 20_500L)
        val summary = engine.onTick(config, false, null, 30_500L).events.single()

        assertTrue(progressed.shouldPersist)
        assertEquals(tripId, resumed.state.tripId)
        assertEquals("2", summary.variables["trip_distance_km"])
        assertEquals("1.2", summary.variables["trip_energy_kwh"])
        assertEquals("60", summary.variables["trip_avg_kwh_per_100km"])
        assertEquals("1.2", summary.variables["total_energy_kwh"])
        assertEquals("60", summary.variables["total_avg_kwh_per_100km"])
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
        assertEquals("n/a", qualified.variables["trip_avg_kwh_per_100km"])
        assertEquals("49.5", qualified.variables["soc_start"])
        assertEquals("48.5", qualified.variables["soc_end"])
    }

    @Test
    fun meaningfulTripEnergyCounterDecreasePreservesTotalsAndActiveTrip() {
        val restored = TelegramEventState(
            initialized = true,
            gear = "D",
            tripId = "stale",
            tripStartedAtMs = 1_000L,
            tripStartEnergyKwh = 9.0,
            bootTotalDistanceKm = 5.0,
            bootTotalEnergyKwh = 2.0,
            bootTotalDurationMs = 60_000L,
            lastTripEnergyCounterKwh = 10.0
        )
        val engine = TelegramEventEngine(TelegramEventState.fromJson(restored.toJson()))

        val reset = engine.onSuccessfulPoll(snapshot(gear = "D", tripEnergy = 9.89), config, 3_000L)

        assertTrue(reset.shouldPersist)
        assertEquals("stale", reset.state.tripId)
        assertEquals(1.0, reset.state.tripAccumulatedEnergyKwh)
        assertEquals(5.0, reset.state.bootTotalDistanceKm)
        assertEquals(2.0, reset.state.bootTotalEnergyKwh)
        assertEquals(60_000L, reset.state.bootTotalDurationMs)
        assertEquals(9.89, reset.state.lastTripEnergyCounterKwh)
        assertEquals(0.0, TelegramEventState.fromJson("{\"initialized\":true}").bootTotalEnergyKwh)
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
    fun expiredParkDeadlineFinalizesOldTripBeforeFreshDriving() {
        val engine = pendingTripEngine()
        val previousTripId = engine.state.tripId

        val finalized = engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.0), config, 20_000L)
        val restarted = engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 101.1), config, 20_500L)

        assertEquals(TelegramEventType.TRIP_SUMMARY, finalized.events.single().type)
        assertNull(finalized.state.tripId)
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

    @Test
    fun restoredOutageStateWaitsFromTheCurrentRuntimeStart() {
        val engine = TelegramEventEngine(
            TelegramEventState(
                initialized = true,
                lastSuccessfulPollAtMs = 1_000L,
                telemetryExpectedSinceMs = 1_000L
            )
        )

        val started = engine.onTick(config, true, "offline", 100_000L, runtimeStartedAtMs = 100_000L)
        assertTrue(started.events.isEmpty())
        assertEquals(100_000L, started.state.telemetryExpectedSinceMs)
        assertTrue(engine.onTick(config, true, "offline", 159_999L, 100_000L).events.isEmpty())
        assertEquals(
            TelegramEventType.TELEMETRY_UNAVAILABLE,
            engine.onTick(config, true, "offline", 160_000L, 100_000L).events.single().type
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

    private fun knownInactiveChargingEngine(): TelegramEventEngine {
        return TelegramEventEngine().also { engine ->
            engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0), config, 0L)
            engine.onSuccessfulPoll(snapshot(chargeGun = false, chargePower = 0.0), config, 500L)
        }
    }

    private fun startedBmsChargingEngine(
        soc: Double = 50.0,
        remainingEnergy: Double = 40.0
    ): TelegramEventEngine {
        return knownInactiveChargingEngine().also { engine ->
            engine.onSuccessfulPoll(
                snapshot(
                    chargeGun = true,
                    chargePower = 0.0,
                    bmsState = "charging",
                    soc = soc,
                    remainingEnergy = remainingEnergy
                ),
                config,
                1_000L
            )
            engine.onSuccessfulPoll(
                snapshot(
                    chargeGun = true,
                    chargePower = 0.0,
                    bmsState = "charging",
                    soc = soc,
                    remainingEnergy = remainingEnergy
                ),
                config,
                1_500L
            )
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
        soc: Double? = 50.0,
        auxVoltage: Double? = 12.5,
        gear: String? = "P",
        odometer: Double? = 100.0,
        tripEnergy: Double? = 2.5,
        remainingEnergy: Double? = 40.0,
        chargePower: Double? = 0.0,
        chargeGun: Boolean? = false,
        bmsState: String? = null
    ): List<NormalizedObservation> = buildList {
        soc?.let { add(number(NormalizedFieldCatalog.soc, it)) }
        remainingEnergy?.let { add(number(NormalizedFieldCatalog.batteryRemainingEnergy, it)) }
        chargePower?.let { add(number(NormalizedFieldCatalog.batteryChargePower, it)) }
        auxVoltage?.let { add(number(NormalizedFieldCatalog.auxVoltage, it)) }
        odometer?.let { add(number(NormalizedFieldCatalog.odometerKm, it)) }
        tripEnergy?.let { add(number(NormalizedFieldCatalog.tripEnergy, it)) }
        add(number(NormalizedFieldCatalog.remainingRangeKm, 300.0))
        gear?.let { add(text(NormalizedFieldCatalog.gearAutoMode, it)) }
        chargeGun?.let { add(bool(NormalizedFieldCatalog.chargeGunConnected, it)) }
        bmsState?.let { add(text(NormalizedFieldCatalog.chargingBatteryDeviceState, it)) }
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
