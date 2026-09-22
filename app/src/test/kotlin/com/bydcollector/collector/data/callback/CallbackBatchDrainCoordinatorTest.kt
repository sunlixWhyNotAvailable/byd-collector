package com.bydcollector.collector.data.callback

import com.bydcollector.collector.data.direct.CallbackBatchDownload
import com.bydcollector.collector.data.direct.CallbackSpoolActionResult
import com.bydcollector.collector.direct.CallbackSpool
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import kotlin.test.*

class CallbackBatchDrainCoordinatorTest {
    private val ok = CollectorHelperProtocol.STATUS_OK
    private val batch = TelemetryCallbackBatch("boot", "helper", 1, 1, 1,
        listOf(TelemetryCallbackBatch.Event(1, 1001, 315621418, TelemetryCallbackBatch.TYPE_INT,
            2, null, 1000, 500, null, "usable")))
    private val descriptor = CallbackSpool.Descriptor(1, 1, batch.identity(), "sample.cbready",
        batch.encode().size.toLong(), TelemetryCallbackBatch.digest(batch.encode()), "boot", "helper", 1, 1)
    private val payload get() = CallbackBatchDownload(ok, descriptor, batch, CallbackDelivery.REPLAY)

    @Test fun `commits raw before exact ack and accumulates replay without event logs`() {
        val calls = mutableListOf<String>()
        var offered = true
        val diagnostics = mutableListOf<String>()
        val coordinator = CallbackBatchDrainCoordinator(
            download = { if (offered) payload else CallbackBatchDownload(ok) },
            importBatch = { received, digest, delivery ->
                assertSame(batch, received); assertEquals(descriptor.sha256, digest)
                assertEquals(CallbackDelivery.REPLAY, delivery)
                calls += "commit"
                CallbackImportResult.Committed(1, 1, false)
            },
            acknowledge = { assertEquals(descriptor, it); calls += "ack"; offered = false; action() },
            quarantine = { _, _ -> error("no quarantine") },
            diagnostic = diagnostics::add,
            monotonicNanos = { 0 }
        )
        val result = coordinator.drain()
        assertTrue(result.drained)
        assertEquals(listOf("commit", "ack"), calls)
        assertEquals(1, result.persistedEvents)
        assertEquals(1, result.replayedEvents)
        coordinator.drain()
        assertEquals(1, diagnostics.size)
    }

    @Test fun `database rollback cannot acknowledge or quarantine`() {
        val coordinator = CallbackBatchDrainCoordinator(
            download = { payload },
            importBatch = { _, _, _ -> error("disk full") },
            acknowledge = { error("must not acknowledge") },
            quarantine = { _, _ -> error("must not discard retryable error") }
        )
        val result = coordinator.drain()
        assertFalse(result.drained)
        assertTrue(result.retryable)
        assertTrue(result.blockedReason!!.contains("disk full"))
        assertEquals(0, result.persistedEvents)
    }

    @Test fun `temporary helper barrier remains retryable without touching storage`() {
        val coordinator = CallbackBatchDrainCoordinator(
            download = {
                CallbackBatchDownload(
                    CollectorHelperProtocol.STATUS_REPLAY_PENDING,
                    error = "callback persistence or archive fence pending"
                )
            },
            importBatch = { _, _, _ -> error("must not import") },
            acknowledge = { error("must not acknowledge") },
            quarantine = { _, _ -> error("must not quarantine") }
        )

        val result = coordinator.drain()

        assertFalse(result.drained)
        assertTrue(result.retryable)
        assertTrue(result.blockedReason!!.contains("status=${CollectorHelperProtocol.STATUS_REPLAY_PENDING}"))
    }

    @Test fun `failed ack retries already committed batch idempotently`() {
        var committed = false
        var offered = true
        var ackCalls = 0
        val coordinator = CallbackBatchDrainCoordinator(
            download = { if (offered) payload else CallbackBatchDownload(ok) },
            importBatch = { _, _, _ ->
                CallbackImportResult.Committed(1, 1, committed).also { committed = true }
            },
            acknowledge = {
                if (++ackCalls == 1) CallbackSpoolActionResult(-1, error = "lost reply")
                else { offered = false; action() }
            },
            quarantine = { _, _ -> error("no quarantine") }
        )
        assertFalse(coordinator.drain().drained)
        val retried = coordinator.drain()
        assertTrue(retried.drained)
        assertEquals(0, retried.persistedEvents)
        assertEquals(1, retried.duplicateBatches)
    }

    @Test fun `only definitive corrupt content is quarantined not interrupted paging`() {
        for (permanent in listOf(false, true)) {
            var offered = true
            var quarantineCount = 0
            val coordinator = CallbackBatchDrainCoordinator(
                download = {
                    if (offered) CallbackBatchDownload(-1, descriptor = descriptor, error = "bad payload",
                        permanentFormatError = permanent) else CallbackBatchDownload(ok)
                },
                importBatch = { _, _, _ -> error("not decoded") },
                acknowledge = { error("not committed") },
                quarantine = { selected, _ ->
                    assertEquals(descriptor, selected); quarantineCount++; offered = false; action()
                }
            )
            assertEquals(permanent, coordinator.drain().drained)
            assertEquals(if (permanent) 1 else 0, quarantineCount)
        }
    }

    @Test fun `regular work budget yields without falsely declaring empty backlog`() {
        var imports = 0
        val coordinator = CallbackBatchDrainCoordinator(
            download = { payload },
            importBatch = { _, _, _ -> imports++; CallbackImportResult.Committed(1, 1, false) },
            acknowledge = { action() }, quarantine = { _, _ -> error("no quarantine") }
        )
        val result = coordinator.drain(maxBatches = 2)
        assertEquals(2, imports)
        assertFalse(result.drained)
        assertNull(result.blockedReason)
    }

    @Test fun `interruption after durable commit leaves exact batch for retry`() {
        val coordinator = CallbackBatchDrainCoordinator(
            download = { payload },
            importBatch = { _, _, _ -> Thread.currentThread().interrupt(); CallbackImportResult.Committed(1, 1, false) },
            acknowledge = { error("interrupted before ACK") },
            quarantine = { _, _ -> error("no quarantine") }
        )
        try { assertFailsWith<InterruptedException> { coordinator.drain() } }
        finally { Thread.interrupted() }
    }

    private fun action() = CallbackSpoolActionResult(ok, affected = 1)
}
