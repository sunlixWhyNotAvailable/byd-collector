package com.bydcollector.collector.direct

import org.junit.Assert.*
import org.junit.Test

class TelemetryCallbackQueueTest {
    @Test fun drainsInSourceOrderAtCountBoundary() {
        val queue = TelemetryCallbackQueue(CollectorHelperProtocol.STREAM_MAIN, "boot", "generation")
        repeat(TelemetryCallbackBatch.MAX_EVENTS) { sequence -> assertTrue(queue.offer(event(sequence.toLong(), sequence.toLong()), 7)) }

        val batch = queue.drainDue(511)!!
        assertEquals(512, batch.events.size)
        assertEquals((0L..511L).toList(), batch.events.map { it.sequence })
        assertEquals(7, batch.epoch)
        assertEquals(0, queue.snapshot().eventCount)
    }

    @Test fun waitsFiveHundredMillisecondsAndPreservesOldAtCapacity() {
        val queue = TelemetryCallbackQueue(CollectorHelperProtocol.STREAM_SECONDARY, "boot", "generation")
        assertTrue(queue.offer(event(1, 100), 2))
        assertNull(queue.drainDue(599))
        assertNotNull(queue.drainDue(600))

        var sequence = 2L
        while (queue.offer(bytesEvent(sequence++, 700, 64 * 1024), 2)) Unit
        val snapshot = queue.snapshot()
        assertTrue(snapshot.eventCount > 0)
        assertTrue(snapshot.retainedBytes <= TelemetryCallbackQueue.MAX_RETAINED_BYTES)
        assertEquals(1L, snapshot.loss!!.count)
        assertEquals("queue_capacity", snapshot.loss.reason)
    }

    @Test fun rejectsOutOfOrderWithoutReplacingQueuedEvidence() {
        val queue = TelemetryCallbackQueue(1, "boot", "generation")
        assertTrue(queue.offer(event(4, 1), 1))
        assertFalse(queue.offer(event(3, 2), 1))
        assertEquals(listOf(4L), queue.drain()!!.events.map { it.sequence })
        assertEquals("non_monotonic_sequence", queue.takeLoss()!!.reason)
    }

    @Test fun epochTransitionRequiresOldBatchFlushWithoutLosingNewEvent() {
        val queue = TelemetryCallbackQueue(1, "boot", "generation")
        assertEquals(TelemetryCallbackQueue.OfferResult.ACCEPTED, queue.offerDetailed(event(1, 1), 4))
        assertEquals(TelemetryCallbackQueue.OfferResult.FLUSH_EPOCH_FIRST, queue.offerDetailed(event(2, 2), 5))
        assertEquals(4, queue.drain()!!.epoch)
        assertEquals(TelemetryCallbackQueue.OfferResult.ACCEPTED, queue.offerDetailed(event(2, 2), 5))
        assertEquals(5, queue.drain()!!.epoch)
        assertNull(queue.takeLoss())
    }

    @Test fun liveTransportReservationSharesTheFourMiBCap() {
        val queue = TelemetryCallbackQueue(1, "boot", "generation")
        val event = bytesEvent(1, 1, 1024)
        assertEquals(TelemetryCallbackQueue.OfferResult.REJECTED_WITH_LOSS,
            queue.offerDetailed(event, 1, TelemetryCallbackQueue.MAX_RETAINED_BYTES - 512))
        assertEquals(0, queue.snapshot().eventCount)
    }

    private fun event(sequence: Long, elapsed: Long) = TelemetryCallbackBatch.Event(
        sequence, 1001, 42, TelemetryCallbackBatch.TYPE_INT, sequence.toInt(), null,
        1_000 + sequence, elapsed, null, "ok"
    )

    private fun bytesEvent(sequence: Long, elapsed: Long, size: Int) = TelemetryCallbackBatch.Event(
        sequence, 1001, 42, TelemetryCallbackBatch.TYPE_BYTES, 0, ByteArray(size),
        1_000 + sequence, elapsed, null, "ok"
    )
}
