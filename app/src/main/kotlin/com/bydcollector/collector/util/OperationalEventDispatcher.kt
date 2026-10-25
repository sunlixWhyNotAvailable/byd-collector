package com.bydcollector.collector.util

import android.util.Log
import com.bydcollector.collector.diagnostics.DiagnosticShareSanitizer
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

internal fun Throwable.diagnosticDetail(context: String? = null): String {
    val prefix = context?.takeIf(String::isNotBlank)?.plus("\n").orEmpty()
    val raw = runCatching { prefix + stackTraceToString() }.getOrElse { formattingError ->
        if (formattingError !== this) addSuppressed(formattingError)
        "$prefix${javaClass.name}: [stack trace formatting failed]"
    }
    val detail = runCatching { DiagnosticShareSanitizer().sanitizeText(raw) }.getOrElse { sanitizationError ->
        if (sanitizationError !== this) addSuppressed(sanitizationError)
        "$prefix${javaClass.name}: [diagnostic sanitization failed; details withheld]"
    }
    if (detail.length <= MAX_DIAGNOSTIC_DETAIL_CHARS) return detail
    val marker = "\n[TRUNCATED; limit=$MAX_DIAGNOSTIC_DETAIL_CHARS chars]"
    return detail.take(MAX_DIAGNOSTIC_DETAIL_CHARS - marker.length) + marker
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

private const val MAX_DIAGNOSTIC_DETAIL_CHARS = 32_768
