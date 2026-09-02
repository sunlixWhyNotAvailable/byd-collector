package com.bydcollector.collector.influx

import com.bydcollector.collector.ha.HaEndpointProfile

enum class InfluxFailureKind {
    TRANSPORT,
    AUTHENTICATION,
    DATA,
    PROTOCOL,
    OTHER
}

/** Metadata-only causal evidence; never include payloads, credentials, or response bodies. */
data class InfluxDiagnosticEvent(
    val type: String,
    val details: Map<String, String> = emptyMap()
)

typealias InfluxDiagnosticSink = (InfluxDiagnosticEvent) -> Unit

data class InfluxRequestContext(
    val requestId: String,
    val mode: String,
    val profile: HaEndpointProfile? = null,
    val source: String
)

data class InfluxActionResult(
    val ok: Boolean,
    val category: String,
    val message: String,
    val httpStatus: Int? = null,
    val failureKind: InfluxFailureKind? = null
) {
    companion object {
        fun ok(message: String = "ok") = InfluxActionResult(true, "ok", message)
        fun fail(
            category: String,
            message: String,
            httpStatus: Int? = null,
            failureKind: InfluxFailureKind? = null
        ) = InfluxActionResult(false, category, message, httpStatus, failureKind)
    }
}

data class InfluxPendingHistoryRow(
    val id: Long,
    val fieldKey: String,
    val category: String,
    val valueType: String,
    val valueText: String?,
    val valueNumber: Double?,
    val valueBool: Boolean?,
    val quality: String,
    val unit: String?,
    val sourcePollId: Long?,
    val sourceKeys: String,
    val observedAt: String,
    val changedAt: String
)

data class InfluxExportStateSnapshot(
    val status: String,
    val mode: String?,
    val pendingRows: Long,
    val oldestPendingAt: String?,
    val nextRetryAt: String?,
    val lastSuccessAt: String?,
    val lastErrorAt: String?,
    val lastError: String?,
    val exportedRowsTotal: Long
)

data class InfluxPendingSummary(
    val rows: Long,
    val oldestObservedAt: String?
)

data class InfluxCursor(
    val fieldKey: String,
    val lastExportedHistoryId: Long
)

interface InfluxExportStore {
    fun ensureInfluxCursors(fieldKeys: Set<String>)
    fun pendingInfluxSummary(fieldKeys: Set<String>): InfluxPendingSummary
    fun pendingInfluxRows(fieldKeys: Set<String>, limit: Int): List<InfluxPendingHistoryRow>
    fun updateInfluxCursorSuccess(fieldKey: String, historyId: Long, exportedAt: String)
    fun updateInfluxCursorError(fieldKey: String, error: String, errorAt: String)
    fun influxExportState(): InfluxExportStateSnapshot
    fun updateInfluxExportState(
        status: String,
        mode: String?,
        pendingRows: Long,
        oldestPendingAt: String?,
        nextRetryAt: String?,
        lastSuccessAt: String?,
        lastErrorAt: String?,
        lastError: String?,
        exportedRowsDelta: Long
    )
    fun recordInfluxEvent(
        eventType: String,
        message: String?,
        batchCount: Int?,
        fromHistoryId: Long?,
        toHistoryId: Long?
    )
}
