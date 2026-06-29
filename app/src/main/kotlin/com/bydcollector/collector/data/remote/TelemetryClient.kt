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
        val warningMessage: String? = null
    ) : TelemetryReadResult()

    data class Failure(
        val category: String,
        val message: String,
        override val rawBody: String?,
        override val elapsedMs: Long
    ) : TelemetryReadResult()
}

interface TelemetryClient {
    fun read(): TelemetryReadResult
}
