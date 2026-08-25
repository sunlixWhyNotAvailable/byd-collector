package com.bydcollector.collector.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramStorageCutoverStateTest {
    @Test
    fun malformedStateFailsStrictParsing() {
        assertNull(TelegramEventState.fromJsonOrNull("{not-json"))
        assertFalse(TelegramEventState.fromJson("{not-json").hasDeferredStorageWork())
    }

    @Test
    fun stableBaselineDoesNotBlockCutover() {
        assertFalse(
            TelegramEventState(
                initialized = true,
                chargingActive = false,
                chargeGunConnected = false,
                gear = "P",
                lastSuccessfulPollAtMs = 123L,
                telemetryExpectedSinceMs = 123L
            ).hasDeferredStorageWork()
        )
    }

    @Test
    fun pendingSemanticWorkBlocksCutover() {
        val blockers = listOf(
            TelegramEventState(tripId = "trip"),
            TelegramEventState(tripParkedSinceMs = 1L),
            TelegramEventState(chargingSessionId = "charge"),
            TelegramEventState(chargingLowPowerSinceMs = 1L),
            TelegramEventState(chargingActiveCandidate = true, chargingActiveCandidateCount = 1),
            TelegramEventState(chargeGunCandidate = true, chargeGunCandidateCount = 1),
            TelegramEventState(gearCandidate = "P", gearCandidateCount = 1),
            TelegramEventState(fullCandidateCount = 1),
            TelegramEventState(fullSent = true),
            TelegramEventState(lowVoltageSinceMs = 1L),
            TelegramEventState(lowVoltageSent = true),
            TelegramEventState(telemetryOutageSent = true),
            TelegramEventState(bootStartSoc = 80.0),
            TelegramEventState(bootEndSoc = 79.0),
            TelegramEventState(bootTotalDistanceKm = 1.0),
            TelegramEventState(bootTotalEnergyKwh = 1.0),
            TelegramEventState(bootTotalDurationMs = 60_000L)
        )

        assertTrue(blockers.all(TelegramEventState::hasDeferredStorageWork))
    }
}
