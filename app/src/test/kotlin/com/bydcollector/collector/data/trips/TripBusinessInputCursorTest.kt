package com.bydcollector.collector.data.trips

import android.app.Application
import com.bydcollector.collector.data.energy.*
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.service.TripRuntimeCoordinator
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class TripBusinessInputCursorTest {
    @Test
    fun cursorAndTripWritesCommitTogetherAndReplaysAreBounded() {
        val context = RuntimeEnvironment.getApplication()
        val name = "trip_business_cursor_atomic_${System.nanoTime()}.db"
        var store = TripStore(TripDatabaseHelper(context, name))
        try {
            val applied = store.consumeBusinessInput("generation-a", 1L, "source-1") { prior ->
                assertEquals(0L, prior)
                store.upsertSession(TripSession(tripId = "trip-1", startedAt = "2026-10-26T10:00:00Z"))
                TripBusinessInputMutation("trip-1", "trip-1")
            }
            assertTrue(applied.applied)
            assertEquals(0L, applied.priorCompletionWatermark)
            assertEquals("trip-1", applied.powerSessionId)

            store.close()
            store = TripStore(TripDatabaseHelper(context, name))
            val replay = store.consumeBusinessInput("generation-a", 1L, "source-1") {
                store.upsertSession(TripSession(tripId = "duplicate-trip", startedAt = "2026-10-26T10:00:00Z"))
                TripBusinessInputMutation(Unit, "duplicate-trip")
            }
            assertFalse(replay.applied)
            assertNull(store.session("duplicate-trip"))
            assertEquals(applied.priorCompletionWatermark, replay.priorCompletionWatermark)
            assertEquals(applied.completionWatermark, replay.completionWatermark)
            assertEquals("trip-1", replay.powerSessionId)

            store.consumeBusinessInput("generation-a", 2L, "source-2") {
                TripBusinessInputMutation(Unit, "trip-1")
            }
            val prefix = assertNotNull(store.businessInputReplay("generation-a", 1L, "source-1"))
            assertEquals(0L, prefix.priorCompletionWatermark)
            assertEquals(0L, prefix.completionWatermark)
            assertNull(prefix.powerSessionId)
            assertFailsWith<IllegalArgumentException> {
                store.businessInputReplay("generation-a", 2L, "changed-source")
            }

            assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
                store.consumeBusinessInput("generation-a", 3L, "source-3") {
                    store.upsertSession(TripSession(tripId = "rolled-back", startedAt = "2026-10-26T10:01:00Z"))
                    TripBusinessInputMutation(Unit, "x".repeat(521))
                }
            }
            assertNull(store.session("rolled-back"))
            assertEquals(2L, store.businessInputCursor()?.inputId)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun awaitedOnOffInputPersistsBoundaryAndExactReplayResultWithoutCallbacks() {
        val context = RuntimeEnvironment.getApplication()
        val name = "trip_business_runtime_${System.nanoTime()}.db"
        val store = TripStore(TripDatabaseHelper(context, name))
        val callbacks = AtomicInteger()
        var runtime: TripRuntimeCoordinator? = null
        try {
            runtime = TripRuntimeCoordinator(
                context = context,
                tripStore = store,
                historyEnabled = { true },
                locationCaptureEnabled = { false },
                persistLocation = {},
                completionEnabled = { true },
                onCompletionReady = { callbacks.incrementAndGet() },
                recordEvent = { _, _, _ -> },
                onConfirmedPowerOn = { callbacks.incrementAndGet() },
                elapsedRealtimeMs = { 9_000L },
                bootIdProvider = { "business-test-boot" }
            )
            val onStamp = stamp("source-on", 1_000L, "2026-10-26T10:00:01Z")
            val on = runtime.onBusinessInput(
                journalGeneration = "generation-a",
                inputId = 1L,
                sourceIdentity = "source-on",
                timestamp = "2026-10-26T10:00:01Z",
                readings = listOf(PollReading("bodywork_power_level", "1", "1")),
                observations = emptyList(),
                liveTelemetry = false,
                eventSourceStamp = onStamp
            ).get(5, TimeUnit.SECONDS)
            assertTrue(on.consumed)
            assertTrue(on.confirmedPowerOn)
            val openTripId = assertNotNull(on.powerSessionId)
            assertEquals(1_000L, store.session(openTripId)?.startElapsedMs)
            assertEquals("business-test-boot", store.session(openTripId)?.startBootId)
            assertEquals(0, callbacks.get(), "Business input callbacks are deferred until ordered Telegram handling")

            assertTrue(runtime.pauseAndAwait("database_maintenance"))
            val offStamp = stamp("source-off", 2_000L, "2026-10-26T10:00:02Z")
            val off = runtime.onBusinessInput(
                journalGeneration = "generation-a",
                inputId = 2L,
                sourceIdentity = "source-off",
                timestamp = "2026-10-26T10:00:02Z",
                readings = listOf(PollReading("bodywork_power_level", "0", "0")),
                observations = emptyList(),
                liveTelemetry = false,
                eventSourceStamp = offStamp,
                allowWhilePaused = true
            ).get(5, TimeUnit.SECONDS)
            assertTrue(off.consumed)
            assertFalse(off.confirmedPowerOn)
            assertEquals(0L, off.priorCompletionWatermark)
            assertEquals(1L, off.completionWatermark)
            assertEquals(openTripId, off.powerSessionId)
            assertEquals(TripSession.STATE_CLOSED, store.session(openTripId)?.state)
            assertEquals(1L, store.completionWatermark())

            runtime.close()
            runtime = null
            store.close()
            val reopened = TripStore(TripDatabaseHelper(context, name))
            try {
                val replayRuntime = TripRuntimeCoordinator(
                    context = context,
                    tripStore = reopened,
                    historyEnabled = { true },
                    locationCaptureEnabled = { false },
                    persistLocation = {},
                    completionEnabled = { true },
                    onCompletionReady = { callbacks.incrementAndGet() },
                    recordEvent = { _, _, _ -> },
                    onConfirmedPowerOn = { callbacks.incrementAndGet() },
                    bootIdProvider = { "business-test-boot" }
                )
                try {
                    val replay = replayRuntime.onBusinessInput(
                        journalGeneration = "generation-a",
                        inputId = 2L,
                        sourceIdentity = "source-off",
                        timestamp = "2026-10-26T10:00:02Z",
                        readings = listOf(PollReading("bodywork_power_level", "0", "0")),
                        observations = emptyList(),
                        liveTelemetry = false,
                        eventSourceStamp = offStamp
                    ).get(5, TimeUnit.SECONDS)
                    assertFalse(replay.consumed)
                    assertFalse(replay.confirmedPowerOn)
                    assertEquals(off.priorCompletionWatermark, replay.priorCompletionWatermark)
                    assertEquals(off.completionWatermark, replay.completionWatermark)
                    assertEquals(off.powerSessionId, replay.powerSessionId)
                    assertEquals(1L, reopened.completionWatermark())
                    assertEquals(0, callbacks.get())
                } finally {
                    replayRuntime.close()
                }
            } finally {
                reopened.close()
            }
        } finally {
            runtime?.close()
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun delayedPowerBoundariesKeepTheComputedEnergySessionAndCompletionAfterReopen() {
        val context = RuntimeEnvironment.getApplication()
        val name = "delayed_power_${System.nanoTime()}.db"
        var expectedSession: String? = null
        var expectedFinal: EnergySnapshot? = null
        try {
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                val energy = EnergySessionCoordinator(store)
                val runtime = TripRuntimeCoordinator(context, store, { true }, { false }, {}, { true }, {},
                    { _, _, _ -> }, bootIdProvider = { "business-test-boot" })
                try {
                    listOf(false, true, false).forEachIndexed { index, on ->
                        val id = index + 1L
                        val acquired = 1_000L + index * 1_000L
                        val evaluated = acquired + 500L
                        val timestamp = java.time.Instant.ofEpochMilli(1_793_000_000_000L + acquired).toString()
                        val result = energy.process(EnergyReceipt(EnergyReceipt.businessIdentity("late", id), timestamp,
                            EnergyInput("business-test-boot", acquired, 400.0, 10.0, on, true, false),
                            businessGeneration = "late", businessInputId = id,
                            evaluationBootId = "business-test-boot", evaluationElapsedMs = evaluated))
                        result.pendingProjection?.let { assertTrue(energy.confirmProjected(it.snapshot.snapshotId)) }
                        val snapshot = assertNotNull(result.snapshot)
                        assertTrue(snapshot.matchesBusinessInput("late", id, "business-test-boot", acquired))
                        assertFalse(snapshot.matchesBusinessInput("late", id + 1, "business-test-boot", acquired))
                        val trip = runtime.onBusinessInput("late", id, "source-$id", timestamp,
                            listOf(PollReading("bodywork_power_level", if (on) "2" else "0", if (on) "2" else "0")),
                            emptyList(), false, snapshot, stamp("source-$id", acquired, timestamp)).get(5, TimeUnit.SECONDS)
                        if (on) {
                            expectedSession = assertNotNull(snapshot.powerSessionId)
                            assertEquals(expectedSession, trip.powerSessionId)
                        }
                        if (index == 2) expectedFinal = snapshot
                    }
                } finally { runtime.close() }
            }
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                val completion = store.allPendingCompletions().last()
                assertEquals(expectedSession, completion.session?.tripId)
                assertEquals(expectedFinal, completion.energySnapshot)
                assertEquals(3_000L, completion.session?.endElapsedMs)
                assertEquals(3_500L, completion.energySnapshot?.sourceElapsedMs)
                assertEquals(3_000L, completion.energySnapshot?.acquiredElapsedMs)
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun stamp(identity: String, elapsedMs: Long, timestamp: String) = NormalizedSourceStamp(
        kind = NormalizedSourceKind.POLL,
        identity = identity,
        bootId = "business-test-boot",
        generatorId = "test",
        sequence = elapsedMs,
        wallMs = java.time.Instant.parse(timestamp).toEpochMilli(),
        elapsedMs = elapsedMs
    )

    @Test fun missingTripBaselinesRemainUnknownAndCoverageSurvivesCompletionReopen() {
        val context = RuntimeEnvironment.getApplication()
        val name = "trip_coverage_${System.nanoTime()}.db"
        val odo = "statistic_1014_1246765072_5"
        val soc = "statistic_1014_1145045040_5"
        val counter = "statistic_statistic_this_trip_total_elec_consumption"
        try {
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                val runtime = TripRuntimeCoordinator(context, store, { true }, { false }, {}, { true }, {},
                    { _, _, _ -> }, bootIdProvider = { "business-test-boot" })
                val normalizer = com.bydcollector.collector.data.normalized.VehicleStateNormalizer()
                val inputs = linkedMapOf<String, com.bydcollector.collector.data.normalized.NormalizedSourceInput>()
                var id = 0L
                fun feed(ms: Long, vararg fields: Pair<String, String>): com.bydcollector.collector.service.TripBusinessResult {
                    id++
                    val at = java.time.Instant.ofEpochMilli(1_793_000_000_000L + ms).toString()
                    val source = stamp("coverage:$id", ms, at)
                    val readings = fields.map { (key, value) -> PollReading(key, value,
                        if (key == counter) Float.fromBits(value.toInt()).toString() else value) }
                    readings.forEach { inputs[it.rawKey] = com.bydcollector.collector.data.normalized.NormalizedSourceInput(it, source, id) }
                    return runtime.onBusinessInput("coverage", id, source.identity, at, readings,
                        normalizer.normalizeSparse(inputs, inputs.keys), false, eventSourceStamp = source).get(5, TimeUnit.SECONDS)
                }
                try {
                    val tripId = assertNotNull(feed(100, "bodywork_power_level" to "2").powerSessionId)
                    feed(500, odo to "1000", soc to "72", counter to 10f.toRawBits().toString())
                    feed(1_000, odo to "1010", counter to 11f.toRawBits().toString())
                    val trip = assertNotNull(store.session(tripId))
                    assertNull(trip.startSoc)
                    assertNull(trip.startOdometerKm)
                    assertNull(trip.distanceKm)
                    assertNull(trip.energyKwh)
                    assertEquals(1.0, assertNotNull(trip.counterCoverage).knownEnergyKwh, 1e-6)
                    feed(2_000, "bodywork_power_level" to "0")
                } finally { runtime.close() }
            }
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                val completion = store.allPendingCompletions().single()
                val trip = assertNotNull(completion.session)
                assertEquals(trip.counterCoverage, store.session(trip.tripId)?.counterCoverage)
                assertEquals(1.0, assertNotNull(trip.counterCoverage).knownDistanceKm, 1e-6)
                assertNull(trip.startSoc)
                assertNull(trip.energyKwh)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionEightSessionsMigrateWithoutInventingCoverage() {
        val context = RuntimeEnvironment.getApplication()
        val name = "trips_v8_${System.nanoTime()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL(requireNotNull(javaClass.getResourceAsStream("/trip-sessions-v8.sql"))
                    .bufferedReader().use { it.readText() })
                db.execSQL("INSERT INTO trip_sessions (trip_id, state, started_at, start_soc, distance_km) " +
                    "VALUES ('legacy', 'closed', '2026-11-10T10:00:00Z', 72, 1.2)")
                db.version = 8
            }
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                assertEquals(9, store.schemaVersion())
                val legacy = assertNotNull(store.session("legacy"))
                assertEquals(72.0, legacy.startSoc)
                assertEquals(1.2, legacy.distanceKm)
                assertNull(legacy.counterCoverage)
                store.upsertSession(legacy.copy(tripId = "new", counterCoverage = TripCounterCoverage()))
            }
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                assertNull(store.session("legacy")?.counterCoverage)
                assertEquals(TripCounterCoverage(), store.session("new")?.counterCoverage)
            }
        } finally { context.deleteDatabase(name) }
    }
}
