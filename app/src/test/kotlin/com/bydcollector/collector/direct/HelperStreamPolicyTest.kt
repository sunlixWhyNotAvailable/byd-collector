package com.bydcollector.collector.direct

import com.bydcollector.collector.direct.CollectorHelperProtocol as P
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelperStreamPolicyTest {
    @Test
    fun dueReadsStayBoundedAndReplacedDeadlinesNeverReplay() {
        val schedule = HelperDueAddressSchedule(23_020, 100L)
        assertTrue(schedule.takeDue(99L, 128).isEmpty())
        assertEquals(1L, schedule.nextDelayMs(99L, 60_000L))
        val observed = mutableSetOf<Int>()
        while (schedule.size() > 0) {
            val page = schedule.takeDue(100L, 128)
            assertTrue(page.size in 1..128)
            page.forEach { assertTrue(observed.add(it), "Repeated catalog ordinal $it") }
        }
        assertEquals((0 until schedule.fieldCount()).toSet(), observed)
        assertTrue(schedule.takeDue(1_000_000L, 128).isEmpty())
        assertEquals(60_000L, schedule.nextDelayMs(1_000_000L, 60_000L))

        schedule.schedule(19, 500L)
        schedule.schedule(19, 2_000L)
        schedule.schedule(8, 500L)
        schedule.schedule(8, Long.MAX_VALUE)
        assertTrue(schedule.takeDue(500L, 128).isEmpty())
        assertEquals(1_500L, schedule.nextDelayMs(500L, 60_000L))
        assertEquals(listOf(19), schedule.takeDue(2_000L, 128))
        assertTrue(schedule.takeDue(2_001L, 128).isEmpty())
    }

    @Test
    fun reattachRetainsAutonomyButManualCaptureStillRequiresLease() {
        val state = HelperStreamRuntimeState()
        val first = state.claim("first-app", P.STREAM_MAIN or P.STREAM_SECONDARY, 0L)
        assertEquals(P.STATUS_OK, first.status)
        assertEquals(P.STATUS_OK, state.setAutonomyAllowed(
            first.controllerToken, P.STREAM_MAIN, first.mainEpoch, 1, 0L
        ).status)
        assertEquals(P.STATUS_OK, state.renew(
            first.controllerToken, P.STREAM_SECONDARY, first.secondaryEpoch, 100L
        ).status)
        assertTrue(state.callbackCaptureAllowed(P.STREAM_MAIN, 100L))
        assertTrue(state.callbackCaptureAllowed(P.STREAM_SECONDARY, 100L))
        val expired = 100L + HelperStreamRuntimeState.LEASE_MS
        assertTrue(state.callbackCaptureAllowed(P.STREAM_MAIN, expired))
        assertFalse(state.callbackCaptureAllowed(P.STREAM_SECONDARY, expired))

        val resumed = state.claim("restarted-app", P.STREAM_MAIN or P.STREAM_SECONDARY, expired)
        assertEquals(P.STATUS_OK, resumed.status)
        assertTrue(state.callbackCaptureAllowed(P.STREAM_MAIN, expired))
        assertTrue(state.fallbackAllowed(P.STREAM_MAIN, expired))
        assertFalse(state.callbackCaptureAllowed(P.STREAM_SECONDARY, expired))
        assertEquals(P.STATUS_STALE_TOKEN, state.renew(
            first.controllerToken, P.STREAM_MAIN, first.mainEpoch, expired
        ).status)
        assertEquals(P.STATUS_OK, state.renew(
            resumed.controllerToken, P.STREAM_SECONDARY, resumed.secondaryEpoch, expired
        ).status)
        assertTrue(state.callbackCaptureAllowed(P.STREAM_SECONDARY, expired))

        assertEquals(P.STATUS_OK, state.renew(
            resumed.controllerToken, P.STREAM_MAIN, resumed.mainEpoch, expired
        ).status)
        assertEquals(P.STATUS_OK, state.beginPause(
            resumed.controllerToken, P.STREAM_MAIN, resumed.mainEpoch, expired
        ).status)
        val paused = state.completePause(resumed.controllerToken, P.STREAM_MAIN, resumed.mainEpoch, expired)
        assertEquals(P.STATUS_OK, paused.status)
        assertTrue(state.callbackCaptureAllowed(P.STREAM_MAIN, expired))
        assertTrue(state.callbackPublishingHeld(P.STREAM_MAIN, expired))
        val active = state.resume(resumed.controllerToken, P.STREAM_MAIN, paused.mainEpoch, expired)
        assertEquals(P.STATUS_OK, active.status)
        assertTrue(state.callbackCaptureAllowed(P.STREAM_MAIN, expired))
        assertFalse(state.callbackPublishingHeld(P.STREAM_MAIN, expired))
        assertEquals(P.STATUS_OK, state.setDesired(
            resumed.controllerToken, P.STREAM_MAIN, active.mainEpoch, 0, expired
        ).status)
        assertFalse(state.callbackCaptureAllowed(P.STREAM_MAIN, expired))
        assertFalse(state.fallbackAllowed(P.STREAM_MAIN, expired + HelperStreamRuntimeState.LEASE_MS))
        assertTrue(state.callbackCaptureAllowed(P.STREAM_SECONDARY, expired))
    }
}
