package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelegramDeliveryStore
import com.bydcollector.collector.data.local.TelegramOutboxEntry
import java.security.MessageDigest

internal data class TelegramDeliveryCredentials(val enabled: Boolean, val token: String, val chatId: String)

/** One sender, shared by ordinary, startup, OFF and manual-test paths. No Android clock/network dependency. */
internal class TelegramDeliveryQueue(
    private val store: TelegramDeliveryStore,
    private val credentials: () -> TelegramDeliveryCredentials,
    private val send: (TelegramSendMessage) -> TelegramSendResult,
    private val commitDelivery: (TelegramOutboxEntry, Long) -> Unit,
    private val record: (String, TelegramOutboxEntry?, String) -> Unit,
    private val nowMs: () -> Long,
    private val retryPolicy: TelegramRetryPolicy = TelegramRetryPolicy()
) {
    private var lastWait: String? = null
    // A drain spans consecutive executor ticks while due work remains. A success
    // later in that drain must not continually re-arm its earlier failed rows.
    private val attemptedInDrain = mutableSetOf<Long>()

    @Synchronized
    fun recover(trigger: String): Long? {
        if (!canDeliver(credentials())) return finishDrain(null)
        expediteNetworkFailures(trigger)
        return flush(trigger)
    }

    @Synchronized
    fun resetSession() {
        attemptedInDrain.clear()
        lastWait = null
    }

    @Synchronized
    fun flush(trigger: String, priorityKey: String? = null, expediteLocal: Boolean = false): Long? {
        val config = credentials()
        if (!canDeliver(config)) return finishDrain(null)
        val now = nowMs()
        val serverAt = store.telegramServerNotBefore(botScope(config.token))
        if (serverAt > now) {
            recordWait(null, trigger, "server_limit", serverAt)
            return finishDrain(pendingDeadline())
        }
        val entry = if (priorityKey == null) store.oldestDueTelegramMessage(now)
        else store.telegramMessageByDedupeKey(priorityKey)
        if (entry == null) {
            val next = pendingDeadline()
            if (next != null) recordWait(null, trigger, "local_backoff", next)
            return finishDrain(next)
        }
        if (entry.blocked || entry.waitsForSummaryKey != null) {
            recordWait(entry, trigger, if (entry.blocked) "blocked" else "summary_dependency", pendingDeadline())
            return finishDrain(pendingDeadline())
        }
        // Priority never bypasses API/server failure waits. Only a network retry may be expedited.
        if (entry.nextAttemptAtMs > now && !(expediteLocal && isNetworkFailure(entry.lastError))) {
            recordWait(entry, trigger, "local_backoff", entry.nextAttemptAtMs)
            return finishDrain(pendingDeadline())
        }
        if (Thread.currentThread().isInterrupted) return finishDrain(pendingDeadline())
        lastWait = null
        attemptedInDrain.add(entry.id)
        record("attempt", entry, "trigger=$trigger age_ms=${(now - entry.createdAtMs).coerceAtLeast(0)} attempt=${entry.attemptCount + 1}")
        when (val result = send(TelegramSendMessage(config.token, config.chatId, entry.payload))) {
            TelegramSendResult.Success -> {
                val deliveredAt = nowMs()
                // The owner atomically commits outbox removal, dependency release and semantic receipt.
                commitDelivery(entry, deliveredAt)
                record("delivered", entry, "trigger=$trigger age_ms=${(deliveredAt - entry.createdAtMs).coerceAtLeast(0)}")
                expediteNetworkFailures("delivery_success")
            }
            is TelegramSendResult.Failure -> {
                val failedAt = nowMs()
                val error = telegramFailureCode(result)
                val localAt = deadlineAfter(failedAt, retryPolicy.delayForFailure(entry.failureCount + 1))
                val serverDeadline = recordServerLimit(config.token, result, failedAt, localAt)
                val next = if (result.kind.retryable) {
                    maxOf(localAt, serverDeadline).also { store.markTelegramRetry(entry.id, error, now, it) }
                } else {
                    store.markTelegramBlocked(entry.id, error, now)
                    null
                }
                record("failed", entry, "trigger=$trigger ${telegramFailureDetail(result)} next_attempt_at_ms=${next ?: "none"}")
            }
        }
        // A future retry of this row must not hide another due row.
        return finishDrain(pendingDeadline())
    }

    @Synchronized
    fun pendingDeadline(): Long? {
        val config = credentials()
        if (!canDeliver(config)) return null
        val localAt = store.nextTelegramAttemptAtMs() ?: return null
        return maxOf(localAt, store.telegramServerNotBefore(botScope(config.token)))
    }

    /** Connection tests remain available with integration OFF, but cannot bypass the bot's server wait. */
    @Synchronized
    fun testConnection(request: TelegramSendMessage): TelegramSendResult {
        if (!request.botToken.matches(Regex("[0-9]+:[A-Za-z0-9_-]+")) || request.chatId.isBlank()) {
            return TelegramSendResult.Failure(TelegramSendFailureKind.CONFIGURATION)
        }
        val now = nowMs()
        val serverAt = store.telegramServerNotBefore(botScope(request.botToken))
        if (serverAt > now) {
            recordWait(null, "connection_test", "server_limit", serverAt)
            val remaining = serverAt - now
            return TelegramSendResult.Failure(
                TelegramSendFailureKind.RATE_LIMITED,
                retryAfterSeconds = remaining / 1_000L + if (remaining % 1_000L == 0L) 0 else 1
            )
        }
        val result = send(request)
        if (result is TelegramSendResult.Failure) {
            val failedAt = nowMs()
            recordServerLimit(request.botToken, result, failedAt, deadlineAfter(failedAt, retryPolicy.delayForFailure(1)))
        } else if (canDeliver(credentials())) {
            expediteNetworkFailures("connection_test")
        }
        return result
    }

    private fun expediteNetworkFailures(trigger: String) {
        if (!canDeliver(credentials())) return
        val count = store.expediteNetworkRetries(nowMs(), attemptedInDrain)
        if (count > 0) {
            lastWait = null
            record("wake", null, "trigger=$trigger expedited=$count next_attempt_at_ms=${pendingDeadline() ?: "none"}")
        }
    }

    private fun finishDrain(next: Long?): Long? {
        if (next == null || next > nowMs()) attemptedInDrain.clear()
        return next
    }

    private fun recordServerLimit(token: String, failure: TelegramSendResult.Failure, now: Long, fallbackAt: Long): Long {
        if (failure.kind != TelegramSendFailureKind.RATE_LIMITED && failure.retryAfterSeconds == null) return 0L
        val seconds = failure.retryAfterSeconds?.coerceAtLeast(0L)
        val until = seconds?.let { deadlineAfter(now, if (it > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else it * 1_000L) }
            ?: fallbackAt
        store.extendTelegramServerNotBefore(botScope(token), until)
        return until
    }

    private fun recordWait(entry: TelegramOutboxEntry?, trigger: String, reason: String, next: Long?) {
        val key = "${entry?.id}:$reason:$next"
        if (lastWait == key) return
        lastWait = key
        record("wait", entry, "trigger=$trigger reason=$reason next_attempt_at_ms=${next ?: "none"}")
    }

    private fun canDeliver(value: TelegramDeliveryCredentials): Boolean =
        value.enabled && value.token.isNotBlank() && value.chatId.isNotBlank()

    companion object {
        internal fun botScope(token: String): String = MessageDigest.getInstance("SHA-256")
            .digest("bot:${token.substringBefore(':')}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        internal fun isNetworkFailure(error: String?): Boolean = error == "network_error" || error?.startsWith("network_error:") == true

        internal fun deadlineAfter(now: Long, delay: Long): Long =
            if (delay > 0 && now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay.coerceAtLeast(0)
    }
}

internal fun telegramFailureCode(result: TelegramSendResult.Failure): String = listOfNotNull(
    result.kind.name.lowercase(), result.httpStatus?.toString(), result.exceptionClass
).joinToString(":")

internal fun telegramFailureDetail(result: TelegramSendResult.Failure): String =
    "kind=${result.kind.name.lowercase()} status=${result.httpStatus ?: "none"} exception=${result.exceptionClass ?: "none"}"
