package com.bydcollector.collector.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.util.diagnosticDetail
import org.json.JSONObject
import java.io.InputStream
import java.time.Instant
import kotlin.concurrent.thread

/** Evidence only: never opens collection SQLite or attempts to recover a crashing process. */
internal object ProcessExitDiagnostics {
    fun install(context: Context, journal: OperationalEventJournal, maintenanceContext: () -> String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous != null) {
            Thread.setDefaultUncaughtExceptionHandler(FatalEvidenceHandler(previous) { failingThread, error ->
                journal.tryAppend(
                    Instant.now().toString(), SystemClock.elapsedRealtime(), "process_exit", "fatal_exception",
                    error.diagnosticDetail(
                        "version=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} " +
                            "thread=${failingThread.name.take(128)}\n${maintenanceContext()}"
                    )
                )
            })
        }
        // Separate from sharedOperationalEventExecutor, which can be waiting on collection SQLite.
        thread(name = "byd-exit-history", isDaemon = true) {
            fun record(event: String, detail: String): Boolean = runCatching {
                journal.tryAppend(Instant.now().toString(), SystemClock.elapsedRealtime(), "process_exit", event, detail)
            }.getOrDefault(false)
            if (previous == null) record("fatal_handler_unavailable", "original_handler=absent; not replaced")
            if (Build.VERSION.SDK_INT < 30) {
                record("history_unsupported", "api=${Build.VERSION.SDK_INT}; requires_api=30")
                return@thread
            }
            try {
                val manager = context.getSystemService(ActivityManager::class.java)
                    ?: error("ActivityManager unavailable")
                val history = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                val prefs = context.getSharedPreferences("process_exit_diagnostics", Context.MODE_PRIVATE)
                val seen = prefs.getStringSet("recorded_exit_ids", emptySet()).orEmpty().toSet()
                val keys = history.map { "${it.timestamp}:${it.pid}:${it.reason}:${it.processName}" }
                val recorded = recordUnseenExits(keys, seen) { index ->
                    val exit = history[index]
                    val detail = JSONObject()
                        .put("exit_id", keys[index])
                        .put("exit_timestamp_ms", exit.timestamp)
                        .put("process", exit.processName)
                        .put("exit_pid", exit.pid)
                        .put("reason", exit.reason)
                        .put("status", exit.status)
                        .put("importance", exit.importance)
                        .put("pss_kib", exit.pss)
                        .put("rss_kib", exit.rss)
                        .put("description", exit.description?.take(1024) ?: JSONObject.NULL)
                    try {
                        exit.traceInputStream?.use { trace ->
                            val anr = exit.reason == ApplicationExitInfo.REASON_ANR
                            val prefix = if (anr) readAnrTrace(trace) else readExitTracePrefix(trace)
                            // Native tombstones are protobuf on API31+. Do not label this as a
                            // decoded stack or hide its strings in base64 from Share redaction.
                            val native = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31
                            detail.put("trace_status", if (prefix.second) {
                                if (anr) "selected_sections" else "prefix_truncated"
                            } else "available")
                                .put("trace_format", if (native) "protobuf_utf8_projection" else "utf8")
                                .put("trace_prefix", prefix.first)
                        } ?: detail.put("trace_status", "unavailable")
                    } catch (error: Exception) {
                        detail.put("trace_status", "read_error")
                            .put("trace_error", "${error::class.java.simpleName}: ${error.message}".take(512))
                    }
                    record("historical_exit", detail.toString())
                }
                if (recorded.isNotEmpty()) {
                    // Bound the dedup set and retain only successful evidence writes. Failed ones retry next start.
                    val retained = keys.filter { it in seen || it in recorded }.toSet()
                    if (!prefs.edit().putStringSet("recorded_exit_ids", retained).commit()) {
                        record("history_checkpoint_failed", "next_start_may_repeat=true")
                    }
                }
                record("history_checked", "available=${history.size}; new=${recorded.size}")
            } catch (error: Exception) {
                record("history_unavailable", "${error::class.java.simpleName}: ${error.message}".take(2048))
            }
        }
    }
}

/** Keep the blocking main stack even when vendor headers consume the old 16 KiB prefix. */
internal fun readAnrTrace(input: InputStream): Pair<String, Boolean> {
    val (trace, scanTruncated) = readExitTracePrefix(input, 1024 * 1024)
    val main = Regex("(?m)^\"main\"[^\\r\\n]*").find(trace)
    if (main == null) return ("[main thread not found within bounded scan]\n" + trace.take(16 * 1024)) to true
    val nextThread = Regex("(?m)^\"").find(trace, main.range.last + 1)?.range?.first ?: trace.length
    val stack = trace.substring(main.range.first, nextThread)
    val selected = "[main thread]\n${stack.take(12 * 1024)}\n[trace header]\n${trace.take(minOf(main.range.first, 4 * 1024))}"
    return selected to (scanTruncated || selected.length < trace.length || stack.length > 12 * 1024)
}

internal class FatalEvidenceHandler(
    private val previous: Thread.UncaughtExceptionHandler,
    private val record: (Thread, Throwable) -> Unit
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            runCatching { record(thread, error) }
        } finally {
            // Do not call ThreadGroup here: it can recurse through the new default handler.
            previous.uncaughtException(thread, error)
        }
    }
}

internal fun recordUnseenExits(keys: List<String>, seen: Set<String>, record: (Int) -> Boolean): Set<String> {
    val recorded = linkedSetOf<String>()
    keys.forEachIndexed { index, key ->
        if (key !in seen && key !in recorded && record(index)) recorded += key
    }
    return recorded
}

internal fun readExitTracePrefix(input: InputStream, limit: Int = 16 * 1024): Pair<String, Boolean> {
    require(limit > 0)
    val bytes = ByteArray(limit + 1)
    var count = 0
    while (count < bytes.size) {
        val read = input.read(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) {
            val next = input.read()
            if (next < 0) break
            bytes[count++] = next.toByte()
        } else count += read
    }
    return String(bytes, 0, minOf(count, limit), Charsets.UTF_8) to (count > limit)
}
