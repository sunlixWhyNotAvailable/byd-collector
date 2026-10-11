package com.bydcollector.collector.service

import com.bydcollector.collector.data.energy.EnergyInput
import com.bydcollector.collector.data.energy.EnergyReceipt
import com.bydcollector.collector.data.energy.EnergySessionCoordinator
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.local.TelegramDatabaseHelper
import com.bydcollector.collector.data.local.TelegramStore
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedFieldDefinition
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.data.trips.TripSession
import com.bydcollector.collector.data.trips.TripStore
import com.bydcollector.collector.telegram.TelegramEventType
import com.bydcollector.collector.telegram.TelegramNavigatorMask
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramMidTripUpgradeReproductionTest {
    @Test
    fun powerTripAndTelegramDriveSurviveRestartShortParkAndCloseOnlyOnPowerOff() {
        val context = RuntimeEnvironment.getApplication()
        val tripName = "t19-trips.db"
        val telegramName = "t19-telegram.db"
        var tripStore = TripStore(TripDatabaseHelper(context, tripName))
        var telegramStore = TelegramStore(context, TelegramDatabaseHelper(context, telegramName))
        val elapsed = AtomicLong(0L)
        val completions = mutableListOf<Long>()
        var energy = EnergySessionCoordinator(tripStore)
        var runtime = newTripRuntime(context, tripStore, elapsed, completions)
        val bootId = "t19-test-boot"
        val telegramConfig = TelegramEventConfig(
            enabledEvents = setOf(TelegramEventType.TRIP_SUMMARY),
            chargeStepPercent = 5,
            lowVoltageThreshold = 12.0,
            unavailableDelayMs = 60_000L,
            tripEndDelayMs = 2_000L,
            sendLocation = true,
            navigatorMask = TelegramNavigatorMask.GOOGLE
        )
        var telegram = drivingTelegramEngine(telegramConfig)

        try {
            submitTripPoll(runtime, energy, elapsed, bootId, 0L, powerOn = false,
                tripTelemetry(soc = 72.0, odometer = 100.0, tripEnergy = 0.5), voltage = 400.0, current = 0.0)
            assertTrue(tripStore.openSessions().isEmpty())

            submitTripPoll(runtime, energy, elapsed, bootId, 1_000L, powerOn = true,
                tripTelemetry(soc = 72.0, odometer = 100.0, tripEnergy = 0.5), voltage = 400.0, current = 25.0)
            val tripId = assertNotNull(tripStore.loadOpenSession()).tripId
            val firstDrive = telegram.onSuccessfulPoll(
                tripTelemetry(gear = "D", soc = 71.0, odometer = 102.0, tripEnergy = 1.5),
                telegramConfig, 2_000L
            )
            assertTrue(firstDrive.events.isEmpty())
            submitTripPoll(runtime, energy, elapsed, bootId, 2_000L, powerOn = true,
                tripTelemetry(gear = "D", soc = 71.0, odometer = 102.0, tripEnergy = 1.5), voltage = 400.0, current = 25.0)

            // A short park is checkpointed in Telegram SQLite before both owners are recreated.
            telegram.onSuccessfulPoll(
                tripTelemetry(gear = "P", soc = 71.0, odometer = 102.0, tripEnergy = 1.5),
                telegramConfig, 3_000L
            )
            val shortPark = telegram.onSuccessfulPoll(
                tripTelemetry(gear = "P", soc = 71.0, odometer = 102.0, tripEnergy = 1.5),
                telegramConfig, 3_500L
            )
            val telegramTripId = assertNotNull(shortPark.state.tripId)
            assertNotNull(shortPark.state.tripParkedSinceMs)
            telegramStore.saveTelegramRuntimeState(shortPark.state.toJson(), nowMs = 3_500L)
            runtime.close()
            tripStore.close()
            telegramStore.close()

            tripStore = TripStore(TripDatabaseHelper(context, tripName))
            telegramStore = TelegramStore(context, TelegramDatabaseHelper(context, telegramName))
            energy = EnergySessionCoordinator(tripStore)
            runtime = newTripRuntime(context, tripStore, elapsed, completions)
            telegram = TelegramEventEngine(
                TelegramEventState.fromJson(requireNotNull(telegramStore.telegramRuntimeState()))
            )
            val recovered = telegram.recoverPendingTrip(telegramConfig, 4_000L)
            assertTrue(recovered.events.isEmpty(), "the short P must not finish the drive on recovery")
            assertEquals(telegramTripId, recovered.state.tripId)
            assertNotNull(recovered.state.tripParkedSinceMs)

            // Restart begins with power still ON; B→C continues the same durable power trip.
            submitTripPoll(runtime, energy, elapsed, bootId, 4_000L, powerOn = true,
                tripTelemetry(gear = "D", soc = 71.0, odometer = 102.0, tripEnergy = 1.5), voltage = 400.0, current = 25.0)
            telegram.onSuccessfulPoll(
                tripTelemetry(gear = "D", soc = 70.0, odometer = 104.0, tripEnergy = 2.0),
                telegramConfig, 4_500L
            )
            submitTripPoll(runtime, energy, elapsed, bootId, 5_000L, powerOn = true,
                tripTelemetry(gear = "D", soc = 70.0, odometer = 106.0, tripEnergy = 2.5), voltage = 400.0, current = 25.0)
            val resumedDrive = telegram.onSuccessfulPoll(
                tripTelemetry(gear = "D", soc = 70.0, odometer = 106.0, tripEnergy = 2.5),
                telegramConfig, 5_000L
            )
            assertTrue(resumedDrive.events.isEmpty())
            assertEquals(telegramTripId, resumedDrive.state.tripId, "short P must resume the original drive")
            assertEquals(tripId, tripStore.openSessions().single().tripId)

            // Long P may finish the Telegram drive, but it is not a vehicle power-off.
            submitTripPoll(runtime, energy, elapsed, bootId, 6_000L, powerOn = true,
                tripTelemetry(gear = "P", soc = 70.0, odometer = 106.0, tripEnergy = 2.5), voltage = 400.0, current = 25.0)
            telegram.onSuccessfulPoll(
                tripTelemetry(gear = "P", soc = 70.0, odometer = 106.0, tripEnergy = 2.5),
                telegramConfig, 6_000L
            )
            submitTripPoll(runtime, energy, elapsed, bootId, 6_500L, powerOn = true,
                tripTelemetry(gear = "P", soc = 70.0, odometer = 106.0, tripEnergy = 2.5), voltage = 400.0, current = 25.0)
            telegram.onSuccessfulPoll(
                tripTelemetry(gear = "P", soc = 70.0, odometer = 106.0, tripEnergy = 2.5),
                telegramConfig, 6_500L
            )
            submitTripPoll(runtime, energy, elapsed, bootId, 7_500L, powerOn = true,
                tripTelemetry(gear = "P", soc = 70.0, odometer = 106.0, tripEnergy = 2.5), voltage = 400.0, current = 25.0)
            submitTripPoll(runtime, energy, elapsed, bootId, 8_500L, powerOn = true,
                tripTelemetry(gear = "P", soc = 70.0, odometer = 106.0, tripEnergy = 2.5), voltage = 400.0, current = 25.0)
            val longPark = telegram.onSuccessfulPoll(
                tripTelemetry(gear = "P", soc = 70.0, odometer = 106.0, tripEnergy = 2.5),
                telegramConfig, 8_500L
            )
            val summary = longPark.events.single { it.type == TelegramEventType.TRIP_SUMMARY }
            assertEquals("$telegramTripId:summary", summary.dedupeKey)
            assertMetric(summary, "trip_distance_km", 6.0)
            assertMetric(summary, "trip_energy_kwh", 2.0)
            assertMetric(summary, "total_distance_km", 6.0)
            assertMetric(summary, "total_energy_kwh", 2.0)
            assertMetric(summary, "soc_start", 72.0)
            assertMetric(summary, "soc_end", 70.0)
            assertMetric(summary, "total_soc_start", 72.0)
            assertMetric(summary, "total_soc_end", 70.0)
            assertEquals(tripId, tripStore.openSessions().single().tripId)

            val finalEnergy = submitTripPoll(runtime, energy, elapsed, bootId, 9_500L, powerOn = false,
                tripTelemetry(gear = "P", soc = 68.0, odometer = 106.0, tripEnergy = 2.5), voltage = 400.0, current = 25.0)
            drain(runtime)
            assertTrue(tripStore.openSessions().isEmpty())
            val completion = tripStore.pendingCompletions(tripStore.completionWatermark()).single()
            val closed = requireNotNull(completion.session)
            assertEquals(tripId, closed.tripId)
            assertEquals(TripSession.STATE_CLOSED, closed.state)
            assertEquals(72.0, closed.startSoc)
            assertEquals(68.0, closed.endSoc)
            assertEquals(6.0, closed.distanceKm)
            assertEquals(2.0, closed.energyKwh)
            assertTrue(requireNotNull(closed.netKwh) > 0.0)
            assertEquals(0L, closed.energyUncoveredMs)
            assertEquals(false, closed.energyPartial)
            assertEquals(finalEnergy.netKwh, closed.netKwh)
            assertEquals(null, completion.lastLocation, "power-off completion must not wait for GPS")
            assertEquals(1, completions.size)

            val powerOffTelegram = telegram.onPowerOffConfirmed(
                telegramConfig,
                TelegramPowerOffSnapshot(106.0, 68.0, 2.5, finalEnergy),
                location = null,
                nowMs = 9_500L
            )
            assertTrue(powerOffTelegram.events.isEmpty(), "the long-P summary already finished without GPS")
            telegramStore.saveTelegramRuntimeState(powerOffTelegram.state.toJson(), nowMs = 9_500L)
            assertTrue(telegramStore.verifyRuntimeState())
        } finally {
            runtime.close()
            tripStore.close()
            telegramStore.close()
        }
    }

    private fun newTripRuntime(
        context: android.content.Context,
        store: TripStore,
        elapsed: AtomicLong,
        completions: MutableList<Long>
    ) = TripRuntimeCoordinator(
        context = context,
        tripStore = store,
        historyEnabled = { true },
        locationCaptureEnabled = { false },
        persistLocation = {},
        completionEnabled = { true },
        onCompletionReady = { completions += it },
        recordEvent = { _, _, _ -> },
        elapsedRealtimeMs = elapsed::get,
        bootIdProvider = { "t19-test-boot" }
    )

    private fun submitTripPoll(
        runtime: TripRuntimeCoordinator,
        energy: EnergySessionCoordinator,
        elapsed: AtomicLong,
        bootId: String,
        atMs: Long,
        powerOn: Boolean,
        observations: List<NormalizedObservation>,
        voltage: Double?,
        current: Double?
    ) = run {
        elapsed.set(atMs)
        val observedAt = Instant.parse("2026-10-11T10:00:00Z").plusMillis(atMs).toString()
        val receipt = energy.process(EnergyReceipt(
            sourceIdentity = "t19:$bootId:$atMs",
            observedAt = observedAt,
            input = EnergyInput(
                bootId = bootId,
                elapsedMs = atMs,
                voltage = voltage,
                current = current,
                powerOn = powerOn,
                gunDisconnected = true,
                externalCharging = false
            )
        ))
        val snapshot = requireNotNull(receipt.snapshot)
        runtime.onSuccessfulPoll(
            timestamp = observedAt,
            readings = listOf(PollReading(
                rawKey = "bodywork_power_level",
                rawValue = if (powerOn) "1" else "0",
                descValue = if (powerOn) "1" else "0"
            )),
            observations = observations,
            liveTelemetry = true,
            energySnapshot = snapshot
        )
        drain(runtime)
        check(energy.confirmProjected(snapshot.snapshotId))
        snapshot
    }

    private fun drain(runtime: TripRuntimeCoordinator) {
        val barrier = CompletableFuture<Unit>()
        runtime.afterPendingTrips { barrier.complete(Unit) }
        barrier.get(5, TimeUnit.SECONDS)
    }

    private fun drivingTelegramEngine(config: TelegramEventConfig) = TelegramEventEngine().also { engine ->
        engine.onSuccessfulPoll(tripTelemetry(gear = "P", soc = 72.0, odometer = 100.0, tripEnergy = 0.5), config, 0L)
        engine.onSuccessfulPoll(tripTelemetry(gear = "D", soc = 72.0, odometer = 100.0, tripEnergy = 0.5), config, 500L)
        engine.onSuccessfulPoll(tripTelemetry(gear = "D", soc = 72.0, odometer = 100.0, tripEnergy = 0.5), config, 1_000L)
    }

    private fun tripTelemetry(
        gear: String? = null,
        soc: Double,
        odometer: Double,
        tripEnergy: Double
    ): List<NormalizedObservation> = buildList {
        add(number(NormalizedFieldCatalog.soc, soc))
        add(number(NormalizedFieldCatalog.odometerKm, odometer))
        add(number(NormalizedFieldCatalog.tripEnergy, tripEnergy))
        if (gear != null) add(observation(
            NormalizedFieldCatalog.gearAutoMode,
            NormalizedValue(NormalizedValueType.TEXT, text = gear)
        ))
        add(observation(
            NormalizedFieldCatalog.chargeGunConnected,
            NormalizedValue(NormalizedValueType.BOOLEAN, bool = false)
        ))
    }

    private fun number(field: NormalizedFieldDefinition, value: Double) = observation(
        field,
        NormalizedValue(NormalizedValueType.NUMBER, number = value)
    )

    private fun observation(field: NormalizedFieldDefinition, value: NormalizedValue) = NormalizedObservation(
        field = field,
        value = value,
        quality = NormalizedQuality.OK,
        sourcePollId = 1L,
        sourceKey = field.sourceKeys.firstOrNull(),
        observedAt = "2026-10-11T10:00:00Z"
    )

    private fun assertMetric(event: TelegramDetectedEvent, key: String, expected: Double) {
        val actual = requireNotNull(event.variables[key]).replace(',', '.').toDouble()
        assertTrue(kotlin.math.abs(actual - expected) < 0.01, "$key expected=$expected actual=$actual")
    }
}
