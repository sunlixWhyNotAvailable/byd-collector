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
    val deferred: Boolean = false,
    /** Remaining delay measured at helper batch-reply time; null keeps the existing poll interval. */
    val nextDueDelayMs: Long? = null
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
                is TelemetryReadResult.NoReadDue -> PollCycleResult(
                    pollId = null,
                    ok = true,
                    category = null,
                    elapsedMs = result.elapsedMs,
                    requestCount = 0,
                    deferred = true,
                    nextDueDelayMs = result.nextDueDelayMs
                )
                is TelemetryReadResult.Success -> {
                    val timestamp = clock.nowIso()
                    val source = liveSource.capture(clock.elapsedRealtimeMs())
                    val attemptedReadings = materializeAttemptedReadings(result.attemptedKeys, result.attemptedReadings)
                    val parameters = parametersForKeys(attemptedReadings.map { it.rawKey })
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
                            readings = attemptedReadings,
                            source = source,
                            origin = PollOrigin.LIVE
                        ),
                        parameters = parameters
                    )
                    persistDiagnosticTransition(result)
                    notifyObserver {
                        successfulPollObserver?.onSourcePoll(
                            sessionId,
                            pollId,
                            timestamp,
                            attemptedReadings,
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
                        valueRowsPersisted = 1L,
                        nextDueDelayMs = result.nextDueDelayMs
                    )
                }

                is TelemetryReadResult.Failure -> {
                    val failureKey = "${result.category}:${result.message}"
                    val nowMs = clock.elapsedRealtimeMs()
                    val timestamp = clock.nowIso()
                    val attemptedKeys = result.attemptedKeys.ifEmpty { result.attemptedReadings.map { it.rawKey } }
                    val attemptedReadings = materializeAttemptedReadings(attemptedKeys, result.attemptedReadings)
                    //throttles identical failures so helper outages do not flood sqlite every second
                    if (attemptedReadings.isEmpty() && shouldSkipRepeatedFailure(failureKey, nowMs)) {
                        return PollCycleResult(
                            pollId = null,
                            ok = false,
                            category = result.category,
                            elapsedMs = result.elapsedMs,
                            requestCount = DIRECT_REQUEST_COUNT,
                            timestamp = timestamp,
                            errorMessage = result.message,
                            nextDueDelayMs = result.nextDueDelayMs
                        )
                    }
                    val source = liveSource.capture(nowMs)
                    val parameters = parametersForKeys(attemptedReadings.map { it.rawKey })
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
                            readings = attemptedReadings,
                            source = source,
                            origin = PollOrigin.LIVE
                        ),
                        parameters = parameters
                    )
                    lastPersistedFailureKey = failureKey
                    lastPersistedFailureAtMs = nowMs
                    notifyObserver {
                        if (attemptedReadings.isNotEmpty()) {
                            successfulPollObserver?.onSourcePoll(
                                sessionId, pollId, timestamp, attemptedReadings, PollOrigin.LIVE, source
                            )
                        } else {
                            successfulPollObserver?.onSourceFailure(
                                sessionId = sessionId, pollId = pollId, timestamp = timestamp,
                                origin = PollOrigin.LIVE, source = source
                            )
                        }
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
                        pollRowsPersisted = 1L,
                        nextDueDelayMs = result.nextDueDelayMs
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

    private fun parametersForKeys(keys: List<String>): List<CatalogParameter> {
        if (keys.isEmpty()) return emptyList()
        val parameters = store.getActiveCatalogParameters().associateBy { it.key }
        return keys.map { key -> requireNotNull(parameters[key]) { "GET result key is absent from active catalog: $key" } }
    }

    private fun materializeAttemptedReadings(
        attemptedKeys: List<String>,
        readings: List<PollReading>
    ): List<PollReading> {
        if (attemptedKeys.isEmpty()) return readings
        val byKey = readings.associateBy { it.rawKey }
        require(byKey.size == readings.size) { "GET result contains duplicate reading keys" }
        require(byKey.keys.all { it in attemptedKeys }) { "GET readings include a key that was not attempted" }
        return attemptedKeys.map { key -> byKey[key] ?: PollReading(key, null, null) }
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
