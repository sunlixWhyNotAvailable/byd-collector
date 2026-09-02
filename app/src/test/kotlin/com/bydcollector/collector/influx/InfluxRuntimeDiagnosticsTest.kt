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
        val diagnostics = InfluxRuntimeDiagnosticsState { events += it }
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
}
