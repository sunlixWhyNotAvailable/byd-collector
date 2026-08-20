package com.bydcollector.collector.util

import android.util.Log
import java.util.concurrent.Executor

private const val TAG = "OperationalEvent"

internal val sharedOperationalEventExecutor: Executor =
    namedSingleThreadExecutor("byd-operational-events")

/**
 * Queues operational-event writes and never falls back to the caller thread
 * when the executor rejects them.
 */
internal fun dispatchOperationalEvent(executor: Executor, action: () -> Unit) {
    try {
        executor.execute { runOperationalEventTask(action) }
    } catch (error: RuntimeException) {
        logOperationalEventError("Operational event dispatch rejected", error)
    }
}

private fun runOperationalEventTask(action: () -> Unit) {
    try {
        action()
    } catch (error: Throwable) {
        logOperationalEventError("Operational event write failed", error)
    }
}

private fun logOperationalEventError(message: String, error: Throwable) {
    // Local JVM tests do not provide Android's Log implementation.
    runCatching { Log.e(TAG, message, error) }
}
