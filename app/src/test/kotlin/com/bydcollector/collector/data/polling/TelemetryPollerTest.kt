package com.bydcollector.collector.data.polling

import com.bydcollector.collector.data.local.Clock
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TelemetryPollerTest {
    @Test
    fun stoppedWorkerReleasesOwnershipBeforeAnotherWorkerCanStart() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = java.util.concurrent.atomic.AtomicInteger()
        val poller = TelemetryPoller(
            coordinator = object : PollCycleRunner {
                override fun pollOnce(sessionId: Long): PollCycleResult? {
                    entered.countDown()
                    // Models a native operation that cannot be interrupted immediately.
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                    return null
                }
            },
            clock = FakeClock(),
            onStopped = { ended.incrementAndGet() }
        )
        try {
            assertTrue(poller.start(1))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(poller.stopAndJoin(5))
            assertTrue(poller.isStopping())
            assertFalse(poller.start(2), "old worker still owns teardown")
            release.countDown()
            assertTrue(poller.stopAndJoin(1_000))
            assertFalse(poller.isStopping())
            assertEquals(1, ended.get())
            assertTrue(poller.start(2))
            assertTrue(poller.stopAndJoin(1_000))
            assertEquals(2, ended.get())
        } finally {
            release.countDown()
            poller.stopAndJoin(1_000)
        }
    }

    @Test
    fun defaultIntervalIsHalfSecond() {
        assertEquals(500L, TelemetryPoller.DEFAULT_INTERVAL_MS)
    }

    @Test
    fun slowPollSkipsSleepAndDoesNotOverlap() {
        val clock = FakeClock()
        val runner = object : PollCycleRunner {
            override fun pollOnce(sessionId: Long): PollCycleResult {
                clock.elapsed += 1_200
                return PollCycleResult(1L, ok = true, category = null, elapsedMs = 1_200, requestCount = 1)
            }
        }
        var sleepCalls = 0
        val poller = TelemetryPoller(runner, clock, intervalMs = 1_000) {
            sleepCalls++
            pollerStopSignal()
        }

        poller.start(sessionId = 1L)
        Thread.sleep(20)
        poller.stop()

        assertEquals(0, sleepCalls)
    }

    @Test
    fun runtimeFailureReportsCycleResultBeforeContinuing() {
        val clock = FakeClock()
        val results = Collections.synchronizedList(mutableListOf<PollCycleResult>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val resultReady = CountDownLatch(1)
        val pollFailure = IllegalStateException("poll failed", IOException("transport closed"))
        val poller = TelemetryPoller(
            coordinator = object : PollCycleRunner {
                override fun pollOnce(sessionId: Long): PollCycleResult {
                    throw pollFailure
                }
            },
            clock = clock,
            intervalMs = 1_000,
            onCycleResult = {
                results += it
                resultReady.countDown()
            },
            onRuntimeError = { errors += it },
            sleeper = { pollerStopSignal() }
        )

        poller.start(sessionId = 1L)
        assertTrue(resultReady.await(1, TimeUnit.SECONDS))
        poller.stop()

        assertTrue(results.isNotEmpty())
        assertEquals(false, results.first().ok)
        assertEquals("poller_runtime_error", results.first().category)
        assertTrue(results.first().errorMessage!!.contains("poll failed"))
        assertSame(pollFailure, errors.single())
    }

    @Test
    fun emptyWorkerCycleDoesNotPublishFakeStatus() {
        val results = mutableListOf<PollCycleResult>()
        val cycleFinished = CountDownLatch(1)
        val poller = TelemetryPoller(
            coordinator = object : PollCycleRunner {
                override fun pollOnce(sessionId: Long): PollCycleResult? = null
            },
            clock = FakeClock(),
            onCycleResult = { results += it },
            sleeper = {
                cycleFinished.countDown()
                pollerStopSignal()
            }
        )

        poller.start(sessionId = 1L)
        assertTrue(cycleFinished.await(1, TimeUnit.SECONDS))
        poller.stop()

        assertTrue(results.isEmpty())
    }

    @Test
    fun replayPendingCycleIsDeliveredAsNeutralDeferral() {
        val results = mutableListOf<PollCycleResult>()
        val cycleFinished = CountDownLatch(1)
        val poller = TelemetryPoller(
            coordinator = object : PollCycleRunner {
                override fun pollOnce(sessionId: Long) = PollCycleResult(null, true, null, 3, 0, deferred = true)
            },
            clock = FakeClock(),
            onCycleResult = { results += it },
            sleeper = { cycleFinished.countDown(); pollerStopSignal() }
        )
        poller.start(1L)
        assertTrue(cycleFinished.await(1, TimeUnit.SECONDS))
        assertTrue(poller.stopAndJoin(1_000))
        assertEquals(1, results.size)
        assertTrue(results.single().deferred)
        assertTrue(results.single().category == null)
        assertTrue(results.single().errorMessage == null)
    }

    @Test
    fun stopInducedIllegalStateDoesNotReportRuntimeFailure() {
        val entered = CountDownLatch(1)
        val cycles = Collections.synchronizedList(mutableListOf<PollCycleResult>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val poller = TelemetryPoller(
            coordinator = object : PollCycleRunner {
                override fun pollOnce(sessionId: Long): PollCycleResult? {
                    entered.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (_: InterruptedException) {
                        // Models a platform call that clears interruption before reporting its close error.
                        Thread.interrupted()
                        throw IllegalStateException("transport closed during Stop")
                    }
                    return null
                }
            },
            clock = FakeClock(),
            onCycleResult = { cycles += it },
            onRuntimeError = { errors += it }
        )

        assertTrue(poller.start(1L))
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertTrue(poller.stopAndJoin(1_000))

        assertTrue(cycles.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun stopAndJoinWaitsForWorkerToStop() {
        val poller = TelemetryPoller(
            coordinator = object : PollCycleRunner {
                override fun pollOnce(sessionId: Long): PollCycleResult {
                    Thread.sleep(10_000)
                    return PollCycleResult(1L, ok = true, category = null, elapsedMs = 0, requestCount = 0)
                }
            },
            sleeper = { Thread.sleep(it) }
        )

        assertTrue(poller.start(sessionId = 1L))

        assertTrue(poller.stopAndJoin(timeoutMs = 1_000L))
        assertFalse(poller.isRunning())
    }

    private fun pollerStopSignal() {
        throw InterruptedException("stop test loop")
    }

    private class FakeClock : Clock {
        var elapsed: Long = 0
        override fun nowIso(): String = "2026-05-25T00:00:00Z"
        override fun elapsedRealtimeMs(): Long = elapsed
    }
}
