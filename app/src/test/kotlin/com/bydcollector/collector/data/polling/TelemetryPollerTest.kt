package com.bydcollector.collector.data.polling

import com.bydcollector.collector.data.local.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryPollerTest {
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
        val results = mutableListOf<PollCycleResult>()
        val poller = TelemetryPoller(
            coordinator = object : PollCycleRunner {
                override fun pollOnce(sessionId: Long): PollCycleResult {
                    throw IllegalStateException("database locked")
                }
            },
            clock = clock,
            intervalMs = 1_000,
            onCycleResult = { results += it },
            sleeper = { pollerStopSignal() }
        )

        poller.start(sessionId = 1L)
        Thread.sleep(20)
        poller.stop()

        assertTrue(results.isNotEmpty())
        assertEquals(false, results.first().ok)
        assertEquals("poller_runtime_error", results.first().category)
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
