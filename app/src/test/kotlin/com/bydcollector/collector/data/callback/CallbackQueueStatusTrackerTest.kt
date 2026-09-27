package com.bydcollector.collector.data.callback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallbackQueueStatusTrackerTest {
    @Test fun `catching up requires a real old head and fresh commit progress`() {
        val clock = FakeClock()
        val tracker = clock.tracker().apply { start() }
        tracker.downloadStarted()
        tracker.pendingBatchObserved(oldestEventWallMs = 0L)

        clock.advance(5_001L)
        val stalled = tracker.snapshot()
        assertEquals(CallbackQueuePhase.WAITING, stalled.phase)
        assertTrue(stalled.reason.orEmpty().contains("no commit/ACK progress"))

        tracker.rawCommitCompleted()
        val catchingUp = tracker.snapshot()
        assertEquals(CallbackQueuePhase.CATCHING_UP, catchingUp.phase)
        assertTrue(catchingUp.reason.orEmpty().contains("pending callback head is 5s old"))

        clock.advance(5_001L)
        val stalledAck = tracker.snapshot()
        assertEquals(CallbackQueuePhase.WAITING, stalledAck.phase)
        assertTrue(stalledAck.reason.orEmpty().contains("no commit/ACK progress"))
    }

    @Test fun `successful ACK clears the exact observed head rather than reusing its old timestamp`() {
        val clock = FakeClock(wall = 10_000L)
        val tracker = clock.tracker().apply { start() }
        tracker.pendingBatchObserved(oldestEventWallMs = 0L)
        tracker.rawCommitCompleted()
        assertEquals(CallbackQueuePhase.CATCHING_UP, tracker.snapshot().phase)

        tracker.pendingBatchCompleted()
        clock.advance(5_001L)
        val afterAck = tracker.snapshot()
        assertFalse(afterAck.phase == CallbackQueuePhase.CATCHING_UP)
        assertFalse(afterAck.reason.orEmpty().contains("pending callback head"))
        assertEquals(CallbackQueuePhase.WAITING, afterAck.phase)
    }

    @Test fun `explicit empty holds prior yellow briefly and then requires stable health`() {
        val clock = FakeClock(wall = 10_000L)
        val tracker = clock.tracker().apply { start() }
        tracker.pendingBatchObserved(oldestEventWallMs = 0L)
        tracker.rawCommitCompleted()
        assertEquals(CallbackQueuePhase.CATCHING_UP, tracker.snapshot().phase)

        tracker.confirmedEmpty()
        assertEquals(CallbackQueuePhase.CATCHING_UP, tracker.snapshot().phase)
        clock.advance(1_000L)
        tracker.confirmedEmpty() // repeated EMPTY must not restart the two-second window
        clock.advance(999L)
        assertEquals(CallbackQueuePhase.CATCHING_UP, tracker.snapshot().phase)
        clock.advance(1L)
        assertEquals(CallbackQueuePhase.HEALTHY, tracker.snapshot().phase)

        tracker.downloadStarted()
        clock.advance(5_001L)
        assertEquals(CallbackQueuePhase.WAITING, tracker.snapshot().phase)
        tracker.confirmedEmpty()
        assertEquals(CallbackQueuePhase.UNKNOWN, tracker.snapshot().phase)
        clock.advance(2_000L)
        assertEquals(CallbackQueuePhase.HEALTHY, tracker.snapshot().phase)
    }

    @Test fun `startup empty window can mature through continuous fresh packets`() {
        val clock = FakeClock()
        val tracker = clock.tracker().apply { start() }
        tracker.confirmedEmpty()

        repeat(2) {
            tracker.downloadStarted()
            tracker.pendingBatchObserved(oldestEventWallMs = clock.wall)
            tracker.rawCommitCompleted()
            tracker.pendingBatchCompleted()
            if (it == 0) {
                assertEquals(CallbackQueuePhase.UNKNOWN, tracker.snapshot().phase)
                clock.advance(1_000L)
            }
        }

        clock.advance(1_000L)
        assertEquals(CallbackQueuePhase.HEALTHY, tracker.snapshot().phase)
    }

    @Test fun `established healthy state survives continuous fresh callback buffering`() {
        val clock = FakeClock()
        val tracker = clock.tracker().apply { start() }
        tracker.confirmedEmpty()
        clock.advance(2_000L)
        assertEquals(CallbackQueuePhase.HEALTHY, tracker.snapshot().phase)

        repeat(8) {
            tracker.downloadStarted()
            tracker.pendingBatchObserved(oldestEventWallMs = clock.wall)
            assertEquals(CallbackQueuePhase.HEALTHY, tracker.snapshot().phase)
            tracker.rawCommitCompleted()
            tracker.pendingBatchCompleted()
            assertEquals(CallbackQueuePhase.HEALTHY, tracker.snapshot().phase)
            clock.advance(1_000L)
        }
    }

    @Test fun `catch up handoff survives ACK to next observed head without a UI sample`() {
        val clock = FakeClock(wall = 10_000L)
        val tracker = clock.tracker().apply { start() }
        tracker.pendingBatchObserved(oldestEventWallMs = 0L)
        clock.advance(5_001L)
        tracker.rawCommitCompleted() // establishes catch-up from real progress, without sampling
        tracker.pendingBatchCompleted()

        tracker.downloadStarted()
        tracker.pendingBatchObserved(oldestEventWallMs = clock.wall)
        val handoff = tracker.snapshot()
        assertEquals(CallbackQueuePhase.CATCHING_UP, handoff.phase)
        assertTrue(handoff.reason.orEmpty().contains("confirming callback queue progress"))

        clock.advance(2_001L)
        val settled = tracker.snapshot()
        assertFalse(settled.phase == CallbackQueuePhase.CATCHING_UP)
        assertEquals(CallbackQueuePhase.UNKNOWN, settled.phase)
    }

    @Test fun `a stalled download becomes waiting without inventing a pending head`() {
        val clock = FakeClock()
        val tracker = clock.tracker().apply { start() }
        tracker.downloadStarted()
        clock.advance(5_001L)

        val state = tracker.snapshot()
        assertEquals(CallbackQueuePhase.WAITING, state.phase)
        assertTrue(state.reason.orEmpty().contains("download has not returned"))
        assertFalse(state.reason.orEmpty().contains("pending callback head"))
    }

    @Test fun `missing event timestamp cannot qualify as aged queue work`() {
        val clock = FakeClock()
        val tracker = clock.tracker().apply { start() }
        tracker.pendingBatchObserved(oldestEventWallMs = null)
        clock.advance(20_000L)
        tracker.rawCommitCompleted()

        val state = tracker.snapshot()
        assertEquals(CallbackQueuePhase.WAITING, state.phase)
        assertTrue(state.reason.orEmpty().contains("no event timestamp"))
    }

    @Test fun `fault reason persists until recovery and every stop start resets state`() {
        val clock = FakeClock()
        val tracker = clock.tracker().apply { start() }
        tracker.failed("SQLite locked")
        val failure = tracker.snapshot()
        assertEquals(CallbackQueuePhase.ERROR, failure.phase)
        assertEquals("SQLite locked", failure.reason)

        tracker.confirmedEmpty()
        assertEquals(CallbackQueuePhase.UNKNOWN, tracker.snapshot().phase)
        clock.advance(2_000L)
        assertEquals(CallbackQueuePhase.HEALTHY, tracker.snapshot().phase)

        tracker.stop()
        assertEquals(CallbackQueuePhase.UNKNOWN, tracker.snapshot().phase)
        tracker.start()
        val restarted = tracker.snapshot()
        assertEquals(CallbackQueuePhase.UNKNOWN, restarted.phase)
        assertEquals(null, restarted.reason)
    }

    private class FakeClock(var wall: Long = 0L, var elapsed: Long = 0L) {
        fun tracker() = CallbackQueueStatusTracker(
            monotonicMs = { elapsed },
            wallTimeMs = { wall }
        )

        fun advance(ms: Long) {
            wall += ms
            elapsed += ms
        }
    }
}
