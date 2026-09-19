package com.bydcollector.collector.data.polling

import com.bydcollector.collector.data.local.Clock
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryPollerTest {
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
        val resultReady = CountDownLatch(1)
        val poller = TelemetryPoller(
            coordinator = object : PollCycleRunner {
                override fun pollOnce(sessionId: Long): PollCycleResult {
                    throw Exception("Android checked exception")
                }
            },
            clock = clock,
            intervalMs = 1_000,
            onCycleResult = {
                results += it
                resultReady.countDown()
            },
            sleeper = { pollerStopSignal() }
        )

        poller.start(sessionId = 1L)
        assertTrue(resultReady.await(1, TimeUnit.SECONDS))
        poller.stop()

        assertTrue(results.isNotEmpty())
        assertEquals(false, results.first().ok)
        assertEquals("poller_runtime_error", results.first().category)
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
    fun replayPendingCycleDoesNotPublishFakeSuccess() {
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
        assertTrue(results.isEmpty())
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
