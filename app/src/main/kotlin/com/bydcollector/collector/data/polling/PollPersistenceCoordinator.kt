package com.bydcollector.collector.data.polling

import android.util.Log
import com.bydcollector.collector.data.local.CatalogParameter
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.PersistedPollInput
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.data.remote.TelemetryClient
import com.bydcollector.collector.data.remote.TelemetryReadResult
import com.bydcollector.collector.util.diagnosticDetail

data class PollCycleResult(
    val pollId: Long?,
    val ok: Boolean,
    val category: String?,
    val elapsedMs: Long,
    val requestCount: Int,
    val timestamp: String? = null,
    val errorMessage: String? = null,
    val pollRowsPersisted: Long = 0L,
    val valueRowsPersisted: Long = 0L,
    val deferred: Boolean = false
)

interface PollCycleRunner {
    fun pollOnce(sessionId: Long): PollCycleResult?
}

interface PollStorage {
    fun getActiveCatalogParameters(): List<CatalogParameter>
    fun insertPoll(sessionId: Long, input: PersistedPollInput, parameters: List<CatalogParameter>): Long
    fun recordEvent(category: String, message: String, detail: String? = null)
}

enum class PollOrigin {
    LIVE,
    REPLAY
}

interface SuccessfulPollObserver {
    fun onSourcePoll(
        sessionId: Long, pollId: Long, timestamp: String, readings: List<PollReading>,
        origin: PollOrigin, source: PollSampleSource
    ) = onSuccessfulPoll(sessionId, pollId, timestamp, readings, origin)

    fun onSuccessfulPoll(
        sessionId: Long,
        pollId: Long,
        timestamp: String,
        readings: List<PollReading>,
        origin: PollOrigin
    )

    fun onSourceFailure(
        sessionId: Long,
        pollId: Long,
        timestamp: String,
        origin: PollOrigin,
        source: PollSampleSource
    ) = Unit
}

