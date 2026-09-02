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
    private val eventSink: InfluxDiagnosticSink = {}
) {
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
    @Volatile private var journal: OperationalEventJournal? = null

    internal fun attachJournal(context: Context) {
        journal = runCatching {
            (context.applicationContext as BydCollectorApplication).operationalEventJournal
        }.getOrNull()
    }

    internal fun record(event: InfluxDiagnosticEvent) {
        val normalized = event.details.mapValues { (_, value) -> value.replace(Regex("[\\p{Cntrl}\\r\\n]+"), " ").take(512) }
        synchronized(lock) {
            val eventRuntimeId = normalized["runtime_id"]
            if (eventRuntimeId != null && normalized["reason"] == "service_started") {
                // Work generations restart with a new Service; old workers may finish later.
                runtimeId = eventRuntimeId
                generation = normalized["generation"]?.toLongOrNull() ?: 0L
                workGeneration = null
                workQueuedAtElapsedMs = null
                retryDeadlineElapsedMs = null
                retryDeadline = null
            }
            val currentRuntime = eventRuntimeId == null || runtimeId == null || eventRuntimeId == runtimeId
            val eventWorkGeneration = normalized["work_generation"]?.toLongOrNull()
            val eventGeneration = normalized["generation"]?.toLongOrNull()
            val staleWork = eventWorkGeneration != null && eventWorkGeneration < generation ||
                eventGeneration != null && eventGeneration < generation
            if (currentRuntime && !staleWork) {
                if (event.type != "influx_cycle_gate") lastGateReason = null
                reason = normalized["reason"] ?: reason
                running = normalized["running"]?.toBooleanStrictOrNull() ?: running
                generation = eventGeneration ?: generation
                queued = normalized["queued"]?.toBooleanStrictOrNull() ?: queued
                inFlight = normalized["inflight"]?.toIntOrNull() ?: inFlight
                if (normalized.containsKey("queued_at_elapsed_ms")) {
                    workQueuedAtElapsedMs = normalized["queued_at_elapsed_ms"]?.toLongOrNull()
                }
                if (normalized.containsKey("work_generation")) {
                    workGeneration = eventWorkGeneration
                }
                if (normalized.containsKey("retry_deadline_elapsed_ms")) {
                    retryDeadlineElapsedMs = normalized["retry_deadline_elapsed_ms"]?.toLongOrNull()
                }
                if (normalized.containsKey("retry_deadline")) {
                    retryDeadline = normalized["retry_deadline"]?.takeUnless { it == "none" }
                }
                frozen = normalized["frozen"]?.toBooleanStrictOrNull() ?: frozen
                if (normalized.containsKey("actual_route")) {
                    actualRoute = normalized["actual_route"]?.takeUnless { it == "none" }
                        ?.let { runCatching { HaEndpointProfile.valueOf(it) }.getOrNull() }
                }
                normalized["endpoints"]?.let { endpoints = it }
            }
        }
        val capturedTimestamp = java.time.Instant.now().toString()
        val capturedElapsedMs = monotonicMs()
        val detail = normalized.entries.joinToString(" ") { (key, value) -> "$key=$value" }
        journal?.let { target ->
            dispatchOperationalEvent(sharedOperationalEventExecutor) {
                runCatching {
                    target.append(
                        timestamp = capturedTimestamp,
                        elapsedMs = capturedElapsedMs,
                        category = "influx_diagnostic",
                        message = event.type,
                        detail = detail.ifBlank { null }
                    )
                }
            }
        }
        runCatching { eventSink(event.copy(details = normalized)) }
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
            "influx_runtime_reason=$reason"
        )
    }

    private fun monotonicMs(): Long = runCatching { SystemClock.elapsedRealtime() }
        .getOrElse { System.nanoTime() / 1_000_000L }
}

internal object InfluxRuntimeDiagnosticsProcess {
    val instance = InfluxRuntimeDiagnosticsState()
}

object InfluxRuntimeDiagnostics {
    internal fun snapshotLines(): List<String> = InfluxRuntimeDiagnosticsProcess.instance.snapshotLines()
}

internal fun safeInfluxDiagnosticHost(host: String): String =
    host.substringAfterLast('@').trim().trimEnd('/').ifBlank { "blank" }
