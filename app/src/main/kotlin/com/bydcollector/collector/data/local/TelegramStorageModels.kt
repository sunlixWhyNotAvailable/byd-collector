package com.bydcollector.collector.data.local

data class TelegramOutboxEntry(
    val id: Long,
    val dedupeKey: String,
    val eventType: String,
    val payload: String,
    val attemptCount: Int
)

data class TelegramEnqueueResult(
    val inserted: Boolean,
    val expiredCount: Int,
    val overflowCount: Int
)

data class TelegramQueueSnapshot(
    val pendingCount: Long,
    val blockedCount: Long,
    val nextAttemptAtMs: Long?,
    val lastError: String?
)
