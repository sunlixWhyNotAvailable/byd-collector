package com.bydcollector.collector.service

import com.bydcollector.collector.data.energy.EnergyInput
import com.bydcollector.collector.data.energy.EnergyReceipt
import com.bydcollector.collector.data.energy.EnergySessionCoordinator
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.local.TelegramDatabaseHelper
import com.bydcollector.collector.data.local.TelegramStore
import com.bydcollector.collector.data.local.TelegramLegacyOutboxRow
import com.bydcollector.collector.data.local.TelegramLegacySnapshot
import com.bydcollector.collector.data.local.TelegramMigrationResult
import com.bydcollector.collector.data.local.TelegramOutboxMessage
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
import com.bydcollector.collector.telegram.TelegramPayloadLimitState
import com.bydcollector.collector.telegram.TelegramTemplateLanguage
import com.bydcollector.collector.telegram.renderTelegramPayload
import org.json.JSONObject
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramMidTripUpgradeReproductionTest {
    @Test
    fun tripLocationRenderingKeepsItsBoundaryAndLengthContracts() {
        val links = "Google: https://maps.example/?q=49,24\nWaze: https://waze.example/?ll=49,24"
        val event = TelegramDetectedEvent(
            type = TelegramEventType.TRIP_SUMMARY,
            dedupeKey = "trip:summary",
            variables = emptyMap(),
            textSuffix = "\n$links"
        )
        val template = "Report\r\n\r\n\n"

        assertEquals(
            "Report\n\n$links",
            renderTelegramPayload(event, template, TelegramTemplateLanguage.EN).text
        )
        assertEquals(
            template,
            renderTelegramPayload(event.copy(textSuffix = null), template, TelegramTemplateLanguage.EN).text
        )
        assertEquals(
            links,
            renderTelegramPayload(
                event.copy(textSuffix = links, locationOnly = true),
                null,
                TelegramTemplateLanguage.EN
            ).text
        )

        val atLimitTemplate = "R".repeat(4_093)
        val atLimit = renderTelegramPayload(event.copy(textSuffix = "\nA"), atLimitTemplate, TelegramTemplateLanguage.EN)
        assertEquals("$atLimitTemplate\n\nA", atLimit.text)
        assertEquals(TelegramPayloadLimitState.NONE, atLimit.limitState)

        val overLimitTemplate = "R".repeat(4_094)
        val overLimit = renderTelegramPayload(event.copy(textSuffix = "\nA"), overLimitTemplate, TelegramTemplateLanguage.EN)
        assertEquals(overLimitTemplate, overLimit.text)
        assertEquals(TelegramPayloadLimitState.WITH_LOCATION, overLimit.limitState)
    }

    @Test fun pendingSurvivesClockJumpButOverflowRemainsBounded() {
        val context = RuntimeEnvironment.getApplication()
        val name = "telegram-clock.db"
        var helper = TelegramDatabaseHelper(context, name)
        var store = TelegramStore(context, helper)
        val created = 1_780_000_000_000L
        val future = created + 60L * 24 * 60 * 60 * 1_000
        try {
            // Older persisted queue input, not a substituted network delivery outcome.
            val oldQueue = TelegramLegacySnapshot(outbox = listOf(
                TelegramLegacyOutboxRow(1, "untouched", "trip_summary", "first", created, created, 0, null, null, false),
                TelegramLegacyOutboxRow(2, "attempted", "trip_summary", "second", created, created + 30_000, 1, created, null, false)
            ))
            assertEquals(TelegramMigrationResult.Status.COMMITTED, store.importLegacySnapshot(oldQueue, created).status)
            store.pruneTelegramDeliveryReceipts(future)
            store.commitTelegramEvents(listOf(TelegramOutboxMessage("new", "trip_summary", "third")), null, future)
            assertNotNull(store.telegramMessageByDedupeKey("untouched"))
            assertEquals(1, store.telegramMessageByDedupeKey("attempted")?.attemptCount)
            store.close()
            helper = TelegramDatabaseHelper(context, name)
            store = TelegramStore(context, helper)
            assertEquals("untouched", store.oldestDueTelegramMessage(future, null)?.dedupeKey)
            assertEquals(1, store.telegramMessageByDedupeKey("attempted")?.attemptCount)

            val inFlight = assertNotNull(store.telegramMessageByDedupeKey("untouched")).id
            val results = store.commitTelegramEvents((1..1_000).map {
                TelegramOutboxMessage("overflow-$it", "trip_summary", "message $it")
            }, null, future, protectedTelegramIds = setOf(inFlight))
            assertEquals(0, results.sumOf { it.expiredCount })
            assertEquals(3, results.sumOf { it.overflowCount })
            assertNotNull(store.telegramMessageByDedupeKey("untouched"), "in-flight row is protected during overflow")
            assertNull(store.telegramMessageByDedupeKey("attempted"), "overflow still removes the oldest unprotected row")
            helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM telegram_outbox", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(1_000, it.getInt(0))
            }
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun legacyTripSummaryMarkerSurvivesSqliteRecoveryUntilPowerOff() {
        val context = RuntimeEnvironment.getApplication()
        val name = "trip-summary-marker.db"
        var helper = TelegramDatabaseHelper(context, name)
        var store = TelegramStore(context, helper)
        val config = TelegramEventConfig(
            enabledEvents = setOf(TelegramEventType.TRIP_SUMMARY),
            chargeStepPercent = 5,
            lowVoltageThreshold = 12.0,
            unavailableDelayMs = 60_000L,
            tripEndDelayMs = 1_000L,
            sendLocation = true,
            navigatorMask = TelegramNavigatorMask.GOOGLE,
            language = TelegramTemplateLanguage.EN
        )
        val engine = drivingTelegramEngine(config)
        try {
            engine.onSuccessfulPoll(
                tripTelemetry(gear = "D", soc = 71.0, odometer = 100.0, tripEnergy = 0.5), config, 2_000L
            )
            engine.onSuccessfulPoll(
                tripTelemetry(gear = "P", soc = 71.0, odometer = 100.0, tripEnergy = 0.5), config, 3_000L
            )
            val parked = engine.onSuccessfulPoll(
                tripTelemetry(gear = "P", soc = 71.0, odometer = 100.0, tripEnergy = 0.5), config, 4_000L
            )
            assertTrue(parked.events.none { it.type == TelegramEventType.TRIP_SUMMARY })
            val legacyBeforeFirst = JSONObject(parked.state.toJson()).apply {
                remove("tripSummaryCreatedInPowerSession")
            }.toString()
            assertFalse(TelegramEventState.fromJson(legacyBeforeFirst).tripSummaryCreatedInPowerSession)
            val summaryResult = engine.onTick(config, mainCollectionExpected = true, lastError = null, nowMs = 5_000L)
            val summary = summaryResult.events.single { it.type == TelegramEventType.TRIP_SUMMARY }
            assertTrue(summary.omitCurrent)
            assertMetric(summary, "trip_distance_km", 0.0)
            assertMetric(summary, "total_distance_km", 0.0)
            assertMetric(summary, "trip_energy_kwh", 0.0)
            assertMetric(summary, "total_energy_kwh", 0.0)
            assertNotNull(summaryResult.state.pendingPowerOffLocationTripId)
            assertTrue(summaryResult.state.hasDeferredStorageWork())

            val legacyJson = JSONObject(summaryResult.state.toJson()).apply {
                remove("tripSummaryCreatedInPowerSession")
            }.toString()
            val migratedState = TelegramEventState.fromJson(legacyJson)
            assertTrue(migratedState.tripSummaryCreatedInPowerSession)
            val firstText = assertNotNull(renderTelegramPayload(summary, null, config.language).text)
            assertFalse(firstText.contains("📊 Current statistics."))
            assertTrue(firstText.contains("📈 Total statistics."))

            val payload = assertNotNull(renderTelegramPayload(summary, null, config.language).text)
            val enqueued = store.commitTelegramEvents(
                listOf(TelegramOutboxMessage(summary.dedupeKey, summary.type.key, payload)),
                migratedState.toJson(),
                nowMs = 5_000L
            )
            assertTrue(enqueued.single().inserted)
            assertNotNull(store.telegramMessageByDedupeKey(summary.dedupeKey))

            store.close()
            helper = TelegramDatabaseHelper(context, name)
            store = TelegramStore(context, helper)
            val recovered = TelegramEventEngine(
                TelegramEventState.fromJson(requireNotNull(store.telegramRuntimeState()))
            )
            assertTrue(recovered.state.tripSummaryCreatedInPowerSession)
            assertNotNull(store.telegramMessageByDedupeKey(summary.dedupeKey), "summary remains queued across restart")
            assertTrue(recovered.recoverPendingTrip(config, 6_000L).state.tripSummaryCreatedInPowerSession)

            recovered.onSuccessfulPoll(
                tripTelemetry(gear = "D", soc = 72.0, odometer = 100.0, tripEnergy = 0.5), config, 6_000L
            )
            recovered.onSuccessfulPoll(
                tripTelemetry(gear = "D", soc = 72.0, odometer = 100.0, tripEnergy = 0.5), config, 6_500L
            )
            recovered.onSuccessfulPoll(
                tripTelemetry(gear = "D", soc = 71.0, odometer = 102.0, tripEnergy = 1.5), config, 7_000L
            )
            recovered.onSuccessfulPoll(
                tripTelemetry(gear = "P", soc = 71.0, odometer = 102.0, tripEnergy = 1.5), config, 7_500L
            )
            recovered.onSuccessfulPoll(
                tripTelemetry(gear = "P", soc = 71.0, odometer = 102.0, tripEnergy = 1.5), config, 8_000L
            )
            val secondResult = recovered.onTick(
                config, mainCollectionExpected = true, lastError = null, nowMs = 9_000L
            )
            val secondSummary = secondResult.events.single { it.type == TelegramEventType.TRIP_SUMMARY }
            assertFalse(secondSummary.omitCurrent)
            assertMetric(secondSummary, "trip_distance_km", 2.0)
            assertMetric(secondSummary, "total_distance_km", 2.0)
            assertMetric(secondSummary, "trip_energy_kwh", 1.0)
            assertMetric(secondSummary, "total_energy_kwh", 1.0)
            listOf(
                "trip_distance_km" to "total_distance_km",
                "trip_energy_kwh" to "total_energy_kwh",
                "trip_avg_kwh_per_100km" to "total_avg_kwh_per_100km",
                "trip_duration" to "total_duration",
                "trip_start_time" to "total_start_time",
                "trip_end_time" to "total_end_time",
                "soc_start" to "total_soc_start",
                "soc_end" to "total_soc_end",
                "trip_discharged_kwh" to "total_discharged_kwh",
                "trip_regenerated_kwh" to "total_regenerated_kwh",
                "trip_net_kwh" to "total_net_kwh",
                "trip_net_kwh_per_100km" to "total_net_kwh_per_100km"
            ).forEach { (current, total) ->
                assertEquals(secondSummary.variables[current], secondSummary.variables[total], "$current == $total")
            }
            val secondText = assertNotNull(renderTelegramPayload(secondSummary, null, config.language).text)
            assertTrue(secondText.contains("📊 Current statistics."))
            assertTrue(secondText.contains("📈 Total statistics."))
            assertTrue(secondResult.state.tripSummaryCreatedInPowerSession)

            val secondPayload = assertNotNull(renderTelegramPayload(secondSummary, null, config.language).text)
            assertTrue(store.commitTelegramEvents(
                listOf(TelegramOutboxMessage(secondSummary.dedupeKey, secondSummary.type.key, secondPayload)),
                secondResult.state.toJson(),
                nowMs = 9_000L
            ).single().inserted)
            assertNotNull(store.telegramMessageByDedupeKey(summary.dedupeKey), "first summary remains queued")
            assertNotNull(store.telegramMessageByDedupeKey(secondSummary.dedupeKey), "second summary is queued")

            store.close()
            helper = TelegramDatabaseHelper(context, name)
            store = TelegramStore(context, helper)
            val afterSecondRestart = TelegramEventEngine(
                TelegramEventState.fromJson(requireNotNull(store.telegramRuntimeState()))
            )
            assertTrue(afterSecondRestart.state.tripSummaryCreatedInPowerSession)
            assertNotNull(store.telegramMessageByDedupeKey(summary.dedupeKey))
            assertNotNull(store.telegramMessageByDedupeKey(secondSummary.dedupeKey))

            val location = TelegramLocationSnapshot(
                latitude = 49.0,
                longitude = 24.0,
                capturedAt = "2026-10-11T10:00:09Z",
                ageSeconds = 0L,
                osmUrl = "https://osm.example/?mlat=49&mlon=24",
                googleUrl = "https://maps.example/?q=49,24",
                appleUrl = "https://maps.apple.example/?q=49,24"
            )
            val powerOff = afterSecondRestart.onPowerOffConfirmed(config, location = location, nowMs = 10_000L)
            val locationEvent = powerOff.events.single { it.type == TelegramEventType.TRIP_SUMMARY }
            assertTrue(locationEvent.locationOnly)
            assertEquals(secondSummary.dedupeKey, locationEvent.waitsForSummaryKey)
            assertFalse(powerOff.state.tripSummaryCreatedInPowerSession)
            val locationPayload = assertNotNull(renderTelegramPayload(locationEvent, null, config.language).text)
            assertTrue(store.commitTelegramEvents(
                listOf(TelegramOutboxMessage(locationEvent.dedupeKey, locationEvent.type.key, locationPayload)),
                powerOff.state.toJson(),
                nowMs = 10_000L
            ).single().inserted)
            assertNotNull(store.telegramMessageByDedupeKey(locationEvent.dedupeKey))
            store.close()

            helper = TelegramDatabaseHelper(context, name)
            store = TelegramStore(context, helper)
            val newPowerSession = TelegramEventEngine(
                TelegramEventState.fromJson(requireNotNull(store.telegramRuntimeState()))
            )
            assertFalse(newPowerSession.state.tripSummaryCreatedInPowerSession)
            newPowerSession.onSuccessfulPoll(
                tripTelemetry(gear = "D", soc = 72.0, odometer = 102.0, tripEnergy = 0.5), config, 11_000L
            )
            newPowerSession.onSuccessfulPoll(
                tripTelemetry(gear = "D", soc = 72.0, odometer = 102.0, tripEnergy = 0.5), config, 11_500L
            )
            newPowerSession.onSuccessfulPoll(
                tripTelemetry(gear = "D", soc = 71.0, odometer = 103.0, tripEnergy = 1.5), config, 12_000L
            )
            val newPowerOff = newPowerSession.onPowerOffConfirmed(
                config,
                TelegramPowerOffSnapshot(odometerKm = 103.0, soc = 71.0, tripEnergyKwh = 1.5),
                nowMs = 13_000L
            )
            val newFirstSummary = newPowerOff.events.single { it.type == TelegramEventType.TRIP_SUMMARY }
            assertTrue(newFirstSummary.omitCurrent)
            assertFalse(newPowerOff.state.tripSummaryCreatedInPowerSession)
            val newFirstText = assertNotNull(renderTelegramPayload(newFirstSummary, null, config.language).text)
            assertFalse(newFirstText.contains("📊 Current statistics."))
            assertTrue(newFirstText.contains("📈 Total statistics."))
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

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
            val totalOnlyUk = assertNotNull(
                renderTelegramPayload(summary, null, TelegramTemplateLanguage.UK).text
            )
            assertFalse(totalOnlyUk.contains("📊 Поточна статистика."))
            assertTrue(totalOnlyUk.contains("📈 Загальна статистика."))

            val historic = renderTelegramPayload(
                summary,
                "Trip complete: {trip_distance_km} km, {trip_energy_kwh} kWh at {time}.",
                TelegramTemplateLanguage.EN
            )
            val historicText = assertNotNull(historic.text)
            assertFalse(historicText.contains("📊 Current statistics."))
            assertTrue(historicText.contains("📈 Total statistics."))

            val fallback = renderTelegramPayload(summary, "{invalid", TelegramTemplateLanguage.EN)
            val fallbackText = assertNotNull(fallback.text)
            assertTrue(fallback.usedFallback)
            assertFalse(fallbackText.contains("📊 Current statistics."))
            assertTrue(fallbackText.contains("📈 Total statistics."))
            assertEquals("Custom trip summary", renderTelegramPayload(
                summary, "Custom trip summary", TelegramTemplateLanguage.EN
            ).text)
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

    @Test
    fun firstParkedSummaryIsTotalOnlyAndLaterPowerOffSummaryShowsBothBlocks() {
        val telegramConfig = TelegramEventConfig(
            enabledEvents = setOf(TelegramEventType.TRIP_SUMMARY),
            chargeStepPercent = 5,
            lowVoltageThreshold = 12.0,
            unavailableDelayMs = 60_000L,
            tripEndDelayMs = 2_000L,
            language = TelegramTemplateLanguage.UK
        )
        val engine = drivingTelegramEngine(telegramConfig)
        val firstDrive = tripTelemetry(gear = "D", soc = 71.0, odometer = 102.0, tripEnergy = 1.5)
        engine.onSuccessfulPoll(firstDrive, telegramConfig, 2_000L)
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "D", soc = 70.0, odometer = 106.0, tripEnergy = 2.5),
            telegramConfig,
            5_000L
        )
        val parked = tripTelemetry(gear = "P", soc = 70.0, odometer = 106.0, tripEnergy = 2.5)
        engine.onSuccessfulPoll(parked, telegramConfig, 6_000L)
        engine.onSuccessfulPoll(parked, telegramConfig, 6_500L)
        engine.onSuccessfulPoll(parked, telegramConfig, 7_500L)
        val parkedSummary = engine.onSuccessfulPoll(
            tripTelemetry(gear = "P", soc = 69.0, odometer = 106.0, tripEnergy = 2.5),
            telegramConfig,
            8_500L
        )
            .events.single { it.type == TelegramEventType.TRIP_SUMMARY }
        assertTrue(parkedSummary.omitCurrent)
        assertMetric(parkedSummary, "soc_end", 70.0)
        assertMetric(parkedSummary, "total_soc_end", 69.0)
        val parkedText = assertNotNull(
            renderTelegramPayload(parkedSummary, null, TelegramTemplateLanguage.UK).text
        )
        assertFalse(parkedText.contains("📊 Поточна статистика."))
        assertTrue(parkedText.contains("📈 Загальна статистика."))

        val englishConfig = telegramConfig.copy(language = TelegramTemplateLanguage.EN)
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "D", soc = 70.0, odometer = 106.0, tripEnergy = 2.5),
            englishConfig,
            9_000L
        )
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "D", soc = 69.0, odometer = 107.0, tripEnergy = 3.0),
            englishConfig,
            9_500L
        )
        val powerOff = engine.onPowerOffConfirmed(
            englishConfig,
            TelegramPowerOffSnapshot(odometerKm = 108.0, soc = 68.0, tripEnergyKwh = 3.5),
            nowMs = 10_000L
        )
        val summary = powerOff.events.single { it.type == TelegramEventType.TRIP_SUMMARY }
        assertFalse(summary.omitCurrent)
        assertMetric(summary, "trip_distance_km", 1.0)
        assertMetric(summary, "total_distance_km", 7.0)
        val rendered = assertNotNull(renderTelegramPayload(summary, null, TelegramTemplateLanguage.EN).text)
        assertTrue(rendered.contains("📊 Current statistics."))
        assertTrue(rendered.contains("📈 Total statistics."))
    }

    @Test
    fun filteredAndDisabledTripsDoNotConsumeFirstSummaryInPowerSession() {
        val enabledConfig = TelegramEventConfig(
            enabledEvents = setOf(TelegramEventType.TRIP_SUMMARY),
            chargeStepPercent = 5,
            lowVoltageThreshold = 12.0,
            unavailableDelayMs = 60_000L,
            tripEndDelayMs = 1_000L
        )
        val disabledConfig = enabledConfig.copy(enabledEvents = emptySet())
        val engine = drivingTelegramEngine(enabledConfig)

        engine.onSuccessfulPoll(
            tripTelemetry(gear = "D", soc = 72.0, odometer = 100.0, tripEnergy = 0.5), enabledConfig, 2_000L
        )
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "P", soc = 72.0, odometer = 100.0, tripEnergy = 0.5), enabledConfig, 2_500L
        )
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "P", soc = 72.0, odometer = 100.0, tripEnergy = 0.5), enabledConfig, 3_000L
        )
        val filtered = engine.onTick(
            enabledConfig, mainCollectionExpected = true, lastError = null, nowMs = 4_000L
        )
        assertTrue(filtered.events.none { it.type == TelegramEventType.TRIP_SUMMARY })
        assertFalse(filtered.state.tripSummaryCreatedInPowerSession)

        engine.onSuccessfulPoll(
            tripTelemetry(gear = "D", soc = 72.0, odometer = 100.0, tripEnergy = 0.5), disabledConfig, 5_000L
        )
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "D", soc = 72.0, odometer = 100.0, tripEnergy = 0.5), disabledConfig, 5_500L
        )
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "D", soc = 71.0, odometer = 102.0, tripEnergy = 1.5), disabledConfig, 6_000L
        )
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "P", soc = 71.0, odometer = 102.0, tripEnergy = 1.5), disabledConfig, 6_500L
        )
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "P", soc = 71.0, odometer = 102.0, tripEnergy = 1.5), disabledConfig, 7_000L
        )
        val disabled = engine.onTick(
            disabledConfig, mainCollectionExpected = true, lastError = null, nowMs = 8_000L
        )
        assertTrue(disabled.events.none { it.type == TelegramEventType.TRIP_SUMMARY })
        assertFalse(disabled.state.tripSummaryCreatedInPowerSession)

        engine.onSuccessfulPoll(
            tripTelemetry(gear = "D", soc = 71.0, odometer = 102.0, tripEnergy = 1.5), enabledConfig, 9_000L
        )
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "D", soc = 71.0, odometer = 102.0, tripEnergy = 1.5), enabledConfig, 9_500L
        )
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "D", soc = 70.0, odometer = 104.0, tripEnergy = 2.5), enabledConfig, 10_000L
        )
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "P", soc = 70.0, odometer = 104.0, tripEnergy = 2.5), enabledConfig, 10_500L
        )
        engine.onSuccessfulPoll(
            tripTelemetry(gear = "P", soc = 70.0, odometer = 104.0, tripEnergy = 2.5), enabledConfig, 11_000L
        )
        val firstEmittedSummary = engine.onTick(
            enabledConfig, mainCollectionExpected = true, lastError = null, nowMs = 12_000L
        ).events.single { it.type == TelegramEventType.TRIP_SUMMARY }
        assertTrue(firstEmittedSummary.omitCurrent)
        assertMetric(firstEmittedSummary, "trip_distance_km", 2.0)
        assertMetric(firstEmittedSummary, "total_distance_km", 4.0)
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
