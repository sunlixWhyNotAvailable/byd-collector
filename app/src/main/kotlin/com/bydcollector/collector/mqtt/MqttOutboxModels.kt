package com.bydcollector.collector.mqtt

data class PendingMqttMessage(
    val targetKey: String,
    val targetType: String,
    val payloadHash: String = "",
    val message: HaMqttMessage,
    val priority: Int,
    val attemptCount: Int
)

interface MqttOutboxStore {
    fun upsertPending(message: HaMqttMessage, targetType: String, priority: Int)
    fun dueMessages(nowIso: String, limit: Int): List<PendingMqttMessage>
    fun pendingMessages(limit: Int): List<PendingMqttMessage> {
        return dueMessages("9999-12-31T23:59:59Z", limit)
    }
    fun markAttempt(targetKey: String, payloadHash: String, attemptedAt: String)
    fun markFailed(targetKey: String, payloadHash: String, error: String, failedAt: String, nextAttemptAt: String?)
    fun markPublished(targetKey: String, payloadHash: String, publishedAt: String)
    fun pendingCount(): Long
}
