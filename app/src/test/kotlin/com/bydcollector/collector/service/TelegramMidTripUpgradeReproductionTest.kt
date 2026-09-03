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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression coverage for the real engine/JSON restart boundary, not APK installation or HTTP. */
class TelegramMidTripUpgradeReproductionTest {
    private val config = TelegramEventConfig(
        enabledEvents = setOf(TelegramEventType.TRIP_SUMMARY),
        chargeStepPercent = 5,
        lowVoltageThreshold = 12.0,
        unavailableDelayMs = 60_000L,
        tripEndDelayMs = 60_000L,
        sendLocation = true,
        navigatorMask = TelegramNavigatorMask.GOOGLE
    )

    @Test
    fun restartWhileDrivingKeepsTripAcrossMissingGearAndReverseSamples() {
        val v276 = drivingEngine()
        val tripId = assertNotNull(v276.state.tripId)

        // APK replacement recreates the engine from the same durable JSON state.
        val v277 = TelegramEventEngine(TelegramEventState.fromJson(v276.state.toJson()))
        assertEquals(tripId, v277.state.tripId)

        // A boot/restart poll may omit normalized gear (partial quality); later
        // D/R confirmations must not drop or replace the open trip.
        v277.onSuccessfulPoll(snapshot(gear = "D", gearQuality = NormalizedQuality.MISSING, odometer = 102.0), config, 10_000L)
        v277.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0), config, 10_500L)
        v277.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0), config, 11_000L)
        v277.onSuccessfulPoll(snapshot(gear = "R", odometer = 102.0), config, 11_500L)
        val reverseConfirmed = v277.onSuccessfulPoll(snapshot(gear = "R", odometer = 102.0), config, 12_000L)

        assertEquals(tripId, reverseConfirmed.state.tripId)
        assertTrue(reverseConfirmed.events.isEmpty())

        val stopped = v277.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(odometerKm = 102.0, soc = 49.0, tripEnergyKwh = 1.5),
            lastValidLocation(),
            20_000L
        )
        val summary = stopped.events.single()
        assertEquals("$tripId:summary", summary.dedupeKey)
        assertEquals("2", summary.variables["trip_distance_km"])
        assertTrue(summary.textSuffix.orEmpty().contains("https://maps.google.com/"))
    }

    @Test
    fun activeTripRestartThenLongParkEmitsSummaryBeforeVehicleOff() {
        val old = drivingEngine()
        old.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, tripEnergy = 1.5), config, 30_000L)
        val tripId = assertNotNull(old.state.tripId)
        val restored = TelegramEventEngine(TelegramEventState.fromJson(old.state.toJson()))
        assertTrue(restored.recoverPendingTrip(config, 35_000L).events.isEmpty())
        restored.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.0, tripEnergy = 1.5), config, 40_000L)
        restored.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.0, tripEnergy = 1.5), config, 40_500L)
        assertTrue(restored.onTick(config, true, null, 100_499L).events.isEmpty())
        val summary = restored.onTick(config, true, null, 100_500L).events.single()
        assertEquals("$tripId:summary", summary.dedupeKey)
        assertEquals("2", summary.variables["trip_distance_km"])
        assertNull(summary.textSuffix)
    }

    @Test
    fun shortParkRestartPreservesTripUntilParkDeadlineWhileUninterruptedControlDoesNotSplit() {
        val control = shortParkEngine()
        val tripId = assertNotNull(control.state.tripId)
        val restored = TelegramEventEngine(TelegramEventState.fromJson(control.state.toJson()))

        // The coordinator invokes this before processing the first post-update poll.
        val recovered = restored.recoverPendingTrip(config, 45_000L)
        assertTrue(recovered.events.isEmpty())
        assertEquals(tripId, recovered.state.tripId)
        assertNotNull(recovered.state.tripParkedSinceMs)

        for (engine in listOf(control, restored)) {
            assertTrue(engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, tripEnergy = 1.5), config, 47_000L).events.isEmpty())
            assertTrue(engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, tripEnergy = 1.5), config, 47_500L).events.isEmpty())
        }
        assertEquals(tripId, control.state.tripId)
        assertEquals(tripId, restored.state.tripId)
        // No intervening power-off: neither restart nor short park splits the leg.
        assertNull(restored.state.pendingPowerOffLocationTripId)
    }

    @Test
    fun delayedSummaryAndLocationObligationSurviveJsonRestartWithoutAnotherDrive() {
        val old = shortParkEngine()
        val summary = old.onTick(config, true, null, 101_000L).events.single()
        assertNotNull(old.markTripSummaryDelivered(summary.dedupeKey, 101_100L))
        val restored = TelegramEventEngine(TelegramEventState.fromJson(old.state.toJson()))
        assertTrue(restored.recoverPendingTrip(config, 105_000L).events.isEmpty())

        val result = restored.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(102.0, 50.0, 1.5),
            lastValidLocation(),
            110_000L
        )
        val locationEvent = result.events.single()
        assertTrue(locationEvent.locationOnly)
        assertNull(locationEvent.waitsForSummaryKey)
        assertEquals("ready", result.locationEligibilityReason)
    }

    @Test
    fun shortParkUpgradeThenSmallParkingMoveSettlesPriorLocationDespiteValidFix() {
        val control = shortParkEngine()
        // Keep this separate from the A fix: seed the restarted arm with an
        // ordinarily completed parked trip, then perform the upgrade.
        val completed = shortParkEngine()
        val priorSummary = completed.onTick(config, true, null, 101_000L).events.single()
        val priorTripId = priorSummary.dedupeKey.removeSuffix(":summary")
        assertNotNull(completed.markTripSummaryDelivered(priorSummary.dedupeKey, 101_100L))
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(completed.state.toJson()))

        // The uninterrupted arm keeps the original leg and emits its
        // qualifying summary with the current location. The restarted arm
        // starts a tiny replacement leg after the persisted summary; its
        // prior location obligation must survive startTrip and be emitted
        // separately at power-off. Keep each arm's timestamps monotonic.
        control.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, tripEnergy = 1.5), config, 47_000L)
        control.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, tripEnergy = 1.5), config, 47_500L)
        control.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.05, tripEnergy = 1.55), config, 50_000L)
        control.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.05, tripEnergy = 1.55), config, 50_500L)
        val controlTripId = assertNotNull(control.state.tripId)
        val normal = control.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(102.05, 50.0, 1.55),
            lastValidLocation(),
            55_000L
        )

        restarted.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, tripEnergy = 1.5), config, 147_000L)
        restarted.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, tripEnergy = 1.5), config, 147_500L)
        restarted.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.05, tripEnergy = 1.55), config, 150_000L)
        restarted.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.05, tripEnergy = 1.55), config, 150_500L)
        assertEquals(priorTripId, restarted.state.pendingPowerOffLocationTripId)
        val missing = restarted.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(102.05, 50.0, 1.55),
            lastValidLocation(),
            155_000L
        )

        assertEquals("$controlTripId:summary", normal.events.single().dedupeKey)
        assertTrue(normal.events.single().textSuffix.orEmpty().contains("https://maps.google.com/"))
        assertEquals("$priorTripId:location", missing.events.single().dedupeKey)
        assertTrue(missing.events.single().locationOnly)
        assertNull(missing.events.single().waitsForSummaryKey)
        assertNull(missing.state.pendingPowerOffLocationTripId)
    }

    @Test
    fun finalizedTinyReplacementStillFlushesPriorLocationAtPowerOff() {
        val completed = shortParkEngine()
        val priorSummary = completed.onTick(config, true, null, 101_000L).events.single()
        val priorTripId = priorSummary.dedupeKey.removeSuffix(":summary")
        assertNotNull(completed.markTripSummaryDelivered(priorSummary.dedupeKey, 101_100L))
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(completed.state.toJson()))

        // A replacement leg is only 50m/0.05kWh. It parks long enough to be
        // finalized before power-off, but must not discard the older marker.
        restarted.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, tripEnergy = 1.5), config, 147_000L)
        restarted.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, tripEnergy = 1.5), config, 147_500L)
        restarted.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.05, tripEnergy = 1.55), config, 150_000L)
        restarted.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.05, tripEnergy = 1.55), config, 150_500L)
        assertEquals(priorTripId, restarted.state.pendingPowerOffLocationTripId)

        val finalizedTiny = restarted.onTick(config, true, null, 210_500L)
        assertTrue(finalizedTiny.events.isEmpty())
        assertNull(finalizedTiny.state.tripId)
        assertEquals(priorTripId, finalizedTiny.state.pendingPowerOffLocationTripId)

        val powerOff = restarted.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(102.05, 50.0, 1.55),
            lastValidLocation(),
            211_000L
        )
        assertEquals("$priorTripId:location", powerOff.events.single().dedupeKey)
        assertTrue(powerOff.events.single().locationOnly)
        assertNull(powerOff.state.pendingPowerOffLocationTripId)
        assertTrue(
            restarted.onPowerOffConfirmed(
                config,
                TelegramPowerOffSnapshot(102.05, 50.0, 1.55),
                lastValidLocation(),
                212_000L
            ).events.isEmpty()
        )
    }

    @Test
    fun qualifyingReplacementCarriesCurrentLocationAndSettlesPriorMarker() {
        val completed = shortParkEngine()
        val priorSummary = completed.onTick(config, true, null, 101_000L).events.single()
        val priorTripId = priorSummary.dedupeKey.removeSuffix(":summary")
        assertNotNull(completed.markTripSummaryDelivered(priorSummary.dedupeKey, 101_100L))
        val restarted = TelegramEventEngine(TelegramEventState.fromJson(completed.state.toJson()))

        restarted.onSuccessfulPoll(snapshot(gear = "D", odometer = 103.0, tripEnergy = 1.6), config, 147_000L)
        restarted.onSuccessfulPoll(snapshot(gear = "D", odometer = 103.0, tripEnergy = 1.6), config, 147_500L)
        restarted.onSuccessfulPoll(snapshot(gear = "P", odometer = 103.2, tripEnergy = 1.6), config, 150_000L)
        restarted.onSuccessfulPoll(snapshot(gear = "P", odometer = 103.2, tripEnergy = 1.6), config, 150_500L)
        val replacementTripId = assertNotNull(restarted.state.tripId)

        val powerOff = restarted.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(103.2, 49.0, 1.6),
            lastValidLocation(),
            155_000L
        )
        assertEquals(listOf("$replacementTripId:summary"), powerOff.events.map { it.dedupeKey })
        assertTrue(powerOff.events.single().textSuffix.orEmpty().contains("https://maps.google.com/"))
        assertNull(powerOff.state.pendingPowerOffLocationTripId)
        assertTrue(powerOff.events.none { it.dedupeKey == "$priorTripId:location" })
    }

    @Test
    fun coldMissingGearStartsTripAfterConfirmedGearAcrossJsonRestart() {
        val cold = TelegramEventEngine()
        cold.onSuccessfulPoll(snapshot(gear = null), config, 0L)
        assertTrue(cold.state.awaitingInitialTripGear)
        assertTrue(cold.state.hasDeferredStorageWork())
        cold.onSuccessfulPoll(snapshot(gear = "D"), config, 500L)
        assertNull(cold.state.tripId)
        val restored = TelegramEventEngine(TelegramEventState.fromJson(cold.state.toJson()))
        assertTrue(restored.state.awaitingInitialTripGear)
        restored.onSuccessfulPoll(snapshot(gear = "D"), config, 1_000L)
        val tripId = assertNotNull(restored.state.tripId)
        assertEquals(1_000L, restored.state.tripStartedAtMs)
        assertFalse(restored.state.awaitingInitialTripGear)
        restored.recoverPendingTrip(config, 10_000L)
        restored.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, tripEnergy = 1.5), config, 11_000L)
        restored.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.0, tripEnergy = 1.5), config, 40_000L)
        restored.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.0, tripEnergy = 1.5), config, 40_500L)
        val summary = restored.onTick(config, true, null, 101_000L).events.single()
        assertEquals("$tripId:summary", summary.dedupeKey)
        assertEquals("2", summary.variables["trip_distance_km"])
        val off = restored.onPowerOffConfirmed(
            config, TelegramPowerOffSnapshot(102.0, 49.0, 1.5), lastValidLocation(), 110_000L
        )
        assertEquals("$tripId:location", off.events.single().dedupeKey)
        assertEquals(summary.dedupeKey, off.events.single().waitsForSummaryKey)
    }

    @Test
    fun confirmedParkAfterMissingGearReturnsToNormalTripStart() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(gear = null), config, 0L)
        engine.onSuccessfulPoll(snapshot(gear = "P"), config, 500L)
        assertTrue(engine.state.awaitingInitialTripGear)
        engine.onSuccessfulPoll(snapshot(gear = "P"), config, 1_000L)
        assertFalse(engine.state.awaitingInitialTripGear)
        assertNull(engine.state.tripId)
        engine.onSuccessfulPoll(snapshot(gear = "D"), config, 1_500L)
        assertNull(engine.state.tripId)
        engine.onSuccessfulPoll(snapshot(gear = "D"), config, 2_000L)
        assertNotNull(engine.state.tripId)
        assertEquals(2_000L, engine.state.tripStartedAtMs)
    }

    @Test
    fun powerOffAndLegacyStateDoNotStartPhantomTripsFromStaleDrivingGear() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(snapshot(gear = null), config, 0L)
        engine.onSuccessfulPoll(snapshot(gear = "D"), config, 500L)
        engine.onPowerOffConfirmed(config, nowMs = 750L)
        assertFalse(engine.state.awaitingInitialTripGear)
        val restored = TelegramEventEngine(TelegramEventState.fromJson(engine.state.toJson()))
        restored.onSuccessfulPoll(snapshot(gear = "D"), config, 1_000L)
        restored.onSuccessfulPoll(snapshot(gear = "D"), config, 1_500L)
        assertNull(restored.state.tripId)

        val legacyState = TelegramEventState.fromJson("""{"initialized":true,"gear":"D"}""")
        assertFalse(legacyState.awaitingInitialTripGear)
        val legacy = TelegramEventEngine(legacyState)
        legacy.onSuccessfulPoll(snapshot(gear = "D"), config, 2_000L)
        legacy.onSuccessfulPoll(snapshot(gear = "D"), config, 2_500L)
        assertNull(legacy.state.tripId)
        restored.reset()
        assertFalse(restored.state.awaitingInitialTripGear)
    }

    @Test
    fun firstPollDoesNotOverwriteAnAlreadyRestoredActiveTrip() {
        val saved = drivingEngine().state.copy(initialized = false)
        for (gear in listOf(null, "D")) {
            val restored = TelegramEventEngine(TelegramEventState.fromJson(saved.toJson()))
            restored.onSuccessfulPoll(snapshot(gear = gear), config, 10_000L)
            assertEquals(saved.tripId, restored.state.tripId)
            assertEquals(saved.tripStartedAtMs, restored.state.tripStartedAtMs)
            assertFalse(restored.state.awaitingInitialTripGear)
        }
    }

    private fun shortParkEngine(): TelegramEventEngine = drivingEngine().also {
        it.onSuccessfulPoll(snapshot(gear = "D", odometer = 102.0, tripEnergy = 1.5), config, 30_000L)
        it.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.0, tripEnergy = 1.5), config, 40_000L)
        it.onSuccessfulPoll(snapshot(gear = "P", odometer = 102.0, tripEnergy = 1.5), config, 40_500L)
    }

    private fun lastValidLocation() = TelegramLocationSnapshot(
        latitude = 50.0,
        longitude = 30.0,
        capturedAt = "2026-09-02T15:24:59Z",
        ageSeconds = 403L,
        osmUrl = "https://www.openstreetmap.org/?mlat=50&mlon=30",
        googleUrl = "https://maps.google.com/?q=50,30",
        appleUrl = "https://maps.apple.com/?ll=50,30"
    )

    private fun drivingEngine(): TelegramEventEngine {
        return TelegramEventEngine().also { engine ->
            engine.onSuccessfulPoll(snapshot(gear = "P", odometer = 100.0, tripEnergy = 0.5), config, 0L)
            engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, tripEnergy = 0.5), config, 500L)
            engine.onSuccessfulPoll(snapshot(gear = "D", odometer = 100.0, tripEnergy = 0.5), config, 1_000L)
        }
    }

    private fun snapshot(
        gear: String? = "P",
        gearQuality: NormalizedQuality = NormalizedQuality.OK,
        odometer: Double = 100.0,
        tripEnergy: Double = 0.5
    ): List<NormalizedObservation> = buildList {
        add(number(NormalizedFieldCatalog.soc, 50.0))
        add(number(NormalizedFieldCatalog.batteryRemainingEnergy, 40.0))
        add(number(NormalizedFieldCatalog.batteryChargePower, 0.0))
        add(number(NormalizedFieldCatalog.auxVoltage, 12.5))
        add(number(NormalizedFieldCatalog.odometerKm, odometer))
        add(number(NormalizedFieldCatalog.tripEnergy, tripEnergy))
        add(number(NormalizedFieldCatalog.remainingRangeKm, 300.0))
        gear?.let {
            add(
                NormalizedObservation(
                    field = NormalizedFieldCatalog.gearAutoMode,
                    value = NormalizedValue(NormalizedValueType.TEXT, text = it),
                    quality = gearQuality,
                    sourcePollId = 1L,
                    sourceKey = NormalizedFieldCatalog.gearAutoMode.sourceKeys.firstOrNull(),
                    observedAt = "2026-09-03T00:00:00Z"
                )
            )
        }
        add(bool(NormalizedFieldCatalog.chargeGunConnected, false))
    }

    private fun number(field: NormalizedFieldDefinition, value: Double) = observation(
        field,
        NormalizedValue(NormalizedValueType.NUMBER, number = value)
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
        observedAt = "2026-09-03T00:00:00Z"
    )
}
