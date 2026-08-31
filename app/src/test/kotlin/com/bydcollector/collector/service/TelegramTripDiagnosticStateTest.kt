package com.bydcollector.collector.service

import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedFieldDefinition
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.telegram.TelegramEventType
import com.bydcollector.collector.telegram.TelegramNavigatorMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramTripDiagnosticStateTest {
    private val tripConfig = TelegramEventConfig(
        enabledEvents = setOf(TelegramEventType.TRIP_SUMMARY),
        chargeStepPercent = 5,
        lowVoltageThreshold = 12.0,
        unavailableDelayMs = 60_000L,
        tripEndDelayMs = 10_000L
    )

    @Test
    fun legacyJsonHasNoInferredLinksAndLinkedStateRoundTrips() {
        val legacy = TelegramEventState.fromJson(
            """{"tripId":"leg","pendingPowerOffLocationTripId":"pending"}"""
        )

        assertNull(legacy.tripPowerSessionId)
        assertNull(legacy.pendingPowerOffLocationPowerSessionId)

        val linked = legacy.copy(
            tripPowerSessionId = "power-1",
            pendingPowerOffLocationPowerSessionId = "power-2"
        )
        assertEquals(linked, TelegramEventState.fromJson(linked.toJson()))
    }

    @Test
    fun activeLegsCanBindTheSameParentWithoutRebindingKnownParents() {
        val first = TelegramEventEngine(TelegramEventState(tripId = "leg-1"))
        val second = TelegramEventEngine(TelegramEventState(tripId = "leg-2"))

        assertEquals("power-1", first.bindTripPowerSession("leg-1", " power-1 ")?.tripPowerSessionId)
        assertEquals("power-1", second.bindTripPowerSession("leg-2", "power-1")?.tripPowerSessionId)
        assertNull(first.bindTripPowerSession("leg-1", "power-2"))
        assertEquals("power-1", first.state.tripPowerSessionId)
    }

    @Test
    fun activeBindingTransfersToPendingLegAndDeliveryAcknowledgementPreservesIt() {
        val engine = TelegramEventEngine(
            TelegramEventState(
                initialized = true,
                gear = "P",
                tripId = "leg",
                tripStartedAtMs = 0L,
                tripStartOdometerKm = 100.0,
                tripStartSoc = 50.0,
                tripParkedSinceMs = 1_000L,
                tripEndOdometerKm = 101.0,
                tripEndSoc = 49.0
            )
        )

        assertEquals("power", engine.bindTripPowerSession("leg", "power")?.tripPowerSessionId)
        val finalized = engine.onTick(tripConfig, mainCollectionExpected = false, lastError = null, nowMs = 20_000L)

        assertTrue(finalized.events.any { it.dedupeKey == "leg:summary" })
        assertNull(finalized.state.tripId)
        assertNull(finalized.state.tripPowerSessionId)
        assertEquals("leg", finalized.state.pendingPowerOffLocationTripId)
        assertEquals("power", finalized.state.pendingPowerOffLocationPowerSessionId)

        val delivered = engine.markTripSummaryDelivered("leg:summary", 21_000L)
        assertEquals("power", delivered?.pendingPowerOffLocationPowerSessionId)
        assertEquals("power", engine.state.pendingPowerOffLocationPowerSessionId)
    }

    @Test
    fun delayedBindingCanAttachAnAlreadyParkedPendingLegAndPowerOffClearsIt() {
        val engine = TelegramEventEngine(
            TelegramEventState(
                initialized = true,
                gear = "P",
                tripId = "leg",
                tripStartedAtMs = 0L,
                tripStartOdometerKm = 100.0,
                tripStartSoc = 50.0,
                tripParkedSinceMs = 1_000L,
                tripEndOdometerKm = 101.0,
                tripEndSoc = 49.0
            )
        )
        engine.onTick(tripConfig, mainCollectionExpected = false, lastError = null, nowMs = 20_000L)

        assertNull(engine.state.pendingPowerOffLocationPowerSessionId)
        assertEquals("power", engine.bindTripPowerSession("leg", "power")?.pendingPowerOffLocationPowerSessionId)

        val powerOff = engine.onPowerOffConfirmed(tripConfig, nowMs = 21_000L)
        assertNull(powerOff.state.pendingPowerOffLocationTripId)
        assertNull(powerOff.state.pendingPowerOffLocationPowerSessionId)
    }

    @Test
    fun blankStaleResetAndConflictingBindingsAreIgnored() {
        val known = TelegramEventEngine(
            TelegramEventState(
                tripId = "active",
                tripPowerSessionId = "known",
                pendingPowerOffLocationTripId = "pending",
                pendingPowerOffLocationPowerSessionId = "known-pending"
            )
        )

        assertNull(known.bindTripPowerSession("active", "other"))
        assertNull(known.bindTripPowerSession("pending", "other"))
        assertNull(known.bindTripPowerSession("  ", "power"))
        assertNull(known.bindTripPowerSession("active", "  "))
        assertNull(known.bindTripPowerSession("stale", "power"))

        known.reset()
        assertNull(known.bindTripPowerSession("active", "power"))
    }

    @Test
    fun failedPersistenceLeavesEveryEngineFieldUnchanged() {
        val engine = TelegramEventEngine(
            TelegramEventState(
                initialized = true,
                tripId = "leg",
                chargingActiveCandidate = true,
                chargingActiveCandidateCount = 1,
                lastSuccessfulPollAtMs = 123L,
                lastPersistedAtMs = 456L
            )
        )
        val before = engine.state

        assertFailsWith<IllegalStateException> {
            engine.bindTripPowerSession("leg", "power") {
                error("persistence failed")
            }
        }

        assertEquals(before, engine.state)
    }

    @Test
    fun parentMetadataDoesNotChangeSummariesLocationsDependenciesOrDeadlines() {
        for ((parkFirst, delivered) in listOf(false to false, true to false, true to true)) {
            for (mask in listOf(TelegramNavigatorMask.NONE, TelegramNavigatorMask.ALL)) {
                val initial = TelegramEventState(
                    initialized = true,
                    gear = "P",
                    tripId = "leg",
                    tripStartedAtMs = 0L,
                    tripStartOdometerKm = 100.0,
                    tripStartSoc = 50.0,
                    tripParkedSinceMs = 1_000L,
                    tripEndOdometerKm = 101.0,
                    tripEndSoc = 49.0
                )
                val plain = TelegramEventEngine(initial)
                val linked = TelegramEventEngine(initial)
                linked.bindTripPowerSession("leg", "power")
                val config = tripConfig.copy(sendLocation = true, navigatorMask = mask)
                if (parkFirst) {
                    val expected = plain.onTick(config, false, null, 20_000L)
                    val actual = linked.onTick(config, false, null, 20_000L)
                    assertEquals(expected, actual.copy(state = actual.state.copy(pendingPowerOffLocationPowerSessionId = null)))
                    if (delivered) {
                        plain.markTripSummaryDelivered("leg:summary", 21_000L)
                        linked.markTripSummaryDelivered("leg:summary", 21_000L)
                    }
                }
                val location = TelegramLocationSnapshot(50.0, 30.0, "12:00", 2L, "osm", "google", "apple", "waze")
                assertEquals(
                    plain.onPowerOffConfirmed(config, location = location, nowMs = 22_000L),
                    linked.onPowerOffConfirmed(config, location = location, nowMs = 22_000L)
                )
                assertNull(linked.state.tripPowerSessionId)
                assertNull(linked.state.pendingPowerOffLocationPowerSessionId)
            }
        }
    }

    @Test
    fun restartKeepsPendingParentButNewLegAndResetRejectLateOldBinding() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot("P", 100.0, 50.0, 1.0), tripConfig, 0L)
        engine.onSuccessfulPoll(snapshot("D", 100.0, 50.0, 1.0), tripConfig, 500L)
        engine.onSuccessfulPoll(snapshot("D", 100.0, 50.0, 1.0), tripConfig, 1_000L)
        val oldLegId = engine.state.tripId!!
        assertEquals("power", engine.bindTripPowerSession(oldLegId, "power")?.tripPowerSessionId)

        engine.onSuccessfulPoll(snapshot("P", 101.0, 49.0, 1.1), tripConfig, 2_000L)
        engine.onSuccessfulPoll(snapshot("P", 101.0, 49.0, 1.1), tripConfig, 2_500L)
        val finalized = engine.onTick(tripConfig, mainCollectionExpected = false, lastError = null, nowMs = 20_000L)
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(finalized.state.toJson()))

        assertEquals("power", restarted.state.pendingPowerOffLocationPowerSessionId)
        assertEquals("power", restarted.markTripSummaryDelivered("$oldLegId:summary", 20_500L)
            ?.pendingPowerOffLocationPowerSessionId)

        restarted.onSuccessfulPoll(snapshot("D", 102.0, 49.0, 0.0), tripConfig, 22_000L)
        restarted.onSuccessfulPoll(snapshot("D", 102.0, 49.0, 0.0), tripConfig, 22_500L)
        val newLegId = restarted.state.tripId
        assertTrue(newLegId != null && newLegId != oldLegId)
        assertNull(restarted.state.pendingPowerOffLocationTripId)
        assertNull(restarted.state.pendingPowerOffLocationPowerSessionId)
        assertNull(restarted.bindTripPowerSession(oldLegId, "late-parent"))

        restarted.reset()
        assertNull(restarted.bindTripPowerSession(newLegId!!, "late-parent"))
    }

    private fun snapshot(
        gear: String,
        odometer: Double,
        soc: Double,
        tripEnergy: Double
    ): List<NormalizedObservation> = listOf(
        number(NormalizedFieldCatalog.soc, soc),
        number(NormalizedFieldCatalog.odometerKm, odometer),
        number(NormalizedFieldCatalog.tripEnergy, tripEnergy),
        text(NormalizedFieldCatalog.gearAutoMode, gear)
    )

    private fun number(field: NormalizedFieldDefinition, value: Double) = observation(
        field,
        NormalizedValue(NormalizedValueType.NUMBER, number = value)
    )

    private fun text(field: NormalizedFieldDefinition, value: String) = observation(
        field,
        NormalizedValue(NormalizedValueType.TEXT, text = value)
    )

    private fun observation(field: NormalizedFieldDefinition, value: NormalizedValue) = NormalizedObservation(
        field = field,
        value = value,
        quality = NormalizedQuality.OK,
        sourcePollId = 1L,
        sourceKey = field.sourceKeys.firstOrNull(),
        observedAt = "2026-08-31T00:00:00Z"
    )
}
