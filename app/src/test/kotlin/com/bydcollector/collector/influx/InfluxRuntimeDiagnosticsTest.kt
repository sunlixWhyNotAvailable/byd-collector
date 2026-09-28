package com.bydcollector.collector.influx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfluxRuntimeDiagnosticsTest {
    @Test
    fun serviceRecreationResetsItsGenerationAndLateOldServiceCannotReplaceIt() {
        val diagnostics = InfluxRuntimeDiagnosticsState()
        fun record(runtime: String, generation: Long, reason: String, queued: Boolean) {
            diagnostics.record(InfluxDiagnosticEvent("influx_runtime_state", mapOf(
                "runtime_id" to runtime, "generation" to generation.toString(),
                "work_generation" to generation.toString(), "reason" to reason,
                "running" to "true", "queued" to queued.toString(),
                "inflight" to if (queued) "1" else "0",
                "endpoints" to "$runtime:8086", "frozen" to queued.toString()
            )))
        }
        record("old", 8, "service_started", true)
        record("new", 0, "service_started", false)
        record("new", 0, "queued", true)
        record("old", 9, "late_completion", false)
        val snapshot = diagnostics.snapshotLines().joinToString("\n")
        assertTrue(snapshot.contains("influx_runtime_id=new"))
        assertTrue(snapshot.contains("influx_runtime_generation=0"))
        assertTrue(snapshot.contains("influx_runtime_queued=true"))
        assertTrue(snapshot.contains("influx_runtime_endpoints=new:8086"))
        assertTrue(snapshot.contains("influx_runtime_reason=queued"))
    }

    @Test
    fun snapshotExposesMetadataAndGateTransitionsAreCoalesced() {
        val events = mutableListOf<InfluxDiagnosticEvent>()
        val diagnostics = InfluxRuntimeDiagnosticsState(eventSink = { events += it })
        diagnostics.updateState(
            running = true,
            generation = 7,
            queued = true,
            inFlight = 1,
            queuedAtElapsedMs = 1L,
            workGeneration = 7,
            retryDeadlineElapsedMs = 99L,
            retryDeadline = "2026-09-02T00:00:30Z",
            frozen = true,
            actualRoute = com.bydcollector.collector.ha.HaEndpointProfile.ALTERNATIVE,
            endpoints = "influx.local:8086,alternative=influx-alt.local:8087",
            reason = "queued"
        )
        diagnostics.gate("backoff")
        diagnostics.gate("backoff")

        val snapshot = diagnostics.snapshotLines().joinToString("\n")
        assertTrue(snapshot.contains("influx_runtime_generation=7"))
        assertTrue(snapshot.contains("influx_runtime_running=true"))
        assertTrue(snapshot.contains("influx_runtime_queued=true"))
        assertTrue(snapshot.contains("influx_runtime_actual_route=alternative"))
        assertEquals(2, events.count { it.type == "influx_cycle_gate" || it.type == "influx_runtime_state" })
    }

    @Test
    fun staleWorkerEvidenceCannotReplaceCurrentQueuedGeneration() {
        val diagnostics = InfluxRuntimeDiagnosticsState()
        diagnostics.updateState(
            running = true,
            generation = 8,
            queued = true,
            inFlight = 1,
            queuedAtElapsedMs = 123L,
            workGeneration = 8,
            frozen = true,
            actualRoute = com.bydcollector.collector.ha.HaEndpointProfile.PRIMARY,
            endpoints = "influx.local:8086",
            reason = "queued"
        )
        diagnostics.record(
            InfluxDiagnosticEvent(
                "influx_work_settled",
                mapOf(
                    "generation" to "8",
                    "work_generation" to "7",
                    "queued" to "false",
                    "inflight" to "0",
                    "queued_at_elapsed_ms" to "none",
                    "frozen" to "false",
                    "actual_route" to "none",
                    "endpoints" to "none"
                )
            )
        )

        val snapshot = diagnostics.snapshotLines().joinToString("\n")
        assertTrue(snapshot.contains("influx_runtime_generation=8"))
        assertTrue(snapshot.contains("influx_runtime_queued=true"))
        assertTrue(snapshot.contains("influx_runtime_inflight=1"))
        assertTrue(snapshot.contains("influx_runtime_work_generation=8"))
        assertTrue(snapshot.contains("influx_runtime_frozen=true"))
        assertTrue(snapshot.contains("influx_runtime_actual_route=primary"))
    }

    @Test
    fun routineEventsStayInMemoryAndSuccessfulWorkIsSummarizedAtSixtySecondsOrFlush() {
        val seen = mutableListOf<InfluxDiagnosticEvent>()
        val journal = mutableListOf<InfluxDiagnosticEvent>()
        var elapsedMs = 1_000L
        val diagnostics = InfluxRuntimeDiagnosticsState(
            eventSink = { seen += it },
            elapsedRealtimeMs = { elapsedMs },
            journalSink = { journal += it }
        )

        diagnostics.record(InfluxDiagnosticEvent("influx_work_queued"))
        diagnostics.record(InfluxDiagnosticEvent("influx_request_started", mapOf(
            "request_id" to "first", "mode" to "export", "source" to "frozen_export",
            "host" to "influx.local", "port" to "8086", "profile" to "primary", "batch_rows" to "3"
        )))
        diagnostics.record(InfluxDiagnosticEvent("influx_request_result", mapOf(
            "request_id" to "first", "mode" to "export", "result" to "ok",
            "duration_ms" to "40", "profile" to "primary"
        )))
        diagnostics.record(InfluxDiagnosticEvent("influx_cursor_persistence_end", mapOf(
            "request_id" to "first", "cursor_rows" to "3", "cursor_fields" to "2"
        )))

        elapsedMs = 61_000L
        diagnostics.record(InfluxDiagnosticEvent("influx_cycle_gate", mapOf("reason" to "no_work")))

        val intervalSummary = journal.single { it.type == "influx_success_summary" }
        assertEquals("interval", intervalSummary.details["reason"])
        assertEquals("1", intervalSummary.details["successful_http_requests"])
        assertEquals("3", intervalSummary.details["confirmed_rows"])
        assertEquals("40", intervalSummary.details["request_duration_total_ms"])
        assertTrue(journal.none { it.type == "influx_work_queued" || it.type == "influx_cycle_gate" })
        assertEquals("false", intervalSummary.details["running"])
        assertEquals("false", intervalSummary.details["queued"])
        assertTrue(seen.any { it.type == "influx_work_queued" })
        assertTrue(seen.any { it.type == "influx_cycle_gate" })

        diagnostics.record(InfluxDiagnosticEvent("influx_request_started", mapOf(
            "request_id" to "second", "mode" to "export", "source" to "frozen_export",
            "host" to "influx.local", "port" to "8086", "profile" to "alternative", "batch_rows" to "2"
        )))
        diagnostics.record(InfluxDiagnosticEvent("influx_request_result", mapOf(
            "request_id" to "second", "mode" to "export", "result" to "ok",
            "duration_ms" to "25", "profile" to "alternative"
        )))
        diagnostics.record(InfluxDiagnosticEvent("influx_cursor_persistence_end", mapOf(
            "request_id" to "second", "cursor_rows" to "2"
        )))
        diagnostics.flushSummary("share")

        val shareSummary = journal.last { it.type == "influx_success_summary" }
        assertEquals("share", shareSummary.details["reason"])
        assertEquals("2", shareSummary.details["confirmed_rows"])
        assertEquals("alternative", shareSummary.details["route"])
    }

    @Test
    fun failuresCaptureBoundedRequestContextAndTheNextCompleteCursorRecordsRecovery() {
        val journal = mutableListOf<InfluxDiagnosticEvent>()
        var elapsedMs = 10L
        val diagnostics = InfluxRuntimeDiagnosticsState(
            elapsedRealtimeMs = { elapsedMs },
            journalSink = { journal += it }
        )
        diagnostics.record(InfluxDiagnosticEvent("influx_runtime_state", mapOf(
            "runtime_id" to "runtime-a", "reason" to "service_started", "generation" to "0",
            "running" to "true", "actual_route" to "PRIMARY"
        )))
        (1..5).forEach { index ->
            diagnostics.record(InfluxDiagnosticEvent("influx_request_started", mapOf(
                "request_id" to "bounded-$index", "mode" to "export", "source" to "frozen_export",
                "host" to "influx.local", "port" to "8086", "profile" to "primary", "batch_rows" to "1"
            )))
        }
        assertTrue(diagnostics.snapshotLines().any { it == "influx_runtime_active_request_count=4" })

        diagnostics.record(InfluxDiagnosticEvent("influx_request_result", mapOf(
            "request_id" to "bounded-5", "mode" to "export", "result" to "ok",
            "duration_ms" to "17", "profile" to "primary"
        )))
        elapsedMs += 20L
        diagnostics.record(InfluxDiagnosticEvent("influx_cursor_persistence_failure", mapOf(
            "request_id" to "bounded-5", "failed_field" to "vehicle.speed", "completed_fields" to "1",
            "error_class" to "IllegalStateException"
        )))

        val failure = journal.single { it.type == "influx_cursor_persistence_failure" }
        assertEquals("export", failure.details["mode"])
        assertEquals("influx.local", failure.details["host"])
        assertEquals("primary", failure.details["profile"])
        assertEquals("0", failure.details["runtime_generation"])
        assertTrue(journal.any { it.type == "influx_problem_started" })
        assertEquals("error", journal.last { it.type == "influx_success_summary" }.details["reason"])

        elapsedMs += 20L
        diagnostics.record(InfluxDiagnosticEvent("influx_cursor_persistence_end", mapOf(
            "request_id" to "later", "cursor_rows" to "4"
        )))
        assertTrue(journal.any { it.type == "influx_problem_recovered" })
    }

    @Test
    fun durableGateReasonsAreDeduplicatedAcrossRoutineEventsUntilSuccess() {
        val journal = mutableListOf<InfluxDiagnosticEvent>()
        val diagnostics = InfluxRuntimeDiagnosticsState(journalSink = { journal += it })

        diagnostics.gate("backoff")
        diagnostics.record(InfluxDiagnosticEvent("influx_work_queued"))
        diagnostics.gate("backoff")
        diagnostics.gate("disabled")

        assertEquals(listOf("backoff", "disabled"), journal.filter { it.type == "influx_cycle_gate" }
            .map { it.details["reason"] })

        diagnostics.record(InfluxDiagnosticEvent("influx_request_result", mapOf(
            "mode" to "export", "result" to "ok", "duration_ms" to "10"
        )))
        diagnostics.gate("backoff")
        assertEquals(2, journal.count { it.type == "influx_cycle_gate" && it.details["reason"] == "backoff" })
    }
}
