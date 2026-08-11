package com.bydcollector.collector.util

import android.util.Log
import java.util.concurrent.Executor

private const val TAG = "OperationalEvent"

/**
 * Runs an operational-event write synchronously only when no executor is
 * supplied; an executor-backed write is always queued and never falls back to
 * the caller thread when the executor rejects it.
 */
internal fun dispatchOperationalEvent(executor: Executor?, action: () -> Unit) {
    if (executor == null) {
        action()
        return
    }
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
