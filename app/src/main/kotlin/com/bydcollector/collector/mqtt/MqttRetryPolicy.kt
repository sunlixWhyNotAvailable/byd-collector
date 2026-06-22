package com.bydcollector.collector.mqtt

import java.time.OffsetDateTime
import kotlin.math.roundToLong

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
    private val delaysMs: List<Long> = listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
) {
    fun delayForFailure(failureCount: Int): Long {
        if (failureCount <= 0) return 0L
        return delaysMs[(failureCount - 1).coerceAtMost(delaysMs.lastIndex)]
    }

    fun withJitter(baseDelayMs: Long, topicOrReason: String): Long {
        val offsetPermille = Math.floorMod(topicOrReason.hashCode(), 201) - 100
        val jittered = (baseDelayMs * (1_000 + offsetPermille) / 1_000.0).roundToLong()
        return jittered.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
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
        const val MIN_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 300_000L
    }
}
