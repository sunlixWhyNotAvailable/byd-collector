package com.bydcollector.collector.influx

import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import java.time.OffsetDateTime

//exports normalized vehicle_state_history to influx as the durable analytics channel
class InfluxExportCoordinator(
    private val store: InfluxExportStore,
    private val client: InfluxClient,
    private val configProvider: () -> InfluxConfig,
    private val clock: Clock = SystemClockAdapter()
) {
    fun testConnection(): InfluxActionResult {
        val config = configProvider()
        return client.test(config)
    }

    fun startExport(): InfluxActionResult {
        val config = configProvider()
        validate(config)?.let { return it }
        val test = client.test(config)
        if (!test.ok) {
            recordFailure("error", test.message)
            return test
        }
        //creates cursors before the first write so every enabled field resumes independently
        store.ensureInfluxCursors(effectiveFields(config))
        store.updateInfluxExportState(
            status = "running",
            mode = "realtime",
            pendingRows = 0,
            oldestPendingAt = null,
            nextRetryAt = null,
            lastSuccessAt = clock.nowIso(),
            lastErrorAt = null,
            lastError = null,
            exportedRowsDelta = 0
        )
        return runOneCycle(force = true)
    }

    fun stopExport(): InfluxActionResult {
        store.updateInfluxExportState(
            status = "stopped",
            mode = null,
            pendingRows = 0,
            oldestPendingAt = null,
            nextRetryAt = null,
            lastSuccessAt = null,
            lastErrorAt = null,
            lastError = null,
            exportedRowsDelta = 0
        )
        return InfluxActionResult.ok("stopped")
    }

    fun reExportNewCategories(): InfluxActionResult {
        val config = configProvider()
        validate(config)?.let { return it }
        //adds missing cursors without rewinding existing ones, useful after enabling more categories
        store.ensureInfluxCursors(effectiveFields(config))
        store.recordInfluxEvent(
            eventType = "influx_reexport_prepared",
            message = "Missing cursors created for enabled categories",
            batchCount = null,
            fromHistoryId = null,
            toHistoryId = null
        )
        return InfluxActionResult.ok("missing cursors ensured")
    }

    fun runOneCycle(force: Boolean = false): InfluxActionResult {
        val config = configProvider()
        validate(config)?.let { return it }
        val state = store.influxExportState()
        val fieldKeys = effectiveFields(config)
        if (fieldKeys.isEmpty()) return InfluxActionResult.ok("no fields enabled")
        store.ensureInfluxCursors(fieldKeys)
        //counts pending history points from cursors so dashboard queue state is not just the current batch size
        val pendingBefore = store.pendingInfluxSummary(fieldKeys)
        //backs off failed writes so a down ha/tailscale endpoint does not spin every poll cycle
        if (!force && !state.nextRetryAt.isNullOrBlank() && !retryDue(state.nextRetryAt, clock.nowIso())) {
            store.updateInfluxExportState(
                status = state.status,
                mode = state.mode,
                pendingRows = pendingBefore.rows,
                oldestPendingAt = pendingBefore.oldestObservedAt,
                nextRetryAt = state.nextRetryAt,
                lastSuccessAt = state.lastSuccessAt,
                lastErrorAt = state.lastErrorAt,
                lastError = state.lastError,
                exportedRowsDelta = 0
            )
            return InfluxActionResult.ok("influx backoff active")
        }

        val cursors = store.influxCursors(fieldKeys)
        val rowsByField = cursors
            .map { cursor -> cursor.fieldKey to store.pendingInfluxRows(cursor.fieldKey, cursor.lastExportedHistoryId, BATCH_LIMIT) }
            .toMap()
        //interleaves fields so one noisy signal cannot starve slower-changing fields during catch-up
        val rows = roundRobin(rowsByField)
        if (rows.isEmpty()) {
            store.updateInfluxExportState(
                status = "running",
                mode = if (force) "catch_up" else "realtime",
                pendingRows = 0,
                oldestPendingAt = null,
                nextRetryAt = null,
                lastSuccessAt = clock.nowIso(),
                lastErrorAt = null,
                lastError = null,
                exportedRowsDelta = 0
            )
            return InfluxActionResult.ok("nothing pending")
        }

        val lines = rows.map { InfluxLineProtocol.toLine(it, config) }
        val write = client.write(config, lines)
        if (!write.ok) {
            rows.map { it.fieldKey }.distinct().forEach { fieldKey ->
                store.updateInfluxCursorError(fieldKey, write.message, clock.nowIso())
            }
            recordFailure("backoff", write.message, pendingBefore)
            return write
        }

        val exportedAt = clock.nowIso()
        rows.groupBy { it.fieldKey }.forEach { (fieldKey, fieldRows) ->
            store.updateInfluxCursorSuccess(fieldKey, fieldRows.maxOf { it.id }, exportedAt)
        }
        val pendingAfter = store.pendingInfluxSummary(fieldKeys)
        store.updateInfluxExportState(
            status = "running",
            mode = if (rows.size >= CATCH_UP_THRESHOLD) "catch_up" else "realtime",
            pendingRows = pendingAfter.rows,
            oldestPendingAt = pendingAfter.oldestObservedAt,
            nextRetryAt = null,
            lastSuccessAt = exportedAt,
            lastErrorAt = null,
            lastError = null,
            exportedRowsDelta = rows.size.toLong()
        )
        store.recordInfluxEvent(
            eventType = "influx_export_batch",
            message = "vehicle_state_history batch exported",
            batchCount = rows.size,
            fromHistoryId = rows.minOf { it.id },
            toHistoryId = rows.maxOf { it.id }
        )
        return InfluxActionResult.ok("exported ${rows.size} rows")
    }

    private fun validate(config: InfluxConfig): InfluxActionResult? {
        if (!config.enabled) return InfluxActionResult.fail("influx_disabled", "InfluxDB export is disabled")
        if (config.host.isBlank()) return InfluxActionResult.fail("influx_host_missing", "InfluxDB host is blank")
        if (config.normalizedDatabase().isBlank()) return InfluxActionResult.fail("influx_database_missing", "InfluxDB database is blank")
        return null
    }

    private fun effectiveFields(config: InfluxConfig): Set<String> {
        return NormalizedFieldCatalog.fields
            .filter { field -> config.enabledCategories.contains(field.category.mqttKey) }
            .map { field -> field.fieldKey }
            .toSet()
    }

    private fun roundRobin(rowsByField: Map<String, List<InfluxPendingHistoryRow>>): List<InfluxPendingHistoryRow> {
        val result = mutableListOf<InfluxPendingHistoryRow>()
        var index = 0
        while (result.size < BATCH_LIMIT) {
            var added = false
            rowsByField.values.forEach { rows ->
                val row = rows.getOrNull(index)
                if (row != null && result.size < BATCH_LIMIT) {
                    result += row
                    added = true
                }
            }
            if (!added) break
            index += 1
        }
        return result
    }

    private fun recordFailure(
        status: String,
        error: String,
        pendingSummary: InfluxPendingSummary = InfluxPendingSummary(rows = 0, oldestObservedAt = null)
    ) {
        val now = clock.nowIso()
        store.updateInfluxExportState(
            status = status,
            mode = null,
            pendingRows = pendingSummary.rows,
            oldestPendingAt = pendingSummary.oldestObservedAt,
            nextRetryAt = plusSeconds(now, BACKOFF_SECONDS),
            lastSuccessAt = null,
            lastErrorAt = now,
            lastError = error,
            exportedRowsDelta = 0
        )
        store.recordInfluxEvent(
            eventType = "influx_export_error",
            message = error,
            batchCount = null,
            fromHistoryId = null,
            toHistoryId = null
        )
    }

    private fun plusSeconds(iso: String, seconds: Long): String {
        return runCatching { OffsetDateTime.parse(iso).plusSeconds(seconds).toString() }.getOrDefault(iso)
    }

    private fun retryDue(nextRetryAt: String, nowIso: String): Boolean {
        return runCatching {
            !OffsetDateTime.parse(nowIso).isBefore(OffsetDateTime.parse(nextRetryAt))
        }.getOrDefault(true)
    }

    private companion object {
        const val EXPORT_SOURCE_TABLE = "vehicle_state_history"
        const val BATCH_LIMIT = 300
        const val CATCH_UP_THRESHOLD = 1_000
        const val BACKOFF_SECONDS = 30L
    }
}
