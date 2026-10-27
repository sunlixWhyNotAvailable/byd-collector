package com.bydcollector.collector.data.trips

import android.app.Application
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

    private fun stamp(identity: String, elapsedMs: Long, timestamp: String) = NormalizedSourceStamp(
        kind = NormalizedSourceKind.POLL,
        identity = identity,
        bootId = "business-test-boot",
        generatorId = "test",
        sequence = elapsedMs,
        wallMs = java.time.Instant.parse(timestamp).toEpochMilli(),
        elapsedMs = elapsedMs
    )
}
