package com.bydcollector.collector.location

/** Bounds GPS start retries and reports only the first failure in an outage. */
class GpsStartRetryGate {
    private var failureCount = 0
    private var nextAttemptAtMs = Long.MIN_VALUE
    private var outageReported = false

    fun canAttempt(nowMs: Long): Boolean = nowMs >= nextAttemptAtMs

    /** Returns true only for the first failed attempt in the current outage. */
    fun onFailure(nowMs: Long): Boolean {
        val delayMs = RETRY_DELAYS_MS[failureCount.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        failureCount += 1
        nextAttemptAtMs = if (nowMs > Long.MAX_VALUE - delayMs) Long.MAX_VALUE else nowMs + delayMs
        if (outageReported) return false
        outageReported = true
        return true
    }

    fun reset() {
        failureCount = 0
        nextAttemptAtMs = Long.MIN_VALUE
        outageReported = false
    }

    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }
}
