package com.bydcollector.collector.data.direct

import com.bydcollector.collector.direct.CollectorHelperProtocol as P
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectStreamControllerTest {
    @Test fun failedSecondaryConsumerReleasesOnlyItsLeaseUntilExplicitReentry() {
        val wire = FakeWire()
        val controller = AppStreamController(wire::call)
        try {
            controller.configureDesired(true, true)
            assertTrue(controller.ensureReady())
            assertEquals(0, wire.renewCount)
            assertNull(controller.credentials(P.STREAM_MAIN))
            assertNull(controller.credentials(P.STREAM_SECONDARY))
            assertTrue(controller.setConsumerReady(P.STREAM_MAIN, true))
            assertTrue(controller.setConsumerReady(P.STREAM_SECONDARY, true))
            controller.releaseLease(P.STREAM_SECONDARY)
            assertEquals(3, wire.mask)
            assertNull(controller.credentials(P.STREAM_SECONDARY))
            assertNotNull(controller.credentials(P.STREAM_MAIN))
            controller.configureDesired(true, true)
            assertTrue(controller.ensureReady(P.STREAM_MAIN))
            assertNull(controller.credentials(P.STREAM_SECONDARY))
            assertTrue(controller.setConsumerReady(P.STREAM_SECONDARY, true))
            assertNotNull(controller.credentials(P.STREAM_SECONDARY))
        } finally { controller.releaseApp() }
    }

    @Test fun secondaryOnlyAndSeparateStopPreserveOtherStream() {
        val wire = FakeWire()
        val controller = AppStreamController(wire::call)
        try {
            controller.configureDesired(false, true)
            assertTrue(controller.ensureReady())
            assertEquals(P.STREAM_SECONDARY, wire.mask)
            assertNull(controller.credentials(P.STREAM_MAIN))
            assertNull(controller.credentials(P.STREAM_SECONDARY))
            assertTrue(controller.setConsumerReady(P.STREAM_SECONDARY, true))
            assertNotNull(controller.credentials(P.STREAM_SECONDARY))
            assertTrue(controller.setDesired(P.STREAM_MAIN, true))
            assertEquals(3, wire.mask)
            assertNull(controller.credentials(P.STREAM_MAIN))
            assertTrue(controller.setDesired(P.STREAM_SECONDARY, false))
            assertEquals(P.STREAM_MAIN, wire.mask)
            assertNull(controller.credentials(P.STREAM_SECONDARY))
            assertTrue(controller.setConsumerReady(P.STREAM_MAIN, true))
            assertNotNull(controller.credentials(P.STREAM_MAIN))
        } finally { controller.releaseApp() }
    }

    @Test fun readinessDoesNotResetEpochOrEnableStoppedStream() {
        val wire = FakeWire()
        val controller = AppStreamController(wire::call)
        try {
            controller.configureDesired(true, true)
            assertTrue(controller.ensureReady())
            assertTrue(controller.setConsumerReady(P.STREAM_MAIN, true))
            assertTrue(controller.setConsumerReady(P.STREAM_SECONDARY, true))
            assertTrue(controller.setDesired(P.STREAM_SECONDARY, false))
            val epoch = wire.secondaryEpoch
            repeat(3) { assertTrue(controller.ensureReady()) }
            assertEquals(P.STREAM_MAIN, wire.mask)
            assertEquals(epoch, wire.secondaryEpoch)
        } finally { controller.releaseApp() }
    }

    @Test fun serviceReleaseLeavesDesiredFallbackAndRecreationReconcilesSettings() {
        val wire = FakeWire()
        val controller = AppStreamController(wire::call)
        controller.configureDesired(true, true, mainAutonomous = true, secondaryAutonomous = true)
        assertTrue(controller.ensureReady())
        assertEquals(3, wire.autonomyMask)
        assertNull(controller.credentials(P.STREAM_MAIN))
        assertEquals(0, wire.renewCount)
        assertTrue(controller.setConsumerReady(P.STREAM_MAIN, true))
        controller.releaseApp()
        assertEquals(3, wire.mask)
        assertNull(controller.credentials(P.STREAM_MAIN))
        assertFalse(controller.ensureReady())
        try {
            controller.configureDesired(false, true)
            assertTrue(controller.ensureReady())
            assertEquals(P.STREAM_SECONDARY, wire.mask)
            assertEquals(0, wire.autonomyMask)
            assertNull(controller.credentials(P.STREAM_SECONDARY))
            assertTrue(controller.setConsumerReady(P.STREAM_SECONDARY, true))
        } finally { controller.releaseApp() }
    }

    @Test fun desiredAndAutonomyReconcileWithoutFabricatingAnAppLease() {
        val wire = FakeWire()
        val controller = AppStreamController(wire::call)
        try {
            controller.configureDesired(true, true, mainAutonomous = true)
            assertEquals(0, wire.claimCount)
            assertTrue(controller.ensureReady())

            assertEquals(P.STREAM_MAIN, wire.autonomyMask)
            assertEquals(0, wire.leaseExpires[P.STREAM_MAIN])
            assertEquals(0, wire.renewCount)
            assertNull(controller.credentials(P.STREAM_MAIN))
            assertNull(controller.credentials(P.STREAM_SECONDARY))

            assertTrue(controller.setConsumerReady(P.STREAM_MAIN, true))
            assertEquals(1, wire.renewCount)
            assertEquals(2_000, wire.leaseExpires[P.STREAM_MAIN])
            assertNotNull(controller.credentials(P.STREAM_MAIN))
            assertNull(controller.credentials(P.STREAM_SECONDARY))
        } finally { controller.releaseApp() }
    }

    @Test fun readinessRevokedDuringClaimCannotRenewOrBeReinstated() {
        val wire = FakeWire()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val controller = AppStreamController { action, nonce, token, stream, epoch, value ->
            if (action == P.CONTROL_CLAIM) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            wire.call(action, nonce, token, stream, epoch, value)
        }
        try {
            controller.configureDesired(true, false)
            val starting = executor.submit<Boolean> { controller.setConsumerReady(P.STREAM_MAIN, true) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(controller.setConsumerReady(P.STREAM_MAIN, false))
            release.countDown()

            assertFalse(starting.get(2, TimeUnit.SECONDS))
            assertEquals(0, wire.renewCount)
            assertNull(controller.credentials(P.STREAM_MAIN))
        } finally {
            release.countDown()
            controller.releaseApp()
            executor.shutdownNow()
        }
    }

    @Test fun secondaryFenceDoesNotBlockMainReadinessOrRenewals() {
        val wire = FakeWire()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renewed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val controller = AppStreamController { action, nonce, token, stream, epoch, value ->
            if (action == P.CONTROL_PAUSE_FENCE) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            if (action == P.CONTROL_RENEW && entered.count == 0L) renewed.countDown()
            wire.call(action, nonce, token, stream, epoch, value)
        }
        try {
            controller.configureDesired(true, true)
            assertTrue(controller.ensureReady())
            assertTrue(controller.setConsumerReady(P.STREAM_MAIN, true))
            assertTrue(controller.setConsumerReady(P.STREAM_SECONDARY, true))
            val paused = executor.submit<Boolean> { controller.pauseAndFence(P.STREAM_SECONDARY) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(controller.ensureReady())
            assertTrue(renewed.await(2, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(paused.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            controller.releaseApp()
            executor.shutdownNow()
        }
    }

    @Test fun detachedConsumerCannotResumeHeartbeatOrRevokeSuccessorAfterLateRenewal() {
        val wire = FakeWire()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockFirst = java.util.concurrent.atomic.AtomicBoolean(true)
        val oldOwnerActive = java.util.concurrent.atomic.AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        val controller = AppStreamController { action, nonce, token, stream, epoch, value ->
            if (action == P.CONTROL_RENEW && blockFirst.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            wire.call(action, nonce, token, stream, epoch, value)
        }
        try {
            controller.configureDesired(false, true, secondaryAutonomous = true)
            val oldStart = executor.submit<Boolean> {
                controller.setConsumerReady(P.STREAM_SECONDARY, true, oldOwnerActive::get)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // Detachment revokes readiness before a potentially failed maintenance join.
            oldOwnerActive.set(false)
            controller.releaseLease(P.STREAM_SECONDARY)
            assertNull(controller.credentials(P.STREAM_SECONDARY))
            assertEquals(P.STREAM_SECONDARY, wire.autonomyMask)
            assertFalse(controller.setConsumerReady(P.STREAM_SECONDARY, true, oldOwnerActive::get))
            assertTrue(controller.setConsumerReady(P.STREAM_SECONDARY, true))
            release.countDown()
            assertFalse(oldStart.get(2, TimeUnit.SECONDS))
            assertNotNull(controller.credentials(P.STREAM_SECONDARY))
        } finally {
            release.countDown()
            controller.releaseApp()
            executor.shutdownNow()
        }
    }

    private class FakeWire {
        var mask = 0
        var autonomyMask = 0
        var claimCount = 0
        var renewCount = 0
        val leaseExpires = longArrayOf(0L, 0L, 0L)
        private var nonce: String? = null
        private var mainEpoch = 1L
        var secondaryEpoch = 1L
        @Synchronized fun call(action: Int, session: String, token: Long, stream: Int, epoch: Long, value: Int): DirectStreamControlResult {
            if (action == P.CONTROL_CLAIM) {
                claimCount++
                if (nonce == null) {
                    nonce = session
                    mask = value
                    autonomyMask = 0
                    leaseExpires.fill(0L)
                }
            } else {
                check(token == 42L)
                val current = if (stream == P.STREAM_MAIN) mainEpoch else secondaryEpoch
                if (current != epoch) return DirectStreamControlResult(P.STATUS_STALE_TOKEN, 42, mainEpoch, secondaryEpoch)
                when (action) {
                    P.CONTROL_SET_DESIRED -> {
                        mask = if (value == 1) mask or stream else mask and stream.inv()
                        if (value == 0) {
                            autonomyMask = autonomyMask and stream.inv()
                            leaseExpires[stream] = 0L
                        }
                        if (stream == P.STREAM_MAIN) mainEpoch++ else secondaryEpoch++
                    }
                    P.CONTROL_PAUSE_FENCE, P.CONTROL_RESUME -> {
                        if (stream == P.STREAM_MAIN) mainEpoch++ else secondaryEpoch++
                    }
                    P.CONTROL_SET_AUTONOMY -> {
                        if (value == 1 && mask and stream == 0) {
                            return DirectStreamControlResult(P.STATUS_INVALID_REQUEST)
                        }
                        autonomyMask = if (value == 1) autonomyMask or stream else autonomyMask and stream.inv()
                    }
                    P.CONTROL_RENEW -> {
                        if (mask and stream == 0) return DirectStreamControlResult(P.STATUS_STALE_TOKEN)
                        renewCount++
                        leaseExpires[stream] += 2_000L
                    }
                }
            }
            return DirectStreamControlResult(
                P.STATUS_OK, 42, mainEpoch, secondaryEpoch,
                if (stream == P.STREAM_MAIN || stream == P.STREAM_SECONDARY) leaseExpires[stream] else 0L
            )
        }
    }
}
