package com.bydcollector.collector.data.energy

import android.app.Application
import com.bydcollector.collector.data.input.BusinessInputKind
import com.bydcollector.collector.data.input.BusinessInputStore
import com.bydcollector.collector.data.input.BusinessInputTimerSource
import com.bydcollector.collector.data.local.PersistedPollInput
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.data.trips.HistoricalEnergyBackfillRecord
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.data.trips.TripSession
import com.bydcollector.collector.data.trips.TripStore
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class HistoricalBusinessInputPersistenceTest {
    @Test
    fun persistedEventHistory() {
        val context = RuntimeEnvironment.getApplication()
        val mainName = "history.db"
        val tripName = "trips.db"
        var mainHelper = TelemetryDatabaseHelper(context, mainName)
        var mainStore = newTelemetryStore(context, mainHelper)
        var tripStore = TripStore(TripDatabaseHelper(context, tripName))
        try {
            val sessionId = mainStore.openSession()
            val parameters = mainStore.getActiveCatalogParameters()
            val bootId = "history-boot"
            val pollGeneration = "history-poll-generation"
            val inputStore = BusinessInputStore(database = { mainHelper.writableDatabase })
            val businessGeneration = mainStore.businessInputGeneration()
            val initialSeed = mainStore.loadBusinessInputInitialCheckpoint()
            assertTrue(initialSeed.isEmpty())

            persistPoll(
                mainStore, sessionId, parameters, bootId, pollGeneration, sequence = 1L,
                elapsedMs = START_ELAPSED_MS, readings = powerReadings(on = false)
            )
            persistPoll(
                mainStore, sessionId, parameters, bootId, pollGeneration, sequence = 2L,
                elapsedMs = START_ELAPSED_MS, readings = powerOnMeasurements()
            )
            persistTimer(
                mainStore, sessionId, bootId, pollGeneration, sequence = 3L,
                elapsedMs = END_ELAPSED_MS
            )
            persistPoll(
                mainStore, sessionId, parameters, bootId, pollGeneration, sequence = 4L,
                elapsedMs = END_ELAPSED_MS, readings = powerReadings(on = false)
            )

            completePendingInputs(mainStore)
            persistPoll(
                mainStore, sessionId, parameters, bootId, pollGeneration, sequence = 5L,
                elapsedMs = END_ELAPSED_MS + 1_000L,
                readings = listOf(PollReading(VOLTAGE_KEY, "9990", "999.0"))
            )
            completePendingInputs(mainStore)
            assertEquals("9990", mainStore.loadBusinessInputCheckpoint().getValue(VOLTAGE_KEY).reading.rawValue)

            mainStore.close()
            mainHelper = TelemetryDatabaseHelper(context, mainName)
            mainStore = newTelemetryStore(context, mainHelper)
            val reopenedInputs = BusinessInputStore(database = { mainHelper.writableDatabase })
            assertEquals(businessGeneration, mainStore.businessInputGeneration())
            assertEquals(initialSeed, mainStore.loadBusinessInputInitialCheckpoint())

            val refs = historyPages(
                reopenedInputs, bootId, START_ELAPSED_MS, END_ELAPSED_MS, pageSize = 2
            )
            assertEquals(4, refs.size)
            assertEquals(
                listOf(BusinessInputKind.GET, BusinessInputKind.GET, BusinessInputKind.TIMER, BusinessInputKind.GET),
                refs.map { it.kind }
            )
            assertEquals(listOf(1L, 2L, 3L, 4L), refs.mapNotNull { it.sequence })
            assertTrue(reopenedInputs.hasBusinessInputAt(bootId, START_ELAPSED_MS))
            assertTrue(reopenedInputs.hasBusinessInputAt(bootId, END_ELAPSED_MS))
            assertFalse(refs.any { it.acquiredElapsedMs == END_ELAPSED_MS + 1_000L })

            val candidate = closedTrip(
                tripId = "history-trip",
                bootId = bootId,
                startElapsedMs = START_ELAPSED_MS,
                endElapsedMs = END_ELAPSED_MS
            )
            tripStore.upsertSession(candidate)
            assertTrue(
                tripStore.commitHistoricalEnergyBackfill(
                    HistoricalEnergyBackfillRecord(
                        tripId = candidate.tripId,
                        startedAt = candidate.startedAt,
                        endedAt = requireNotNull(candidate.endedAt),
                        sourceIdentity = "legacy-poll-progress",
                        outcome = "rejected",
                        reason = HistoricalEnergyBackfillRecord.RETRY_REASON,
                        updatedAt = nowIso(),
                        algorithmVersion = HistoricalEnergyBackfillRecord.ALGORITHM_VERSION
                    ),
                    snapshot = null
                )
            )
            assertEquals(
                listOf(candidate.tripId),
                tripStore.historicalBusinessEnergyCandidatePage(null, 8, businessGeneration).map { it.tripId }
            )

            val accumulator = HistoricalBusinessEnergyAccumulator(
                trip = candidate,
                businessGeneration = businessGeneration,
                initialSeed = mainStore.loadBusinessInputInitialCheckpoint()
            )
            refs.forEach { ref -> accumulator.accept(ref, reopenedInputs.loadBusinessInputDelta(ref)) }
            val snapshot = assertIs<HistoricalEnergyResult.Complete>(accumulator.finish()).snapshot
            assertEquals(2_000L, snapshot.energyCoveredMs)
            assertEquals(0L, snapshot.energyUncoveredMs)
            assertFalse(snapshot.energyPartial)
            assertEquals(0.022222222222222223, snapshot.dischargedKwh!!, 1e-12)
            assertEquals(END_ELAPSED_MS, snapshot.sourceElapsedMs)

            val eventRecord = HistoricalEnergyBackfillRecord(
                tripId = candidate.tripId,
                startedAt = candidate.startedAt,
                endedAt = requireNotNull(candidate.endedAt),
                sourceIdentity = businessGeneration,
                outcome = "complete",
                reason = "complete",
                updatedAt = nowIso(),
                algorithmVersion = HistoricalEnergyBackfillRecord.EVENT_ALGORITHM_VERSION
            )
            assertTrue(tripStore.commitHistoricalEnergyBackfill(eventRecord, snapshot))
            assertEquals(snapshot.dischargedKwh, tripStore.session(candidate.tripId)?.dischargedKwh)
            assertFalse(tripStore.commitHistoricalEnergyBackfill(eventRecord, snapshot))

            val rejectedTrip = closedTrip(
                tripId = "generation-scoped-rejection",
                bootId = bootId,
                startElapsedMs = END_ELAPSED_MS + 2_000L,
                endElapsedMs = END_ELAPSED_MS + 3_000L
            )
            tripStore.upsertSession(rejectedTrip)
            assertTrue(
                tripStore.commitHistoricalEnergyBackfill(
                    HistoricalEnergyBackfillRecord(
                        tripId = rejectedTrip.tripId,
                        startedAt = rejectedTrip.startedAt,
                        endedAt = requireNotNull(rejectedTrip.endedAt),
                        sourceIdentity = businessGeneration,
                        outcome = "rejected",
                        reason = "missing_start_bookend",
                        updatedAt = nowIso(),
                        algorithmVersion = HistoricalEnergyBackfillRecord.EVENT_ALGORITHM_VERSION
                    ),
                    snapshot = null
                )
            )
            assertFalse(
                tripStore.historicalBusinessEnergyCandidatePage(null, 8, businessGeneration)
                    .any { it.tripId == rejectedTrip.tripId }
            )
            assertTrue(
                tripStore.historicalBusinessEnergyCandidatePage(null, 8, "new-business-generation")
                    .any { it.tripId == rejectedTrip.tripId }
            )

            val liveEnergyTrip = closedTrip(
                tripId = "live-energy-preserved",
                bootId = bootId,
                startElapsedMs = END_ELAPSED_MS + 4_000L,
                endElapsedMs = END_ELAPSED_MS + 5_000L
            ).copy(
                dischargedKwh = 0.5,
                regeneratedKwh = 0.1,
                netKwh = 0.4,
                energyCoveredMs = 1_000L,
                energyUncoveredMs = 0L,
                energyPartial = false,
                energyObservedAt = nowIso()
            )
            tripStore.upsertSession(liveEnergyTrip)
            val liveRecord = eventRecord.copy(
                tripId = liveEnergyTrip.tripId,
                startedAt = liveEnergyTrip.startedAt,
                endedAt = requireNotNull(liveEnergyTrip.endedAt),
                outcome = "rejected",
                reason = "already_has_live_energy"
            )
            assertFalse(tripStore.commitHistoricalEnergyBackfill(liveRecord, snapshot = null))
            assertEquals(0.5, tripStore.session(liveEnergyTrip.tripId)?.dischargedKwh)

            tripStore.close()
            tripStore = TripStore(TripDatabaseHelper(context, tripName))
            assertEquals(0.5, tripStore.session(liveEnergyTrip.tripId)?.dischargedKwh)
            assertEquals(
                HistoricalEnergyBackfillRecord.EVENT_ALGORITHM_VERSION,
                tripStore.historicalEnergyBackfillRecords().single { it.tripId == candidate.tripId }.algorithmVersion
            )
            assertEquals(initialSeed, mainStore.loadBusinessInputInitialCheckpoint())
            assertEquals("9990", mainStore.loadBusinessInputCheckpoint().getValue(VOLTAGE_KEY).reading.rawValue)
        } finally {
            mainStore.close()
            tripStore.close()
            context.deleteDatabase(mainName)
            context.deleteDatabase(tripName)
        }
    }

    @Test
    fun historyGapsAndTtl() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "history.db"
        val helper = TelemetryDatabaseHelper(context, databaseName)
        val store = newTelemetryStore(context, helper)
        try {
            val sessionId = store.openSession()
            val parameters = store.getActiveCatalogParameters()
            val history = BusinessInputStore(database = { helper.writableDatabase })
            val businessGeneration = store.businessInputGeneration()
            val seed = store.loadBusinessInputInitialCheckpoint()

            val gapBoot = "history-gap-boot"
            val gapGeneration = "history-gap-poll-generation"
            val gapStart = 1_000L
            val gapEnd = gapStart + 2_001L
            persistPoll(
                store, sessionId, parameters, gapBoot, gapGeneration, sequence = 1L,
                elapsedMs = gapStart, readings = powerOnMeasurements()
            )
            persistTimer(store, sessionId, gapBoot, gapGeneration, sequence = 2L, elapsedMs = gapEnd)
            persistPoll(
                store, sessionId, parameters, gapBoot, gapGeneration, sequence = 3L,
                elapsedMs = gapEnd, readings = powerReadings(on = false)
            )
            val gapTrip = closedTrip("gap-trip", gapBoot, gapStart, gapEnd)
            assertIs<HistoricalEnergyResult.Rejected>(reconstruct(history, gapTrip, businessGeneration, seed))

            val ttlBoot = "history-ttl-boot"
            val ttlGeneration = "history-ttl-poll-generation"
            val ttlStart = 100_000L
            val ttlEnd = ttlStart + 30_001L
            persistPoll(
                store, sessionId, parameters, ttlBoot, ttlGeneration, sequence = 1L,
                elapsedMs = ttlStart, readings = powerOnMeasurements()
            )
            var timerSequence = 2L
            for (elapsedMs in (ttlStart + 2_000L)..(ttlStart + 30_000L) step 2_000L) {
                persistTimer(store, sessionId, ttlBoot, ttlGeneration, timerSequence++, elapsedMs)
            }
            persistTimer(store, sessionId, ttlBoot, ttlGeneration, timerSequence, ttlEnd)
            persistPoll(
                store, sessionId, parameters, ttlBoot, ttlGeneration, sequence = timerSequence + 1L,
                elapsedMs = ttlEnd, readings = powerReadings(on = false)
            )
            val ttlTrip = closedTrip("ttl-trip", ttlBoot, ttlStart, ttlEnd)
            assertIs<HistoricalEnergyResult.Rejected>(reconstruct(history, ttlTrip, businessGeneration, seed))
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun newTelemetryStore(
        context: android.content.Context,
        helper: TelemetryDatabaseHelper
    ) = TelemetryStore(
        context = context,
        helper = helper,
        operationalEventJournal = OperationalEventJournal(context)
    )

    private fun persistPoll(
        store: TelemetryStore,
        sessionId: Long,
        parameters: List<com.bydcollector.collector.data.local.CatalogParameter>,
        bootId: String,
        generation: String,
        sequence: Long,
        elapsedMs: Long,
        readings: List<PollReading>
    ) {
        val timestamp = atElapsed(elapsedMs)
        store.insertPoll(
            sessionId,
            PersistedPollInput(
                timestamp = timestamp,
                ok = true,
                elapsedMs = 5L,
                requestCount = readings.size,
                errors = null,
                rawResponseBody = null,
                readings = readings,
                source = PollSampleSource(
                    identity = "poll:$bootId:$generation:$sequence",
                    bootId = bootId,
                    capturedElapsedMs = elapsedMs,
                    generatorId = generation,
                    sequence = sequence
                ),
                origin = PollOrigin.LIVE
            ),
            parameters
        )
    }

    private fun persistTimer(
        store: TelemetryStore,
        sessionId: Long,
        bootId: String,
        generation: String,
        sequence: Long,
        elapsedMs: Long
    ) {
        store.enqueueEvaluation(
            BusinessInputTimerSource(
                identity = "timer:$bootId:$generation:$sequence",
                bootId = bootId,
                generation = generation,
                sequence = sequence,
                wallMs = wallAtElapsed(elapsedMs),
                elapsedMs = elapsedMs
            ),
            sessionId
        )
    }

    private fun completePendingInputs(store: TelemetryStore) {
        while (true) {
            val ref = store.nextPendingBusinessInput() ?: return
            val delta = store.loadBusinessInputDelta(ref)
            assertTrue(store.completeBusinessInput(ref, delta.sourceInputs))
        }
    }

    private fun historyPages(
        store: BusinessInputStore,
        bootId: String,
        startElapsedMs: Long,
        endElapsedMs: Long,
        pageSize: Int
    ) = buildList {
        var afterId = 0L
        while (true) {
            val page = store.businessInputEventHistory(
                bootId, startElapsedMs, endElapsedMs, afterId, pageSize
            )
            if (page.isEmpty()) break
            addAll(page)
            afterId = page.last().id
            if (page.size < pageSize) break
        }
    }

    private fun reconstruct(
        store: BusinessInputStore,
        trip: TripSession,
        businessGeneration: String,
        seed: Map<String, NormalizedSourceInput>
    ): HistoricalEnergyResult {
        val accumulator = HistoricalBusinessEnergyAccumulator(trip, businessGeneration, seed)
        val refs = historyPages(
            store,
            requireNotNull(trip.startBootId),
            requireNotNull(trip.startElapsedMs),
            requireNotNull(trip.endElapsedMs),
            pageSize = 64
        )
        refs.forEach { accumulator.accept(it, store.loadBusinessInputDelta(it)) }
        return accumulator.finish()
    }

    private fun powerOnMeasurements() = listOf(
        PollReading(POWER_KEY, "1", "1"),
        PollReading(VOLTAGE_KEY, "4000", "400.0"),
        PollReading(CURRENT_KEY, "1000", "100.0"),
        PollReading(GUN_KEY, "1", null)
    )

    private fun powerReadings(on: Boolean) = listOf(
        PollReading(POWER_KEY, if (on) "1" else "0", if (on) "1" else "0")
    )

    private fun closedTrip(
        tripId: String,
        bootId: String,
        startElapsedMs: Long,
        endElapsedMs: Long
    ) = TripSession(
        tripId = tripId,
        state = TripSession.STATE_CLOSED,
        startedAt = atElapsed(startElapsedMs),
        endedAt = atElapsed(endElapsedMs),
        startElapsedMs = startElapsedMs,
        endElapsedMs = endElapsedMs,
        startBootId = bootId,
        endBootId = bootId,
        startSegmentId = "history-segment",
        endSegmentId = "history-segment",
        movementObserved = true
    )

    private fun atElapsed(elapsedMs: Long): String = Instant.ofEpochMilli(wallAtElapsed(elapsedMs)).toString()

    private fun wallAtElapsed(elapsedMs: Long): Long = BASE_WALL_MS + elapsedMs

    private fun nowIso(): String = Instant.now().toString()

    private companion object {
        const val POWER_KEY = "bodywork_power_level"
        const val VOLTAGE_KEY = "charging_charge_battery_volt"
        const val CURRENT_KEY = "charging_charge_current"
        const val GUN_KEY = "charging_1009_876609586_5"
        const val START_ELAPSED_MS = 1_000L
        const val END_ELAPSED_MS = 3_000L
        val BASE_WALL_MS: Long = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()
    }
}
