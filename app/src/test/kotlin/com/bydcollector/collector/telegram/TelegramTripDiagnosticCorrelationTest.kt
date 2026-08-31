package com.bydcollector.collector.telegram

import com.bydcollector.collector.service.TelegramEventConfig
import com.bydcollector.collector.service.TelegramEventEngine
import com.bydcollector.collector.service.TelegramEventState
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramTripDiagnosticCorrelationTest {
    @Test
    fun completedFutureBeforeRegistrationBindsOnlyWhenQueuedActionRuns() {
        val future = CompletableFuture<String?>().also { it.complete("power-1") }
        val queued = mutableListOf<() -> Unit>()
        val bound = mutableListOf<Pair<String, String>>()

        correlateTripDiagnostic(
            legId = "leg-1",
            powerSession = future,
            isCurrent = { true },
            enqueue = { queued += it },
            bind = { leg, power -> bound += leg to power }
        )

        assertTrue(bound.isEmpty())
        assertEquals(1, queued.size)
        queued.single().invoke()
        assertEquals(listOf("leg-1" to "power-1"), bound)
    }

    @Test
    fun futureCompletionAfterRegistrationQueuesThenBinds() {
        val future = CompletableFuture<String?>()
        val queued = mutableListOf<() -> Unit>()
        val bound = mutableListOf<Pair<String, String>>()

        correlateTripDiagnostic(
            legId = "leg-2",
            powerSession = future,
            isCurrent = { true },
            enqueue = { queued += it },
            bind = { leg, power -> bound += leg to power }
        )
        assertTrue(queued.isEmpty())

        future.complete("power-2")
        assertEquals(1, queued.size)
        queued.single().invoke()
        assertEquals(listOf("leg-2" to "power-2"), bound)
    }

    @Test
    fun nullBlankAndExceptionalParentsAndBlankLegsNeverBind() {
        val queued = mutableListOf<() -> Unit>()
        val bound = mutableListOf<Pair<String, String>>()
        fun register(leg: String, complete: (CompletableFuture<String?>) -> Unit) {
            val future = CompletableFuture<String?>()
            correlateTripDiagnostic(
                legId = leg,
                powerSession = future,
                isCurrent = { true },
                enqueue = { queued += it },
                bind = { linkedLeg, power -> bound += linkedLeg to power }
            )
            complete(future)
        }

        register("leg-null") { it.complete(null) }
        register("leg-blank") { it.complete("  ") }
        register("leg-error") { it.completeExceptionally(IllegalStateException("trip failed")) }
        register("  ") { it.complete("power-blank-leg") }

        assertTrue(queued.isEmpty())
        assertTrue(bound.isEmpty())
    }

    @Test
    fun invalidatedCoordinatorBeforeResolutionOrBeforeQueuedExecutionSkipsBind() {
        var current = false
        val beforeResolution = CompletableFuture<String?>()
        val queuedBeforeResolution = mutableListOf<() -> Unit>()
        val boundBeforeResolution = mutableListOf<Pair<String, String>>()
        correlateTripDiagnostic(
            legId = "leg-before",
            powerSession = beforeResolution,
            isCurrent = { current },
            enqueue = { queuedBeforeResolution += it },
            bind = { leg, power -> boundBeforeResolution += leg to power }
        )
        beforeResolution.complete("power-before")
        assertTrue(queuedBeforeResolution.isEmpty())
        assertTrue(boundBeforeResolution.isEmpty())

        current = true
        val afterEnqueue = CompletableFuture<String?>()
        val queuedAfterEnqueue = mutableListOf<() -> Unit>()
        val boundAfterEnqueue = mutableListOf<Pair<String, String>>()
        correlateTripDiagnostic(
            legId = "leg-after",
            powerSession = afterEnqueue,
            isCurrent = { current },
            enqueue = { queuedAfterEnqueue += it },
            bind = { leg, power -> boundAfterEnqueue += leg to power }
        )
        afterEnqueue.complete("power-after")
        assertEquals(1, queuedAfterEnqueue.size)
        current = false
        queuedAfterEnqueue.single().invoke()
        assertTrue(boundAfterEnqueue.isEmpty())
    }

    @Test
    fun independentPollFuturesKeepLegAndParentPairsWhenResolvedOutOfOrder() {
        val first = CompletableFuture<String?>()
        val second = CompletableFuture<String?>()
        val queued = mutableListOf<() -> Unit>()
        val bound = mutableListOf<Pair<String, String>>()
        val enqueue: ((() -> Unit) -> Unit) = { queued += it }
        val bind: (String, String) -> Unit = { leg, power -> bound += leg to power }

        correlateTripDiagnostic("leg-a", first, { true }, enqueue, bind)
        correlateTripDiagnostic("leg-b", second, { true }, enqueue, bind)
        second.complete("power-b")
        first.complete("power-a")

        assertEquals(2, queued.size)
        queued.forEach { it.invoke() }
        assertEquals(
            listOf("leg-b" to "power-b", "leg-a" to "power-a"),
            bound
        )
    }

    @Test
    fun lateResolutionBindsPendingLegThroughRealEngineGuard() {
        val engine = TelegramEventEngine(
            TelegramEventState(
                initialized = true,
                gear = "P",
                tripId = "leg-pending",
                tripStartedAtMs = 0L,
                tripStartOdometerKm = 100.0,
                tripStartSoc = 50.0,
                tripParkedSinceMs = 1_000L,
                tripEndOdometerKm = 101.0,
                tripEndSoc = 49.0
            )
        )
        val config = TelegramEventConfig(
            enabledEvents = setOf(TelegramEventType.TRIP_SUMMARY),
            chargeStepPercent = 5,
            lowVoltageThreshold = 12.0,
            unavailableDelayMs = 60_000L,
            tripEndDelayMs = 10_000L
        )
        val future = CompletableFuture<String?>()
        val queued = mutableListOf<() -> Unit>()

        correlateTripDiagnostic(
            legId = "leg-pending",
            powerSession = future,
            isCurrent = { true },
            enqueue = { queued += it },
            bind = { leg, power -> engine.bindTripPowerSession(leg, power) }
        )

        engine.onTick(config, mainCollectionExpected = false, lastError = null, nowMs = 20_000L)
        future.complete("power-pending")
        assertEquals(1, queued.size)
        queued.single().invoke()
        assertEquals("power-pending", engine.state.pendingPowerOffLocationPowerSessionId)
    }
}
