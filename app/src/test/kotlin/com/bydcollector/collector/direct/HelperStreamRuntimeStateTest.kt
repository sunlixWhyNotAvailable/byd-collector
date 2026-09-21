package com.bydcollector.collector.direct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HelperStreamRuntimeStateTest {
    @Test
    fun claimIsProcessNonceIdempotentAndNewNonceAtomicallyReplacesBothStreams() {
        val state = HelperStreamRuntimeState()
        val first = state.claim("app-a", 3, 100)
        val duplicate = state.claim("app-a", 0, 1_900)

        assertTrue(first.controllerToken > 0)
        assertEquals(first.controllerToken, duplicate.controllerToken)
        assertEquals(first.mainEpoch, duplicate.mainEpoch)
        assertEquals(first.secondaryEpoch, duplicate.secondaryEpoch)
        assertEquals(0, duplicate.leaseExpiresElapsedMs)
        assertTrue(state.replayAllowed(CollectorHelperProtocol.STREAM_MAIN, 1_999))
        assertTrue(state.replayAllowed(CollectorHelperProtocol.STREAM_SECONDARY, 1_999))

        val replacement = state.claim("app-b", 2, 2_000)
        assertNotEquals(first.controllerToken, replacement.controllerToken)
        assertTrue(!state.fallbackAllowed(CollectorHelperProtocol.STREAM_MAIN, 4_000))
        assertTrue(state.fallbackAllowed(CollectorHelperProtocol.STREAM_SECONDARY, 4_000))
        assertEquals(
            CollectorHelperProtocol.STATUS_STALE_TOKEN,
            state.renew(first.controllerToken, CollectorHelperProtocol.STREAM_SECONDARY,
                first.secondaryEpoch, 2_100).status
        )
    }

    @Test
    fun leasesAreIndependentAndPassiveActivityCannotRenewThem() {
        val state = HelperStreamRuntimeState()
        val claim = state.claim("app", 3, 0)
        val renewed = state.renew(claim.controllerToken, CollectorHelperProtocol.STREAM_MAIN,
            claim.mainEpoch, 1_500)

        assertEquals(3_500, renewed.leaseExpiresElapsedMs)
        assertTrue(state.fallbackAllowed(CollectorHelperProtocol.STREAM_SECONDARY, 2_000))
        assertTrue(!state.fallbackAllowed(CollectorHelperProtocol.STREAM_MAIN, 2_000))
        state.snapshot(CollectorHelperProtocol.STREAM_SECONDARY, 2_500)
        assertTrue(state.fallbackAllowed(CollectorHelperProtocol.STREAM_SECONDARY, 2_500))
    }

    @Test
    fun sameDesiredIsNoOpAndStopRetainsOwnershipButDisablesWork() {
        val state = HelperStreamRuntimeState()
        val claim = state.claim("app", 1, 0)
        val same = state.setDesired(claim.controllerToken, CollectorHelperProtocol.STREAM_MAIN,
            claim.mainEpoch, 1, 500)
        assertEquals(claim.mainEpoch, same.mainEpoch)
        assertEquals(2_000, same.leaseExpiresElapsedMs)

        val stopped = state.setDesired(claim.controllerToken, CollectorHelperProtocol.STREAM_MAIN,
            claim.mainEpoch, 0, 600)
        assertEquals(claim.mainEpoch + 1, stopped.mainEpoch)
        assertTrue(!state.replayAllowed(CollectorHelperProtocol.STREAM_MAIN, 700))
        assertTrue(!state.fallbackAllowed(CollectorHelperProtocol.STREAM_MAIN, 700))
    }

    @Test
    fun pauseKeepsOldEpochRenewableUntilFenceCompletes() {
        val state = HelperStreamRuntimeState()
        val claim = state.claim("app", 2, 0)
        val pending = state.beginPause(claim.controllerToken, CollectorHelperProtocol.STREAM_SECONDARY,
            claim.secondaryEpoch, 1_000)
        assertEquals(claim.secondaryEpoch, pending.secondaryEpoch)

        val renewed = state.renew(claim.controllerToken, CollectorHelperProtocol.STREAM_SECONDARY,
            claim.secondaryEpoch, 1_900)
        assertEquals(3_900, renewed.leaseExpiresElapsedMs)
        val paused = state.completePause(claim.controllerToken, CollectorHelperProtocol.STREAM_SECONDARY,
            claim.secondaryEpoch, 2_100)
        assertEquals(claim.secondaryEpoch + 1, paused.secondaryEpoch)
        assertEquals(CollectorHelperProtocol.STATUS_REPLAY_PENDING,
            state.authorizeLive(claim.controllerToken, CollectorHelperProtocol.STREAM_SECONDARY,
                paused.secondaryEpoch, 2_200))
    }

    @Test
    fun expiredPendingPauseUnsticksAndResumesFallback() {
        val state = HelperStreamRuntimeState()
        val claim = state.claim("app", 2, 0)
        state.beginPause(claim.controllerToken, CollectorHelperProtocol.STREAM_SECONDARY,
            claim.secondaryEpoch, 1_900)

        val completed = state.completePause(claim.controllerToken, CollectorHelperProtocol.STREAM_SECONDARY,
            claim.secondaryEpoch, 2_000)
        assertEquals(CollectorHelperProtocol.STATUS_LEASE_EXPIRED, completed.status)
        assertTrue(state.fallbackAllowed(CollectorHelperProtocol.STREAM_SECONDARY, 2_000))
    }
}
