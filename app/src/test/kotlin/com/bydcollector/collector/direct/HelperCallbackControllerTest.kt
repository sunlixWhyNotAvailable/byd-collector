package com.bydcollector.collector.direct

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class HelperCallbackControllerTest {
    @Test fun batchCapturedBeforeStopCannotRepopulateCacheAfterRestart() {
        val row = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 99)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val host = object : HelperCallbackController.Host {
            override fun appOwns(stream: Int) = true
            override fun publishingHeld(stream: Int) = false
            override fun liveBytes(stream: Int) = 0L
            override fun deliver(batch: TelemetryCallbackBatch): CallbackSpool.AppendResult {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                delivered.countDown()
                return CallbackSpool.AppendResult.SUCCESS
            }
            override fun spill(stream: Int) = CallbackSpool.AppendResult.SUCCESS
            override fun recordLoss(stream: Int, loss: TelemetryCallbackQueue.Loss?) = Unit
            override fun noteError(message: String) = Unit
        }
        val controller = HelperCallbackController("boot", "generation", listOf(row), emptyList(),
            HelperCallbackController.Platform(), host, { 1_000L }, { 2_000L })
        try {
            controller.updatePlan(view(true, 2), view(false, 1))
            controller.callbackForTest(1002, 99, TelemetryCallbackBatch.TYPE_INT, 7, null)
            controller.updatePlan(view(false, 3), view(false, 1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.updatePlan(view(true, 4), view(false, 1))
            release.countDown()
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            val result = controller.readHybrid(listOf(row)) { rows -> result(rows, 8) }
            assertEquals(8, result.values.single().raw)
            assertNull(result.values.single().callbackSource)
        } finally {
            release.countDown()
            controller.close()
        }
    }

    @Test fun mainOwnsSharedRawAndMatchingSeedEnablesCachedReads() {
        val main = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1001, 42)
        val secondary = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1001, 42)
        val host = FakeHost()
        val controller = HelperCallbackController("boot", "generation", listOf(main), listOf(secondary),
            HelperCallbackController.Platform(), host, { 1_000L }, { 2_000L })
        try {
            controller.updatePlan(view(true, 7), view(true, 9))
            controller.callbackForTest(1001, 42, TelemetryCallbackBatch.TYPE_INT, 123, null)
            controller.flushForTest(CollectorHelperProtocol.STREAM_MAIN)

            assertEquals(listOf(CollectorHelperProtocol.STREAM_MAIN), host.batches.map { it.stream })
            var polls = 0
            val seeded = controller.readHybrid(listOf(main)) { rows ->
                polls++
                result(rows, 123)
            }
            assertEquals(1, polls)
            assertNotNull(seeded.values.single().callbackSource)

            val cached = controller.readHybrid(listOf(main)) { throw AssertionError("fresh getter should be suppressed") }
            assertEquals(123, cached.values.single().raw)
            assertNotNull(cached.values.single().callbackSource)
        } finally { controller.close() }
    }

    @Test fun twoProvenMismatchesFallBackAndFreshCallbackNeedsMatchingReconcileToRecover() {
        val row = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 77)
        val host = FakeHost()
        val controller = HelperCallbackController("boot", "generation", listOf(row), emptyList(),
            HelperCallbackController.Platform(), host, { 1_000L }, { 2_000L })
        try {
            controller.updatePlan(view(true, 3), view(false, 1))
            controller.callbackForTest(1002, 77, TelemetryCallbackBatch.TYPE_INT, 10, null)
            controller.flushForTest(CollectorHelperProtocol.STREAM_MAIN)
            controller.readHybrid(listOf(row)) { rows -> result(rows, 10) }

            repeat(2) {
                controller.forceReconcileForTest(row)
                val fresh = controller.readHybrid(listOf(row)) { rows -> result(rows, 11) }
                assertNull(fresh.values.single().callbackSource)
            }
            var polled = false
            controller.readHybrid(listOf(row)) { rows -> polled = true; result(rows, 11) }
            assertTrue(polled)

            controller.callbackForTest(1002, 77, TelemetryCallbackBatch.TYPE_INT, 11, null)
            controller.flushForTest(CollectorHelperProtocol.STREAM_MAIN)
            controller.forceReconcileForTest(row)
            val recovered = controller.readHybrid(listOf(row)) { rows -> result(rows, 11) }
            assertNull(recovered.values.single().callbackSource)
            assertNotNull(controller.readHybrid(listOf(row)) { error("callback-first should resume") }
                .values.single().callbackSource)
        } finally { controller.close() }
    }

    @Test fun matchingReconcileIsFreshPollThenCallbackFirstResumesWithOriginalClock() {
        val row = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 78)
        val elapsed = AtomicLong(1_000L)
        val host = FakeHost()
        val controller = HelperCallbackController("boot", "generation", listOf(row), emptyList(),
            HelperCallbackController.Platform(), host, elapsed::get, { 2_000L })
        try {
            controller.updatePlan(view(true, 3), view(false, 1))
            controller.callbackForTest(1002, 78, TelemetryCallbackBatch.TYPE_INT, 10, null)
            controller.flushForTest(CollectorHelperProtocol.STREAM_MAIN)
            val seed = controller.readHybrid(listOf(row)) { rows -> result(rows, 10) }
            assertNotNull(seed.values.single().callbackSource)

            elapsed.set(6_000L)
            controller.forceReconcileForTest(row)
            val reconcile = controller.readHybrid(listOf(row)) { rows -> result(rows, 10) }
            assertNull(reconcile.values.single().callbackSource)

            val cached = controller.readHybrid(listOf(row)) { throw AssertionError("callback-first should resume") }
            assertEquals(1_000L, cached.values.single().callbackSource!!.receivedElapsedMs)
        } finally { controller.close() }
    }

    @Test fun listenerErrorImmediatelyRestoresPollingAndFastKeysNeverUseCache() {
        val ordinary = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 88)
        val power = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1001, 315621418)
        val host = FakeHost()
        val controller = HelperCallbackController("boot", "generation", listOf(ordinary, power), emptyList(),
            HelperCallbackController.Platform(), host, { 1_000L }, { 2_000L })
        try {
            controller.updatePlan(view(true, 2), view(false, 1))
            controller.callbackForTest(1002, 88, TelemetryCallbackBatch.TYPE_INT, 5, null)
            controller.callbackForTest(1001, 315621418, TelemetryCallbackBatch.TYPE_INT, 6, null)
            controller.flushForTest(CollectorHelperProtocol.STREAM_MAIN)
            controller.readHybrid(listOf(ordinary, power)) { rows -> result(rows, 5, 6) }
            controller.listenerErrorForTest()

            var calls = 0
            val values = controller.readHybrid(listOf(ordinary, power)) { rows ->
                calls++
                result(rows, 5, 6)
            }
            assertEquals(1, calls)
            assertNull(values.values[0].callbackSource)
            assertNull(values.values[1].callbackSource)

            controller.callbackForTest(1002, 88, TelemetryCallbackBatch.TYPE_INT, 5, null)
            controller.flushForTest(CollectorHelperProtocol.STREAM_MAIN)
            controller.forceReconcileForTest(ordinary)
            val recovered = controller.readHybrid(listOf(ordinary, power)) { rows -> result(rows, 5, 6) }
            assertNull(recovered.values[0].callbackSource)
            assertNull(recovered.values[1].callbackSource)
            val cached = controller.readHybrid(listOf(ordinary)) { error("callback-first should resume") }
            assertNotNull(cached.values[0].callbackSource)
        } finally { controller.close() }
    }

    @Test fun pausedReplayAuthorizationDoesNotAuthorizeStoppedOrStaleEpoch() {
        val state = HelperStreamRuntimeState()
        val claim = state.claim("app", 1, 0)
        state.beginPause(claim.controllerToken, CollectorHelperProtocol.STREAM_MAIN, claim.mainEpoch, 100)
        val paused = state.completePause(claim.controllerToken, CollectorHelperProtocol.STREAM_MAIN, claim.mainEpoch, 101)
        assertEquals(CollectorHelperProtocol.STATUS_OK,
            state.authorizeReplay(claim.controllerToken, CollectorHelperProtocol.STREAM_MAIN, paused.mainEpoch, 102))
        assertEquals(CollectorHelperProtocol.STATUS_STALE_TOKEN,
            state.authorizeReplay(claim.controllerToken, CollectorHelperProtocol.STREAM_MAIN, claim.mainEpoch, 102))
        val stopped = state.setDesired(claim.controllerToken, CollectorHelperProtocol.STREAM_MAIN, paused.mainEpoch, 0, 103)
        assertEquals(CollectorHelperProtocol.STATUS_LEASE_EXPIRED,
            state.authorizeReplay(claim.controllerToken, CollectorHelperProtocol.STREAM_MAIN, stopped.mainEpoch, 104))
    }

    @Test fun pausedPublishingHoldsOnlyNextEpochUntilResume() {
        val row = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 91)
        val elapsed = AtomicLong(1_000L)
        val host = FakeHost()
        val controller = HelperCallbackController("boot", "generation", listOf(row), emptyList(),
            HelperCallbackController.Platform(), host, elapsed::get, { 2_000L })
        try {
            controller.updatePlan(view(true, 2), view(false, 1))
            host.holdPublishing = true
            controller.callbackForTest(1002, 91, TelemetryCallbackBatch.TYPE_INT, 4, null)
            elapsed.set(1_600L)
            controller.maintenanceForTest()
            assertTrue(host.batches.isEmpty())
            assertTrue(controller.diagnosticsSnapshot().mainQueueBytes > 0)

            host.holdPublishing = false
            controller.maintenanceForTest()
            assertEquals(1, host.batches.size)
        } finally { controller.close() }
    }

    @Test fun transportOwnsDeliveryRejectionLossExactlyOnce() {
        val row = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 92)
        val host = FakeHost().apply { deliveryResult = CallbackSpool.AppendResult.CAP_REACHED }
        val controller = HelperCallbackController("boot", "generation", listOf(row), emptyList(),
            HelperCallbackController.Platform(), host, { 1_000L }, { 2_000L })
        try {
            controller.updatePlan(view(true, 2), view(false, 1))
            controller.callbackForTest(1002, 92, TelemetryCallbackBatch.TYPE_INT, 4, null)
            controller.flushForTest(CollectorHelperProtocol.STREAM_MAIN)
            assertEquals(0L, host.recordedLoss)
        } finally { controller.close() }
    }

    @Test fun diagnosticsAreBoundedAggregatesWithoutRawEventContent() {
        val row = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 93)
        val host = FakeHost().apply { holdPublishing = true }
        val controller = HelperCallbackController("boot", "generation", listOf(row), emptyList(),
            HelperCallbackController.Platform(), host, { 1_000L }, { 2_000L })
        try {
            controller.updatePlan(view(true, 2), view(false, 1))
            controller.callbackForTest(1002, 93, TelemetryCallbackBatch.TYPE_INT, 99, null)
            val snapshot = controller.diagnosticsSnapshot()
            assertEquals(1L, snapshot.callbacksReceived)
            assertEquals(1, snapshot.pollKeys)
            assertEquals(0, snapshot.promotedKeys)
            assertTrue(snapshot.mainQueueBytes > 0)
            assertTrue(snapshot.mainQueueHighWaterBytes >= snapshot.mainQueueBytes)
            assertEquals(-1L, snapshot.secondaryQueueOldestAgeMs)
        } finally { controller.close() }
    }

    private fun view(desired: Boolean, epoch: Long) =
        HelperStreamRuntimeState.StreamView(desired, false, epoch, desired)

    private fun result(rows: List<CollectorHelperDaemon.Address>, vararg raws: Int): CollectorHelperDaemon.BatchResult {
        require(rows.size == raws.size)
        return CollectorHelperDaemon.BatchResult(CollectorHelperProtocol.STATUS_OK,
            CollectorHelperProtocol.MODE_NATIVE, true, 1, 0, 0, 0, 1,
            Array(rows.size) { CollectorHelperDaemon.ReadValue.ok(raws[it]) }, null)
    }

    private class FakeHost : HelperCallbackController.Host {
        val batches = mutableListOf<TelemetryCallbackBatch>()
        var holdPublishing = false
        var deliveryResult = CallbackSpool.AppendResult.SUCCESS
        var recordedLoss = 0L
        override fun appOwns(stream: Int) = true
        override fun publishingHeld(stream: Int) = holdPublishing
        override fun liveBytes(stream: Int) = 0L
        override fun deliver(batch: TelemetryCallbackBatch): CallbackSpool.AppendResult {
            batches += batch
            return deliveryResult
        }
        override fun spill(stream: Int) = CallbackSpool.AppendResult.SUCCESS
        override fun recordLoss(stream: Int, loss: TelemetryCallbackQueue.Loss?) {
            recordedLoss += loss?.count ?: 0L
        }
        override fun noteError(message: String) = Unit
    }
}
