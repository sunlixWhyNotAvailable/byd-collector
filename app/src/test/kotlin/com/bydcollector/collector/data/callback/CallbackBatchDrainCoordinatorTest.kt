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
        val coordinator = CallbackBatchDrainCoordinator(
            download = { if (offered) payload else CallbackBatchDownload(ok) },
            importBatch = { received, digest, delivery ->
                assertSame(batch, received); assertEquals(descriptor.sha256, digest)
                assertEquals(CallbackDelivery.REPLAY, delivery)
                calls += "commit"
                CallbackImportResult.Committed(1, 1, false)
            },
            acknowledge = { assertEquals(descriptor, it); calls += "ack"; offered = false; action() },
            quarantine = { _, _ -> error("no quarantine") }
        )
        val result = coordinator.drain()
        assertTrue(result.drained)
        assertEquals(CallbackDrainKind.PROGRESS, result.kind)
        assertEquals(listOf("commit", "ack"), calls)
        assertEquals(1, result.persistedEvents)
        assertEquals(1, result.replayedEvents)
    }

    @Test fun `database rollback cannot acknowledge or quarantine`() {
        val diskFull = IllegalStateException("disk full")
        val coordinator = CallbackBatchDrainCoordinator(
            download = { payload },
            importBatch = { _, _, _ -> throw diskFull },
            acknowledge = { error("must not acknowledge") },
            quarantine = { _, _ -> error("must not discard retryable error") }
        )
        val result = coordinator.drain()
        assertFalse(result.drained)
        assertTrue(result.retryable)
        assertTrue(result.blockedReason!!.contains("disk full"))
        assertEquals(CallbackDrainKind.FAULT, result.kind)
        assertSame(diskFull, result.fault)
        assertEquals(0, result.persistedEvents)
    }

    @Test fun `only replay pending and stale token statuses are classified as pending`() {
        for (status in listOf(
            CollectorHelperProtocol.STATUS_REPLAY_PENDING,
            CollectorHelperProtocol.STATUS_STALE_TOKEN
        )) {
            val coordinator = CallbackBatchDrainCoordinator(
                download = { CallbackBatchDownload(status, error = "temporary helper barrier") },
                importBatch = { _, _, _ -> error("must not import") },
                acknowledge = { error("must not acknowledge") },
                quarantine = { _, _ -> error("must not quarantine") }
            )
            val result = coordinator.drain()
            assertFalse(result.drained)
            assertTrue(result.retryable)
            assertEquals(status, result.status)
            assertEquals(CallbackDrainKind.PENDING, result.kind)
        }

        val otherStatus = CallbackBatchDrainCoordinator(
            download = { CallbackBatchDownload(CollectorHelperProtocol.STATUS_READ_ERROR, error = "read failed") },
            importBatch = { _, _, _ -> error("must not import") },
            acknowledge = { error("must not acknowledge") },
            quarantine = { _, _ -> error("must not quarantine") }
        ).drain()
        assertEquals(CallbackDrainKind.FAULT, otherStatus.kind)
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
        val failedAck = coordinator.drain()
        assertFalse(failedAck.drained)
        assertEquals(CallbackDrainKind.FAULT, failedAck.kind)
        assertEquals(-1, failedAck.status)
        val retried = coordinator.drain()
        assertTrue(retried.drained)
        assertEquals(CallbackDrainKind.PROGRESS, retried.kind)
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
            val result = coordinator.drain()
            assertEquals(permanent, result.drained)
            if (permanent) assertEquals(CallbackDrainKind.PROGRESS, result.kind)
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
        assertEquals(CallbackDrainKind.PROGRESS, result.kind)
        assertFalse(result.retryable)
    }

    @Test fun `empty polls after a successful packet remain progress and the next empty slice is empty`() {
        var available = true
        val coordinator = CallbackBatchDrainCoordinator(
            download = { if (available) payload else CallbackBatchDownload(ok) },
            importBatch = { _, _, _ -> CallbackImportResult.Committed(1, 1, false) },
            acknowledge = { available = false; action() },
            quarantine = { _, _ -> error("no quarantine") }
        )

        val progress = coordinator.drain(maxBatches = 1)
        val empty = coordinator.drain(maxBatches = 1)

        assertFalse(progress.drained)
        assertEquals(CallbackDrainKind.PROGRESS, progress.kind)
        assertTrue(empty.drained)
        assertEquals(CallbackDrainKind.EMPTY, empty.kind)
        assertNull(progress.blockedReason)
        assertFalse(progress.retryable)
    }

    @Test fun `result reports the actual downloaded head and durable progress times`() {
        val observedBatch = TelemetryCallbackBatch("boot", "helper", 1, 1, 2, listOf(
            TelemetryCallbackBatch.Event(1, 1001, 315621418, TelemetryCallbackBatch.TYPE_INT,
                2, null, 2_000, 500, null, "usable"),
            TelemetryCallbackBatch.Event(2, 1001, 315621418, TelemetryCallbackBatch.TYPE_INT,
                3, null, 700, 600, null, "usable")
        ))
        val bytes = observedBatch.encode()
        val selectedDescriptor = CallbackSpool.Descriptor(
            1, 1, observedBatch.identity(), "sample.cbready", bytes.size.toLong(),
            TelemetryCallbackBatch.digest(bytes), "boot", "helper", 1, 2
        )
        val coordinator = CallbackBatchDrainCoordinator(
            download = { CallbackBatchDownload(ok, selectedDescriptor, observedBatch, CallbackDelivery.REPLAY) },
            importBatch = { received, _, _ ->
                assertSame(observedBatch, received)
                CallbackImportResult.Committed(1, 2, false)
            },
            acknowledge = { action() },
            quarantine = { _, _ -> error("no quarantine") },
            wallTimeMs = { 5_000L }
        )

        val result = coordinator.drain(maxBatches = 1)

        assertEquals(700L, result.oldestObservedWallMs)
        assertEquals(5_000L, result.lastRawCommitWallMs)
        assertEquals(5_000L, result.lastProgressWallMs)
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
