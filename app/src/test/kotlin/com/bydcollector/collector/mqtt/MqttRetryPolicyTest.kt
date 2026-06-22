package com.bydcollector.collector.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MqttRetryPolicyTest {
    @Test
    fun delayForFailureUsesBoundedSequence() {
        val policy = MqttRetryPolicy()

        assertEquals(5_000L, policy.delayForFailure(1))
        assertEquals(15_000L, policy.delayForFailure(2))
        assertEquals(30_000L, policy.delayForFailure(3))
        assertEquals(60_000L, policy.delayForFailure(4))
        assertEquals(120_000L, policy.delayForFailure(5))
        assertEquals(300_000L, policy.delayForFailure(6))
        assertEquals(300_000L, policy.delayForFailure(42))
    }

    @Test
    fun jitterIsDeterministicAndClamped() {
        val policy = MqttRetryPolicy()

        val first = policy.withJitter(60_000L, "bydcollector/state/battery")
        val second = policy.withJitter(60_000L, "bydcollector/state/battery")

        assertEquals(first, second)
        assertTrue(first in 54_000L..66_000L)
        assertEquals(1_000L, policy.withJitter(500L, "short-delay"))
        assertEquals(300_000L, policy.withJitter(600_000L, "long-delay"))
    }

    @Test
    fun successResetsFailureCountToZero() {
        val policy = MqttRetryPolicy()

        assertEquals(0, policy.failureCountAfterSuccess())
    }

    @Test
    fun forceAllowsImmediateAttemptEvenWhenBackoffIsNotDue() {
        val policy = MqttRetryPolicy()
        val state = MqttRetryState(
            failureCount = 2,
            nextAttemptAt = "2026-06-14T12:01:00+03:00",
            lastFailureAt = "2026-06-14T12:00:00+03:00",
            lastSuccessAt = null,
            lastError = "connect failed"
        )

        assertFalse(policy.canAttempt(state, nowIso = "2026-06-14T12:00:30+03:00", force = false))
        assertTrue(policy.canAttempt(state, nowIso = "2026-06-14T12:00:30+03:00", force = true))
        assertTrue(policy.canAttempt(state, nowIso = "2026-06-14T12:01:00+03:00", force = false))
    }
}
