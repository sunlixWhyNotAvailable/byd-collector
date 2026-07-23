package com.bydcollector.collector.telegram

import kotlin.math.max

class TelegramRetryPolicy {
    fun delayForFailure(failureCount: Int, retryAfterSeconds: Long? = null): Long {
        if (failureCount <= 0) return 0L
        val exponentialDelay = if (failureCount >= MAX_DOUBLING_FAILURE) {
            MAX_DELAY_MS
        } else {
            BASE_DELAY_MS shl (failureCount - 1)
        }
        val serverDelay = retryAfterSeconds
            ?.coerceAtLeast(0L)
            ?.let { if (it > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else it * 1_000L }
            ?: 0L
        return max(exponentialDelay, serverDelay)
    }

    fun failureCountAfterSuccess(): Int = 0

    private companion object {
        const val BASE_DELAY_MS = 30_000L
        const val MAX_DELAY_MS = 30L * 60L * 1_000L
        const val MAX_DOUBLING_FAILURE = 7
    }
}
