package com.bydcollector.collector.influx

import android.content.Context
import android.os.SystemClock
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.ha.HaEndpointProfile
import com.bydcollector.collector.util.dispatchOperationalEvent
import com.bydcollector.collector.util.sharedOperationalEventExecutor

/** Process-local Influx state and journal-only causal breadcrumbs. */
class InfluxRuntimeDiagnosticsState(
    private val eventSink: InfluxDiagnosticSink = {},
    private val elapsedRealtimeMs: () -> Long = ::readElapsedRealtimeMs,
    private val journalSink: InfluxDiagnosticSink? = null
) {
    private data class RequestContext(
        val requestId: String,
        val mode: String,
        val source: String,
        val host: String,
        val port: String,
        val profile: String,
        val batchRows: String?,
        val frozen: String?,
        val startedElapsedMs: Long,
        val runtimeId: String?,
        val generation: Long?
    )

    private val lock = Any()
    private var runtimeId: String? = null
    private var running = false
    private var generation = 0L
    private var queued = false
    private var inFlight = 0
    private var workQueuedAtElapsedMs: Long? = null
    private var workGeneration: Long? = null
    private var retryDeadlineElapsedMs: Long? = null
    private var retryDeadline: String? = null
    private var frozen = false
    private var actualRoute: HaEndpointProfile? = null
    private var endpoints = "none"
    private var reason = "none"
    private var lastGateReason: String? = null
    private val requestContexts = linkedMapOf<String, RequestContext>()
    private var summaryStartedElapsedMs: Long? = null
    private var successfulHttpRequests = 0L
    private var confirmedRows = 0L
    private var requestDurationTotalMs = 0L
    private var summaryRoute: String? = null
    private var summaryRuntimeId: String? = null
    private var problemStartedElapsedMs: Long? = null
    private var lastDurableGateKey: String? = null
    @Volatile private var journal: OperationalEventJournal? = null

    internal fun attachJournal(context: Context) {
        journal = runCatching {
            (context.applicationContext as BydCollectorApplication).operationalEventJournal
        }.getOrNull()
    }

    internal fun record(event: InfluxDiagnosticEvent) {
        val normalized = normalize(event)
        val now = monotonicMs()
        val persisted = mutableListOf<InfluxDiagnosticEvent>()
        val emitted = mutableListOf<InfluxDiagnosticEvent>(normalized)

        synchronized(lock) {
            val details = normalized.details
            val eventRuntimeId = details["runtime_id"]
            if (normalized.type == "influx_runtime_state" && details["reason"] == "service_started") {
                // Work generations restart with a new Service; old workers may finish later.
                val previousRuntimeId = runtimeId
                runtimeId = eventRuntimeId
                generation = details["generation"]?.toLongOrNull() ?: 0L
                workGeneration = null
                workQueuedAtElapsedMs = null
                retryDeadlineElapsedMs = null
                retryDeadline = null
                lastDurableGateKey = null
                if (previousRuntimeId != null && previousRuntimeId != runtimeId) {
                    requestContexts.entries.removeAll { it.value.runtimeId != runtimeId }
                }
            }

            val currentRuntime = eventRuntimeId == null || runtimeId == null || eventRuntimeId == runtimeId
            val eventWorkGeneration = details["work_generation"]?.toLongOrNull()
            val eventGeneration = details["generation"]?.toLongOrNull()
            val requestId = details["request_id"]
            var requestContext = requestId?.let(requestContexts::get)
            val staleWork = eventWorkGeneration != null && eventWorkGeneration < generation ||
                eventGeneration != null && eventGeneration < generation
            val hasRuntimeMarker = eventRuntimeId != null || eventWorkGeneration != null || eventGeneration != null
            val stateCanBeAssociated = hasRuntimeMarker ||
                normalized.type == "influx_request_started" ||
                normalized.type == "influx_session_captured" ||
                normalized.type == "influx_session_ended" ||
                requestContext?.let(::isCurrentRequestContext) == true ||
                runtimeId == null
            val freshState = currentRuntime && !staleWork && stateCanBeAssociated
            val previousRoute = actualRoute

            if (freshState) {
                if (normalized.type != "influx_cycle_gate") lastGateReason = null
                reason = details["reason"] ?: reason
                running = details["running"]?.toBooleanStrictOrNull() ?: running
                generation = eventGeneration ?: generation
                queued = details["queued"]?.toBooleanStrictOrNull() ?: queued
                inFlight = details["inflight"]?.toIntOrNull() ?: inFlight
                if (details.containsKey("queued_at_elapsed_ms")) {
                    workQueuedAtElapsedMs = details["queued_at_elapsed_ms"]?.toLongOrNull()
                }
                if (details.containsKey("work_generation")) workGeneration = eventWorkGeneration
                if (details.containsKey("retry_deadline_elapsed_ms")) {
                    retryDeadlineElapsedMs = details["retry_deadline_elapsed_ms"]?.toLongOrNull()
                }
                if (details.containsKey("retry_deadline")) {
                    retryDeadline = details["retry_deadline"]?.takeUnless { it == "none" }
                }
                frozen = details["frozen"]?.toBooleanStrictOrNull() ?: frozen
                if (details.containsKey("actual_route")) {
                    actualRoute = details["actual_route"]?.takeUnless { it == "none" }
                        ?.let { runCatching { HaEndpointProfile.valueOf(it) }.getOrNull() }
                }
                details["endpoints"]?.let { endpoints = it }
            }

            if (normalized.type == "influx_request_started" && requestId != null && freshState) {
                requestContext = RequestContext(
                    requestId = requestId,
                    mode = details["mode"].orEmpty(),
                    source = details["source"].orEmpty(),
                    host = details["host"].orEmpty(),
                    port = details["port"].orEmpty(),
                    profile = details["profile"].orEmpty(),
                    batchRows = details["batch_rows"],
                    frozen = details["frozen"],
                    startedElapsedMs = now,
                    runtimeId = runtimeId,
                    generation = generation.takeIf { runtimeId != null }
                )
                requestContexts[requestId] = requestContext
                while (requestContexts.size > MAX_REQUEST_CONTEXTS) {
                    requestContexts.remove(requestContexts.keys.first())
                }
            }

            val enriched = normalize(enrich(normalized, requestContext, now, freshState))
            if (shouldPersistLocked(enriched)) persisted += enriched

            if (freshState && details.containsKey("actual_route") && previousRoute != actualRoute) {
                val routeChanged = normalize(
                    InfluxDiagnosticEvent(
                        "influx_route_changed",
                        mapOf(
                            "from_route" to (previousRoute?.name?.lowercase() ?: "none"),
                            "to_route" to (actualRoute?.name?.lowercase() ?: "none"),
                            "runtime_id" to (runtimeId ?: "none"),
                            "generation" to generation.toString()
                        )
                    )
                )
                persisted += routeChanged
                emitted += routeChanged
            }

            if (normalized.type == "influx_request_result") {
                val mode = details["mode"] ?: requestContext?.mode
                if (mode == "export" && details["result"] == "ok") {
                    lastDurableGateKey = null
                    beginSummaryWindow(now)
                    successfulHttpRequests += 1
                    requestDurationTotalMs += details["duration_ms"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    summaryRoute = details["profile"] ?: requestContext?.profile ?: summaryRoute
                    summaryRuntimeId = eventRuntimeId ?: requestContext?.runtimeId ?: summaryRuntimeId
                }
                if (mode == "test" || details["result"] != "ok") {
                    requestId?.let(requestContexts::remove)
                }
            }

            if (normalized.type == "influx_cursor_persistence_end") {
                lastDurableGateKey = null
                beginSummaryWindow(now)
                confirmedRows += details["cursor_rows"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                requestId?.let(requestContexts::remove)
                if (problemStartedElapsedMs != null) {
                    val recovery = normalize(
                        InfluxDiagnosticEvent(
                            "influx_problem_recovered",
                            mapOf(
                                "recovered_by" to "cursor_persistence_end",
                                "request_id" to (requestId ?: "none"),
                                "confirmed_rows" to (details["cursor_rows"] ?: "0"),
                                "problem_duration_ms" to (now - checkNotNull(problemStartedElapsedMs)).coerceAtLeast(0L).toString(),
                                "runtime_id" to (runtimeId ?: "none")
                            )
                        )
                    )
                    problemStartedElapsedMs = null
                    persisted += recovery
                    emitted += recovery
                }
            }

            if (normalized.type == "influx_cursor_persistence_failure") {
                requestId?.let(requestContexts::remove)
            }

            if (isFailure(enriched)) {
                if (problemStartedElapsedMs == null) {
                    problemStartedElapsedMs = now
                    val problem = normalize(
                        InfluxDiagnosticEvent(
                            "influx_problem_started",
                            buildMap {
                                put("source_type", enriched.type)
                                put("reason", enriched.details["category"] ?: enriched.details["error_kind"] ?: enriched.type)
                                put("request_id", enriched.details["request_id"] ?: "none")
                                put("mode", enriched.details["mode"] ?: "unknown")
                                put("route", enriched.details["actual_route"] ?: enriched.details["profile"] ?: actualRoute?.name?.lowercase() ?: "none")
                                runtimeId?.let { put("runtime_id", it) }
                                put("generation", generation.toString())
                            }
                        )
                    )
                    persisted += problem
                    emitted += problem
                }
                takeSummaryLocked("error", now)?.let {
                    persisted += it
                    emitted += it
                }
            } else if (freshState && normalized.type == "influx_runtime_state" && details["reason"] == "service_destroyed") {
                takeSummaryLocked("stop", now)?.let {
                    persisted += it
                    emitted += it
                }
            } else {
                takeElapsedSummaryLocked(now)?.let {
                    persisted += it
                    emitted += it
                }
            }

            // Queue while holding the same lock that orders state changes, so Share's
            // subsequent event-executor barrier cannot overtake an event being recorded.
            queueJournalEventsLocked(persisted)
        }

        publishCallbacks(persisted, emitted)
    }

    /** Emits the pending successful-export window for Share, Clear, Stop, or an error boundary. */
    fun flushSummary(reason: String) {
        val summary = synchronized(lock) {
            takeSummaryLocked(reason, monotonicMs())?.also { queueJournalEventsLocked(listOf(it)) }
        } ?: return
        publishCallbacks(listOf(summary), listOf(summary))
    }

    internal fun gate(reason: String, details: Map<String, String> = emptyMap()) {
        val changed = synchronized(lock) {
            val key = "${details["runtime_id"] ?: "process"}:$reason"
            val transition = lastGateReason != key
            lastGateReason = key
            transition
        }
        if (changed) record(InfluxDiagnosticEvent("influx_cycle_gate", details + ("reason" to reason)))
    }

    internal fun updateState(
        running: Boolean,
        generation: Long,
        queued: Boolean,
        inFlight: Int,
        queuedAtElapsedMs: Long? = null,
        workGeneration: Long? = null,
        retryDeadlineElapsedMs: Long? = null,
        retryDeadline: String? = null,
        frozen: Boolean = false,
        actualRoute: HaEndpointProfile? = null,
        endpoints: String = "none",
        reason: String? = null
    ) {
        val details = buildMap {
            put("running", running.toString())
            put("generation", generation.toString())
            put("queued", queued.toString())
            put("inflight", inFlight.toString())
            put("queued_at_elapsed_ms", queuedAtElapsedMs?.toString() ?: "none")
            put("work_generation", workGeneration?.toString() ?: "none")
            put("retry_deadline_elapsed_ms", retryDeadlineElapsedMs?.toString() ?: "none")
            put("retry_deadline", retryDeadline ?: "none")
            put("frozen", frozen.toString())
            put("actual_route", actualRoute?.name ?: "none")
            put("endpoints", endpoints)
            reason?.let { put("reason", it) }
        }
        record(InfluxDiagnosticEvent("influx_runtime_state", details))
    }

    internal fun snapshotLines(): List<String> = synchronized(lock) {
        val age = workQueuedAtElapsedMs?.let { (monotonicMs() - it).coerceAtLeast(0L) }
        val activeRequest = requestContexts.values.lastOrNull(::isCurrentRequestContext)
        val requestAge = activeRequest?.let { (monotonicMs() - it.startedElapsedMs).coerceAtLeast(0L) }
        listOf(
            "influx_runtime_id=${runtimeId ?: "none"}",
            "influx_runtime_running=$running",
            "influx_runtime_generation=$generation",
            "influx_runtime_queued=$queued",
            "influx_runtime_inflight=$inFlight",
            "influx_runtime_work_age_ms=${age ?: "none"}",
            "influx_runtime_work_generation=${workGeneration ?: "none"}",
            "influx_runtime_retry_deadline_elapsed_ms=${retryDeadlineElapsedMs ?: "none"}",
            "influx_runtime_retry_deadline=${retryDeadline ?: "none"}",
            "influx_runtime_frozen=$frozen",
            "influx_runtime_actual_route=${actualRoute?.name?.lowercase() ?: "none"}",
            "influx_runtime_endpoints=$endpoints",
            "influx_runtime_reason=$reason",
            "influx_runtime_problem=${problemStartedElapsedMs != null}",
            "influx_runtime_active_request_count=${requestContexts.values.count(::isCurrentRequestContext)}",
            "influx_runtime_active_request=${activeRequest?.let { "${it.mode}/${it.profile} host=${it.host}:${it.port} age_ms=$requestAge rows=${it.batchRows ?: "none"}" } ?: "none"}"
        )
    }

    private fun isCurrentRequestContext(context: RequestContext): Boolean =
        (context.runtimeId == null || context.runtimeId == runtimeId) &&
            (context.generation == null || context.generation == generation)

    private fun beginSummaryWindow(now: Long) {
        if (summaryStartedElapsedMs == null) summaryStartedElapsedMs = now
    }

    private fun takeElapsedSummaryLocked(now: Long): InfluxDiagnosticEvent? {
        val started = summaryStartedElapsedMs ?: return null
        if ((now - started).coerceAtLeast(0L) < SUMMARY_INTERVAL_MS) return null
        return takeSummaryLocked("interval", now)
    }

    private fun takeSummaryLocked(reason: String, now: Long): InfluxDiagnosticEvent? {
        val started = summaryStartedElapsedMs ?: return null
        if (successfulHttpRequests == 0L && confirmedRows == 0L) return null
        val event = normalize(
            InfluxDiagnosticEvent(
                "influx_success_summary",
                buildMap {
                    put("reason", reason.replace(Regex("[\\p{Cntrl}\\r\\n]+"), " ").take(64))
                    put("window_ms", (now - started).coerceAtLeast(0L).toString())
                    put("successful_http_requests", successfulHttpRequests.toString())
                    put("confirmed_rows", confirmedRows.toString())
                    put("request_duration_total_ms", requestDurationTotalMs.toString())
                    put("request_duration_average_ms", if (successfulHttpRequests == 0L) "0" else (requestDurationTotalMs / successfulHttpRequests).toString())
                    summaryRoute?.let { put("route", it.lowercase()) }
                    summaryRuntimeId?.let { put("success_runtime_id", it) }
                    put("running", running.toString())
                    put("queued", queued.toString())
                    put("inflight", inFlight.toString())
                    put("runtime_reason", this@InfluxRuntimeDiagnosticsState.reason)
                    put("generation", generation.toString())
                    put("frozen", frozen.toString())
                    put("active_route", actualRoute?.name?.lowercase() ?: "none")
                    put("active_request_count", requestContexts.values.count(::isCurrentRequestContext).toString())
                    runtimeId?.let { put("runtime_id", it) }
                }
            )
        )
        summaryStartedElapsedMs = null
        successfulHttpRequests = 0L
        confirmedRows = 0L
        requestDurationTotalMs = 0L
        summaryRoute = null
        summaryRuntimeId = null
        return event
    }

    private fun enrich(
        event: InfluxDiagnosticEvent,
        requestContext: RequestContext?,
        now: Long,
        freshState: Boolean
    ): InfluxDiagnosticEvent {
        val details = buildMap {
            requestContext?.let { context ->
                put("mode", context.mode)
                put("source", context.source)
                put("host", context.host)
                put("port", context.port)
                put("profile", context.profile)
                context.batchRows?.let { put("batch_rows", it) }
                context.frozen?.let { put("frozen", it) }
                put("request_age_ms", (now - context.startedElapsedMs).coerceAtLeast(0L).toString())
            }
            if (freshState) {
                runtimeId?.let { put("runtime_id", it) }
                put("runtime_generation", generation.toString())
                put("runtime_running", running.toString())
                put("runtime_queued", queued.toString())
                put("runtime_inflight", inFlight.toString())
                put("runtime_route", actualRoute?.name?.lowercase() ?: "none")
            }
            putAll(event.details)
        }
        return event.copy(details = details)
    }

    private fun shouldPersist(event: InfluxDiagnosticEvent): Boolean = when (event.type) {
        "influx_request_started",
        "influx_server_ack",
        "influx_cursor_persistence_start",
        "influx_cursor_persistence_end",
        "influx_work_queued",
        "influx_work_started",
        "influx_work_settled" -> false
        "influx_request_result" -> event.details["mode"] == "test" || event.details["result"] != "ok"
        "influx_http_stage" -> !event.details["error_class"].isNullOrBlank()
        "influx_cycle_gate" -> event.details["reason"] !in ROUTINE_GATE_REASONS
        "influx_runtime_state" -> event.details["reason"] in RUNTIME_TRANSITION_REASONS
        else -> true
    }

    private fun shouldPersistLocked(event: InfluxDiagnosticEvent): Boolean {
        if (event.type != "influx_cycle_gate") return shouldPersist(event)
        val reason = event.details["reason"]
        if (reason in ROUTINE_GATE_REASONS) return false
        val key = "${event.details["runtime_id"] ?: runtimeId ?: "process"}:$reason"
        if (key == lastDurableGateKey) return false
        lastDurableGateKey = key
        return shouldPersist(event)
    }

    private fun isFailure(event: InfluxDiagnosticEvent): Boolean = when (event.type) {
        "influx_cursor_persistence_failure",
        "influx_poison_summary_failure",
        "influx_retry_pending",
        "influx_export_error" -> true
        "influx_request_result" -> event.details["mode"] == "export" && event.details["result"] == "error"
        "influx_http_stage" -> event.details["mode"] == "export" && !event.details["error_class"].isNullOrBlank()
        else -> event.type.endsWith("_failure") ||
            (event.type.endsWith("_error") && event.details["mode"] != "test") ||
            (event.details["result"] == "error" && event.details["mode"] != "test")
    }

    private fun normalize(event: InfluxDiagnosticEvent): InfluxDiagnosticEvent = event.copy(
        type = event.type.replace(Regex("[\\p{Cntrl}\\r\\n]+"), " ").take(128),
        details = event.details.mapValues { (_, value) -> value.replace(Regex("[\\p{Cntrl}\\r\\n]+"), " ").take(MAX_DETAIL_VALUE_CHARS) }
    )

    private fun queueJournalEventsLocked(persisted: List<InfluxDiagnosticEvent>) {
        if (persisted.isEmpty() || journalSink != null) return
        val target = journal ?: return
        val timestamp = java.time.Instant.now().toString()
        val capturedElapsedMs = monotonicMs()
        dispatchOperationalEvent(sharedOperationalEventExecutor) {
            persisted.forEach { event ->
                val detail = event.details.entries.joinToString(" ") { (key, value) -> "$key=$value" }
                runCatching {
                    target.append(
                        timestamp = timestamp,
                        elapsedMs = capturedElapsedMs,
                        category = "influx_diagnostic",
                        message = event.type,
                        detail = detail.ifBlank { null }
                    )
                }
            }
        }
    }

    private fun publishCallbacks(persisted: List<InfluxDiagnosticEvent>, emitted: List<InfluxDiagnosticEvent>) {
        if (persisted.isNotEmpty()) {
            journalSink?.let { sink -> persisted.forEach { event -> runCatching { sink(event) } } }
        }
        emitted.forEach { event -> runCatching { eventSink(event) } }
    }

    private fun monotonicMs(): Long = runCatching { elapsedRealtimeMs() }
        .getOrElse { System.nanoTime() / 1_000_000L }

    private companion object {
        const val SUMMARY_INTERVAL_MS = 60_000L
        const val MAX_REQUEST_CONTEXTS = 4
        const val MAX_DETAIL_VALUE_CHARS = 512
        val ROUTINE_GATE_REASONS = setOf(
            "no_work", "no_categories", "singleflight_occupied", "stale_generation"
        )
        val RUNTIME_TRANSITION_REASONS = setOf("service_started", "service_destroyed")
    }
}

internal object InfluxRuntimeDiagnosticsProcess {
    val instance = InfluxRuntimeDiagnosticsState()
}

object InfluxRuntimeDiagnostics {
    internal fun snapshotLines(): List<String> = InfluxRuntimeDiagnosticsProcess.instance.snapshotLines()
}

internal fun safeInfluxDiagnosticHost(host: String): String =
    host.substringAfterLast('@').trim().trimEnd('/').ifBlank { "blank" }

private fun readElapsedRealtimeMs(): Long = runCatching { SystemClock.elapsedRealtime() }
    .getOrElse { System.nanoTime() / 1_000_000L }
