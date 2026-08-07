package com.bydcollector.collector.telegram

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class TelegramReachabilityProbe(
    private val logFile: File,
    private val processGeneration: Long,
    private val epochMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxBytes: Long = MAX_LOG_BYTES,
    private val onAppendFailure: (String) -> Unit = {}
) {
    private var lastRequest: TelegramRequestEvidence? = null
    private var lastProbeAtMs = Long.MIN_VALUE
    private var resetGeneration = 0L
    private val probeRequestGeneration = ThreadLocal<Long?>()
    @Volatile private var loggingCapped = false
    @Volatile private var appendFailureClass: String? = null

    @Synchronized
    fun onRequest(evidence: TelegramRequestEvidence) {
        val requestGeneration = probeRequestGeneration.get()
        if (evidence.operation == "getMe" && requestGeneration != null && requestGeneration != resetGeneration) return
        lastRequest = evidence
    }

    fun maybeProbe(
        telegramEnabled: Boolean,
        mainCollectionExpected: Boolean,
        mainPollStaleMs: Long?,
        getMe: () -> TelegramSendResult
    ) = maybeProbe(
        telegramEnabled = { telegramEnabled },
        mainPollState = { mainCollectionExpected to mainPollStaleMs },
        getMe = getMe
    )

    fun maybeProbe(
        telegramEnabled: () -> Boolean,
        mainPollState: () -> Pair<Boolean, Long?>,
        getMe: () -> TelegramSendResult
    ) {
        val reservation = synchronized(this) {
            val (mainCollectionExpected, mainPollStaleMs) = mainPollState()
            if (!telegramEnabled() || !mainCollectionExpected || mainPollStaleMs == null || mainPollStaleMs < MAIN_STALE_MS) {
                return
            }
            val nowElapsedMs = elapsedRealtimeMs()
            if (lastProbeAtMs != Long.MIN_VALUE && nowElapsedMs - lastProbeAtMs < PROBE_INTERVAL_MS) return
            lastProbeAtMs = nowElapsedMs
            ProbeReservation(
                generation = resetGeneration,
                recentRequest = lastRequest?.takeIf {
                    nowElapsedMs >= it.elapsedRealtimeMs && nowElapsedMs - it.elapsedRealtimeMs < PROBE_INTERVAL_MS
                }
            )
        }
        if (reservation.recentRequest != null) {
            reportAppendFailure(synchronized(this) {
                if (reservation.generation == resetGeneration) append(reservation.recentRequest) else null
            })
            return
        }

        val startedEpochMs = epochMs()
        val startedElapsedMs = elapsedRealtimeMs()
        probeRequestGeneration.set(reservation.generation)
        val result = try {
            getMe()
        } finally {
            probeRequestGeneration.remove()
        }
        reportAppendFailure(synchronized(this) {
            if (reservation.generation != resetGeneration) return@synchronized null
            val evidence = lastRequest?.takeIf {
                it.operation == "getMe" && it.elapsedRealtimeMs >= startedElapsedMs
            } ?: TelegramRequestEvidence.localFailure(
                operation = "getMe",
                epochMs = startedEpochMs,
                elapsedRealtimeMs = startedElapsedMs,
                durationMs = (elapsedRealtimeMs() - startedElapsedMs).coerceAtLeast(0L),
                result = result
            )
            append(evidence)
        })
    }

    fun reset() {
        synchronized(this) { resetLocked() }
    }

    fun resetAndRunAtomically(action: () -> Unit) {
        synchronized(this) {
            resetLocked()
            action()
        }
    }

    private fun resetLocked() {
        resetGeneration += 1L
        lastRequest = null
        lastProbeAtMs = Long.MIN_VALUE
    }

    fun isLoggingCapped(): Boolean = loggingCapped

    fun lastAppendFailureClass(): String? = appendFailureClass

    private fun append(evidence: TelegramRequestEvidence): String? {
        val json = JSONObject()
            .put("operation", evidence.operation)
            .put("epoch_ms", evidence.epochMs)
            .put("elapsed_realtime_ms", evidence.elapsedRealtimeMs)
            .put("process_generation", processGeneration)
            .put("duration_ms", evidence.durationMs)
            .put("http_status", evidence.httpStatus ?: JSONObject.NULL)
            .put("network_reached", evidence.networkReached)
            .put("authenticated", evidence.authenticated)
            .put("result", evidence.result)
            .put("failure_kind", evidence.failureKind ?: JSONObject.NULL)
            .put("exception_class", evidence.exceptionClass ?: JSONObject.NULL)
        val bytes = (json.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        if (logFile.length() + bytes.size > maxBytes) {
            loggingCapped = true
            return null
        }
        val error = runCatching {
            logFile.parentFile?.mkdirs()
            FileOutputStream(logFile, true).use { it.write(bytes) }
        }.exceptionOrNull()
        if (error == null) {
            appendFailureClass = null
            return null
        }
        val failureClass = error::class.java.name
        val changed = appendFailureClass != failureClass
        appendFailureClass = failureClass
        return failureClass.takeIf { changed }
    }

    private fun reportAppendFailure(failureClass: String?) {
        if (failureClass != null) runCatching { onAppendFailure(failureClass) }
    }

    private data class ProbeReservation(
        val generation: Long,
        val recentRequest: TelegramRequestEvidence?
    )

    companion object {
        const val MAIN_STALE_MS = 10_000L
        const val PROBE_INTERVAL_MS = 30_000L
        const val MAX_LOG_BYTES = 8L * 1024L * 1024L
    }
}
