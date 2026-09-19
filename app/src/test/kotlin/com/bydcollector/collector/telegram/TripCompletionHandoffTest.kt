package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.trips.TripCompletionIntent
import com.bydcollector.collector.data.trips.TripSession
import com.bydcollector.collector.service.TelegramEventState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TripCompletionHandoffTest {
    private fun intent(sequence: Long) = TripCompletionIntent(
        sequence, "off:$sequence", "2026-09-19T08:00:30Z"
    )

    @Test fun crashBoundariesRetainTheIntentOrItsSingleDurableAcceptance() {
        // Each exception models a process stopping at that boundary. The two stores survive.
        for (crashAt in listOf("before_accept", "after_accept", "before_ack", "after_ack", "none")) {
            val pending = mutableListOf(intent(1), intent(2))
            val accepted = mutableSetOf<String>()
            val outbox = mutableListOf<String>()
            var crashed = false
            fun crash(point: String) {
                if (!crashed && crashAt == point) {
                    crashed = true
                    error("process stopped at $point")
                }
            }
            fun drain() = handoffTripCompletions(
                2,
                pending = { pending.toList() },
                accept = { row ->
                    crash("before_accept")
                    if (accepted.add(row.identity)) outbox.add(row.identity)
                    crash("after_accept")
                    row
                },
                acknowledge = { row -> crash("before_ack"); pending.remove(row) },
                afterAck = { crash("after_ack") }
            )
            if (crashAt != "none") assertFailsWith<IllegalStateException> { drain() }
            drain()
            assertTrue(pending.isEmpty(), crashAt)
            assertEquals(listOf("off:1", "off:2"), outbox, crashAt)
        }
    }

    @Test fun capturedFrontierDoesNotConsumeAFutureOffBeforeAnOlderOn() {
        val pending = mutableListOf(intent(1), intent(2))
        val applied = mutableListOf<String>()
        fun drain(frontier: Long) = handoffTripCompletions(
            frontier, { pending.filter { it.sequence <= frontier } },
            { applied.add(it.identity); it }, { pending.remove(it) }, {}
        )
        drain(0) // Earlier ON was queued before either OFF became durable.
        applied.add("on:1")
        drain(1)
        applied.add("on:2")
        drain(2)
        assertEquals(listOf("on:1", "off:1", "on:2", "off:2"), applied)
        assertTrue(pending.isEmpty())
    }

    @Test fun failedAckPreventsLaterAcceptanceAndFutureFrontierIsRejected() {
        val accepted = mutableListOf<Long>()
        assertFailsWith<IllegalStateException> {
            handoffTripCompletions(2, { listOf(intent(1), intent(2)) },
                { accepted.add(it.sequence) }, { false }, {})
        }
        assertEquals(listOf(1L), accepted)
        assertFailsWith<IllegalStateException> {
            handoffTripCompletions(1, { listOf(intent(2)) }, { error("must not accept") }, { true }, {})
        }
    }

    @Test fun missingSenderHistoryUsesKnownTripButNeverReplacesParkedOrActiveLeg() {
        val trip = TripSession(
            "power:1", TripSession.STATE_CLOSED, "2026-09-19T08:00:00Z",
            endedAt = "2026-09-19T08:00:30Z", movementObserved = true,
            startSoc = 80.0, endSoc = 79.0, startOdometerKm = 100.0,
            lastOdometerKm = 102.0, energyKwh = 0.4
        )
        val completion = intent(1).copy(session = trip)
        val restored = recoverCompletionState(TelegramEventState(), completion)
        assertEquals(trip.tripId, restored.tripPowerSessionId)
        assertEquals(100.0, restored.tripStartOdometerKm)
        assertEquals(0.4, restored.tripAccumulatedEnergyKwh)
        val parked = TelegramEventState(pendingPowerOffLocationTripId = "parked")
        assertEquals(parked, recoverCompletionState(parked, completion))
        val active = TelegramEventState(tripId = "moving")
        assertEquals(active, recoverCompletionState(active, completion))
    }

    @Test fun slowHttpDoesNotBlockLocalOwnerAndQuiescenceWaitsThroughReceipt() {
        val httpEntered = CountDownLatch(1)
        val httpRelease = CountDownLatch(1)
        val receiptEntered = CountDownLatch(1)
        val receiptRelease = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val runtime = TelegramDeliveryRuntime(send = {
            httpEntered.countDown()
            check(httpRelease.await(3, TimeUnit.SECONDS))
            TelegramSendResult.Success
        })
        try {
            runtime.executor.submit {
                runtime.dispatchSend(TelegramSendMessage("123:test", "chat", "message")) {
                    receiptEntered.countDown()
                    check(receiptRelease.await(3, TimeUnit.SECONDS))
                    delivered.set(it == TelegramSendResult.Success)
                }
            }.get(1, TimeUnit.SECONDS)
            assertTrue(httpEntered.await(1, TimeUnit.SECONDS))
            assertEquals("local trip closed", runtime.executor.submit<String> { "local trip closed" }.get(1, TimeUnit.SECONDS))
            assertFalse(runtime.quiesceAndAwait(20))
            httpRelease.countDown()
            assertTrue(receiptEntered.await(1, TimeUnit.SECONDS))
            assertTrue(runtime.hasInFlightDelivery)
            assertFalse(runtime.quiesceAndAwait(20))
            receiptRelease.countDown()
            assertTrue(runtime.quiesceAndAwait(1_000))
            assertTrue(delivered.get())
        } finally {
            httpRelease.countDown()
            receiptRelease.countDown()
            runtime.close()
        }
    }

    @Test fun oldServiceDetachCannotRemoveNewServiceCallbacks() {
        val runtime = TelegramDeliveryRuntime(send = { TelegramSendResult.Success })
        val first = Any()
        val next = Any()
        var deadline: Long? = null
        try {
            runtime.attach(first, { error("stale callback") }, { throw it })
            runtime.attach(next, { deadline = it }, { throw it })
            runtime.detach(first)
            runtime.deliveryReady(42L)
            assertEquals(42L, deadline)
        } finally { runtime.close() }
    }
}
