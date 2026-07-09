package com.bydcollector.collector.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MqttRetryPolicyTest {
    @Test
    fun delayForFailureUsesFixedThirtySecondRetry() {
        val policy = MqttRetryPolicy()

        assertEquals(0L, policy.delayForFailure(0))
        listOf(1, 2, 3, 4, 5, 42).forEach { failureCount ->
            assertEquals(30_000L, policy.delayForFailure(failureCount))
        }
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
