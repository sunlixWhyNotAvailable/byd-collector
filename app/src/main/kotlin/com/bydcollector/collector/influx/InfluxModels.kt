package com.bydcollector.collector.influx

data class InfluxActionResult(
    val ok: Boolean,
    val category: String,
    val message: String
) {
    companion object {
        fun ok(message: String = "ok") = InfluxActionResult(true, "ok", message)
        fun fail(category: String, message: String) = InfluxActionResult(false, category, message)
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

data class InfluxCursor(
    val fieldKey: String,
    val lastExportedHistoryId: Long
)

interface InfluxExportStore {
    fun ensureInfluxCursors(fieldKeys: Set<String>)
    fun pendingInfluxRows(fieldKey: String, afterHistoryId: Long, limit: Int): List<InfluxPendingHistoryRow>
    fun influxCursors(fieldKeys: Set<String>): List<InfluxCursor>
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
