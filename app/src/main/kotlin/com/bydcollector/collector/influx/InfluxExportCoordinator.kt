package com.bydcollector.collector.influx

import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.ha.HaEndpointProfile
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

//exports normalized vehicle_state_history to influx as the durable analytics channel
class InfluxExportCoordinator(
    private val store: InfluxExportStore,
    private val client: InfluxClient,
    private val configProvider: () -> InfluxConfig,
    private val clock: Clock = SystemClockAdapter(),
    private val diagnostics: InfluxDiagnosticSink = {}
) {
    private var currentBatchSize: Int? = null
    private val cancellationGeneration = AtomicLong(0L)
    @Volatile private var sessionConnection: InfluxConfig? = null
    private var lastDiagnosticGate: String? = null

    @Volatile
    private var currentRoute: HaEndpointProfile? = null

    val activeRoute: HaEndpointProfile?
        get() = currentRoute

    internal val sessionFrozen: Boolean
        get() = sessionConnection != null

    /** Credential-free endpoint metadata for the frozen export session. */
    internal val frozenEndpoints: String
        get() = sessionConnection?.let(::endpoints) ?: "none"

    fun testConnection(configOverride: InfluxConfig? = null, profile: HaEndpointProfile? = null): InfluxActionResult {
        val config = configOverride ?: configProvider()
        val requestId = UUID.randomUUID().toString()
        val startedNs = System.nanoTime()
        diagnostic(
            "influx_request_started",
            mapOf(
                "request_id" to requestId,
                "mode" to "test",
                "source" to "current_test",
                "host" to safeInfluxDiagnosticHost(config.host),
                "port" to config.port.toString(),
                "profile" to (profile?.name?.lowercase() ?: "unknown")
            )
        )
        val result = client.test(config, requestId, profile)
        diagnosticResult(
            "test",
            requestId,
            config,
            result,
            profile = profile,
            durationMs = elapsedDiagnosticMs(startedNs)
        )
        return result
    }

    fun endSession() {
        sessionConnection = null
        currentRoute = null
        diagnostic("influx_session_ended", mapOf("frozen" to "false", "actual_route" to "none"))
    }

    /** Cancels split work between HTTP requests without discarding an acknowledged write. */
    fun cancelInFlight() {
        cancellationGeneration.incrementAndGet()
    }

    fun startExport(isCurrent: () -> Boolean = { true }): InfluxActionResult {
        //a real batch write is the only start success signal; a separate HTTP test caused a false-success flicker
        return runOneCycle(force = true, isCurrent = isCurrent)
    }

    fun resumeExport(isCurrent: () -> Boolean = { true }): InfluxActionResult {
        val pass = ExportPass(cancellationGeneration.get(), isCurrent)
        if (!isCurrent()) return InfluxActionResult.ok("influx work superseded")
        val liveConfig = configProvider()
        if (sessionConnection == null) {
            validate(liveConfig)?.let {
                diagnosticValidationGate(it)
                return it
            }
            captureSession(liveConfig)
        }
        val config = runtimeConfig(liveConfig)
        validate(config)?.let {
            diagnosticValidationGate(it)
            return it
        }
        val fieldKeys = effectiveFields(config)
        store.ensureInfluxCursors(fieldKeys)
        val pending = store.pendingInfluxSummary(fieldKeys)
        val state = store.influxExportState()
        if (pass.cancelled()) return InfluxActionResult.ok("influx work superseded")
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
        val config = runtimeConfig(configProvider())
        if (validate(config) != null) return null
        val fieldKeys = effectiveFields(config)
        if (fieldKeys.isEmpty()) {
            diagnosticGate("no_categories")
            return null
        }
        // The completed cycle already persisted its deadline; scheduling must not rescan history.
        val nextRetryAt = store.influxExportState().nextRetryAt ?: return null
        return runCatching {
            val now = OffsetDateTime.parse(clock.nowIso()).toInstant().toEpochMilli()
            (OffsetDateTime.parse(nextRetryAt).toInstant().toEpochMilli() - now).coerceAtLeast(0L)
        }.getOrDefault(0L)
    }

    fun stopExport(): InfluxActionResult {
        cancelInFlight()
        return try {
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
            InfluxActionResult.ok("stopped")
        } finally {
            endSession()
        }
    }

    fun runOneCycle(force: Boolean = false, isCurrent: () -> Boolean = { true }): InfluxActionResult {
        // Capture before config/SQL; old work must not adopt a cancellation generation advanced during preparation.
        val pass = ExportPass(cancellationGeneration.get(), isCurrent)
        if (!isCurrent()) return InfluxActionResult.ok("influx work superseded")
        val liveConfig = configProvider()
        if (sessionConnection == null) {
            validate(liveConfig)?.let {
                diagnosticValidationGate(it)
                return it
            }
            captureSession(liveConfig)
        }
        val config = runtimeConfig(liveConfig)
        validate(config)?.let {
            diagnosticValidationGate(it)
            return it
        }
        val state = store.influxExportState()
        val fieldKeys = effectiveFields(config)
        if (fieldKeys.isEmpty()) {
            diagnosticGate("no_categories")
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
        //honors the short success pacing and the longer persisted failure backoff
        if (!force && !state.nextRetryAt.isNullOrBlank() && !retryDue(state.nextRetryAt, clock.nowIso())) {
            diagnosticGate("backoff", mapOf("retry_deadline" to state.nextRetryAt))
            // Keep the last calculated snapshot/error, rather than rewriting it as a fresh count.
            return InfluxActionResult.ok("influx next attempt pending")
        }
        store.ensureInfluxCursors(fieldKeys)
        //counts pending history points from cursors so dashboard queue state is not just the current batch size
        val pendingBefore = store.pendingInfluxSummary(fieldKeys)

        val batchLimit = nextBatchLimit(pendingBefore.rows)
        val rows = store.pendingInfluxRows(fieldKeys, batchLimit)
        if (pass.cancelled()) return InfluxActionResult.ok("influx work superseded")
        if (rows.isEmpty()) {
            diagnosticGate("no_work")
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
            val exportedAt = clock.nowIso()
            var exportFailure: Throwable? = null
            val batch = try {
                exportRows(config, rows, exportedAt, pass = pass)
            } catch (error: Throwable) {
                exportFailure = error
                throw error
            } finally {
                if (exportFailure == null) {
                    recordPoisonSummary(pass)
                } else {
                    try {
                        recordPoisonSummary(pass)
                    } catch (_: Throwable) {
                        // Preserve the original cancellation/fatal signal.
                    }
                }
            }
            batch.failure?.let { failure ->
                val pendingAfterFailure = store.pendingInfluxSummary(fieldKeys)
                if (isTransientFailure(failure)) {
                    deescalateBatchSize(pendingAfterFailure.rows)
                }
                recordFailure(STATUS_BACKOFF, failure.message, pendingAfterFailure)
                return failure
            }

            if (batch.deferred || batch.cancelled) {
                val pendingAfter = store.pendingInfluxSummary(fieldKeys)
                val stopped = batch.cancelled && !configProvider().enabled
                store.updateInfluxExportState(
                    status = if (stopped) STATUS_STOPPED else STATUS_SCHEDULED,
                    mode = modeFor(pendingAfter.rows),
                    pendingRows = pendingAfter.rows,
                    oldestPendingAt = pendingAfter.oldestObservedAt,
                    nextRetryAt = if (!stopped && pendingAfter.rows > 0L) {
                        plusSeconds(exportedAt, SUCCESS_BATCH_INTERVAL_SECONDS)
                    } else {
                        null
                    },
                    lastSuccessAt = exportedAt.takeIf { batch.exportedRows > 0 } ?: state.lastSuccessAt,
                    lastErrorAt = null,
                    lastError = null,
                    exportedRowsDelta = batch.exportedRows.toLong()
                )
                return InfluxActionResult.ok(
                    if (batch.cancelled) "influx export stopped between requests"
                    else "influx split pass scheduled"
                )
            }

            val pendingAfter = store.pendingInfluxSummary(fieldKeys)
            if (batch.exportedRows > 0) {
                rampBatchSize(pendingAfter.rows)
            } else if (influxDesiredBatchLimit(pendingAfter.rows) == REALTIME_BATCH_LIMIT) {
                currentBatchSize = REALTIME_BATCH_LIMIT
            }
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
                exportedRowsDelta = batch.exportedRows.toLong()
            )
            if (batch.exportedRows > 0) {
                store.recordInfluxEvent(
                    eventType = "influx_export_batch",
                    message = "vehicle_state_history batch exported",
                    batchCount = batch.exportedRows,
                    fromHistoryId = rows.minOf { it.id },
                    toHistoryId = rows.maxOf { it.id }
                )
            }
            val poisonSuffix = if (batch.poisonRows > 0) "; quarantined ${batch.poisonRows} poison rows" else ""
            InfluxActionResult.ok("exported ${batch.exportedRows} rows$poisonSuffix")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Error) {
            throw error
        } catch (error: RuntimeException) {
            val detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}".take(512)
            deescalateBatchSize(pendingBefore.rows)
            recordFailure(STATUS_BACKOFF, detail, store.pendingInfluxSummary(fieldKeys))
            InfluxActionResult.fail("influx_export_exception", detail)
        }
    }

    private fun validate(config: InfluxConfig): InfluxActionResult? {
        if (!config.enabled) return InfluxActionResult.fail("influx_disabled", "InfluxDB export is disabled")
        config.validateEndpoints()?.let {
            return InfluxActionResult.fail(
                "influx_endpoint_invalid",
                it,
                failureKind = InfluxFailureKind.PROTOCOL
            )
        }
        if (config.normalizedDatabase().isBlank()) return InfluxActionResult.fail("influx_database_missing", "InfluxDB database is blank")
        return null
    }

    private fun captureSession(config: InfluxConfig) {
        sessionConnection = config.copy(enabledCategories = config.enabledCategories.toSet())
        currentRoute = null
        diagnostic("influx_session_captured", mapOf("frozen" to "true", "source" to "frozen_export", "endpoints" to endpoints(config)))
    }

    private fun runtimeConfig(live: InfluxConfig): InfluxConfig {
        val frozen = sessionConnection ?: live
        return frozen.copy(
            enabled = live.enabled,
            enabledCategories = live.enabledCategories.toSet()
        )
    }

    private fun effectiveFields(config: InfluxConfig): Set<String> {
        return NormalizedFieldCatalog.fields
            .filter { field -> config.isCategoryEnabled(field.category.mqttKey) }
            .map { field -> field.fieldKey }
            .toSet()
    }

    private fun nextBatchLimit(pendingRows: Long): Int {
        val desired = influxDesiredBatchLimit(pendingRows)
        if (desired == REALTIME_BATCH_LIMIT) {
            currentBatchSize = REALTIME_BATCH_LIMIT
            return REALTIME_BATCH_LIMIT
        }
        val next = (currentBatchSize ?: desired).coerceIn(REALTIME_BATCH_LIMIT, desired)
        currentBatchSize = next
        return next
    }

    private fun rampBatchSize(pendingRows: Long) {
        val desired = influxDesiredBatchLimit(pendingRows)
        if (desired == REALTIME_BATCH_LIMIT) {
            currentBatchSize = REALTIME_BATCH_LIMIT
            return
        }
        val current = currentBatchSize ?: REALTIME_BATCH_LIMIT
        currentBatchSize = minOf(desired, maxOf(REALTIME_BATCH_LIMIT, current * 2))
    }

    private fun deescalateBatchSize(pendingRows: Long) {
        val desired = influxDesiredBatchLimit(pendingRows)
        if (desired == REALTIME_BATCH_LIMIT) {
            currentBatchSize = REALTIME_BATCH_LIMIT
            return
        }
        val current = currentBatchSize ?: desired
        currentBatchSize = when {
            current >= CATCH_UP_BATCH_LIMIT -> 1_000
            current >= 1_000 -> 500
            else -> REALTIME_BATCH_LIMIT
        }.coerceIn(REALTIME_BATCH_LIMIT, desired)
    }

    private fun exportRows(
        config: InfluxConfig,
        rows: List<InfluxPendingHistoryRow>,
        exportedAt: String,
        pinnedRoute: HaEndpointProfile? = null,
        pass: ExportPass
    ): BatchExportResult {
        if (rows.isEmpty()) return BatchExportResult()
        val route = pinnedRoute ?: currentRoute ?: HaEndpointProfile.PRIMARY
        val lines = rows.map { InfluxLineProtocol.toLine(it, config) }
        val writeAttempt = if (pinnedRoute == null) {
            writeWithFailover(config, route, lines, pass)
        } else {
            writeOnce(config, route, lines, pass)
        } ?: return pass.cancelled().let { cancelled ->
            BatchExportResult(deferred = !cancelled, cancelled = cancelled)
        }
        val write = writeAttempt.result
        val actualRoute = writeAttempt.route
        if (write.ok) {
            currentRoute = actualRoute
            diagnostic(
                "influx_server_ack",
                mapOf(
                    "request_id" to writeAttempt.requestId,
                    "mode" to "export",
                    "source" to "frozen_export",
                    "profile" to actualRoute.name.lowercase(),
                    "actual_route" to actualRoute.name,
                    "host" to safeInfluxDiagnosticHost(configForRoute(config, actualRoute).host),
                    "port" to configForRoute(config, actualRoute).port.toString(),
                    "http_status" to observedHttpStatus(write).toString()
                )
            )
            val cursorGroups = rows.groupBy { it.fieldKey }
            val firstHistoryId = rows.minOf { it.id }
            val lastHistoryId = rows.maxOf { it.id }
            diagnostic(
                "influx_cursor_persistence_start",
                mapOf(
                    "request_id" to writeAttempt.requestId,
                    "cursor_fields" to cursorGroups.size.toString(),
                    "cursor_rows" to rows.size.toString(),
                    "history_id_min" to firstHistoryId.toString(),
                    "history_id_max" to lastHistoryId.toString(),
                    "completed_fields" to "0"
                )
            )
            var completedFields = 0
            var failedField: String? = null
            var failedHistoryId: Long? = null
            try {
                cursorGroups.forEach { (fieldKey, fieldRows) ->
                    val historyId = fieldRows.maxOf { it.id }
                    failedField = fieldKey
                    failedHistoryId = historyId
                    store.updateInfluxCursorSuccess(fieldKey, historyId, exportedAt)
                    completedFields += 1
                }
            } catch (error: RuntimeException) {
                diagnostic(
                    "influx_cursor_persistence_failure",
                    mapOf(
                        "request_id" to writeAttempt.requestId,
                        "failed_field" to (failedField ?: "unknown"),
                        "failed_history_id" to (failedHistoryId ?: -1L).toString(),
                        "completed_fields" to completedFields.toString(),
                        "cursor_fields" to cursorGroups.size.toString(),
                        "error_class" to error::class.java.simpleName
                    )
                )
                throw error
            }
            diagnostic(
                "influx_cursor_persistence_end",
                mapOf(
                    "request_id" to writeAttempt.requestId,
                    "cursor_fields" to cursorGroups.size.toString(),
                    "cursor_rows" to rows.size.toString(),
                    "history_id_min" to firstHistoryId.toString(),
                    "history_id_max" to lastHistoryId.toString(),
                    "completed_fields" to completedFields.toString()
                )
            )
            return BatchExportResult(exportedRows = rows.size)
        }
        if (isInfluxLineProtocolDataFailure(write)) {
            if (rows.size == 1) {
                val row = rows.single()
                store.updateInfluxCursorSuccess(row.fieldKey, row.id, exportedAt)
                pass.recordPoison(row, checkNotNull(influxDataFormatReason(write)))
                return BatchExportResult(poisonRows = 1)
            }
            val midpoint = rows.size / 2
            val left = exportRows(config, rows.subList(0, midpoint), exportedAt, pinnedRoute = actualRoute, pass = pass)
            if (left.failure != null || left.deferred || left.cancelled) return left
            val right = exportRows(config, rows.subList(midpoint, rows.size), exportedAt, pinnedRoute = actualRoute, pass = pass)
            return BatchExportResult(
                exportedRows = left.exportedRows + right.exportedRows,
                poisonRows = left.poisonRows + right.poisonRows,
                failure = right.failure,
                deferred = right.deferred,
                cancelled = right.cancelled
            )
        }
        rows.map { it.fieldKey }.distinct().forEach { fieldKey ->
            store.updateInfluxCursorError(fieldKey, write.message, exportedAt)
        }
        return BatchExportResult(failure = write)
    }

    private fun writeWithFailover(
        config: InfluxConfig,
        preferred: HaEndpointProfile,
        lines: List<String>,
        pass: ExportPass
    ): RouteWriteResult? {
        val first = writeOnce(config, preferred, lines, pass) ?: return null
        if (first.result.ok || !isFallbackEligible(first.result)) {
            return first
        }
        val alternate = otherProfile(config, preferred) ?: run {
            currentRoute = null
            return first
        }
        diagnostic(
            "influx_failover",
            mapOf(
                "reason" to "fallback_eligible",
                "from_profile" to preferred.name.lowercase(),
                "to_profile" to alternate.name.lowercase(),
                "request_id" to first.requestId
            )
        )
        val second = writeOnce(config, alternate, lines, pass) ?: return null
        if (!second.result.ok) currentRoute = null
        return second
    }

    private fun writeOnce(
        config: InfluxConfig,
        route: HaEndpointProfile,
        lines: List<String>,
        pass: ExportPass
    ): RouteWriteResult? {
        if (!pass.reserveWrite()) return null
        val requestId = UUID.randomUUID().toString()
        val startedNs = System.nanoTime()
        val routeConfig = runCatching { configForRoute(config, route) }.getOrElse { error ->
            val result = InfluxActionResult.fail(
                "influx_write_exception",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                failureKind = InfluxFailureKind.OTHER
            )
            diagnosticResult(
                "export",
                requestId,
                config,
                result,
                route,
                durationMs = elapsedDiagnosticMs(startedNs)
            )
            return RouteWriteResult(route, requestId, result)
        }
        diagnostic(
            "influx_request_started",
            mapOf(
                "request_id" to requestId,
                "mode" to "export",
                "source" to "frozen_export",
                "profile" to route.name.lowercase(),
                "host" to safeInfluxDiagnosticHost(routeConfig.host),
                "port" to routeConfig.port.toString(),
                "batch_rows" to lines.size.toString(),
                "frozen" to (sessionConnection != null).toString()
            )
        )
        var thrownErrorClass: String? = null
        val result = try {
            client.write(routeConfig, lines, requestId, route)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Error) {
            throw error
        } catch (error: RuntimeException) {
            thrownErrorClass = error::class.java.simpleName
            InfluxActionResult.fail(
                "influx_write_exception",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                failureKind = InfluxFailureKind.OTHER
            )
        }
        diagnosticResult(
            "export",
            requestId,
            routeConfig,
            result,
            route,
            thrownErrorClass,
            durationMs = elapsedDiagnosticMs(startedNs)
        )
        return RouteWriteResult(route, requestId, result)
    }

    private fun isFallbackEligible(result: InfluxActionResult): Boolean {
        if (result.ok) return false
        if (result.failureKind == InfluxFailureKind.AUTHENTICATION ||
            result.failureKind == InfluxFailureKind.DATA ||
            result.failureKind == InfluxFailureKind.PROTOCOL
        ) return false
        return result.failureKind == InfluxFailureKind.TRANSPORT ||
            result.httpStatus in 502..504 ||
            (result.failureKind == null && result.category == "influx_network_error")
    }

    private fun otherProfile(config: InfluxConfig, profile: HaEndpointProfile): HaEndpointProfile? {
        return when (profile) {
            HaEndpointProfile.PRIMARY -> {
                if (config.alternativeHost.isNullOrBlank() && config.alternativePort == null) null
                else HaEndpointProfile.ALTERNATIVE
            }
            HaEndpointProfile.ALTERNATIVE -> HaEndpointProfile.PRIMARY
        }
    }

    private data class RouteWriteResult(
        val route: HaEndpointProfile,
        val requestId: String,
        val result: InfluxActionResult
    )

    private inner class ExportPass(private val generation: Long, private val isCurrent: () -> Boolean) {
        private var writes = 0
        var poisonRows = 0
            private set
        private val poisonExamples = mutableListOf<String>()
        private var firstPoisonId: Long? = null
        private var lastPoisonId: Long? = null

        fun cancelled(): Boolean = Thread.currentThread().isInterrupted ||
            generation != cancellationGeneration.get() ||
            !isCurrent() ||
            !configProvider().enabled

        fun reserveWrite(): Boolean {
            if (cancelled() || writes >= MAX_HTTP_WRITES_PER_PASS) return false
            writes += 1
            return true
        }

        fun recordPoison(row: InfluxPendingHistoryRow, reason: String) {
            poisonRows += 1
            firstPoisonId = firstPoisonId?.let { minOf(it, row.id) } ?: row.id
            lastPoisonId = lastPoisonId?.let { maxOf(it, row.id) } ?: row.id
            if (poisonExamples.size < MAX_POISON_EXAMPLES) {
                poisonExamples += "field=${row.fieldKey} history_id=${row.id} reason=$reason"
            }
        }

        fun recordSummary() {
            if (poisonRows == 0) return
            store.recordInfluxEvent(
                eventType = "influx_export_poison_row",
                message = "count=$poisonRows examples=${poisonExamples.joinToString(" | ")}",
                batchCount = poisonRows,
                fromHistoryId = firstPoisonId,
                toHistoryId = lastPoisonId
            )
        }
    }

    private fun recordPoisonSummary(pass: ExportPass) {
        try {
            pass.recordSummary()
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            diagnostic(
                "influx_poison_summary_failure",
                mapOf("error_class" to error::class.java.simpleName)
            )
        }
    }

    private fun isTransientFailure(result: InfluxActionResult): Boolean {
        val detail = "${result.category} ${result.message}".lowercase(Locale.US)
        return result.httpStatus?.let { it in 500..599 } == true ||
            detail.contains("network") ||
            detail.contains("timeout") ||
            detail.contains("timed out") ||
            detail.contains("exception")
    }

    private fun diagnostic(type: String, details: Map<String, String>) {
        if (type != "influx_cycle_gate") lastDiagnosticGate = null
        runCatching { diagnostics(InfluxDiagnosticEvent(type, details)) }
    }

    private fun diagnosticGate(reason: String, details: Map<String, String> = emptyMap()) {
        if (lastDiagnosticGate == reason) return
        lastDiagnosticGate = reason
        diagnostic("influx_cycle_gate", details + ("reason" to reason))
    }

    private fun diagnosticValidationGate(result: InfluxActionResult) {
        diagnosticGate(
            if (result.category == "influx_disabled") "disabled" else "invalid_config",
            mapOf("category" to result.category)
        )
    }

    private fun diagnosticResult(
        mode: String,
        requestId: String,
        config: InfluxConfig,
        result: InfluxActionResult,
        profile: HaEndpointProfile? = null,
        errorClass: String? = null,
        durationMs: Long? = null
    ) {
        val details = linkedMapOf(
            "request_id" to requestId,
            "mode" to mode,
            "source" to if (mode == "test") "current_test" else "frozen_export",
            "host" to safeInfluxDiagnosticHost(config.host),
            "port" to config.port.toString(),
            "profile" to (profile?.name?.lowercase() ?: "unknown"),
            "result" to if (result.ok) "ok" else "error",
            "category" to result.category
        )
        result.httpStatus?.let { details["http_status"] = it.toString() }
        result.failureKind?.let { details["error_kind"] = it.name.lowercase() }
        errorClass?.let { details["error_class"] = it }
        durationMs?.let { details["duration_ms"] = it.toString() }
        observedHttpStatus(result)?.let { details["http_status"] = it.toString() }
        diagnostic("influx_request_result", details)
    }

    private fun elapsedDiagnosticMs(startedNs: Long): Long =
        ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)

    private fun configForRoute(config: InfluxConfig, route: HaEndpointProfile): InfluxConfig = config.forProfile(route)

    private fun endpoints(config: InfluxConfig): String = buildString {
        append(safeInfluxDiagnosticHost(config.host)).append(':').append(config.port)
        if (!config.alternativeHost.isNullOrBlank() || config.alternativePort != null) {
            append(",alternative=")
            append(safeInfluxDiagnosticHost(config.alternativeHost.orEmpty())).append(':').append(config.alternativePort ?: "none")
        }
    }

    private fun observedHttpStatus(result: InfluxActionResult): Int? {
        return result.httpStatus ?: Regex("\\bhttp\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)
            .find(result.message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
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
        diagnostic(
            "influx_retry_pending",
            mapOf("retry_deadline" to plusSeconds(now, FAILURE_RETRY_INTERVAL_SECONDS), "reason" to "failure")
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
        const val REALTIME_BATCH_LIMIT = 300
        const val CATCH_UP_BATCH_LIMIT = 2_000
        const val CATCH_UP_THRESHOLD = 5_000
        const val SUCCESS_BATCH_INTERVAL_SECONDS = 1L
        const val FAILURE_RETRY_INTERVAL_SECONDS = 30L
        const val MAX_HTTP_WRITES_PER_PASS = 64
        const val MAX_POISON_EXAMPLES = 5
        const val STATUS_IDLE = "idle"
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_EXPORTING = "exporting"
        const val STATUS_BACKOFF = "backoff"
        const val STATUS_STOPPED = "stopped"
    }
}

internal fun influxDesiredBatchLimit(pendingRows: Long): Int {
    return if (pendingRows >= 5_000L) 2_000 else 300
}

internal fun isInfluxLineProtocolDataFailure(result: InfluxActionResult): Boolean {
    return influxDataFormatReason(result) != null
}

internal fun influxDataFormatReason(result: InfluxActionResult): String? {
    if (result.httpStatus != 400 && result.httpStatus != 422) return null
    val text = "${result.category} ${result.message}".lowercase(Locale.US)
    return when {
        text.contains("field type conflict") -> "field_type_conflict"
        text.contains("unable to parse") -> "unable_to_parse"
        text.contains("points beyond retention policy") -> "retention_policy"
        else -> null
    }
}

private data class BatchExportResult(
    val exportedRows: Int = 0,
    val poisonRows: Int = 0,
    val failure: InfluxActionResult? = null,
    val deferred: Boolean = false,
    val cancelled: Boolean = false
)
