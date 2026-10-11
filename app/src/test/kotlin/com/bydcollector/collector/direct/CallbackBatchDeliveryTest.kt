package com.bydcollector.collector.direct

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallbackBatchDeliveryTest {
    @Test
    fun idleSleepsAndSparseAndFullBatchesKeepEveryRawEvent() {
        val host = BatchHost()
        val controller = controller(host)
        try {
            controller.updatePlan(view(true), view(false))
            // Let the one plan-change reconciliation finish; idle must not keep touching the host.
            Thread.sleep(200)
            val idleChecks = host.ownerChecks.get()
            Thread.sleep(600)
            assertEquals(idleChecks, host.ownerChecks.get())
            controller.callbackForTest(1001, 315621418, TelemetryCallbackBatch.TYPE_INT, 77, null)
            assertNull(host.batches.poll(100, TimeUnit.MILLISECONDS))
            val sparse = assertNotNull(host.batches.poll(2, TimeUnit.SECONDS))
            assertEquals(77, sparse.events.single().rawBits)
            repeat(512) { controller.callbackForTest(1001, 315621418, TelemetryCallbackBatch.TYPE_INT, it, null) }
            val full = assertNotNull(host.batches.poll(2, TimeUnit.SECONDS))
            assertEquals((0 until 512).toList(), full.events.map { it.rawBits })
            assertEquals((1L..512L).toList(), full.events.map { it.sequence })
            assertTrue(full.events.all { it.receivedWallMs > 0L && it.receivedElapsedMs > 0L })
            host.held = true
            repeat(512) { controller.callbackForTest(1001, 315621418, TelemetryCallbackBatch.TYPE_INT, 1_000 + it, null) }
            Thread.sleep(200)
            val heldChecks = host.ownerChecks.get()
            val started = System.nanoTime()
            repeat(30) {
                controller.callbackForTest(1001, 315621418, TelemetryCallbackBatch.TYPE_INT, 1_512 + it, null)
                Thread.sleep(5)
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(host.ownerChecks.get() - heldChecks <= (elapsedMs / 100 + 3) * 2,
                "a held full queue must retry at a bounded rate, not for every event")
            assertNull(host.batches.poll())
            host.held = false
            val resumed = assertNotNull(host.batches.poll(2, TimeUnit.SECONDS)).events +
                assertNotNull(host.batches.poll(2, TimeUnit.SECONDS)).events
            assertEquals((1_000 until 1_542).toList(), resumed.map { it.rawBits })
            assertTrue(host.errors.isEmpty(), host.errors.toString())
        } finally {
            assertTrue(controller.closeAndAwait(2_000))
        }
    }

    @Test
    fun leaseLossSpillsWithoutAnotherCallbackAndShutdownDrainsPartialBatch() {
        val host = BatchHost()
        val controller = controller(host)
        try {
            controller.updatePlan(view(true), view(false))
            controller.callbackForTest(1001, 315621418, TelemetryCallbackBatch.TYPE_INT, 1, null)
            assertNotNull(host.batches.poll(2, TimeUnit.SECONDS))
            host.live.set(123)
            host.owns = false
            controller.updatePlan(HelperStreamRuntimeState.StreamView(true, false, 1, false, true), view(false))
            assertNotNull(host.spills.poll(2, TimeUnit.SECONDS))
            assertEquals(0L, host.live.get())
            controller.callbackForTest(1001, 315621418, TelemetryCallbackBatch.TYPE_INT, 2, null)
            assertTrue(controller.closeAndAwait(2_000))
            assertEquals(2, assertNotNull(host.batches.poll(1, TimeUnit.SECONDS)).events.single().rawBits)
            assertTrue(host.errors.isEmpty(), host.errors.toString())
        } finally {
            controller.closeAndAwait(2_000)
        }
    }

    private fun view(on: Boolean) = HelperStreamRuntimeState.StreamView(on, false, 1, on, on)
    private fun controller(host: BatchHost) = HelperCallbackController(
        "boot", "generation", listOf(CollectorHelperDaemon.Address(5, 1001, 315621418)), emptyList(),
        HelperCallbackController.Platform(), host, { System.nanoTime() / 1_000_000 }, System::currentTimeMillis
    )

    private class BatchHost : HelperCallbackController.Host {
        val batches = LinkedBlockingQueue<TelemetryCallbackBatch>()
        val spills = LinkedBlockingQueue<Int>()
        val errors = LinkedBlockingQueue<String>()
        val ownerChecks = AtomicInteger()
        val live = AtomicLong()
        @Volatile var owns = true
        @Volatile var held = false
        override fun appOwns(stream: Int): Boolean { ownerChecks.incrementAndGet(); return owns }
        override fun captureAllowed(stream: Int) = true
        override fun publishingHeld(stream: Int) = held
        override fun liveBytes(stream: Int) = if (stream == 1) live.get() else 0L
        override fun deliver(batch: TelemetryCallbackBatch): CallbackSpool.AppendResult {
            batches.add(batch)
            return CallbackSpool.AppendResult.SUCCESS
        }
        override fun spill(stream: Int): CallbackSpool.AppendResult {
            live.set(0)
            spills.add(stream)
            return CallbackSpool.AppendResult.SUCCESS
        }
        override fun recordLoss(stream: Int, loss: TelemetryCallbackQueue.Loss) { errors.add(loss.reason) }
        override fun noteError(message: String) { errors.add(message) }
    }
}