//ties one vehicle read to raw persistence, normalized observers, and explicit failure records
class PollPersistenceCoordinator(
    private val store: PollStorage,
    private val client: TelemetryClient,
    private val clock: Clock = SystemClockAdapter(),
    private val successfulPollObserver: SuccessfulPollObserver? = null,
    private val isActive: () -> Boolean = { true }
) : PollCycleRunner {
    private var lastPersistedFailureKey: String? = null
    private var lastPersistedFailureAtMs: Long = Long.MIN_VALUE
    private var lastDiagnosticKey: String? = null
    private var replayPendingLogged = false
    private val liveSource = LivePollSource()

    override fun pollOnce(sessionId: Long): PollCycleResult {
        checkActive()
        val parameters = store.getActiveCatalogParameters()
        val result = client.read()
        checkActive()
        if (result !is TelemetryReadResult.ReplayPending) replayPendingLogged = false

        return try {
            when (result) {
                is TelemetryReadResult.ReplayPending -> {
                    if (!replayPendingLogged) {
                        recordEventSafely("worker_replay_pending", "Live read deferred until helper spool is replayed", null)
                        replayPendingLogged = true
                    }
                    PollCycleResult(null, ok = true, category = null, elapsedMs = result.elapsedMs,
                        requestCount = 0, deferred = true)
                }
                is TelemetryReadResult.Success -> {
                    val timestamp = clock.nowIso()
                    val source = liveSource.capture(clock.elapsedRealtimeMs())
                    //stores raw readings before observers derive state for mqtt/influx consumers
                    val pollId = store.insertPoll(
                        sessionId,
                        PersistedPollInput(
                            timestamp = timestamp,
                            ok = true,
                            elapsedMs = result.elapsedMs,
                            requestCount = DIRECT_REQUEST_COUNT,
                            errors = result.warningMessage?.let { warning ->
                                "${result.warningCategory ?: "poll_warning"}: $warning"
                            },
                            errorCategory = result.warningCategory,
                            errorMessage = result.warningMessage,
                            rawResponseBody = null,
                            readings = result.readings
                        ),
                        parameters = parameters
                    )
                    persistDiagnosticTransition(result)
                    notifyObserver {
                        successfulPollObserver?.onSourcePoll(
                            sessionId,
                            pollId,
                            timestamp,
                            result.readings,
                            PollOrigin.LIVE,
                            source
                        )
                    }
                    lastPersistedFailureKey = null
                    lastPersistedFailureAtMs = Long.MIN_VALUE
                    PollCycleResult(
                        pollId = pollId,
                        ok = true,
                        category = null,
                        elapsedMs = result.elapsedMs,
                        requestCount = DIRECT_REQUEST_COUNT,
                        timestamp = timestamp,
                        pollRowsPersisted = 1L,
                        valueRowsPersisted = 1L
                    )
                }

                is TelemetryReadResult.Failure -> {
                    val failureKey = "${result.category}:${result.message}"
                    val nowMs = clock.elapsedRealtimeMs()
                    val timestamp = clock.nowIso()
                    //throttles identical failures so helper outages do not flood sqlite every second
                    if (shouldSkipRepeatedFailure(failureKey, nowMs)) {
                        return PollCycleResult(
                            pollId = null,
                            ok = false,
                            category = result.category,
                            elapsedMs = result.elapsedMs,
                            requestCount = DIRECT_REQUEST_COUNT,
                            timestamp = timestamp,
                            errorMessage = result.message
                        )
                    }
                    val source = liveSource.capture(nowMs)
                    val pollId = store.insertPoll(
                        sessionId,
                        PersistedPollInput(
                            timestamp = timestamp,
                            ok = false,
                            elapsedMs = result.elapsedMs,
                            requestCount = DIRECT_REQUEST_COUNT,
                            errors = "${result.category}: ${result.message}",
                            errorCategory = result.category,
                            errorMessage = result.message,
                            rawResponseBody = result.rawBody,
                            readings = emptyList()
                        ),
                        parameters = parameters
                    )
                    lastPersistedFailureKey = failureKey
                    lastPersistedFailureAtMs = nowMs
                    notifyObserver {
                        successfulPollObserver?.onSourceFailure(
                            sessionId = sessionId, pollId = pollId, timestamp = timestamp,
                            origin = PollOrigin.LIVE, source = source
                        )
                    }
                    recordEventSafely("poll_failure", "Poll failed: ${result.category}", result.message)
                    PollCycleResult(
                        pollId = pollId,
                        ok = false,
                        category = result.category,
                        elapsedMs = result.elapsedMs,
                        requestCount = DIRECT_REQUEST_COUNT,
                        timestamp = timestamp,
                        errorMessage = result.message,
                        pollRowsPersisted = 1L
                    )
                }
            }
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            checkActive()
            val detail = error.diagnosticDetail("Main poll persistence")
            runCatching { Log.e(TAG, "Database write failed", error) }
            try {
                store.recordEvent("db_write_error", "Database write failed", detail)
            } catch (eventError: Exception) {
                if (eventError is InterruptedException) throw eventError
                logError("Failed to record database write failure", eventError)
            }
            PollCycleResult(null, ok = false, category = "db_write_error", elapsedMs = 0,
                requestCount = DIRECT_REQUEST_COUNT,
                errorMessage = "${error::class.java.simpleName}: ${error.message ?: "no message"}")
        }
    }

    private fun persistDiagnosticTransition(result: TelemetryReadResult.Success) {
        val key = result.diagnosticKey ?: return
        if (key == lastDiagnosticKey) return
        try {
            store.recordEvent(
                "direct_read_mode",
                "Direct telemetry read mode changed",
                result.diagnosticMessage
            )
            lastDiagnosticKey = key
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            logError("Failed to record direct read mode", error)
        }
    }

    private fun shouldSkipRepeatedFailure(failureKey: String, nowMs: Long): Boolean {
        if (lastPersistedFailureKey != failureKey) return false
        return nowMs - lastPersistedFailureAtMs < REPEATED_FAILURE_PERSIST_INTERVAL_MS
    }

    private fun notifyObserver(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            checkActive()
            logError("Normalized state write failed", error)
            recordEventSafely("normalized_write_error", "Normalized state write failed",
                error.diagnosticDetail("Main normalization"))
        }
    }

    private fun checkActive() {
        if (!isActive() || Thread.currentThread().isInterrupted)
            throw InterruptedException("Main poll owner stopped")
    }

    private fun recordEventSafely(category: String, message: String, detail: String?) {
        try {
            store.recordEvent(category, message, detail)
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            logError("Failed to record $category", error)
        }
    }

    private fun logError(message: String, error: Exception) {
        runCatching { Log.e(TAG, message, error) }
    }

    companion object {
        private const val TAG = "BYDCollectorPoller"
        private const val DIRECT_REQUEST_COUNT = 1
        private const val REPEATED_FAILURE_PERSIST_INTERVAL_MS = 30_000L
    }
}
