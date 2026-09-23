package com.bydcollector.collector.direct

import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectValueDecoders
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.service.KpiFreshness
import com.bydcollector.collector.ui.VehicleKpiMapper
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class HelperCallbackControllerTest {
    @Test fun stuckVendorCleanupCannotExtendCloseDeadlineOrBlockRawDrain() {
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val platform = object : HelperCallbackController.Platform() {
            override fun close() {
                closeEntered.countDown()
                check(releaseClose.await(5, TimeUnit.SECONDS))
            }
        }
        val host = FakeHost().apply { holdPublishing = true }
        val row = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 99)
        val controller = HelperCallbackController("boot", "generation", listOf(row), emptyList(),
            platform, host, { 1_000L }, { 2_000L })
        try {
            controller.updatePlan(view(true, 1), view(false, 1))
            controller.callbackForTest(row.dev, row.fid, TelemetryCallbackBatch.TYPE_INT, 42, null)
            val started = System.nanoTime()
            assertTrue("raw writers should drain independently", controller.closeAndAwait(100))
            assertTrue("close exceeded its shared budget", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS))
            assertEquals(1L, releaseClose.count)
            assertEquals(listOf(42), host.batches.flatMap { it.events }.map { it.rawBits })
        } finally {
            releaseClose.countDown()
            controller.closeAndAwait(2_000)
        }
    }

    @Test fun batchCapturedBeforeStopCannotRepopulateCacheAfterRestart() {
        val row = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 99)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val host = object : HelperCallbackController.Host {
            override fun appOwns(stream: Int) = true
            override fun captureAllowed(stream: Int) = true
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

    @Test fun blockedDeliveryOnEitherStreamDoesNotBlockOtherStream() {
        assertIndependentDelivery(CollectorHelperProtocol.STREAM_MAIN)
        assertIndependentDelivery(CollectorHelperProtocol.STREAM_SECONDARY)
    }

    @Test fun timedOutCloseKeepsTransportAndQueuedDeliveriesAliveUntilDrained() {
        val row = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 90)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        val calls = AtomicInteger()
        val transportClosed = AtomicBoolean(false)
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val host = object : HelperCallbackController.Host {
            override fun appOwns(stream: Int) = true
            override fun captureAllowed(stream: Int) = true
            override fun publishingHeld(stream: Int) = true
            override fun liveBytes(stream: Int) = 0L
            override fun deliver(batch: TelemetryCallbackBatch): CallbackSpool.AppendResult {
                if (calls.getAndIncrement() == 0) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "blocked delivery was not released" }
                }
                check(!transportClosed.get()) { "delivery wrote after callback transport close" }
                values += batch.events.map { it.rawBits }
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
            controller.updatePlan(view(true, 1), view(false, 1))
            controller.callbackForTest(row.dev, row.fid, TelemetryCallbackBatch.TYPE_INT, 1, null)
            controller.updatePlan(view(false, 2), view(false, 1))
            assertTrue("first delivery did not block", entered.await(2, TimeUnit.SECONDS))

            controller.updatePlan(view(true, 3), view(false, 1))
            controller.callbackForTest(row.dev, row.fid, TelemetryCallbackBatch.TYPE_INT, 2, null)
            controller.updatePlan(view(false, 4), view(false, 1))

            assertFalse("close must report an active writer", controller.closeAndAwait(100L))
            assertFalse(transportClosed.get())
            assertTrue(controller.busy(CollectorHelperProtocol.STREAM_MAIN))

            release.countDown()
            assertTrue("queued deliveries did not drain", controller.closeAndAwait(2_000L))
            transportClosed.set(true)
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), values.toList())
            assertFalse(controller.busy(CollectorHelperProtocol.STREAM_MAIN))
        } finally {
            release.countDown()
            if (controller.closeAndAwait(2_000L)) transportClosed.set(true)
        }
    }

    @Test fun mainOwnsSharedRawAndSuccessfulInitialGetterSeedRemainsFresh() {
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
            assertNull("successful initial getter seed is a fresh poll, even when unchanged",
                seeded.values.single().callbackSource)

            val cached = controller.readHybrid(listOf(main)) { throw AssertionError("fresh getter should be suppressed") }
            assertEquals(123, cached.values.single().raw)
            assertNotNull(cached.values.single().callbackSource)
        } finally { controller.close() }
    }

    @Test fun sharedKeyFollowsEffectiveCaptureOwnerAndPreservesQueuedEvents() {
        val shared = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 95)
        val elapsed = AtomicLong(1_000L)
        val host = FakeHost()
        val controller = HelperCallbackController("boot", "generation", listOf(shared), listOf(shared),
            HelperCallbackController.Platform(), host, elapsed::get, { 2_000L })
        val secondaryAutonomous = view(true, 20, activeLease = false, autonomyAllowed = true)
        try {
            host.captureMask = CollectorHelperProtocol.STREAM_MAIN or CollectorHelperProtocol.STREAM_SECONDARY
            controller.updatePlan(view(true, 10, activeLease = true), secondaryAutonomous)
            controller.callbackForTest(1002, 95, TelemetryCallbackBatch.TYPE_INT, 1, null)
            val mainQueued = controller.diagnosticsSnapshot().mainQueueBytes
            assertTrue(mainQueued > 0L)
            assertEquals(0L, controller.diagnosticsSnapshot().secondaryQueueBytes)

            host.captureMask = CollectorHelperProtocol.STREAM_SECONDARY
            controller.updatePlan(view(true, 10, activeLease = false), secondaryAutonomous)
            elapsed.incrementAndGet()
            controller.callbackForTest(1002, 95, TelemetryCallbackBatch.TYPE_INT, 2, null)
            val afterFallback = controller.diagnosticsSnapshot()
            assertEquals(mainQueued, afterFallback.mainQueueBytes)
            assertTrue(afterFallback.secondaryQueueBytes > 0L)

            host.captureMask = CollectorHelperProtocol.STREAM_MAIN or CollectorHelperProtocol.STREAM_SECONDARY
            controller.updatePlan(view(true, 10, activeLease = true), secondaryAutonomous)
            elapsed.incrementAndGet()
            controller.callbackForTest(1002, 95, TelemetryCallbackBatch.TYPE_INT, 3, null)
            assertTrue(controller.diagnosticsSnapshot().mainQueueBytes > mainQueued)

            controller.flushForTest(CollectorHelperProtocol.STREAM_MAIN)
            controller.flushForTest(CollectorHelperProtocol.STREAM_SECONDARY)
            assertEquals(listOf(CollectorHelperProtocol.STREAM_MAIN, CollectorHelperProtocol.STREAM_SECONDARY),
                host.batches.map { it.stream })
            assertEquals(listOf(1, 3), host.batches[0].events.map { it.rawBits })
            assertEquals(listOf(2), host.batches[1].events.map { it.rawBits })

            val seeded = controller.readHybrid(listOf(shared)) { rows -> result(rows, 3) }
            assertNull("successful matching getter is fresh even on the initial seed",
                seeded.values.single().callbackSource)
            val cached = controller.readHybrid(listOf(shared)) { error("matching seed should enable callback cache") }
            assertEquals(CollectorHelperProtocol.STREAM_MAIN, cached.values.single().callbackSource!!.stream)
            assertEquals(3, cached.values.single().callbackSource!!.rawBits)
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
            assertNull("matching initial seed is the successful getter result", seed.values.single().callbackSource)

            elapsed.set(6_000L)
            controller.forceReconcileForTest(row)
            val reconcile = controller.readHybrid(listOf(row)) { rows -> result(rows, 10) }
            assertNull(reconcile.values.single().callbackSource)

            val cached = controller.readHybrid(listOf(row)) { throw AssertionError("callback-first should resume") }
            assertEquals(1_000L, cached.values.single().callbackSource!!.receivedElapsedMs)
        } finally { controller.close() }
    }

    @Test fun compositeKpiStaysVisibleAcrossReconcilePeriodsAndExpiresAfterGetterFailure() {
        val elapsed = AtomicLong(1_000L)
        val entries = listOf(
            DirectFidRegistry.entries.single { it.key == "charging_charge_battery_volt" },
            DirectFidRegistry.entries.single { it.key == "charging_charge_current" }
        )
        val ordinaryEntry = DirectFidRegistry.entries.single { it.key == "statistic_total_elec_consumption" }
        val allEntries = entries + ordinaryEntry
        val rows = allEntries.map { CollectorHelperDaemon.Address(it.tx, it.dev, it.fid) }
        val currentRaw = mapOf(
            rows[0] to 400,
            rows[1] to java.lang.Float.floatToRawIntBits(100.0f),
            rows[2] to java.lang.Float.floatToRawIntBits(20.0f)
        )
        val host = FakeHost()
        val controller = HelperCallbackController("boot", "generation", rows, emptyList(),
            HelperCallbackController.Platform(), host, elapsed::get, { 100_000L + elapsed.get() })
        val freshness = KpiFreshness("boot", 3_000L)
        val normalizer = VehicleStateNormalizer()
        val getterCalls = mutableListOf<List<CollectorHelperDaemon.Address>>()

        fun poll(): CollectorHelperDaemon.BatchResult = controller.readHybrid(rows) { selected ->
            getterCalls += selected.toList()
            result(selected, *selected.map { currentRaw.getValue(it) }.toIntArray())
        }

        fun inputs(batch: CollectorHelperDaemon.BatchResult, now: Long): Map<String, NormalizedSourceInput> =
            entries.indices.mapNotNull { index ->
                val entry = entries[index]
                val value = batch.values[index]
                val raw = value.raw ?: return@mapNotNull null
                if (value.status != CollectorHelperProtocol.STATUS_OK) return@mapNotNull null
                val callback = value.callbackSource
                val stamp = if (callback == null) {
                    NormalizedSourceStamp(NormalizedSourceKind.POLL, "poll:$now:${entry.key}",
                        "boot", "app", now, 100_000L + now, now)
                } else {
                    val identity = "${callback.bootId}:${callback.helperGeneration}:" +
                        "${callback.stream}:${callback.epoch}:${callback.eventSequence}"
                    NormalizedSourceStamp(NormalizedSourceKind.CALLBACK,
                        identity,
                        callback.bootId, callback.helperGeneration, callback.eventSequence,
                        callback.receivedWallMs, callback.receivedElapsedMs)
                }
                entry.key to NormalizedSourceInput(
                    PollReading(entry.key, raw.toString(), DirectValueDecoders.decode(entry, raw), callback),
                    stamp,
                    null
                )
            }.toMap()

        fun normalize(batch: CollectorHelperDaemon.BatchResult, now: Long) = inputs(batch, now).let { sourceInputs ->
            normalizer.normalizeSparse(sourceInputs, sourceInputs.keys)
        }

        fun publish(now: Long) =
            VehicleKpiMapper.fromObservations(freshness.freshObservations(now))

        try {
            controller.updatePlan(view(true, 7), view(false, 1))
            allEntries.forEachIndexed { index, entry ->
                val raw = currentRaw.getValue(rows[index])
                val type = if (entry.tx == DirectFidRegistry.TX_GET_FLOAT)
                    TelemetryCallbackBatch.TYPE_FLOAT else TelemetryCallbackBatch.TYPE_INT
                controller.callbackForTest(entry.dev, entry.fid, type, raw, null)
            }
            controller.flushForTest(CollectorHelperProtocol.STREAM_MAIN)

            val seed = poll()
            assertEquals(rows, getterCalls.single())
            assertTrue("matching initial getters must be fresh polls", seed.values.all { it.callbackSource == null })
            val seededObservations = normalize(seed, elapsed.get())
            assertTrue(freshness.accept(seededObservations, elapsed.get()))
            assertEquals("-40.0 кВт", publish(elapsed.get()).batteryPowerKw)

            elapsed.set(2_000L)
            val cached = controller.readHybrid(rows) { error("KPI source should wait for its 2s reconcile") }
            assertTrue(cached.values.all { it.callbackSource?.receivedElapsedMs == 1_000L })
            val replay = normalize(cached, elapsed.get())
            assertEquals(1_000L, replay.single { it.field.fieldKey == "battery_discharge_power_kw" }
                .sourceStamp?.elapsedMs)
            assertTrue(freshness.accept(replay, elapsed.get()))
            assertEquals("cached callback clocks do not extend the 3s KPI age", 2_000L,
                freshness.remainingMs(elapsed.get()))

            for (now in listOf(3_000L, 5_000L)) {
                elapsed.set(now)
                val refreshed = poll()
                assertEquals("KPI sources are due every 2s; ordinary sources stay on 5s", rows.take(2),
                    getterCalls.last())
                assertTrue(refreshed.values.take(2).all { it.callbackSource == null })
                val observations = normalize(refreshed, now)
                assertTrue(freshness.accept(observations, now))
                assertEquals(now, observations.single { it.field.fieldKey == "battery_discharge_power_kw" }
                    .sourceStamp?.elapsedMs)
                assertEquals("-40.0 кВт", publish(now).batteryPowerKw)
            }

            elapsed.set(6_000L)
            val ordinaryRefresh = poll()
            assertEquals("non-KPI source retains 5s reconciliation", listOf(rows[2]), getterCalls.last())
            assertEquals(1_000L, ordinaryRefresh.values.take(2).first().callbackSource?.receivedElapsedMs)
            assertFalse(freshness.accept(normalize(ordinaryRefresh, elapsed.get()), elapsed.get()))

            elapsed.set(7_000L)
            val refreshed = poll()
            assertEquals(rows.take(2), getterCalls.last())
            assertTrue(refreshed.values.take(2).all { it.callbackSource == null })
            val latestObservations = normalize(refreshed, elapsed.get())
            assertTrue(freshness.accept(latestObservations, elapsed.get()))
            assertEquals(7_000L, latestObservations.single { it.field.fieldKey == "battery_discharge_power_kw" }
                .sourceStamp?.elapsedMs)
            assertEquals("-40.0 кВт", publish(elapsed.get()).batteryPowerKw)

            elapsed.set(8_000L)
            assertFalse("older callback replay cannot replace a later getter", freshness.accept(replay, 8_000L))
            assertEquals(7_000L, freshness.freshObservations(8_000L)
                .single { it.field.fieldKey == "battery_discharge_power_kw" }.sourceStamp?.elapsedMs)
            assertEquals("-40.0 кВт", publish(8_000L).batteryPowerKw)

            elapsed.set(9_000L)
            val unavailable = controller.readHybrid(rows) { selected ->
                getterCalls += selected.toList()
                CollectorHelperDaemon.BatchResult(
                    CollectorHelperProtocol.STATUS_READ_ERROR,
                    CollectorHelperProtocol.MODE_NATIVE,
                    true, 0, 0, selected.size, selected.size, 0L,
                    Array(selected.size) {
                        CollectorHelperDaemon.ReadValue.error(CollectorHelperProtocol.STATUS_READ_ERROR, "unavailable")
                    },
                    "unavailable"
                )
            }
            assertTrue(unavailable.values.take(2).all { it.status != CollectorHelperProtocol.STATUS_OK })
            val failedObservations = normalize(unavailable, elapsed.get())
            assertTrue(failedObservations.isEmpty())
            assertFalse(freshness.accept(failedObservations, elapsed.get()))
            assertEquals(1_000L, freshness.remainingMs(elapsed.get()))
            assertEquals("-40.0 кВт", publish(elapsed.get()).batteryPowerKw)

            elapsed.set(10_000L)
            assertEquals(0L, freshness.remainingMs(elapsed.get()))
            assertEquals("-", publish(elapsed.get()).batteryPowerKw)
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
        state.renew(claim.controllerToken, CollectorHelperProtocol.STREAM_MAIN, claim.mainEpoch, 0)
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

    @Test fun leaseExpiryStopsCallbacksWithoutDrainingQueuesOrChangingDesiredPlan() {
        val row = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 94)
        val elapsed = AtomicLong(1_000L)
        val host = FakeHost()
        val controller = HelperCallbackController("boot", "generation", listOf(row), emptyList(),
            HelperCallbackController.Platform(), host, elapsed::get, { 2_000L })
        try {
            controller.updatePlan(view(true, 8, activeLease = true), view(false, 1))
            controller.callbackForTest(1002, 94, TelemetryCallbackBatch.TYPE_INT, 10, null)
            val beforeExpiry = controller.diagnosticsSnapshot()
            assertTrue(beforeExpiry.mainQueueBytes > 0)

            host.captureAllowed = false
            controller.updatePlan(view(true, 8, activeLease = false), view(false, 1))
            controller.callbackForTest(1002, 94, TelemetryCallbackBatch.TYPE_INT, 11, null)
            val afterExpiry = controller.diagnosticsSnapshot()
            assertEquals(beforeExpiry.mainQueueBytes, afterExpiry.mainQueueBytes)
            assertEquals(beforeExpiry.pollKeys, afterExpiry.pollKeys)
            assertEquals(2L, afterExpiry.callbacksReceived)

            elapsed.set(2_000L)
            controller.maintenanceForTest()
            assertEquals(1, host.batches.size)
            assertEquals(0L, controller.diagnosticsSnapshot().mainQueueBytes)

            controller.quiesce(CollectorHelperProtocol.STREAM_MAIN, 100L)
            controller.callbackForTest(1002, 94, TelemetryCallbackBatch.TYPE_INT, 12, null)
            assertEquals(0L, controller.diagnosticsSnapshot().mainQueueBytes)
            assertEquals(3L, controller.diagnosticsSnapshot().callbacksReceived)

            controller.updatePlan(view(true, 8, activeLease = false, autonomyAllowed = true), view(false, 1))
            host.captureAllowed = true
            controller.callbackForTest(1002, 94, TelemetryCallbackBatch.TYPE_INT, 13, null)
            assertTrue(controller.diagnosticsSnapshot().mainQueueBytes > 0)
        } finally { controller.close() }
    }

    @Test fun helperEpochCatchUpPreservesAlreadyQueuedPostFenceEvents() {
        val row = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 96)
        val host = FakeHost().apply { holdPublishing = true }
        val controller = HelperCallbackController("boot", "generation", listOf(row), emptyList(),
            HelperCallbackController.Platform(), host, { 1_000L }, { 2_000L })
        try {
            controller.updatePlan(view(true, 4), view(false, 1))
            assertTrue(controller.quiesce(CollectorHelperProtocol.STREAM_MAIN, 100L))

            controller.callbackForTest(1002, 96, TelemetryCallbackBatch.TYPE_INT, 5, null)
            val postFenceBytes = controller.diagnosticsSnapshot().mainQueueBytes
            assertTrue(postFenceBytes > 0L)

            controller.updatePlan(view(true, 5, capturePaused = true), view(false, 1))
            assertEquals(postFenceBytes, controller.diagnosticsSnapshot().mainQueueBytes)
            controller.flushForTest(CollectorHelperProtocol.STREAM_MAIN)
            assertEquals(listOf(5), host.batches.single().events.map { it.rawBits })
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

    private fun view(
        desired: Boolean,
        epoch: Long,
        activeLease: Boolean = desired,
        autonomyAllowed: Boolean = false,
        capturePaused: Boolean = false
    ) = HelperStreamRuntimeState.StreamView(desired, capturePaused, epoch, activeLease, autonomyAllowed)

    private fun assertIndependentDelivery(blockedStream: Int) {
        val main = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 97)
        val secondary = CollectorHelperDaemon.Address(CollectorHelperProtocol.AUTO_TX_INT, 1002, 98)
        val blockedEntered = CountDownLatch(1)
        val releaseBlocked = CountDownLatch(1)
        val otherDelivered = CountDownLatch(1)
        val deliveriesFinished = CountDownLatch(2)
        val host = object : HelperCallbackController.Host {
            override fun appOwns(stream: Int) = true
            override fun captureAllowed(stream: Int) = true
            override fun publishingHeld(stream: Int) = true
            override fun liveBytes(stream: Int) = 0L
            override fun deliver(batch: TelemetryCallbackBatch): CallbackSpool.AppendResult {
                if (batch.stream == blockedStream) {
                    blockedEntered.countDown()
                    check(releaseBlocked.await(2, TimeUnit.SECONDS)) { "blocked stream was not released" }
                } else {
                    otherDelivered.countDown()
                }
                deliveriesFinished.countDown()
                return CallbackSpool.AppendResult.SUCCESS
            }
            override fun spill(stream: Int) = CallbackSpool.AppendResult.SUCCESS
            override fun recordLoss(stream: Int, loss: TelemetryCallbackQueue.Loss?) = Unit
            override fun noteError(message: String) = Unit
        }
        val controller = HelperCallbackController("boot", "generation", listOf(main), listOf(secondary),
            HelperCallbackController.Platform(), host, { 1_000L }, { 2_000L })
        val otherStream = if (blockedStream == CollectorHelperProtocol.STREAM_MAIN)
            CollectorHelperProtocol.STREAM_SECONDARY else CollectorHelperProtocol.STREAM_MAIN
        try {
            controller.updatePlan(view(true, 1), view(true, 1))
            controller.callbackForTest(main.dev, main.fid, TelemetryCallbackBatch.TYPE_INT, 1, null)
            controller.callbackForTest(secondary.dev, secondary.fid, TelemetryCallbackBatch.TYPE_INT, 2, null)
            controller.updatePlan(view(false, 2), view(false, 2))

            assertTrue("blocked stream did not enter delivery", blockedEntered.await(2, TimeUnit.SECONDS))
            assertTrue("in-flight bytes settled before delivery completed", controller.busy(blockedStream))
            assertTrue("$otherStream waited behind $blockedStream", otherDelivered.await(2, TimeUnit.SECONDS))
            assertEquals(1L, releaseBlocked.count)

            releaseBlocked.countDown()
            assertTrue("both stream deliveries did not finish", deliveriesFinished.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while ((controller.busy(CollectorHelperProtocol.STREAM_MAIN) ||
                    controller.busy(CollectorHelperProtocol.STREAM_SECONDARY)) &&
                System.nanoTime() < deadline) Thread.yield()
            assertFalse(controller.busy(CollectorHelperProtocol.STREAM_MAIN))
            assertFalse(controller.busy(CollectorHelperProtocol.STREAM_SECONDARY))
        } finally {
            releaseBlocked.countDown()
            controller.close()
        }
    }

    private fun result(rows: List<CollectorHelperDaemon.Address>, vararg raws: Int): CollectorHelperDaemon.BatchResult {
        require(rows.size == raws.size)
        return CollectorHelperDaemon.BatchResult(CollectorHelperProtocol.STATUS_OK,
            CollectorHelperProtocol.MODE_NATIVE, true, 1, 0, 0, 0, 1,
            Array(rows.size) { CollectorHelperDaemon.ReadValue.ok(raws[it]) }, null)
    }

    private class FakeHost : HelperCallbackController.Host {
        val batches = mutableListOf<TelemetryCallbackBatch>()
        var holdPublishing = false
        var captureAllowed = true
        var captureMask = CollectorHelperProtocol.STREAM_MAIN or CollectorHelperProtocol.STREAM_SECONDARY
        var deliveryResult = CallbackSpool.AppendResult.SUCCESS
        var recordedLoss = 0L
        override fun appOwns(stream: Int) = true
        override fun captureAllowed(stream: Int) = captureAllowed && (captureMask and stream) != 0
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
