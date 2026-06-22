package com.bydcollector.collector.data.remote

import com.bydcollector.collector.data.local.PollReading

sealed class DiPlusResult {
    abstract val rawBody: String?
    abstract val elapsedMs: Long

    data class Success(
        override val rawBody: String,
        override val elapsedMs: Long,
        val readings: List<PollReading>,
        val warningCategory: String? = null,
        val warningMessage: String? = null
    ) : DiPlusResult()

    data class Failure(
        val category: String,
        val message: String,
        override val rawBody: String?,
        override val elapsedMs: Long,
        val readings: List<PollReading> = emptyList()
    ) : DiPlusResult()

    companion object {
        const val NETWORK_ERROR = "network_error"
        const val TIMEOUT = "timeout"
        const val HTTP_ERROR = "http_error"
        const val DI_SUCCESS_FALSE = "di_success_false"
        const val PARSE_ERROR = "parse_error"
    }
}

interface DiPlusClient {
    fun get(request: DiPlusRequest): DiPlusResult
}
