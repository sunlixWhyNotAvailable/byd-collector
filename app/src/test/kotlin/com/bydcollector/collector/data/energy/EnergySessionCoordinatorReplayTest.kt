package com.bydcollector.collector.data.energy

import android.app.Application
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.data.trips.TripStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class EnergySessionCoordinatorReplayTest {
    @Test
    fun checkpointCacheFollowsPersistedChanges() {
        val context = RuntimeEnvironment.getApplication()
        val name = "cache.db"
        val store = TripStore(TripDatabaseHelper(context, name))
        try {
            val first = EnergySessionCoordinator(store)
            val second = EnergySessionCoordinator(store)
            confirm(first, first.process(receipt("cache", 1L, 100L, 100L, false)))
            assertFalse(second.runtimeStatus().active)
            val started = first.process(receipt("cache", 2L, 200L, 200L, true))
            assertTrue(second.runtimeStatus().active)
            assertEquals(started.pendingProjection, second.pendingProjection())
            confirm(second, started)
            assertNull(first.pendingProjection())
            val stopped = second.process(receipt("cache", 3L, 300L, 300L, false))
            confirm(second, stopped)
            assertFalse(first.runtimeStatus().active)
            assertEquals(3L, first.runtimeStatus().lastBusinessInputId)
            assertEquals(stopped.snapshot, first.currentSnapshot())
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun businessCursorAndEvaluationClockSurviveActualSqliteReopen() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "energy_replay_${System.nanoTime()}.db"
        val generation = "energy-replay-generation"
        var store: TripStore? = TripStore(TripDatabaseHelper(context, databaseName))
        try {
            var energy = EnergySessionCoordinator(requireNotNull(store))
            val initialOff = energy.process(receipt(generation, 1L, 100L, 500L, false))
            assertEquals(EnergySessionTransition.NONE, initialOff.transition)
            confirm(energy, initialOff)

            val started = energy.process(receipt(generation, 2L, 100L, 1_000L, true))
            assertEquals(EnergySessionTransition.STARTED, started.transition)
            assertEquals(1_000L, started.snapshot?.sourceElapsedMs)
            assertFalse(requireNotNull(started.snapshot).energyPartial)
            confirm(energy, started)

            val evaluated = energy.process(receipt(generation, 3L, 100L, 2_000L, true))
            val evaluatedSnapshot = assertNotNull(evaluated.snapshot)
            assertEquals(2_000L, evaluatedSnapshot.sourceElapsedMs)
            assertEquals(1_000L, evaluatedSnapshot.energyCoveredMs)
            assertFalse(evaluatedSnapshot.energyPartial)
            confirm(energy, evaluated)

            store?.close()
            store = TripStore(TripDatabaseHelper(context, databaseName))
            energy = EnergySessionCoordinator(requireNotNull(store))

            val prefix = energy.process(receipt(generation, 1L, 50L, 2_100L, false))
            assertTrue(prefix.stale)
            assertNull(prefix.snapshot)
            assertTrue(energy.runtimeStatus().active)

            val repeat = energy.process(receipt(generation, 3L, 100L, 2_000L, true))
            assertTrue(repeat.duplicate)
            assertEquals(evaluatedSnapshot, repeat.snapshot)
            confirm(energy, repeat)

            val sameMillisecond = energy.process(receipt(generation, 4L, 100L, 2_000L, true, current = 200.0))
            val sameMillisecondSnapshot = assertNotNull(sameMillisecond.snapshot)
            assertEquals(2_000L, sameMillisecondSnapshot.sourceElapsedMs)
            assertEquals(1_000L, sameMillisecondSnapshot.energyCoveredMs)
            assertFalse(sameMillisecondSnapshot.energyPartial)
            confirm(energy, sameMillisecond)

            val stopped = energy.process(
                receipt(generation, 5L, 50L, 2_000L, false, lateRelevantSource = true)
            )
            val stoppedSnapshot = assertNotNull(stopped.snapshot)
            assertEquals(EnergySessionTransition.STOPPED, stopped.transition)
            assertFalse(stoppedSnapshot.active)
            assertEquals(2_000L, stoppedSnapshot.sourceElapsedMs)
            assertTrue(stoppedSnapshot.energyPartial)
            confirm(energy, stopped)

            val row = assertNotNull(store?.readEnergyRuntimeRow())
            val state = EnergyStateCodec.decodeState(row.stateJson)
            assertEquals(50L, state.lastSourceElapsedMs)
            assertEquals(2_000L, state.lastEvaluationElapsedMs)
            assertEquals(5L, state.lastBusinessInputId)

            store?.close()
            store = TripStore(TripDatabaseHelper(context, databaseName))
            energy = EnergySessionCoordinator(requireNotNull(store))
            val olderPrefix = energy.process(receipt(generation, 4L, 100L, 2_500L, true))
            assertTrue(olderPrefix.stale)
            assertNull(olderPrefix.snapshot)
            val repeatedStop = energy.process(
                receipt(generation, 5L, 50L, 2_000L, false, lateRelevantSource = true)
            )
            assertTrue(repeatedStop.duplicate)
            assertEquals(stoppedSnapshot, repeatedStop.snapshot)
            confirm(energy, repeatedStop)
            assertFalse(energy.runtimeStatus().active)
            assertEquals(5L, energy.runtimeStatus().lastBusinessInputId)
        } finally {
            store?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun receipt(
        generation: String,
        inputId: Long,
        sourceElapsedMs: Long,
        evaluationElapsedMs: Long,
        powerOn: Boolean,
        current: Double = 100.0,
        lateRelevantSource: Boolean = false
    ) = EnergyReceipt(
        sourceIdentity = EnergyReceipt.businessIdentity(generation, inputId),
        observedAt = "2026-10-26T10:00:00Z",
        input = EnergyInput(
            bootId = "energy-replay-boot",
            elapsedMs = sourceElapsedMs,
            voltage = 400.0,
            current = current,
            powerOn = powerOn,
            gunDisconnected = true,
            externalCharging = false
        ),
        businessGeneration = generation,
        businessInputId = inputId,
        evaluationBootId = "energy-replay-boot",
        evaluationElapsedMs = evaluationElapsedMs,
        lateRelevantSource = lateRelevantSource
    )

    private fun confirm(coordinator: EnergySessionCoordinator, result: EnergyProcessResult) {
        result.pendingProjection?.let { assertTrue(coordinator.confirmProjected(it.snapshot.snapshotId)) }
    }
}
