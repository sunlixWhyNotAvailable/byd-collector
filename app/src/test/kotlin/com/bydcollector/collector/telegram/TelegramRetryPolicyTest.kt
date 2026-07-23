package com.bydcollector.collector.telegram

import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramRetryPolicyTest {
    @Test
    fun doublesFromThirtySecondsAndCapsAtThirtyMinutes() {
        val policy = TelegramRetryPolicy()

        assertEquals(0L, policy.delayForFailure(0))
        assertEquals(30_000L, policy.delayForFailure(1))
        assertEquals(60_000L, policy.delayForFailure(2))
        assertEquals(120_000L, policy.delayForFailure(3))
        assertEquals(960_000L, policy.delayForFailure(6))
        assertEquals(1_800_000L, policy.delayForFailure(7))
        assertEquals(1_800_000L, policy.delayForFailure(Int.MAX_VALUE))
    }

    @Test
    fun retryAfterCanDelayARetryBeyondExponentialBackoff() {
        val policy = TelegramRetryPolicy()

        assertEquals(90_000L, policy.delayForFailure(1, retryAfterSeconds = 90))
        assertEquals(120_000L, policy.delayForFailure(3, retryAfterSeconds = 10))
        assertEquals(0, policy.failureCountAfterSuccess())
    }
}
