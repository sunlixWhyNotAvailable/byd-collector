package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelegramDeliveryStore
import com.bydcollector.collector.data.local.TelegramOutboxEntry
import java.security.MessageDigest
import java.util.UUID

internal data class TelegramDeliveryCredentials(val enabled: Boolean, val token: String, val chatId: String)

/** Immutable hand-off from the ordered local owner to the HTTP executor. */
internal data class TelegramDeliveryAttempt(
    val token: Long,
    val entry: TelegramOutboxEntry?,
    val request: TelegramSendMessage,
    val trigger: String,
    val startedAtMs: Long,
    val dedupeKey: String? = entry?.dedupeKey,
    val eventType: String = entry?.eventType ?: "connection_test"
)

internal data class TelegramPendingResult(
    val attempt: TelegramDeliveryAttempt,
    val result: TelegramSendResult,
    val confirmedAtMs: Long
)

internal sealed class TelegramConnectionSelection {
    data class Immediate(val result: TelegramSendResult) : TelegramConnectionSelection()
    data class Ready(val attempt: TelegramDeliveryAttempt) : TelegramConnectionSelection()
    object Busy : TelegramConnectionSelection()
}

/** One sender, shared by ordinary, startup, OFF and manual-test paths. No Android clock/network dependency. */
internal class TelegramDeliveryQueue(
    private val store: TelegramDeliveryStore,
    private val credentials: () -> TelegramDeliveryCredentials,
    private val send: (TelegramSendMessage) -> TelegramSendResult = {
        error("Synchronous Telegram delivery is unavailable")
    },
    private val commitDelivery: (TelegramOutboxEntry, Long?, Long) -> Unit,
    private val record: (String, TelegramOutboxEntry?, String) -> Unit,
    private val nowMs: () -> Long,
    private val retryPolicy: TelegramRetryPolicy = TelegramRetryPolicy(),
    private val deliveryLaneAvailable: () -> Boolean = { true }
) {
    private var lastWait: String? = null
    private var inFlight: TelegramDeliveryAttempt? = null
    private var nextAttemptToken = 1L
    // A drain spans consecutive executor ticks while due work remains. A success
    // later in that drain must not continually re-arm its earlier failed rows.
    private val attemptedInDrain = mutableSetOf<Long>()

    @Synchronized
    fun recover(trigger: String): Long? {
        val attempt = beginRecovery(trigger) ?: return pendingDeadline()
        return runSynchronous(attempt)
    }

    @Synchronized
    fun resetSession() {
        attemptedInDrain.clear()
        lastWait = null
    }

    @Synchronized
    fun flush(trigger: String, priorityKey: String? = null, expediteLocal: Boolean = false): Long? {
        val attempt = beginAttempt(trigger, priorityKey, expediteLocal) ?: return pendingDeadline()
        return runSynchronous(attempt)
    }

    /**
     * Reserves the shared delivery lane and captures the exact request to send. This method performs
     * no HTTP and is safe to call on the ordered local owner.
     */
    @Synchronized
    fun beginAttempt(
        trigger: String,
        priorityKey: String? = null,
        expediteLocal: Boolean = false
    ): TelegramDeliveryAttempt? {
        if (inFlight != null || !deliveryLaneAvailable()) return null
        val config = credentials()
        if (!canDeliver(config)) {
            finishDrain(null)
            return null
        }
        val now = nowMs()
        val serverAt = store.telegramServerNotBefore(botScope(config.token))
        if (serverAt > now) {
            recordWait(null, trigger, "server_limit", serverAt)
            finishDrain(pendingDeadline())
            return null
        }
        val entry = if (priorityKey == null) store.oldestDueTelegramMessage(now)
        else store.telegramMessageByDedupeKey(priorityKey)
        if (entry == null) {
            val next = pendingDeadline()
            if (next != null) recordWait(null, trigger, "local_backoff", next)
            finishDrain(next)
            return null
        }
        if (entry.blocked || entry.waitsForSummaryKey != null) {
            recordWait(entry, trigger, if (entry.blocked) "blocked" else "summary_dependency", pendingDeadline())
            finishDrain(pendingDeadline())
            return null
        }
        // Priority never bypasses API/server failure waits. Only a network retry may be expedited.
        if (entry.nextAttemptAtMs > now && !(expediteLocal && isNetworkFailure(entry.lastError))) {
            recordWait(entry, trigger, "local_backoff", entry.nextAttemptAtMs)
            finishDrain(pendingDeadline())
            return null
        }
        if (Thread.currentThread().isInterrupted) {
            finishDrain(pendingDeadline())
            return null
        }
        lastWait = null
        attemptedInDrain.add(entry.id)
        record(
            "attempt",
            entry,
            "trigger=$trigger age_ms=${(now - entry.createdAtMs).coerceAtLeast(0)} " +
                "created_at_ms=${entry.createdAtMs} occurred_at_ms=${entry.occurredAtMs ?: "unknown"} " +
                "attempt=${entry.attemptCount + 1}"
        )
        return TelegramDeliveryAttempt(
            token = allocateAttemptToken(),
            entry = entry,
            request = TelegramSendMessage(config.token, config.chatId, entry.payload),
            trigger = trigger,
            startedAtMs = now
        ).also { inFlight = it }
    }

    /** Expedites eligible local network failures, then reserves one recovery delivery. */
    @Synchronized
    fun beginRecovery(trigger: String): TelegramDeliveryAttempt? {
        if (inFlight != null || !deliveryLaneAvailable()) return null
        if (!canDeliver(credentials())) {
            finishDrain(null)
            return null
        }
        expediteNetworkFailures(trigger)
        return beginAttempt(trigger)
    }

    /** Applies an HTTP result for an attempt reserved by this queue. */
    @Synchronized
    fun completeAttempt(attempt: TelegramDeliveryAttempt, result: TelegramSendResult): Long? {
        check(inFlight == attempt && attempt.entry != null) { "Telegram delivery attempt is not active" }
        return settleReceivedAttempt(attempt, result, nowMs()) {}
    }

    /** Applies a process-retained result, adopting its immutable attempt after owner recreation. */
    @Synchronized
    fun settleReceivedAttempt(
        attempt: TelegramDeliveryAttempt,
        result: TelegramSendResult,
        confirmedAtMs: Long,
        onDurablySettled: () -> Unit
    ): Long? {
        check(attempt.entry != null) { "Telegram outbox attempt has no entry" }
        check(inFlight == null || inFlight == attempt) { "Telegram delivery queue is busy" }
        if (inFlight == null) inFlight = attempt
        val entry = requireNotNull(attempt.entry)
        try {
            when (result) {
                is TelegramSendResult.Success -> {
                    // The owner atomically commits outbox removal, dependency release and semantic receipt.
                    commitDelivery(entry, result.messageId, confirmedAtMs)
                    onDurablySettled()
                    runCatching {
                        record(
                            "delivered",
                            entry,
                            "trigger=${attempt.trigger} age_ms=${(confirmedAtMs - entry.createdAtMs).coerceAtLeast(0)} " +
                                "created_at_ms=${entry.createdAtMs} occurred_at_ms=${entry.occurredAtMs ?: "unknown"} " +
                                "http_confirmed_at_ms=$confirmedAtMs message_id=${result.messageId ?: "none"}"
                        )
                    }
                    runCatching { expediteNetworkFailures("delivery_success") }
                }
                is TelegramSendResult.Failure -> {
                    val failedAt = confirmedAtMs
                    val error = telegramFailureCode(result)
                    val localAt = deadlineAfter(failedAt, retryPolicy.delayForFailure(entry.failureCount + 1))
                    val serverDeadline = recordServerLimit(attempt.request.botToken, result, failedAt, localAt)
                    val next = if (result.kind.retryable) {
                        maxOf(localAt, serverDeadline).also {
                            store.markTelegramRetry(entry.id, error, attempt.startedAtMs, it)
                        }
                    } else {
                        store.markTelegramBlocked(entry.id, error, attempt.startedAtMs)
                        null
                    }
                    onDurablySettled()
                    runCatching {
                        record(
                            "failed",
                            entry,
                            "trigger=${attempt.trigger} ${telegramFailureDetail(result)} " +
                                "created_at_ms=${entry.createdAtMs} occurred_at_ms=${entry.occurredAtMs ?: "unknown"} " +
                                "http_result_at_ms=$confirmedAtMs next_attempt_at_ms=${next ?: "none"}"
                        )
                    }
                }
            }
        } finally {
            if (inFlight == attempt) inFlight = null
        }
        // A future retry of this row must not hide another due row.
        return runCatching { finishDrain(pendingDeadline()) }.getOrNull()
    }

    @Synchronized
    fun pendingDeadline(): Long? {
        // Completion posts the next owner callback. Returning an immediate deadline here while HTTP
        // is active would make the local executor spin on a lane that cannot yet make progress.
        if (inFlight != null || !deliveryLaneAvailable()) return null
        val config = credentials()
        if (!canDeliver(config)) return null
        val localAt = store.nextTelegramAttemptAtMs() ?: return null
        return maxOf(localAt, store.telegramServerNotBefore(botScope(config.token)))
    }

    /** Connection tests remain available with integration OFF, but cannot bypass the bot's server wait. */
    @Synchronized
    fun testConnection(request: TelegramSendMessage): TelegramSendResult {
        return when (val selection = beginConnectionTest(request)) {
            is TelegramConnectionSelection.Immediate -> selection.result
            is TelegramConnectionSelection.Ready -> {
                val result = try {
                    send(selection.attempt.request)
                } catch (throwable: Throwable) {
                    abandon(selection.attempt)
                    throw throwable
                }
                completeConnectionTest(selection.attempt, result)
            }
            TelegramConnectionSelection.Busy -> TelegramSendResult.Failure(
                TelegramSendFailureKind.NETWORK_ERROR,
                exceptionClass = "TelegramDeliveryBusy"
            )
        }
    }

    /** Connection tests share the same single-flight lane as durable deliveries. */
    @Synchronized
    fun beginConnectionTest(
        request: TelegramSendMessage,
        requestKey: String = "manual:${UUID.randomUUID()}"
    ): TelegramConnectionSelection {
        if (!request.botToken.matches(Regex("[0-9]+:[A-Za-z0-9_-]+")) || request.chatId.isBlank()) {
            return TelegramConnectionSelection.Immediate(
                TelegramSendResult.Failure(TelegramSendFailureKind.CONFIGURATION)
            )
        }
        if (inFlight != null || !deliveryLaneAvailable() || Thread.currentThread().isInterrupted) {
            return TelegramConnectionSelection.Busy
        }
        val now = nowMs()
        val serverAt = store.telegramServerNotBefore(botScope(request.botToken))
        if (serverAt > now) {
            recordWait(null, "connection_test", "server_limit", serverAt)
            val remaining = serverAt - now
            return TelegramConnectionSelection.Immediate(
                TelegramSendResult.Failure(
                    TelegramSendFailureKind.RATE_LIMITED,
                    retryAfterSeconds = remaining / 1_000L + if (remaining % 1_000L == 0L) 0 else 1
                )
            )
        }
        return TelegramConnectionSelection.Ready(
            TelegramDeliveryAttempt(
                token = allocateAttemptToken(),
                entry = null,
                request = request,
                trigger = "connection_test",
                startedAtMs = now,
                dedupeKey = requestKey,
                eventType = "connection_test"
            ).also { inFlight = it }
        )
    }

    @Synchronized
    fun completeConnectionTest(
        attempt: TelegramDeliveryAttempt,
        result: TelegramSendResult
    ): TelegramSendResult {
        check(inFlight == attempt && attempt.entry == null) { "Telegram connection attempt is not active" }
        return settleReceivedConnectionTest(
            attempt = attempt,
            result = result,
            confirmedAtMs = nowMs(),
            persistSuccess = {},
            onDurablySettled = {}
        )
    }

    /** Persists a manual success/cooldown before the process-owned lane is released. */
    @Synchronized
    fun settleReceivedConnectionTest(
        attempt: TelegramDeliveryAttempt,
        result: TelegramSendResult,
        confirmedAtMs: Long,
        persistSuccess: (TelegramSendResult.Success) -> Unit,
        onDurablySettled: () -> Unit
    ): TelegramSendResult {
        check(attempt.entry == null) { "Telegram connection attempt unexpectedly has an outbox row" }
        check(inFlight == null || inFlight == attempt) { "Telegram delivery queue is busy" }
        if (inFlight == null) inFlight = attempt
        try {
            when (result) {
                is TelegramSendResult.Success -> persistSuccess(result)
                is TelegramSendResult.Failure -> {
                    val failedAt = confirmedAtMs
                    recordServerLimit(
                        attempt.request.botToken,
                        result,
                        failedAt,
                        deadlineAfter(failedAt, retryPolicy.delayForFailure(1))
                    )
                }
            }
            onDurablySettled()
            if (result is TelegramSendResult.Success && canDeliver(credentials())) {
                runCatching { expediteNetworkFailures("connection_test") }
            }
        } finally {
            if (inFlight == attempt) inFlight = null
        }
        return result
    }

    private fun runSynchronous(attempt: TelegramDeliveryAttempt): Long? {
        val result = try {
            send(attempt.request)
        } catch (throwable: Throwable) {
            abandon(attempt)
            throw throwable
        }
        return completeAttempt(attempt, result)
    }

    @Synchronized
    private fun abandon(attempt: TelegramDeliveryAttempt) {
        if (inFlight == attempt) inFlight = null
    }

    private fun allocateAttemptToken(): Long {
        val token = nextAttemptToken
        nextAttemptToken = if (token == Long.MAX_VALUE) 1L else token + 1L
        return token
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

internal fun telegramFailureDetail(result: TelegramSendResult.Failure): String {
    val deliveryState = when (result.kind) {
        TelegramSendFailureKind.NETWORK_ERROR,
        TelegramSendFailureKind.INVALID_RESPONSE,
        TelegramSendFailureKind.SERVER_ERROR -> "ambiguous"
        TelegramSendFailureKind.CONFIGURATION,
        TelegramSendFailureKind.INVALID_MESSAGE -> "not_sent"
        else -> "rejected"
    }
    return "kind=${result.kind.name.lowercase()} status=${result.httpStatus ?: "none"} " +
        "exception=${result.exceptionClass ?: "none"} delivery_state=$deliveryState"
}
