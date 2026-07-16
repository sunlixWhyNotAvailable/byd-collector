package com.bydcollector.collector.adb

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbPipelineCoordinatorTest {
    @Test
    fun normalRequestsAreSingleFlight() {
        val coordinator = AdbPipelineCoordinator()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        assertTrue(coordinator.submit(AccessCheckMode.NORMAL) {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertFalse(coordinator.submit(AccessCheckMode.NORMAL) { error("must not run") })
        release.countDown()
    }

    @Test
    fun forceCancelsThePreviousRunBeforeStartingFreshWork() {
        val coordinator = AdbPipelineCoordinator()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val firstStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val stalePublished = AtomicBoolean(false)

        coordinator.submit(AccessCheckMode.NORMAL) { lease ->
            events += "first_started"
            firstStarted.countDown()
            try {
                while (true) {
                    lease.cancellation.throwIfCancelled()
                    Thread.sleep(5_000)
                }
            } catch (_: Exception) {
                events += "first_ended"
                stalePublished.set(coordinator.publishIfCurrent(lease) { })
            }
        }
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS))

        assertTrue(coordinator.submit(AccessCheckMode.FORCE) {
            events += "second_started"
            secondFinished.countDown()
        })

        assertTrue(secondFinished.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("first_started", "first_ended", "second_started"), events.toList())
        assertFalse(stalePublished.get())
    }

    @Test
    fun coldStartReplacesABackgroundNormalCheck() {
        val coordinator = AdbPipelineCoordinator()
        val normalStarted = CountDownLatch(1)
        val coldStarted = CountDownLatch(1)

        coordinator.submit(AccessCheckMode.NORMAL) { lease ->
            normalStarted.countDown()
            while (!lease.cancellation.isCancelled) Thread.sleep(10)
        }
        assertTrue(normalStarted.await(1, TimeUnit.SECONDS))

        assertTrue(coordinator.submit(AccessCheckMode.COLD_START) {
            coldStarted.countDown()
        })

        assertTrue(coldStarted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun forceReturnsBeforeAnUncooperativePreviousRunEndsButStartsAfterIt() {
        val coordinator = AdbPipelineCoordinator()
        val firstStarted = CountDownLatch(1)
        val allowFirstExit = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)

        coordinator.submit(AccessCheckMode.NORMAL) {
            firstStarted.countDown()
            while (true) {
                try {
                    allowFirstExit.await()
                    break
                } catch (_: InterruptedException) {
                    //simulates cleanup that must finish before the replacement can start
                }
            }
        }
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        assertTrue(coordinator.submit(AccessCheckMode.FORCE) { secondStarted.countDown() })
        val submitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(submitMs < 500, "FORCE cancellation must not block the caller")
        assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS))
        allowFirstExit.countDown()
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun newestForceSupersedesAnEarlierQueuedForce() {
        val coordinator = AdbPipelineCoordinator()
        val firstStarted = CountDownLatch(1)
        val allowFirstExit = CountDownLatch(1)
        val latestFinished = CountDownLatch(1)
        val supersededRan = AtomicBoolean(false)

        coordinator.submit(AccessCheckMode.NORMAL) {
            firstStarted.countDown()
            while (true) {
                try {
                    allowFirstExit.await()
                    break
                } catch (_: InterruptedException) {
                    //keeps the pipeline occupied until both replacements are queued
                }
            }
        }
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS))

        assertTrue(coordinator.submit(AccessCheckMode.FORCE) { supersededRan.set(true) })
        assertTrue(coordinator.submit(AccessCheckMode.FORCE) { latestFinished.countDown() })
        allowFirstExit.countDown()

        assertTrue(latestFinished.await(2, TimeUnit.SECONDS))
        assertFalse(supersededRan.get())
    }

    @Test
    fun automaticRepairIsRateLimitedButForceCanBypassTheDecision() {
        val throttle = AccessRepairThrottle(intervalMs = 60_000)

        assertTrue(throttle.tryAcquire(1_000))
        assertFalse(throttle.tryAcquire(60_999))
        assertTrue(throttle.tryAcquire(61_000))
    }
}
