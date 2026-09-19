package com.bydcollector.collector.telegram

import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Process-owned bridge between ordered Telegram state and blocking HTTP. */
class TelegramDeliveryRuntime(
    private val send: (TelegramSendMessage) -> TelegramSendResult = TelegramHttpClient()::sendMessage,
    private val httpExecutor: ExecutorService = namedSingleThreadExecutor(HTTP_THREAD_NAME)
) {
    val executor: ExecutorService = namedSingleThreadExecutor(OWNER_THREAD_NAME)

    @Volatile
    var coordinator: TelegramCoordinator? = null

    private val lock = Object()
    private var attachedToken: Any? = null
    private var readyCallback: ((Long?) -> Unit)? = null
    private var failureCallback: ((Throwable) -> Unit)? = null
    private var inFlight = false
    private var quiesced = false

    val hasInFlightDelivery: Boolean
        get() = synchronized(lock) { inFlight }

    fun attach(token: Any, onReady: (Long?) -> Unit, onFailure: (Throwable) -> Unit) {
        synchronized(lock) {
            attachedToken = token
            readyCallback = onReady
            failureCallback = onFailure
        }
    }

    fun detach(token: Any) {
        synchronized(lock) {
            if (attachedToken !== token) return
            attachedToken = null
            readyCallback = null
            failureCallback = null
        }
    }

    /** Called on [executor]; HTTP runs separately and its receipt always returns to this owner. */
    fun dispatchSend(
        request: TelegramSendMessage,
        completion: (TelegramSendResult) -> Unit
    ) {
        check(Thread.currentThread().name == OWNER_THREAD_NAME) { "Telegram send must be dispatched by its owner" }
        val rejection = synchronized(lock) {
            when {
                quiesced -> "TelegramDeliveryQuiesced"
                inFlight -> "TelegramDeliveryBusy"
                else -> {
                    inFlight = true
                    null
                }
            }
        }
        if (rejection != null) {
            completeReceipt(completion, networkFailure(rejection), releaseInFlight = false)
            return
        }
        try {
            httpExecutor.execute {
                val result = try {
                    send(request)
                } catch (error: Exception) {
                    if (error is InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw error
                    }
                    networkFailure(error::class.java.name)
                }
                try {
                    executor.execute { completeReceipt(completion, result) }
                } catch (error: RejectedExecutionException) {
                    finishInFlight()
                    notifyFailure(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            completeReceipt(completion, networkFailure(error::class.java.name))
        }
    }

    fun deliveryReady(deadline: Long?) {
        val callback = synchronized(lock) { readyCallback }
        callback?.invoke(deadline)
    }

    /** Blocks new sends and waits for HTTP, its durable receipt, and prior owner work. */
    fun quiesceAndAwait(timeoutMs: Long): Boolean {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        val startedAt = System.nanoTime()
        synchronized(lock) {
            quiesced = true
            if (inFlight && Thread.currentThread().name == OWNER_THREAD_NAME) return false
            while (inFlight) {
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
    }

    fun close() {
        // A timeout is not proof that an HTTP success/receipt may be discarded.
        if (!quiesceAndAwait(CLOSE_TIMEOUT_MS)) return
        httpExecutor.shutdown()
        executor.shutdown()
    }

    private fun completeReceipt(
        completion: (TelegramSendResult) -> Unit,
        result: TelegramSendResult,
        releaseInFlight: Boolean = true
    ) {
        try {
            completion(result)
        } catch (error: Exception) {
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
            notifyFailure(error)
        } finally {
            if (releaseInFlight) finishInFlight()
        }
    }

    private fun finishInFlight() {
        synchronized(lock) {
            inFlight = false
            lock.notifyAll()
        }
    }

    private fun notifyFailure(error: Throwable) {
        val callback = synchronized(lock) { failureCallback }
        callback?.invoke(error)
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
