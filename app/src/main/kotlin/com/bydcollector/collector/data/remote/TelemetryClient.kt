package com.bydcollector.collector.data.remote

import com.bydcollector.collector.data.local.PollReading

sealed class TelemetryReadResult {
    abstract val rawBody: String?
    abstract val elapsedMs: Long

    data class Success(
        override val rawBody: String,
        override val elapsedMs: Long,
        val readings: List<PollReading>,
        val warningCategory: String? = null,
        val warningMessage: String? = null,
        val diagnosticKey: String? = null,
        val diagnosticMessage: String? = null,
        /** Keys for every GET actually attempted, including per-field failures. */
        val attemptedKeys: List<String> = readings.map { it.rawKey },
        /** Remaining delay measured when the helper formed its batch reply. */
        val nextDueDelayMs: Long? = null,
        /** All actual GET attempts, including failed fields represented by null/null readings. */
        val attemptedReadings: List<PollReading> = readings
    ) : TelemetryReadResult() {
        init {
            require(attemptedKeys.distinct().size == attemptedKeys.size) { "duplicate attempted telemetry key" }
            require(attemptedReadings.map { it.rawKey }.distinct().size == attemptedReadings.size) {
                "duplicate attempted telemetry reading"
            }
            require(nextDueDelayMs == null || nextDueDelayMs >= 0L) { "negative next telemetry due delay" }
        }
    }

    data class Failure(
        val category: String,
        val message: String,
        override val rawBody: String?,
        override val elapsedMs: Long,
        /** Keys for every GET actually attempted, including per-field failures. */
        val attemptedKeys: List<String> = emptyList(),
        /** Remaining delay measured when the helper formed its batch reply. */
        val nextDueDelayMs: Long? = null,
        /** Actual attempted fields on an all-failed sparse GET; empty for transport failures. */
        val attemptedReadings: List<PollReading> = emptyList()
    ) : TelemetryReadResult() {
        init {
            require(attemptedKeys.distinct().size == attemptedKeys.size) { "duplicate attempted telemetry key" }
            require(attemptedReadings.map { it.rawKey }.distinct().size == attemptedReadings.size) {
                "duplicate attempted telemetry reading"
            }
            require(nextDueDelayMs == null || nextDueDelayMs >= 0L) { "negative next telemetry due delay" }
        }
    }

    /** The helper has no due reads; this cycle must not create a poll or notify source observers. */
    data class NoReadDue(
        override val elapsedMs: Long,
        val nextDueDelayMs: Long
    ) : TelemetryReadResult() {
        override val rawBody: String? = null

        init {
            require(nextDueDelayMs > 0L) { "no-read-due delay must be positive" }
        }
    }

    data class ReplayPending(
        override val elapsedMs: Long,
        override val rawBody: String? = null
    ) : TelemetryReadResult()
}

interface TelemetryClient {
    fun read(): TelemetryReadResult
}
