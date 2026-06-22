package com.bydcollector.collector.data.polling

import android.util.Log
import com.bydcollector.collector.data.local.CatalogParameter
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.PersistedPollInput
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.data.remote.DiPlusClient
import com.bydcollector.collector.data.remote.DiPlusResult
import com.bydcollector.collector.data.remote.DiPlusTemplateBuilder

data class PollCycleResult(
    val pollId: Long?,
    val ok: Boolean,
    val category: String?,
    val elapsedMs: Long,
    val requestCount: Int
)

interface PollCycleRunner {
    fun pollOnce(sessionId: Long): PollCycleResult
}

interface PollStorage {
    fun getActiveCatalogParameters(): List<CatalogParameter>
    fun insertPoll(sessionId: Long, input: PersistedPollInput, parameters: List<CatalogParameter>): Long
    fun recordEvent(category: String, message: String, detail: String? = null)
}

interface SuccessfulPollObserver {
    fun onSuccessfulPoll(sessionId: Long, pollId: Long, timestamp: String, readings: List<PollReading>)
}

class PollPersistenceCoordinator(
    private val store: PollStorage,
    private val client: DiPlusClient,
    private val templateBuilder: DiPlusTemplateBuilder = DiPlusTemplateBuilder(),
    private val clock: Clock = SystemClockAdapter(),
    private val successfulPollObserver: SuccessfulPollObserver? = null
) : PollCycleRunner {
    private var lastPersistedFailureKey: String? = null
    private var lastPersistedFailureAtMs: Long = Long.MIN_VALUE

    override fun pollOnce(sessionId: Long): PollCycleResult {
        val parameters = store.getActiveCatalogParameters()
        val request = templateBuilder.build(parameters)
        val result = client.get(request)

        return try {
            when (result) {
                is DiPlusResult.Success -> {
                    val timestamp = clock.nowIso()
                    val pollId = store.insertPoll(
                        sessionId,
                        PersistedPollInput(
                            timestamp = timestamp,
                            ok = true,
                            elapsedMs = result.elapsedMs,
                            requestCount = request.requestCount,
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
                    try {
                        successfulPollObserver?.onSuccessfulPoll(sessionId, pollId, timestamp, result.readings)
                    } catch (error: RuntimeException) {
                        val detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                        logError("Normalized state write failed", error)
                        try {
                            store.recordEvent("normalized_write_error", "Normalized state write failed", detail)
                        } catch (eventError: RuntimeException) {
                            logError("Failed to record normalized state write failure", eventError)
                        }
                    }
                    lastPersistedFailureKey = null
                    lastPersistedFailureAtMs = Long.MIN_VALUE
                    PollCycleResult(pollId, ok = true, category = null, elapsedMs = result.elapsedMs, requestCount = request.requestCount)
                }

                is DiPlusResult.Failure -> {
                    val failureKey = "${result.category}:${result.message}"
                    val nowMs = clock.elapsedRealtimeMs()
                    if (shouldSkipRepeatedFailure(failureKey, nowMs)) {
                        return PollCycleResult(null, ok = false, category = result.category, elapsedMs = result.elapsedMs, requestCount = request.requestCount)
                    }
                    val pollId = store.insertPoll(
                        sessionId,
                        PersistedPollInput(
                            timestamp = clock.nowIso(),
                            ok = false,
                            elapsedMs = result.elapsedMs,
                            requestCount = request.requestCount,
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
                    store.recordEvent("poll_failure", "Poll failed: ${result.category}", result.message)
                    PollCycleResult(pollId, ok = false, category = result.category, elapsedMs = result.elapsedMs, requestCount = request.requestCount)
                }
            }
        } catch (error: RuntimeException) {
            val detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            runCatching { Log.e(TAG, "Database write failed", error) }
            try {
                store.recordEvent("db_write_error", "Database write failed", detail)
            } catch (eventError: RuntimeException) {
                logError("Failed to record database write failure", eventError)
            }
            PollCycleResult(null, ok = false, category = "db_write_error", elapsedMs = 0, requestCount = request.requestCount)
        }
    }

    private fun shouldSkipRepeatedFailure(failureKey: String, nowMs: Long): Boolean {
        if (lastPersistedFailureKey != failureKey) return false
        return nowMs - lastPersistedFailureAtMs < REPEATED_FAILURE_PERSIST_INTERVAL_MS
    }

    private fun logError(message: String, error: RuntimeException) {
        runCatching { Log.e(TAG, message, error) }
    }

    companion object {
        private const val TAG = "BYDCollectorPoller"
        private const val REPEATED_FAILURE_PERSIST_INTERVAL_MS = 30_000L
    }
}
