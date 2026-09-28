package com.bydcollector.collector.telegram

import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Process-owned bridge between ordered Telegram state and blocking HTTP. */
class TelegramDeliveryRuntime(
    private val send: (TelegramSendMessage) -> TelegramSendResult = TelegramHttpClient()::sendMessage,
    private val httpExecutor: ExecutorService = namedSingleThreadExecutor(HTTP_THREAD_NAME),
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    val executor: ExecutorService = namedSingleThreadExecutor(OWNER_THREAD_NAME)

    private val lock = Object()
    private var attachedToken: Any? = null
    private var readyCallback: ((Long?) -> Unit)? = null
    private var failureCallback: ((Throwable) -> Unit)? = null
    private var resultHandler: ((TelegramPendingResult, () -> Unit) -> Long?)? = null
    private var activeAttempt: TelegramDeliveryAttempt? = null
    private var pendingResult: TelegramPendingResult? = null
    private var settling = false
    private var quiesced = false

    val hasInFlightDelivery: Boolean
        get() = synchronized(lock) { activeAttempt != null }

    val hasPendingResult: Boolean
        get() = synchronized(lock) { pendingResult != null }

    /** Outbox rows must not age out while HTTP or its local receipt is unresolved. */
    val protectedTelegramIds: Set<Long>
        get() = synchronized(lock) { activeAttempt?.entry?.id?.let(::setOf) ?: emptySet() }

    internal fun attach(
        token: Any,
        onReady: (Long?) -> Unit,
        onFailure: (Throwable) -> Unit,
        onPendingResult: (TelegramPendingResult, () -> Unit) -> Long?
    ) {
        synchronized(lock) {
            attachedToken = token
            readyCallback = onReady
            failureCallback = onFailure
            resultHandler = onPendingResult
        }
        retryPendingResult()
    }

    fun detach(token: Any) {
        synchronized(lock) {
            if (attachedToken !== token) return
            attachedToken = null
            readyCallback = null
            failureCallback = null
            resultHandler = null
        }
    }

    /** Called on [executor]; HTTP runs separately and its immutable result returns to this owner. */
    internal fun dispatchSend(attempt: TelegramDeliveryAttempt) {
        check(Thread.currentThread().name == OWNER_THREAD_NAME) { "Telegram send must be dispatched by its owner" }
        val doNotSend = synchronized(lock) {
            check(activeAttempt == null) { "Telegram delivery lane is already occupied" }
            activeAttempt = attempt
            quiesced
        }
        if (doNotSend) {
            receiveResult(attempt, networkFailure("TelegramDeliveryQuiesced"), nowMs())
            return
        }
        try {
            httpExecutor.execute {
                val result = try {
                    send(attempt.request)
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    networkFailure(error::class.java.name)
                }
                val confirmedAtMs = nowMs().coerceAtLeast(0L)
                receiveResult(attempt, result, confirmedAtMs)
            }
        } catch (error: RejectedExecutionException) {
            receiveResult(attempt, networkFailure(error::class.java.name), nowMs())
        }
    }

    /** Replays a retained HTTP outcome through the currently attached owner's store. */
    fun retryPendingResult() {
        if (Thread.currentThread().name == OWNER_THREAD_NAME) {
            settlePendingResult()
            return
        }
        try {
            executor.execute(::settlePendingResult)
        } catch (error: RejectedExecutionException) {
            notifyFailure(error)
        }
    }

    fun deliveryReady(deadline: Long?) {
        val callback = synchronized(lock) { readyCallback }
        runCatching { callback?.invoke(deadline) }
    }

    /** Blocks new sends and waits for HTTP plus its durable local settlement. */
    fun quiesceAndAwait(timeoutMs: Long): Boolean {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        val startedAt = System.nanoTime()
        synchronized(lock) {
            quiesced = true
            if (activeAttempt != null && Thread.currentThread().name == OWNER_THREAD_NAME) return false
            while (activeAttempt != null) {
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
        synchronized(lock) { quiesced = false }
        retryPendingResult()
    }

    fun close() {
        // A timeout is not proof that an HTTP success/receipt may be discarded.
        if (!quiesceAndAwait(CLOSE_TIMEOUT_MS)) return
        httpExecutor.shutdown()
        executor.shutdown()
    }

    private fun receiveResult(attempt: TelegramDeliveryAttempt, result: TelegramSendResult, confirmedAtMs: Long) {
        synchronized(lock) {
            if (activeAttempt != attempt) return
            if (pendingResult == null) {
                pendingResult = TelegramPendingResult(attempt, result, confirmedAtMs.coerceAtLeast(0L))
            }
        }
        if (Thread.currentThread().name == OWNER_THREAD_NAME) {
            settlePendingResult()
        } else {
            try {
                executor.execute(::settlePendingResult)
            } catch (error: RejectedExecutionException) {
                // The result is already process-owned even if its owner thread is closing.
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
        var acknowledged = false
        try {
            val deadline = handler(outcome) {
                synchronized(lock) {
                    if (pendingResult == outcome) {
                        pendingResult = null
                        activeAttempt = null
                        acknowledged = true
                        lock.notifyAll()
                    }
                }
            }
            if (acknowledged) deliveryReady(deadline)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            if (!acknowledged) notifyFailure(error)
        } finally {
            synchronized(lock) { settling = false }
        }
    }

    private fun notifyFailure(error: Throwable) {
        val callback = synchronized(lock) { failureCallback }
        runCatching { callback?.invoke(error) }
    }

    private fun networkFailure(exceptionClass: String) = TelegramSendResult.Failure(
        kind = TelegramSendFailureKind.NETWORK_ERROR,
        exceptionClass = exceptionClass
    )

    private companion object {
        const val OWNER_THREAD_NAME = "byd-telegram"
        const val HTTP_THREAD_NAME = "byd-telegram-http"
        const val CLOSE_TIMEOUT_MS = 16_000L
    }
}
