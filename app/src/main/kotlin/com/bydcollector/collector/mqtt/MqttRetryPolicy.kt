package com.bydcollector.collector.mqtt

import java.time.OffsetDateTime

data class MqttRetryState(
    val failureCount: Int,
    val nextAttemptAt: String?,
    val lastFailureAt: String?,
    val lastSuccessAt: String?,
    val lastError: String?
)

interface MqttRetryStateStore {
    fun retryState(): MqttRetryState
    fun recordRetryFailure(error: String, failedAt: String, nextAttemptAt: String)
    fun recordRetrySuccess(successAt: String)
}

class MqttRetryPolicy(
    private val retryDelayMs: Long = FIXED_RETRY_DELAY_MS
) {
    fun delayForFailure(failureCount: Int): Long {
        if (failureCount <= 0) return 0L
        return retryDelayMs
    }

    fun failureCountAfterSuccess(): Int = 0

    fun canAttempt(state: MqttRetryState, nowIso: String, force: Boolean = false): Boolean {
        if (force) return true
        val nextAttemptAt = state.nextAttemptAt ?: return true
        return !isBefore(nowIso, nextAttemptAt)
    }

    private fun isBefore(leftIso: String, rightIso: String): Boolean {
        return runCatching {
            OffsetDateTime.parse(leftIso).isBefore(OffsetDateTime.parse(rightIso))
        }.getOrElse {
            leftIso < rightIso
        }
    }

    private companion object {
        const val FIXED_RETRY_DELAY_MS = 30_000L
    }
}
