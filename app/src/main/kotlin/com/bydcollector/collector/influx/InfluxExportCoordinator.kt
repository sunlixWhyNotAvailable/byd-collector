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
        //a real batch write is the only start success signal; a separate HTTP test caused a false-success flicker
        return runOneCycle(force = true)
    }

    fun resumeExport(): InfluxActionResult {
        val config = configProvider()
        validate(config)?.let { return it }
        val fieldKeys = effectiveFields(config)
        store.ensureInfluxCursors(fieldKeys)
        val pending = store.pendingInfluxSummary(fieldKeys)
        val state = store.influxExportState()
        val preservesFailure = pending.rows > 0L && state.status == STATUS_BACKOFF && !state.nextRetryAt.isNullOrBlank()
        store.updateInfluxExportState(
            status = when {
                pending.rows == 0L -> STATUS_IDLE
                preservesFailure -> STATUS_BACKOFF
                else -> STATUS_SCHEDULED
            },
            mode = modeFor(pending.rows),
            pendingRows = pending.rows,
            oldestPendingAt = pending.oldestObservedAt,
            nextRetryAt = when {
                pending.rows == 0L -> null
                !state.nextRetryAt.isNullOrBlank() -> state.nextRetryAt
                else -> plusSeconds(clock.nowIso(), SUCCESS_BATCH_INTERVAL_SECONDS)
            },
            lastSuccessAt = state.lastSuccessAt,
            lastErrorAt = state.lastErrorAt.takeIf { preservesFailure },
            lastError = state.lastError.takeIf { preservesFailure },
            exportedRowsDelta = 0
        )
        return InfluxActionResult.ok("resumed")
    }

    fun retryDelayMs(): Long? {
        val config = configProvider()
        if (validate(config) != null) return null
        val fieldKeys = effectiveFields(config)
        if (fieldKeys.isEmpty() || store.pendingInfluxSummary(fieldKeys).rows == 0L) return null
        val nextRetryAt = store.influxExportState().nextRetryAt ?: return null
        return runCatching {
            val now = OffsetDateTime.parse(clock.nowIso()).toInstant().toEpochMilli()
            (OffsetDateTime.parse(nextRetryAt).toInstant().toEpochMilli() - now).coerceAtLeast(0L)
        }.getOrDefault(0L)
    }

    fun stopExport(): InfluxActionResult {
        val config = configProvider()
        val fieldKeys = effectiveFields(config)
        store.ensureInfluxCursors(fieldKeys)
        val pending = store.pendingInfluxSummary(fieldKeys)
        val state = store.influxExportState()
        store.updateInfluxExportState(
            status = STATUS_STOPPED,
            mode = modeFor(pending.rows),
            pendingRows = pending.rows,
            oldestPendingAt = pending.oldestObservedAt,
            nextRetryAt = null,
            lastSuccessAt = state.lastSuccessAt,
            lastErrorAt = state.lastErrorAt,
            lastError = state.lastError,
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
        if (fieldKeys.isEmpty()) {
            store.updateInfluxExportState(
                status = STATUS_IDLE,
                mode = "realtime",
                pendingRows = 0,
                oldestPendingAt = null,
                nextRetryAt = null,
                lastSuccessAt = state.lastSuccessAt,
                lastErrorAt = null,
                lastError = null,
                exportedRowsDelta = 0
            )
            return InfluxActionResult.ok("no fields enabled")
        }
        store.ensureInfluxCursors(fieldKeys)
        //counts pending history points from cursors so dashboard queue state is not just the current batch size
        val pendingBefore = store.pendingInfluxSummary(fieldKeys)
        //honors the short success pacing and the longer persisted failure backoff
        if (!force && !state.nextRetryAt.isNullOrBlank() && !retryDue(state.nextRetryAt, clock.nowIso())) {
            val preservesFailure = state.status == STATUS_BACKOFF
            store.updateInfluxExportState(
                status = if (preservesFailure) STATUS_BACKOFF else STATUS_SCHEDULED,
                mode = modeFor(pendingBefore.rows),
                pendingRows = pendingBefore.rows,
                oldestPendingAt = pendingBefore.oldestObservedAt,
                nextRetryAt = state.nextRetryAt,
                lastSuccessAt = state.lastSuccessAt,
                lastErrorAt = state.lastErrorAt.takeIf { preservesFailure },
                lastError = state.lastError.takeIf { preservesFailure },
                exportedRowsDelta = 0
            )
            return InfluxActionResult.ok("influx next attempt pending")
        }

        val rows = store.pendingInfluxRows(fieldKeys, BATCH_LIMIT)
        if (rows.isEmpty()) {
            store.updateInfluxExportState(
                status = STATUS_IDLE,
                mode = modeFor(pendingBefore.rows),
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

        store.updateInfluxExportState(
            status = STATUS_EXPORTING,
            mode = modeFor(pendingBefore.rows),
            pendingRows = pendingBefore.rows,
            oldestPendingAt = pendingBefore.oldestObservedAt,
            nextRetryAt = null,
            lastSuccessAt = state.lastSuccessAt,
            lastErrorAt = null,
            lastError = null,
            exportedRowsDelta = 0
        )
        return try {
            val lines = rows.map { InfluxLineProtocol.toLine(it, config) }
            val write = client.write(config, lines)
            if (!write.ok) {
                rows.map { it.fieldKey }.distinct().forEach { fieldKey ->
                    store.updateInfluxCursorError(fieldKey, write.message, clock.nowIso())
                }
                recordFailure(STATUS_BACKOFF, write.message, pendingBefore)
                return write
            }

            val exportedAt = clock.nowIso()
            rows.groupBy { it.fieldKey }.forEach { (fieldKey, fieldRows) ->
                store.updateInfluxCursorSuccess(fieldKey, fieldRows.maxOf { it.id }, exportedAt)
            }
            val pendingAfter = store.pendingInfluxSummary(fieldKeys)
            store.updateInfluxExportState(
                status = if (pendingAfter.rows > 0L) STATUS_SCHEDULED else STATUS_IDLE,
                mode = modeFor(pendingAfter.rows),
                pendingRows = pendingAfter.rows,
                oldestPendingAt = pendingAfter.oldestObservedAt,
                nextRetryAt = if (pendingAfter.rows > 0L) {
                    plusSeconds(exportedAt, SUCCESS_BATCH_INTERVAL_SECONDS)
                } else {
                    null
                },
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
            InfluxActionResult.ok("exported ${rows.size} rows")
        } catch (error: RuntimeException) {
            val detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}".take(512)
            recordFailure(STATUS_BACKOFF, detail, pendingBefore)
            InfluxActionResult.fail("influx_export_exception", detail)
        }
    }

    private fun validate(config: InfluxConfig): InfluxActionResult? {
        if (!config.enabled) return InfluxActionResult.fail("influx_disabled", "InfluxDB export is disabled")
        if (config.host.isBlank()) return InfluxActionResult.fail("influx_host_missing", "InfluxDB host is blank")
        if (config.normalizedDatabase().isBlank()) return InfluxActionResult.fail("influx_database_missing", "InfluxDB database is blank")
        return null
    }

    private fun effectiveFields(config: InfluxConfig): Set<String> {
        return NormalizedFieldCatalog.fields
            .filter { field -> config.isCategoryEnabled(field.category.mqttKey) }
            .map { field -> field.fieldKey }
            .toSet()
    }

    private fun recordFailure(
        status: String,
        error: String,
        pendingSummary: InfluxPendingSummary = InfluxPendingSummary(rows = 0, oldestObservedAt = null)
    ) {
        val now = clock.nowIso()
        val state = store.influxExportState()
        store.updateInfluxExportState(
            status = status,
            mode = modeFor(pendingSummary.rows),
            pendingRows = pendingSummary.rows,
            oldestPendingAt = pendingSummary.oldestObservedAt,
            nextRetryAt = plusSeconds(now, FAILURE_RETRY_INTERVAL_SECONDS),
            lastSuccessAt = state.lastSuccessAt,
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

    private fun modeFor(pendingRows: Long): String {
        return if (pendingRows >= CATCH_UP_THRESHOLD) "catch_up" else "realtime"
    }

    private companion object {
        const val EXPORT_SOURCE_TABLE = "vehicle_state_history"
        const val BATCH_LIMIT = 300
        const val CATCH_UP_THRESHOLD = 1_000
        const val SUCCESS_BATCH_INTERVAL_SECONDS = 1L
        const val FAILURE_RETRY_INTERVAL_SECONDS = 30L
        const val STATUS_IDLE = "idle"
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_EXPORTING = "exporting"
        const val STATUS_BACKOFF = "backoff"
        const val STATUS_STOPPED = "stopped"
    }
}
