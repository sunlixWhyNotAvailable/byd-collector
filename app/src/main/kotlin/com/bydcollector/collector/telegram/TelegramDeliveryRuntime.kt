package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.direct.DirectVehicleHelperClient
import com.bydcollector.collector.data.local.TelegramPersistedDeliveryAttempt
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Process-owned bridge between ordered Telegram state and the durable helper HTTP lane. */
class TelegramDeliveryRuntime(
    private val send: (TelegramSendMessage) -> TelegramSendResult = TelegramHttpClient()::sendMessage,
    private val httpExecutor: ExecutorService = namedSingleThreadExecutor(HTTP_THREAD_NAME),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val helper: DirectVehicleHelperClient = DirectVehicleHelperClient()
) {
    val executor: ExecutorService = namedSingleThreadExecutor(OWNER_THREAD_NAME)

    private val lock = Object()
    private var attachedToken: Any? = null
    private var attachmentGeneration = 0L
    private var restorePending = false
    private var restoreCallInFlight = false
    private var preparedActionInFlight = false
    private var readyCallback: ((Long?) -> Unit)? = null
    private var failureCallback: ((Throwable) -> Unit)? = null
    private var resultHandler: ((TelegramPendingResult, () -> Unit) -> Long?)? = null
    private var restoreAttempt: (() -> TelegramDeliveryAttempt?)? = null
    private var preparedAttemptAction: ((TelegramDeliveryAttempt) -> TelegramPreparedAttemptAction)? = null
    private var discardPreparedAttempt: ((TelegramDeliveryAttempt) -> Long?)? = null
    private var acknowledgeAttempt: ((TelegramDeliveryAttempt) -> Long?)? = null
    private var activeAttempt: TelegramDeliveryAttempt? = null
    private var pendingResult: TelegramPendingResult? = null
    private var helperCallInFlight = false
    private var helperState: TelegramHelperState? = null
    private var settling = false
    private var quiesced = false

    val hasInFlightDelivery: Boolean
        get() = synchronized(lock) {
            activeAttempt != null || restoreCallInFlight || (restorePending && attachedToken != null)
        }

    val hasPendingResult: Boolean
        get() = synchronized(lock) { pendingResult != null }

    /** Outbox rows must not age out while HTTP or its local receipt is unresolved. */
    val protectedTelegramIds: Set<Long>
        get() = synchronized(lock) { activeAttempt?.entry?.id?.let(::setOf) ?: emptySet() }

    internal fun attach(
        token: Any,
        onReady: (Long?) -> Unit,
        onFailure: (Throwable) -> Unit,
        onPendingResult: (TelegramPendingResult, () -> Unit) -> Long?,
        restoreAttempt: () -> TelegramDeliveryAttempt?,
        preparedAttemptAction: (TelegramDeliveryAttempt) -> TelegramPreparedAttemptAction,
        discardPreparedAttempt: (TelegramDeliveryAttempt) -> Long?,
        acknowledgeAttempt: (TelegramDeliveryAttempt) -> Long?
    ) {
        synchronized(lock) {
            attachmentGeneration++
            attachedToken = token
            readyCallback = onReady
            failureCallback = onFailure
            resultHandler = onPendingResult
            this.restoreAttempt = restoreAttempt
            this.preparedAttemptAction = preparedAttemptAction
            this.discardPreparedAttempt = discardPreparedAttempt
            this.acknowledgeAttempt = acknowledgeAttempt
            restorePending = activeAttempt == null
            lock.notifyAll()
        }
        retryPendingResult()
    }

    fun detach(token: Any) {
        synchronized(lock) {
            if (attachedToken !== token) return
            attachmentGeneration++
            attachedToken = null
            readyCallback = null
            failureCallback = null
            resultHandler = null
            restoreAttempt = null
            preparedAttemptAction = null
            discardPreparedAttempt = null
            acknowledgeAttempt = null
            if (activeAttempt == null) restorePending = true
            lock.notifyAll()
        }
    }

    /** Called on [executor]; manual connection tests stay local to the APP. */
    internal fun dispatchSend(attempt: TelegramDeliveryAttempt) {
        check(Thread.currentThread().name == OWNER_THREAD_NAME) { "Telegram send must be dispatched by its owner" }
        synchronized(lock) {
            check(activeAttempt == null || activeAttempt == attempt) { "Telegram delivery lane is already occupied" }
            activeAttempt = attempt
            restorePending = false
            lock.notifyAll()
            if (quiesced) {
                if (attempt.persisted == null) {
                    receiveResult(attempt, networkFailure("TelegramDeliveryQuiesced"), nowMs())
                } else {
                    queryHelper(attempt)
                }
                return
            }
        }
        if (attempt.persisted == null) {
            try {
                httpExecutor.execute {
                    val result = try {
                        send(attempt.request)
                    } catch (error: Exception) {
                        if (error is InterruptedException) Thread.currentThread().interrupt()
                        networkFailure(error::class.java.name)
                    }
                    receiveResult(attempt, result, nowMs())
                }
            } catch (error: RejectedExecutionException) {
                receiveResult(attempt, networkFailure(error::class.java.name), nowMs())
            }
            return
        }
        queryHelper(attempt)
    }

    /** Rechecks the durable helper result, or discovers an APP-death attempt from SQLite. */
    fun retryPendingResult() {
        if (Thread.currentThread().name == OWNER_THREAD_NAME) {
            retryOnOwner()
            return
        }
        try {
            executor.execute(::retryOnOwner)
        } catch (error: RejectedExecutionException) {
            notifyFailure(error)
        }
    }

    fun deliveryReady(deadline: Long?) {
        val callback = synchronized(lock) { readyCallback }
        try {
            callback?.invoke(deadline)
        } catch (error: Exception) {
            notifyFailure(error)
        }
    }

    /** Blocks new sends and waits for helper outcome plus its durable local settlement. */
    fun quiesceAndAwait(timeoutMs: Long): Boolean {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        val startedAt = System.nanoTime()
        synchronized(lock) {
            quiesced = true
            if (hasUnsettledDeliveryLocked() && Thread.currentThread().name == OWNER_THREAD_NAME) return false
            while (hasUnsettledDeliveryLocked()) {
                val remaining = timeoutNanos - (System.nanoTime() - startedAt)
                if (remaining <= 0L) return false
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        if (Thread.currentThread().name == OWNER_THREAD_NAME) return true
        val remaining = timeoutNanos - (System.nanoTime() - startedAt)
        if (remaining <= 0L) return false
        return try {
            executor.submit {}.get(remaining, TimeUnit.NANOSECONDS)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
    }

    fun resume() {
        synchronized(lock) {
            quiesced = false
            if (activeAttempt == null && attachedToken != null) restorePending = true
        }
        retryPendingResult()
    }

    fun close() {
        // A timeout is not proof that an HTTP success/receipt may be discarded.
        if (!quiesceAndAwait(CLOSE_TIMEOUT_MS)) return
        httpExecutor.shutdown()
        executor.shutdown()
    }

    private fun retryOnOwner() {
        check(Thread.currentThread().name == OWNER_THREAD_NAME) { "Telegram recovery must run by its owner" }
        val current = synchronized(lock) {
            activeAttempt?.also {
                restorePending = false
                lock.notifyAll()
            }
        }
        if (current != null) {
            if (current.persisted != null) queryHelper(current)
            else settlePendingResult()
            return
        }
        val restoreState = synchronized(lock) {
            val callback = restoreAttempt
            if (quiesced) {
                restorePending = false
                lock.notifyAll()
                null
            } else if (attachedToken == null || callback == null || restoreCallInFlight) {
                if (attachedToken == null && !restoreCallInFlight) {
                    restorePending = false
                    lock.notifyAll()
                }
                null
            } else {
                restoreCallInFlight = true
                callback to attachmentGeneration
            }
        } ?: return
        val (restore, generation) = restoreState
        val recovered = try {
            restore()
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val stillAttached = synchronized(lock) {
                restoreCallInFlight = false
                val current = attachedToken != null && attachmentGeneration == generation
                if (!current && attachedToken == null) restorePending = false
                lock.notifyAll()
                current
            }
            if (stillAttached) {
                notifyFailure(error)
                deliveryReady(nowMs() + HELPER_RETRY_MS)
            }
            return
        }
        val active = synchronized(lock) {
            restoreCallInFlight = false
            if (attachedToken == null || attachmentGeneration != generation) {
                if (attachedToken == null) restorePending = false
                lock.notifyAll()
                return
            }
            restorePending = false
            if (activeAttempt == null) activeAttempt = recovered
            lock.notifyAll()
            activeAttempt
        }
        if (recovered == null) {
            deliveryReady(null)
            return
        }
        if (active?.persisted?.attemptId != recovered.persisted?.attemptId) {
            notifyFailure(IllegalStateException("Telegram delivery attempt changed while APP recovery was reading SQLite"))
            deliveryReady(nowMs() + HELPER_RETRY_MS)
            return
        }
        queryHelper(checkNotNull(active))
    }

    private fun processPreparedAttempt(attempt: TelegramDeliveryAttempt) {
        val shouldProcess = synchronized(lock) {
            if (quiesced || activeAttempt != attempt || preparedActionInFlight) false
            else {
                preparedActionInFlight = true
                true
            }
        }
        if (!shouldProcess) return
        try {
            val action = try {
                synchronized(lock) { preparedAttemptAction }?.invoke(attempt)
                    ?: TelegramPreparedAttemptAction.WAIT
            } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                notifyFailure(error)
                return
            }
            when (action) {
                TelegramPreparedAttemptAction.SUBMIT -> submitHelper(attempt)
                TelegramPreparedAttemptAction.WAIT -> deliveryReady(nowMs() + HELPER_RETRY_MS)
                TelegramPreparedAttemptAction.DISCARD -> try {
                    val deadline = synchronized(lock) { discardPreparedAttempt }?.invoke(attempt)
                    clearAttempt(attempt)
                    deliveryReady(deadline)
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    notifyFailure(error)
                }
            }
        } finally {
            synchronized(lock) {
                preparedActionInFlight = false
                lock.notifyAll()
            }
        }
    }

    private fun submitHelper(attempt: TelegramDeliveryAttempt) = runHelperCall(attempt, submit = true)

    private fun queryHelper(attempt: TelegramDeliveryAttempt) = runHelperCall(attempt, submit = false)

    private fun runHelperCall(attempt: TelegramDeliveryAttempt, submit: Boolean) {
        val persisted = checkNotNull(attempt.persisted) { "Telegram helper call has no persisted attempt" }
        val key = helperKey(persisted)
        val shouldRun = synchronized(lock) {
            if (activeAttempt != attempt || helperCallInFlight || quiesced) false
            else {
                helperCallInFlight = true
                helperState = null
                true
            }
        }
        if (!shouldRun) return
        try {
            httpExecutor.execute {
                try {
                    var outcome = if (submit) helper.submitTelegram(key, attempt.request) else helper.queryTelegram(key)
                    while (outcome.state == TelegramHelperState.RUNNING) {
                        Thread.sleep(HELPER_POLL_MS)
                        outcome = helper.queryTelegram(key)
                    }
                    executor.execute {
                        synchronized(lock) { helperCallInFlight = false }
                        handleHelperOutcome(attempt, outcome, submit)
                    }
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    try {
                        executor.execute {
                            synchronized(lock) { helperCallInFlight = false }
                            notifyFailure(error)
                            deliveryReady(nowMs() + HELPER_RETRY_MS)
                        }
                    } catch (rejected: RejectedExecutionException) {
                        notifyFailure(error)
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            synchronized(lock) { helperCallInFlight = false }
            notifyFailure(error)
        }
    }

    private fun handleHelperOutcome(
        attempt: TelegramDeliveryAttempt,
        outcome: TelegramHelperOutcome,
        submitted: Boolean
    ) {
        check(Thread.currentThread().name == OWNER_THREAD_NAME) { "Telegram helper result must be applied by its owner" }
        if (synchronized(lock) { activeAttempt != attempt }) return
        try {
            when (outcome.state) {
                TelegramHelperState.RUNNING -> deliveryReady(nowMs() + HELPER_POLL_MS)
                TelegramHelperState.RESULT -> {
                    val result = checkNotNull(outcome.result) { "Telegram helper RESULT has no result" }
                    receiveResult(attempt, result, checkNotNull(outcome.confirmedAtMs))
                }
                TelegramHelperState.ACKED -> {
                    check(attempt.persisted?.localSettled == true) {
                        "Telegram helper ACKED before APP local settlement; attempt retained"
                    }
                    deliveryReady(acknowledgeHelperAndApp(attempt, helperAlreadyAcked = true))
                }
                TelegramHelperState.ABSENT -> {
                    check(attempt.persisted?.localSettled != true) {
                        "Telegram helper lost the result of an APP-settled attempt"
                    }
                    synchronized(lock) {
                        helperState = TelegramHelperState.ABSENT
                        lock.notifyAll()
                    }
                    if (submitted) deliveryReady(nowMs() + HELPER_RETRY_MS)
                    else if (!synchronized(lock) { quiesced }) processPreparedAttempt(attempt)
                }
                TelegramHelperState.AMBIGUOUS -> when (checkNotNull(outcome.ambiguity)) {
                    TelegramHelperAmbiguity.INTERRUPTED_ATTEMPT -> {
                        if (attempt.persisted?.localSettled == true) {
                            val persisted = checkNotNull(attempt.persisted)
                            val entry = checkNotNull(attempt.entry) {
                                "Telegram interrupted attempt was locally settled without its failure row"
                            }
                            check(entry.id == persisted.outboxId && entry.dedupeKey == persisted.dedupeKey &&
                                entry.eventType == persisted.eventType &&
                                entry.lastError?.startsWith("network_error:TelegramHelperInterruptedAttempt") == true) {
                                "Telegram helper interruption does not match the APP's durable failure outcome"
                            }
                            deliveryReady(acknowledgeHelperAndApp(attempt, helperAlreadyAcked = false))
                        } else {
                            receiveResult(
                                attempt,
                                TelegramSendResult.Failure(
                                    kind = TelegramSendFailureKind.NETWORK_ERROR,
                                    exceptionClass = "TelegramHelperInterruptedAttempt",
                                    diagnostic = outcome.diagnostic
                                ),
                                nowMs()
                            )
                        }
                    }
                    TelegramHelperAmbiguity.LEDGER_MISSING -> throw IllegalStateException(
                        "Telegram helper ledger is missing for a noninitial APP attempt; " +
                            "the request remains pending without retry or ACK. ${outcome.diagnostic.orEmpty()}"
                    )
                }
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            notifyFailure(error)
            deliveryReady(nowMs() + HELPER_RETRY_MS)
        }
    }

    private fun receiveResult(
        attempt: TelegramDeliveryAttempt,
        result: TelegramSendResult,
        confirmedAtMs: Long
    ) {
        synchronized(lock) {
            if (activeAttempt != attempt) return
            if (pendingResult == null) {
                pendingResult = TelegramPendingResult(
                    attempt,
                    result,
                    confirmedAtMs.coerceAtLeast(0L)
                )
            }
        }
        if (Thread.currentThread().name == OWNER_THREAD_NAME) {
            settlePendingResult()
        } else {
            try {
                executor.execute(::settlePendingResult)
            } catch (error: RejectedExecutionException) {
                // The helper or local HTTP result is durable even if its owner thread is closing.
                notifyFailure(error)
            }
        }
    }

    private fun settlePendingResult() {
        check(Thread.currentThread().name == OWNER_THREAD_NAME) { "Telegram receipt must be settled by its owner" }
        val (outcome, handler) = synchronized(lock) {
            val current = pendingResult ?: return
            val currentHandler = resultHandler ?: return
            if (settling) return
            settling = true
            current to currentHandler
        }
        try {
            val deadline = handler(outcome) {
                val attempt = outcome.attempt
                val persisted = attempt.persisted
                if (persisted != null) {
                    attempt.persisted = persisted.copy(localSettled = true)
                    acknowledgeHelperAndApp(attempt, helperAlreadyAcked = false)
                }
                synchronized(lock) {
                    if (pendingResult == outcome) {
                        pendingResult = null
                        activeAttempt = null
                        helperState = null
                        lock.notifyAll()
                    }
                }
            }
            if (synchronized(lock) { activeAttempt == null }) deliveryReady(deadline)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            notifyFailure(error)
            deliveryReady(nowMs() + HELPER_RETRY_MS)
        } finally {
            synchronized(lock) { settling = false }
        }
    }

    private fun clearAttempt(attempt: TelegramDeliveryAttempt) {
        synchronized(lock) {
            if (activeAttempt == attempt) {
                activeAttempt = null
                pendingResult = null
                helperState = null
                lock.notifyAll()
            }
        }
    }

    private fun acknowledgeHelperAndApp(attempt: TelegramDeliveryAttempt, helperAlreadyAcked: Boolean): Long? {
        if (!helperAlreadyAcked) {
            val persisted = checkNotNull(attempt.persisted) { "Telegram ACK has no persisted attempt" }
            val ack = helper.acknowledgeTelegram(helperKey(persisted))
            check(ack.state == TelegramHelperState.ACKED) {
                "Telegram helper did not ACK the locally settled attempt: ${ack.state}"
            }
        }
        val deadline = synchronized(lock) { acknowledgeAttempt }?.invoke(attempt)
        clearAttempt(attempt)
        return deadline
    }

    private fun hasUnsettledDeliveryLocked(): Boolean =
        restoreCallInFlight || preparedActionInFlight || (restorePending && attachedToken != null) ||
            helperCallInFlight || settling || pendingResult != null ||
        (activeAttempt != null && (activeAttempt?.persisted == null || helperState != TelegramHelperState.ABSENT))

    private fun helperKey(attempt: TelegramPersistedDeliveryAttempt) = TelegramHelperAttemptKey(
        ownerId = UUID.fromString(attempt.ownerId),
        attemptId = UUID.fromString(attempt.attemptId),
        sequence = attempt.sequence,
        requestDigest = attempt.requestDigest,
        destinationDigest = attempt.destinationDigest,
        dedupeRef = attempt.dedupeRef
    )

    private fun notifyFailure(error: Throwable) {
        val callback = synchronized(lock) { failureCallback }
        try {
            callback?.invoke(error)
        } catch (callbackError: Exception) {
            error.addSuppressed(callbackError)
        }
    }

    private fun networkFailure(exceptionClass: String) = TelegramSendResult.Failure(
        kind = TelegramSendFailureKind.NETWORK_ERROR,
        exceptionClass = exceptionClass
    )

    private companion object {
        const val OWNER_THREAD_NAME = "byd-telegram"
        const val HTTP_THREAD_NAME = "byd-telegram-http"
        const val HELPER_POLL_MS = 500L
        const val HELPER_RETRY_MS = 5_000L
        const val CLOSE_TIMEOUT_MS = 16_000L
    }
}
