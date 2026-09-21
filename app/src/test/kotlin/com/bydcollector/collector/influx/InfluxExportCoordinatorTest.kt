package com.bydcollector.collector.influx

import com.bydcollector.collector.ha.HaEndpointProfile
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.service.InfluxCycleDemand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InfluxExportCoordinatorTest {
    @Test
    fun staleServiceOwnershipCannotEnterCycleStartOrResume() {
        val store = FakeInfluxStore(listOf(row(10, "soc")))
        val client = FakeInfluxClient()
        val coordinator = coordinator(store, client)
        val before = store.influxExportState()
        var serviceGeneration = 1
        val submittedGeneration = serviceGeneration
        val isCurrent = { submittedGeneration == serviceGeneration }
        serviceGeneration++ // The executor's last admission check already passed.
        coordinator.cancelInFlight()

        assertTrue(coordinator.runOneCycle(isCurrent = isCurrent).ok)
        assertTrue(coordinator.startExport(isCurrent = isCurrent).ok)
        assertTrue(coordinator.resumeExport(isCurrent = isCurrent).ok)
        assertFalse(coordinator.sessionFrozen)
        assertEquals(0, store.ensureCalls)
        assertEquals(0, store.summaryCalls)
        assertTrue(client.writtenLines.isEmpty())
        assertEquals(before, store.influxExportState())
    }

    @Test
    fun cancellationDuringQueueReadCannotBeAdoptedAsANewPass() {
        val store = FakeInfluxStore(listOf(row(10, "soc")))
        val client = FakeInfluxClient()
        val coordinator = coordinator(store, client)
        val before = store.influxExportState()
        store.afterPendingSummary = { coordinator.cancelInFlight() }

        assertTrue(coordinator.runOneCycle(force = true).ok)
        assertTrue(client.writtenLines.isEmpty())
        assertEquals(0L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(before, store.influxExportState())

        store.afterPendingSummary = null
        assertTrue(coordinator.startExport().ok)
        assertEquals(10L, store.cursor("soc").lastExportedHistoryId)
    }

    @Test
    fun serviceOwnershipLossDuringPreparationStopsStartAndResume() {
        for (resume in listOf(false, true)) {
            val store = FakeInfluxStore(listOf(row(10, "soc")))
            val client = FakeInfluxClient()
            val coordinator = coordinator(store, client)
            val before = store.influxExportState()
            var current = true
            store.afterPendingSummary = { current = false }

            val result = if (resume) coordinator.resumeExport(isCurrent = { current })
            else coordinator.startExport(isCurrent = { current })

            assertTrue(result.ok)
            assertTrue(client.writtenLines.isEmpty())
            assertEquals(0L, store.cursor("soc").lastExportedHistoryId)
            assertEquals(before, store.influxExportState())
        }
    }

    @Test
    fun serviceOwnershipLossAfterHttpStillPersistsAcknowledgedCursor() {
        val store = FakeInfluxStore((1L..4L).map { row(it, "soc") })
        val client = ScriptedInfluxClient(
            InfluxActionResult.fail("influx_http_error", "partial write: field type conflict", httpStatus = 400),
            InfluxActionResult.ok(),
            InfluxActionResult.ok()
        )
        val coordinator = coordinator(store, client)
        var current = true
        client.afterWrite = { call -> if (call == 2) current = false }

        assertTrue(coordinator.runOneCycle(isCurrent = { current }).ok)
        assertEquals(2, client.writeCalls)
        assertEquals(2L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(2L, store.influxExportState().exportedRowsTotal)
        assertEquals(2L, store.influxExportState().pendingRows)
    }

    @Test
    fun newHistoryAfterFinalSummaryIsExportedByRetainedDemand() {
        val store = FakeInfluxStore(listOf(row(10, "soc")))
        val client = FakeInfluxClient()
        val coordinator = coordinator(store, client)
        val demand = InfluxCycleDemand()
        val first = requireNotNull(demand.tryAcquire(revision = 1, generation = 1))
        var inserted = false
        store.afterPendingSummary = { summary ->
            if (!inserted && summary.rows == 0L) {
                inserted = true
                store.addRow(row(11, "soc"))
                demand.signal()
            }
        }

        assertTrue(coordinator.runOneCycle().ok)
        assertEquals(0L, store.influxExportState().pendingRows) // Snapshot predates row 11.
        assertEquals(null, coordinator.retryDelayMs())
        assertTrue(demand.settle(first, represented = true).followUpDemand)
        val second = requireNotNull(demand.tryAcquire(revision = 2, generation = 1))
        assertTrue(coordinator.runOneCycle().ok)
        assertFalse(demand.settle(second, represented = true).followUpDemand)
        assertEquals(11L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(2, client.writtenLines.size)
        assertEquals(4, store.summaryCalls)
    }

    @Test
    fun successfulCycleAndRepeatedSchedulingUseOnlyTwoAggregates() {
        val store = FakeInfluxStore((1L..350L).map { row(it, "soc") })
        val coordinator = coordinator(store, FakeInfluxClient())

        assertTrue(coordinator.runOneCycle().ok)
        assertEquals(50L, store.influxExportState().pendingRows)
        assertEquals(2, store.summaryCalls)
        val ensuresAfterCycle = store.ensureCalls
        repeat(5) { assertEquals(1_000L, coordinator.retryDelayMs()) }
        assertEquals(2, store.summaryCalls)
        assertEquals(ensuresAfterCycle, store.ensureCalls)
    }

    @Test
    fun earlyBackoffAndSchedulingDoNoQueueWorkAndPreserveSnapshot() {
        val store = FakeInfluxStore(listOf(row(10, "soc")))
        store.setNextRetryAt("2026-06-15T12:01:00Z")
        val before = store.influxExportState()
        val client = FakeInfluxClient()
        val coordinator = coordinator(store, client)

        repeat(5) {
            assertTrue(coordinator.runOneCycle().ok)
            assertEquals(60_000L, coordinator.retryDelayMs())
        }
        assertEquals(0, store.summaryCalls)
        assertEquals(0, store.ensureCalls)
        assertTrue(store.pendingBatchLimits.isEmpty())
        assertTrue(client.writtenLines.isEmpty())
        assertEquals(before, store.influxExportState())
    }

    @Test
    fun failedCycleSchedulesWithoutRecountingOrAdvancingCursors() {
        val store = FakeInfluxStore(listOf(row(10, "soc")))
        val client = FakeInfluxClient(writeResult = InfluxActionResult.fail("influx_error", "offline"))
        val coordinator = coordinator(store, client)

        assertFalse(coordinator.runOneCycle().ok)
        assertEquals(2, store.summaryCalls)
        assertEquals(30_000L, coordinator.retryDelayMs())
        assertTrue(coordinator.runOneCycle().ok)
        assertEquals(2, store.summaryCalls)
        assertEquals(0L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(1, client.writtenLines.size)
        assertEquals("offline", store.influxExportState().lastError)
    }

    @Test
    fun categoryChangesPreserveDivergentCursorsAndReadEarlierNewlyEnabledHistory() {
        val store = FakeInfluxStore(listOf(
            row(5, "speed_kmh").copy(category = "motion"),
            row(10, "soc"), row(11, "soc"),
            row(20, "speed_kmh").copy(category = "motion")
        ))
        store.updateInfluxCursorSuccess("soc", 10, "2026-06-15T12:00:00Z")
        var liveConfig = config()
        val client = FakeInfluxClient()
        val coordinator = InfluxExportCoordinator(store, client, { liveConfig }, FakeClock())

        assertTrue(coordinator.runOneCycle().ok)
        assertEquals(11L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(0L, store.cursor("speed_kmh").lastExportedHistoryId)
        store.addRow(row(12, "soc"))
        liveConfig = liveConfig.copy(enabledCategories = setOf("motion"))
        assertTrue(coordinator.runOneCycle().ok)
        assertEquals(20L, store.cursor("speed_kmh").lastExportedHistoryId)
        assertEquals(11L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(2, client.writtenLines[1].size)
        liveConfig = liveConfig.copy(enabledCategories = setOf("battery", "motion"))
        assertTrue(coordinator.runOneCycle().ok)
        assertEquals(12L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(listOf(1, 2, 1), client.writtenLines.map { it.size })
    }

    @Test
    fun failedWriteDoesNotAdvanceCursor() {
        val store = FakeInfluxStore(rows = listOf(row(id = 10, fieldKey = "soc")))
        val client = FakeInfluxClient(writeResult = InfluxActionResult.fail("influx_error", "write failed"))
        val coordinator = coordinator(store, client)

        val result = coordinator.runOneCycle(force = true)

        assertFalse(result.ok)
        assertEquals(0, store.cursor("soc").lastExportedHistoryId)
        assertEquals("write failed", store.cursorErrors["soc"])
    }

    @Test
    fun failedWriteKeepsPendingRowsVisibleForDashboard() {
        val store = FakeInfluxStore(rows = listOf(row(id = 10, fieldKey = "soc")))
        val client = FakeInfluxClient(writeResult = InfluxActionResult.fail("influx_error", "write failed"))
        val coordinator = coordinator(store, client)

        coordinator.runOneCycle(force = true)

        assertEquals(1, store.influxExportState().pendingRows)
    }

    @Test
    fun thrownWriteBecomesPersistedBackoffWithRetry() {
        val store = FakeInfluxStore(rows = listOf(row(id = 10, fieldKey = "soc")))
        val coordinator = coordinator(
            store,
            FakeInfluxClient(writeError = IllegalStateException("network unavailable"))
        )

        val result = coordinator.runOneCycle(force = true)

        assertFalse(result.ok)
        assertEquals("backoff", store.influxExportState().status)
        assertTrue(store.influxExportState().lastError.orEmpty().contains("network unavailable"))
        assertEquals("2026-06-15T12:00:30Z", store.influxExportState().nextRetryAt)
        assertEquals(30_000L, coordinator.retryDelayMs())
    }

    @Test
    fun thrownTransientWriteDeescalatesAdaptiveBatch() {
        val store = FakeInfluxStore((1L..10_000L).map { id -> row(id, "soc") })
        val coordinator = coordinator(
            store,
            FakeInfluxClient(writeError = IllegalStateException("network timeout"))
        )

        coordinator.runOneCycle(force = true)
        coordinator.runOneCycle(force = true)

        assertEquals(listOf(2_000, 1_000), store.pendingBatchLimits)
    }

    @Test
    fun successfulWriteAdvancesPerFieldCursor() {
        val store = FakeInfluxStore(
            rows = listOf(
                row(id = 10, fieldKey = "soc"),
                row(id = 11, fieldKey = "remaining_range_km")
            )
        )
        val client = FakeInfluxClient()
        val coordinator = coordinator(store, client)

        val result = coordinator.runOneCycle(force = true)

        assertTrue(result.ok)
        assertEquals(10, store.cursor("soc").lastExportedHistoryId)
        assertEquals(11, store.cursor("remaining_range_km").lastExportedHistoryId)
        assertEquals(2, client.writtenLines.single().size)
    }

    @Test
    fun cursorPersistenceBreadcrumbsAreBatchScopedAcrossFields() {
        val store = FakeInfluxStore(
            rows = listOf(
                row(id = 10, fieldKey = "soc"),
                row(id = 11, fieldKey = "remaining_range_km")
            )
        )
        val events = mutableListOf<InfluxDiagnosticEvent>()
        val coordinator = InfluxExportCoordinator(
            store = store,
            client = FakeInfluxClient(),
            configProvider = { config() },
            clock = FakeClock(),
            diagnostics = { events += it }
        )

        assertTrue(coordinator.runOneCycle(force = true).ok)
        assertEquals(1, events.count { it.type == "influx_cursor_persistence_start" })
        assertEquals(1, events.count { it.type == "influx_cursor_persistence_end" })
        assertEquals("2", events.single { it.type == "influx_cursor_persistence_start" }.details["cursor_fields"])
        assertEquals("2", events.single { it.type == "influx_cursor_persistence_end" }.details["completed_fields"])
    }

    @Test
    fun cursorPersistenceFailureRecordsFailedFieldWithoutBatchSuccess() {
        val store = FakeInfluxStore(
            rows = listOf(
                row(id = 10, fieldKey = "soc"),
                row(id = 11, fieldKey = "remaining_range_km")
            ),
            cursorFailureField = "remaining_range_km"
        )
        val events = mutableListOf<InfluxDiagnosticEvent>()
        val coordinator = InfluxExportCoordinator(
            store = store,
            client = FakeInfluxClient(),
            configProvider = { config() },
            clock = FakeClock(),
            diagnostics = { events += it }
        )

        assertFalse(coordinator.runOneCycle(force = true).ok)
        assertEquals(1, events.count { it.type == "influx_server_ack" })
        assertEquals(1, events.count { it.type == "influx_cursor_persistence_start" })
        assertEquals(0, events.count { it.type == "influx_cursor_persistence_end" })
        val failure = events.single { it.type == "influx_cursor_persistence_failure" }
        assertEquals("remaining_range_km", failure.details["failed_field"])
        assertEquals("1", failure.details["completed_fields"])
        assertEquals(10, store.cursor("soc").lastExportedHistoryId)
        assertEquals(0, store.cursor("remaining_range_km").lastExportedHistoryId)
    }

    @Test
    fun semanticCutoverFiltersRetiredChargingAndExportsUnitlessRawSensors() {
        val store = FakeInfluxStore(
            rows = listOf(
                row(id = 1, fieldKey = "charging_state"),
                row(id = 2, fieldKey = "max_discharge_power_allow_raw").copy(
                    valueNumber = 123.0,
                    unit = null
                ),
                row(id = 3, fieldKey = "bodywork_sunroof_windoblind_position").copy(
                    category = "body",
                    valueNumber = 4.0,
                    unit = null
                )
            )
        )
        val client = FakeInfluxClient()
        val coordinator = coordinator(
            store,
            client,
            influxConfig = config().copy(enabledCategories = setOf("battery", "body"))
        )

        val result = coordinator.runOneCycle(force = true)

        assertTrue(result.ok)
        val lines = client.writtenLines.single()
        assertEquals(2, lines.size)
        assertTrue(lines.none { it.contains("field_key=charging_state") })
        assertTrue(lines.any { it.contains("field_key=max_discharge_power_allow_raw") && it.contains("unit=none") })
        assertTrue(lines.any { it.contains("field_key=bodywork_sunroof_windoblind_position") && it.contains("unit=none") })
    }

    @Test
    fun successfulBatchKeepsRemainingPendingRowsVisibleForDashboard() {
        val rows = (1L..301L).map { id -> row(id = id, fieldKey = "soc") }
        val store = FakeInfluxStore(rows = rows)
        val client = FakeInfluxClient()
        val clock = FakeClock()
        val coordinator = coordinator(store, client, clock)

        coordinator.runOneCycle(force = true)

        assertEquals(300, client.writtenLines.single().size)
        assertEquals(listOf(300), store.pendingBatchLimits)
        assertEquals(300, store.cursor("soc").lastExportedHistoryId)
        assertEquals(1, store.influxExportState().pendingRows)
        assertEquals("2026-06-15T12:00:01Z", store.influxExportState().nextRetryAt)

        coordinator.runOneCycle(force = false)

        assertEquals(1, client.writtenLines.size)

        clock.now = "2026-06-15T12:00:01Z"
        coordinator.runOneCycle(force = false)

        assertEquals(2, client.writtenLines.size)
        assertEquals(0, store.influxExportState().pendingRows)
        assertEquals(null, store.influxExportState().nextRetryAt)
    }

    @Test
    fun manualStartWritesPendingRowsWithoutASeparateConnectionPreflight() {
        val store = FakeInfluxStore(rows = listOf(row(id = 10, fieldKey = "soc")))
        val client = FakeInfluxClient()
        val clock = FakeClock()
        val coordinator = coordinator(store, client, clock)

        coordinator.runOneCycle(force = true)

        assertEquals(0, store.influxExportState().pendingRows)
        assertEquals(null, store.influxExportState().nextRetryAt)
        assertEquals(null, coordinator.retryDelayMs())

        clock.now = "2026-06-15T12:00:20Z"
        store.addRow(row(id = 11, fieldKey = "soc"))
        coordinator.startExport()

        assertEquals(2, client.writtenLines.size)
        assertEquals(0, client.testCalls)
        assertEquals(0, store.influxExportState().pendingRows)
        assertEquals(null, coordinator.retryDelayMs())

        clock.now = "2026-06-15T12:00:30Z"
        coordinator.runOneCycle(force = false)

        assertEquals(2, client.writtenLines.size)
        assertEquals(0, store.influxExportState().pendingRows)
        assertEquals(null, store.influxExportState().nextRetryAt)
    }

    @Test
    fun resumeMarksHealthyBacklogAsScheduled() {
        val store = FakeInfluxStore(rows = listOf(row(id = 10, fieldKey = "soc")))
        val coordinator = coordinator(store, FakeInfluxClient())

        val result = coordinator.resumeExport()

        assertTrue(result.ok)
        assertEquals("scheduled", store.influxExportState().status)
        assertEquals("2026-06-15T12:00:01Z", store.influxExportState().nextRetryAt)
        assertEquals(1_000L, coordinator.retryDelayMs())
    }

    @Test
    fun resumePreservesPersistedRetryWithoutNetworkRequest() {
        val store = FakeInfluxStore(rows = listOf(row(id = 10, fieldKey = "soc")))
        store.setNextRetryAt("2026-06-15T12:01:00Z")
        val client = FakeInfluxClient()

        val result = coordinator(store, client).resumeExport()

        assertTrue(result.ok)
        assertEquals(0, client.testCalls)
        assertTrue(client.writtenLines.isEmpty())
        assertEquals("2026-06-15T12:01:00Z", store.influxExportState().nextRetryAt)
        assertEquals(1, store.influxExportState().pendingRows)
        assertEquals("backoff", store.influxExportState().status)
    }

    @Test
    fun resumeClearsStaleFailureAfterBacklogWasAlreadyDrained() {
        val store = FakeInfluxStore(rows = listOf(row(id = 10, fieldKey = "soc")))
        store.ensureInfluxCursors(setOf("soc"))
        store.updateInfluxCursorSuccess("soc", 10, "2026-06-15T12:00:00Z")
        store.setNextRetryAt("2026-06-15T12:01:00Z")

        coordinator(store, FakeInfluxClient()).resumeExport()

        assertEquals("idle", store.influxExportState().status)
        assertEquals(null, store.influxExportState().nextRetryAt)
        assertEquals(null, store.influxExportState().lastError)
    }

    @Test
    fun failedStandaloneRetrySurvivesOwnerRecreationAndDrains() {
        val store = FakeInfluxStore(rows = listOf(row(id = 10, fieldKey = "soc")))
        val clock = FakeClock()
        val client = ScriptedInfluxClient(
            InfluxActionResult.fail("influx_error", "offline"),
            InfluxActionResult.ok()
        )

        val first = coordinator(store, client, clock)
        assertFalse(first.startExport().ok)
        assertEquals("backoff", store.influxExportState().status)
        assertEquals("2026-06-15T12:00:30Z", store.influxExportState().nextRetryAt)
        assertEquals(30_000L, first.retryDelayMs())
        assertEquals(0, store.cursor("soc").lastExportedHistoryId)

        val recovered = coordinator(store, client, clock)
        assertTrue(recovered.resumeExport().ok)
        assertEquals(1, client.writeCalls)
        assertEquals("backoff", store.influxExportState().status)

        clock.now = "2026-06-15T12:00:30Z"
        assertTrue(recovered.runOneCycle(force = false).ok)
        assertEquals(2, client.writeCalls)
        assertEquals(10, store.cursor("soc").lastExportedHistoryId)
        assertEquals("idle", store.influxExportState().status)
        assertEquals(null, store.influxExportState().nextRetryAt)
        assertEquals(null, recovered.retryDelayMs())
    }

    @Test
    fun failedStartWriteKeepsAuthoritativePendingSummary() {
        val store = FakeInfluxStore(rows = listOf(row(id = 10, fieldKey = "soc")))
        val client = FakeInfluxClient(writeResult = InfluxActionResult.fail("influx_error", "offline"))

        val result = coordinator(store, client).startExport()

        assertFalse(result.ok)
        assertEquals(0, client.testCalls)
        assertEquals(1, store.influxExportState().pendingRows)
        assertEquals("2026-06-15T12:00:30Z", store.influxExportState().nextRetryAt)
    }

    @Test
    fun failureAndStopPreserveLastSuccessErrorAndRealPendingRows() {
        val store = FakeInfluxStore(rows = listOf(row(id = 10, fieldKey = "soc")))
        val clock = FakeClock()
        coordinator(store, FakeInfluxClient(), clock).runOneCycle(force = true)

        clock.now = "2026-06-15T12:01:00Z"
        store.addRow(row(id = 11, fieldKey = "soc"))
        coordinator(
            store,
            FakeInfluxClient(writeResult = InfluxActionResult.fail("influx_error", "offline")),
            clock
        ).runOneCycle(force = true)

        coordinator(store, FakeInfluxClient(), clock).stopExport()

        val state = store.influxExportState()
        assertEquals("stopped", state.status)
        assertEquals("realtime", state.mode)
        assertEquals(1, state.pendingRows)
        assertEquals("2026-06-15T12:00:00Z", state.lastSuccessAt)
        assertEquals("2026-06-15T12:01:00Z", state.lastErrorAt)
        assertEquals("offline", state.lastError)
        assertEquals(null, state.nextRetryAt)
    }

    @Test
    fun batchTargetUsesTheExactFiveThousandRowThreshold() {
        val realtimeStore = FakeInfluxStore((1L..4_999L).map { id -> row(id, "soc") })
        val catchUpStore = FakeInfluxStore((1L..5_000L).map { id -> row(id, "soc") })
        val realtimeClient = FakeInfluxClient()
        val catchUpClient = FakeInfluxClient()

        coordinator(realtimeStore, realtimeClient).runOneCycle(force = true)
        coordinator(catchUpStore, catchUpClient).runOneCycle(force = true)

        assertEquals(listOf(300), realtimeStore.pendingBatchLimits)
        assertEquals(300, realtimeClient.writtenLines.single().size)
        assertEquals(4_699L, realtimeStore.influxExportState().pendingRows)
        assertEquals(listOf(2_000), catchUpStore.pendingBatchLimits)
        assertEquals(2_000, catchUpClient.writtenLines.single().size)
        assertEquals(2_000L, catchUpStore.cursor("soc").lastExportedHistoryId)
        assertEquals(3_000L, catchUpStore.influxExportState().pendingRows)
    }

    @Test
    fun catchUpFallsBackToRealtimeBatchesAsBacklogShrinksWithoutSkippingRows() {
        val store = FakeInfluxStore((1L..5_301L).map { id -> row(id, "soc") })
        val client = FakeInfluxClient()
        val clock = FakeClock()
        val coordinator = coordinator(store, client, clock)

        coordinator.runOneCycle(force = true)
        assertEquals(2_000L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(3_301L, store.influxExportState().pendingRows)
        assertEquals("2026-06-15T12:00:01Z", store.influxExportState().nextRetryAt)

        clock.now = "2026-06-15T12:00:01Z"
        coordinator.runOneCycle(force = false)
        assertEquals(2_300L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(3_001L, store.influxExportState().pendingRows)

        clock.now = "2026-06-15T12:00:02Z"
        coordinator.runOneCycle(force = false)

        assertEquals(listOf(2_000, 300, 300), store.pendingBatchLimits)
        assertEquals(listOf(2_000, 300, 300), client.writtenLines.map { it.size })
        assertEquals(2_600L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(2_701L, store.influxExportState().pendingRows)
        assertEquals("2026-06-15T12:00:03Z", store.influxExportState().nextRetryAt)
    }

    @Test
    fun adaptiveBatchDeescalatesOnlyTransientFailures() {
        val store = FakeInfluxStore((1L..10_000L).map { id -> row(id, "soc") })
        val transient = InfluxActionResult.fail("influx_network_error", "offline", httpStatus = 503)
        val client = ScriptedInfluxClient(transient, transient, transient, transient)
        val coordinator = coordinator(store, client)

        repeat(4) { coordinator.runOneCycle(force = true) }

        assertEquals(listOf(2_000, 1_000, 500, 300), store.pendingBatchLimits)
        assertEquals(0L, store.cursor("soc").lastExportedHistoryId)

        val nonTransientStore = FakeInfluxStore((1L..10_000L).map { id -> row(id, "soc") })
        val nonTransient = InfluxActionResult.fail("influx_http_error", "bad request", httpStatus = 400)
        val nonTransientClient = ScriptedInfluxClient(nonTransient, nonTransient)
        val nonTransientCoordinator = coordinator(nonTransientStore, nonTransientClient)

        nonTransientCoordinator.runOneCycle(force = true)
        nonTransientCoordinator.runOneCycle(force = true)

        assertEquals(listOf(2_000, 2_000), nonTransientStore.pendingBatchLimits)
    }

    @Test
    fun successfulBatchesRampBackTowardDesiredTarget() {
        val store = FakeInfluxStore((1L..10_000L).map { id -> row(id, "soc") })
        val client = ScriptedInfluxClient(
            InfluxActionResult.fail("influx_network_error", "offline", httpStatus = 503),
            InfluxActionResult.ok(),
            InfluxActionResult.ok()
        )
        val coordinator = coordinator(store, client)

        coordinator.runOneCycle(force = true)
        coordinator.runOneCycle(force = true)
        coordinator.runOneCycle(force = true)

        assertEquals(listOf(2_000, 1_000, 2_000), store.pendingBatchLimits)
    }

    @Test
    fun dataFormat400IsBisectedInOrderAndOnlyPoisonRowIsQuarantined() {
        val store = FakeInfluxStore((1L..4L).map { id -> row(id, "soc") })
        val dataFailure = InfluxActionResult.fail(
            "influx_http_error",
            "partial write: field type conflict",
            httpStatus = 400
        )
        val client = ScriptedInfluxClient(
            dataFailure,
            InfluxActionResult.ok(),
            dataFailure,
            dataFailure,
            InfluxActionResult.ok()
        )
        val result = coordinator(store, client).runOneCycle(force = true)

        assertTrue(result.ok)
        assertEquals(listOf(300), store.pendingBatchLimits)
        assertEquals(listOf(4, 2, 2, 1, 1), client.writtenLineSizes)
        assertEquals(4L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(1, store.influxEvents.count { it.eventType == "influx_export_poison_row" })
        val poison = store.influxEvents.single { it.eventType == "influx_export_poison_row" }
        assertTrue(poison.message.orEmpty().contains("history_id=3"))
        assertTrue(poison.message.orEmpty().contains("reason=field_type_conflict"))
        assertEquals(1, poison.batchCount)
        assertEquals(3L, poison.fromHistoryId)
        assertEquals(3L, poison.toHistoryId)
    }

    @Test
    fun generic400DoesNotSplitOrAdvanceCursor() {
        val store = FakeInfluxStore((1L..4L).map { id -> row(id, "soc") })
        val client = FakeInfluxClient(
            writeResult = InfluxActionResult.fail("influx_http_error", "bad request", httpStatus = 400)
        )

        val result = coordinator(store, client).runOneCycle(force = true)

        assertFalse(result.ok)
        assertEquals(listOf(300), store.pendingBatchLimits)
        assertEquals(listOf(4), client.writtenLines.map { it.size })
        assertEquals(0L, store.cursor("soc").lastExportedHistoryId)
        assertTrue(store.influxEvents.none { it.eventType == "influx_export_poison_row" })
    }

    @Test
    fun dataFormatTextWithoutHttp400DoesNotSplit() {
        val store = FakeInfluxStore((1L..4L).map { id -> row(id, "soc") })
        val client = FakeInfluxClient(
            writeResult = InfluxActionResult.fail("influx_http_error", "partial write: field type conflict")
        )

        val result = coordinator(store, client).runOneCycle(force = true)

        assertFalse(result.ok)
        assertEquals(listOf(300), store.pendingBatchLimits)
        assertEquals(listOf(4), client.writtenLines.map { it.size })
        assertEquals(0L, store.cursor("soc").lastExportedHistoryId)
    }

    @Test
    fun `recognized 422 retention error is split per row without skipping valid neighbors`() {
        val store = FakeInfluxStore((1L..4L).map { id -> row(id, "soc") })
        val retention = InfluxActionResult.fail(
            "influx_http_error",
            "partial write: points beyond retention policy dropped=1",
            httpStatus = 422,
            failureKind = InfluxFailureKind.DATA
        )
        val client = ScriptedInfluxClient(
            retention,
            InfluxActionResult.ok(),
            retention,
            retention,
            InfluxActionResult.ok()
        )

        val result = coordinator(store, client).runOneCycle(force = true)

        assertTrue(result.ok)
        assertEquals(listOf(4, 2, 2, 1, 1), client.writtenLineSizes)
        assertEquals(4L, store.cursor("soc").lastExportedHistoryId)
        val poison = store.influxEvents.single { it.eventType == "influx_export_poison_row" }
        assertEquals(1, poison.batchCount)
        assertTrue(poison.message.orEmpty().contains("reason=retention_policy"))
    }

    @Test
    fun `unknown 422 remains pending and enters backoff`() {
        val store = FakeInfluxStore((1L..2L).map { id -> row(id, "soc") })
        val client = FakeInfluxClient(
            writeResult = InfluxActionResult.fail(
                "influx_http_error",
                "partial write: unsupported shard rejection",
                httpStatus = 422,
                failureKind = InfluxFailureKind.DATA
            )
        )

        val result = coordinator(store, client).runOneCycle(force = true)

        assertFalse(result.ok)
        assertEquals(0L, store.cursor("soc").lastExportedHistoryId)
        assertEquals("backoff", store.influxExportState().status)
        assertTrue(store.influxEvents.none { it.eventType == "influx_export_poison_row" })
    }

    @Test
    fun `split pass is capped at 64 writes and resume excludes confirmed poison prefix`() {
        val store = FakeInfluxStore((1L..100L).map { id -> row(id, "soc") })
        val client = AlwaysDataFailureClient(firstTransportFailure = true)
        val coordinator = coordinator(
            store,
            client,
            influxConfig = config(alternativeHost = "influx-alt.local", alternativePort = 8087)
        )

        val first = coordinator.runOneCycle(force = true)
        val confirmed = store.cursor("soc").lastExportedHistoryId

        assertTrue(first.ok)
        assertEquals(64, client.writeCalls)
        assertTrue(confirmed in 1L..99L)
        assertEquals("scheduled", store.influxExportState().status)
        val firstSummary = store.influxEvents.single { it.eventType == "influx_export_poison_row" }
        assertEquals(confirmed.toInt(), firstSummary.batchCount)
        assertTrue(firstSummary.message.orEmpty().split("history_id=").size - 1 <= 5)

        coordinator.runOneCycle(force = true)

        assertEquals((100L - confirmed).toInt(), client.writtenLineSizes[64])
        assertTrue(client.writeCalls <= 128)
    }

    @Test
    fun `cancellation after successful child preserves cursor and resume starts at remainder`() {
        val store = FakeInfluxStore((1L..4L).map { id -> row(id, "soc") })
        val dataFailure = InfluxActionResult.fail(
            "influx_http_error",
            "partial write: field type conflict",
            httpStatus = 400
        )
        val client = ScriptedInfluxClient(dataFailure, InfluxActionResult.ok(), InfluxActionResult.ok())
        lateinit var coordinator: InfluxExportCoordinator
        coordinator = coordinator(store, client)
        client.afterWrite = { call -> if (call == 2) coordinator.cancelInFlight() }

        val stopped = coordinator.runOneCycle(force = true)

        assertTrue(stopped.ok)
        assertEquals(2, client.writeCalls)
        assertEquals(2L, store.cursor("soc").lastExportedHistoryId)
        assertEquals(2L, store.influxExportState().pendingRows)
        client.afterWrite = null

        assertTrue(coordinator.runOneCycle(force = true).ok)
        assertEquals(listOf(4, 2, 2), client.writtenLineSizes)
        assertEquals(4L, store.cursor("soc").lastExportedHistoryId)
    }

    @Test
    fun `poison summary is emitted once even when a later split fails`() {
        val store = FakeInfluxStore((1L..2L).map { id -> row(id, "soc") })
        val dataFailure = InfluxActionResult.fail(
            "influx_http_error",
            "partial write: unable to parse",
            httpStatus = 400
        )
        val unknown = InfluxActionResult.fail(
            "influx_http_error",
            "unprocessable request",
            httpStatus = 422,
            failureKind = InfluxFailureKind.DATA
        )
        val client = ScriptedInfluxClient(dataFailure, dataFailure, unknown)

        val result = coordinator(store, client).runOneCycle(force = true)

        assertFalse(result.ok)
        assertEquals(1L, store.cursor("soc").lastExportedHistoryId)
        val summaries = store.influxEvents.filter { it.eventType == "influx_export_poison_row" }
        assertEquals(1, summaries.size)
        assertEquals(1, summaries.single().batchCount)
    }

    @Test
    fun `poison summary survives cancellation later in the same split pass`() {
        val store = FakeInfluxStore((1L..4L).map { id -> row(id, "soc") })
        val dataFailure = InfluxActionResult.fail(
            "influx_http_error",
            "partial write: field type conflict",
            httpStatus = 400
        )
        val client = ScriptedInfluxClient(dataFailure, dataFailure, dataFailure)
        lateinit var coordinator: InfluxExportCoordinator
        coordinator = coordinator(store, client)
        client.afterWrite = { call -> if (call == 3) coordinator.cancelInFlight() }

        val result = coordinator.runOneCycle(force = true)

        assertTrue(result.ok)
        assertEquals(1L, store.cursor("soc").lastExportedHistoryId)
        val summaries = store.influxEvents.filter { it.eventType == "influx_export_poison_row" }
        assertEquals(1, summaries.size)
        assertEquals(1, summaries.single().batchCount)
        assertEquals("scheduled", store.influxExportState().status)
    }

    @Test
    fun `interrupted split rethrows with flag and poison logging cannot mask it`() {
        val store = FakeInfluxStore(
            rows = (1L..4L).map { id -> row(id, "soc") },
            eventFailure = IllegalStateException("event store unavailable")
        )
        val coordinator = coordinator(store, PoisonThenInterruptedClient())

        try {
            assertFailsWith<InterruptedException> {
                coordinator.runOneCycle(force = true)
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1L, store.cursor("soc").lastExportedHistoryId)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `fatal client error is not converted into export backoff`() {
        val store = FakeInfluxStore(rows = listOf(row(1L, "soc")))
        val fatal = object : InfluxClient {
            override fun test(config: InfluxConfig): InfluxActionResult = InfluxActionResult.ok()
            override fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult {
                throw AssertionError("fatal")
            }
        }

        assertFailsWith<AssertionError> {
            coordinator(store, fatal).runOneCycle(force = true)
        }
        assertEquals(0L, store.cursor("soc").lastExportedHistoryId)
    }

    @Test
    fun normalResumeCreatesMissingCursorsWithoutResettingExisting() {
        val store = FakeInfluxStore(rows = emptyList())
        store.ensureInfluxCursors(setOf("soc"))
        store.updateInfluxCursorSuccess("soc", 99, "2026-06-15T12:00:00Z")
        val coordinator = coordinator(store, FakeInfluxClient())

        val result = coordinator.resumeExport()

        assertTrue(result.ok)
        assertEquals(99, store.cursor("soc").lastExportedHistoryId)
        assertTrue(store.cursors.keys.containsAll(setOf(
            "soc_internal",
            "battery_remaining_energy_kwh",
            "trip_energy_kwh",
            "cumulative_energy_kwh"
        )))
    }

    @Test
    fun transportFailureFallsBackToAlternativeAndSticksForNextBatch() {
        val store = FakeInfluxStore(rows = listOf(row(id = 1, fieldKey = "soc")))
        val client = ScriptedInfluxClient(
            InfluxActionResult.fail("influx_network_error", "primary offline", failureKind = InfluxFailureKind.TRANSPORT),
            InfluxActionResult.ok(),
            InfluxActionResult.ok()
        )
        val coordinator = coordinator(
            store,
            client,
            influxConfig = config(alternativeHost = "influx-alt.local", alternativePort = 8087)
        )

        assertTrue(coordinator.runOneCycle(force = true).ok)
        store.addRow(row(id = 2, fieldKey = "soc"))
        assertTrue(coordinator.runOneCycle(force = true).ok)

        assertEquals(listOf("influx.local", "influx-alt.local", "influx-alt.local"), client.configs.map { it.host })
        assertEquals(client.writtenLines[0], client.writtenLines[1])
        assertEquals(HaEndpointProfile.ALTERNATIVE, coordinator.activeRoute)
    }

    @Test
    fun alternativeFailureFallsBackToPrimaryAndBothFailuresResetForNextCycle() {
        val store = FakeInfluxStore(rows = listOf(row(id = 1, fieldKey = "soc")))
        val client = ScriptedInfluxClient(
            InfluxActionResult.fail("influx_network_error", "primary offline", failureKind = InfluxFailureKind.TRANSPORT),
            InfluxActionResult.ok(),
            InfluxActionResult.fail("influx_network_error", "alternative offline", failureKind = InfluxFailureKind.TRANSPORT),
            InfluxActionResult.ok(),
            InfluxActionResult.fail("influx_network_error", "primary offline", failureKind = InfluxFailureKind.TRANSPORT),
            InfluxActionResult.fail("influx_network_error", "alternative offline", failureKind = InfluxFailureKind.TRANSPORT),
            InfluxActionResult.ok()
        )
        val coordinator = coordinator(
            store,
            client,
            influxConfig = config(alternativeHost = "influx-alt.local", alternativePort = 8087)
        )

        assertTrue(coordinator.runOneCycle(force = true).ok)
        store.addRow(row(id = 2, fieldKey = "soc"))
        assertTrue(coordinator.runOneCycle(force = true).ok)
        store.addRow(row(id = 3, fieldKey = "soc"))
        assertFalse(coordinator.runOneCycle(force = true).ok)
        store.addRow(row(id = 4, fieldKey = "soc"))
        assertTrue(coordinator.runOneCycle(force = true).ok)

        assertEquals(
            listOf("influx.local", "influx-alt.local", "influx-alt.local", "influx.local", "influx.local", "influx-alt.local", "influx.local"),
            client.configs.map { it.host }
        )
        assertEquals(HaEndpointProfile.PRIMARY, coordinator.activeRoute)
    }

    @Test
    fun authenticationAndRateLimitFailuresStayOnCurrentEndpoint() {
        val store = FakeInfluxStore(rows = listOf(row(id = 1, fieldKey = "soc")))
        val client = ScriptedInfluxClient(
            InfluxActionResult.fail("influx_http_error", "unauthorized", httpStatus = 401, failureKind = InfluxFailureKind.AUTHENTICATION),
            InfluxActionResult.fail("influx_http_error", "too many requests", httpStatus = 429)
        )
        val coordinator = coordinator(
            store,
            client,
            influxConfig = config(alternativeHost = "influx-alt.local", alternativePort = 8087)
        )

        assertFalse(coordinator.runOneCycle(force = true).ok)
        assertEquals(listOf("influx.local"), client.configs.map { it.host })
        store.addRow(row(id = 2, fieldKey = "soc"))
        assertFalse(coordinator.runOneCycle(force = true).ok)
        assertEquals(listOf("influx.local", "influx.local"), client.configs.map { it.host })
    }

    @Test
    fun unsuccessfulAlternativeDoesNotBecomeStickyAfterPrimaryTransportFailure() {
        val failures = listOf(
            InfluxActionResult.fail("influx_http_error", "unauthorized", httpStatus = 401, failureKind = InfluxFailureKind.AUTHENTICATION),
            InfluxActionResult.fail("influx_http_error", "invalid data", httpStatus = 422, failureKind = InfluxFailureKind.DATA),
            InfluxActionResult.fail("influx_protocol_error", "TLS failure", failureKind = InfluxFailureKind.PROTOCOL),
            InfluxActionResult.fail("influx_http_error", "too many requests", httpStatus = 429)
        )
        failures.forEach { failure ->
            val store = FakeInfluxStore(rows = listOf(row(id = 1, fieldKey = "soc")))
            val client = ScriptedInfluxClient(
                InfluxActionResult.fail("influx_network_error", "primary offline", failureKind = InfluxFailureKind.TRANSPORT),
                failure,
                InfluxActionResult.ok()
            )
            val coordinator = coordinator(
                store,
                client,
                influxConfig = config(alternativeHost = "influx-alt.local", alternativePort = 8087)
            )

            assertFalse(coordinator.runOneCycle(force = true).ok)
            assertEquals(null, coordinator.activeRoute, failure.message)
            assertEquals(0L, store.cursor("soc").lastExportedHistoryId)
            assertEquals(listOf("influx.local", "influx-alt.local"), client.configs.map { it.host })
            assertTrue(coordinator.runOneCycle(force = true).ok)
            assertEquals(listOf("influx.local", "influx-alt.local", "influx.local"), client.configs.map { it.host })
            assertTrue(client.writtenLines.all { it == client.writtenLines.first() })
            assertEquals(HaEndpointProfile.PRIMARY, coordinator.activeRoute)
            assertEquals(1L, store.cursor("soc").lastExportedHistoryId)
        }
    }

    @Test
    fun successfulPinnedSplitEstablishesAlternativeAsStickyRoute() {
        val store = FakeInfluxStore(rows = listOf(row(id = 1, fieldKey = "soc"), row(id = 2, fieldKey = "soc")))
        val client = ScriptedInfluxClient(
            InfluxActionResult.fail("influx_network_error", "primary offline", failureKind = InfluxFailureKind.TRANSPORT),
            InfluxActionResult.fail("influx_http_error", "partial write: field type conflict", httpStatus = 400),
            InfluxActionResult.ok(),
            InfluxActionResult.ok(),
            InfluxActionResult.ok()
        )
        val coordinator = coordinator(
            store,
            client,
            influxConfig = config(alternativeHost = "influx-alt.local", alternativePort = 8087)
        )

        assertTrue(coordinator.runOneCycle(force = true).ok)
        assertEquals(HaEndpointProfile.ALTERNATIVE, coordinator.activeRoute)
        store.addRow(row(id = 3, fieldKey = "soc"))
        assertTrue(coordinator.runOneCycle(force = true).ok)
        assertEquals(listOf("influx.local", "influx-alt.local", "influx-alt.local", "influx-alt.local", "influx-alt.local"), client.configs.map { it.host })
        assertEquals(3L, store.cursor("soc").lastExportedHistoryId)
    }

    @Test
    fun poison400SplittingStaysPinnedToAlternativeEndpoint() {
        val store = FakeInfluxStore(rows = listOf(row(id = 1, fieldKey = "soc")))
        val client = ScriptedInfluxClient(
            InfluxActionResult.fail("influx_network_error", "primary offline", failureKind = InfluxFailureKind.TRANSPORT),
            InfluxActionResult.ok(),
            InfluxActionResult.fail("influx_http_error", "partial write: field type conflict", httpStatus = 400),
            InfluxActionResult.fail("influx_http_error", "partial write: field type conflict", httpStatus = 400),
            InfluxActionResult.fail("influx_http_error", "partial write: field type conflict", httpStatus = 400)
        )
        val coordinator = coordinator(
            store,
            client,
            influxConfig = config(alternativeHost = "influx-alt.local", alternativePort = 8087)
        )

        assertTrue(coordinator.runOneCycle(force = true).ok)
        store.addRow(row(id = 2, fieldKey = "soc"))
        store.addRow(row(id = 3, fieldKey = "soc"))
        assertTrue(coordinator.runOneCycle(force = true).ok)

        assertEquals(listOf("influx.local", "influx-alt.local", "influx-alt.local", "influx-alt.local", "influx-alt.local"), client.configs.map { it.host })
        assertEquals(3L, store.cursor("soc").lastExportedHistoryId)
    }

    @Test
    fun ownedResumeUsesFrozenEndpointWhenLiveDraftBecomesInvalid() {
        val store = FakeInfluxStore(rows = emptyList())
        var live = config(alternativeHost = "influx-alt.local", alternativePort = 8087)
        val coordinator = InfluxExportCoordinator(
            store = store,
            client = FakeInfluxClient(),
            configProvider = { live },
            clock = FakeClock()
        )

        assertTrue(coordinator.resumeExport().ok)
        live = live.copy(alternativeHost = "influx alt", alternativePort = 8087)

        assertTrue(coordinator.resumeExport().ok)
    }

    @Test
    fun serverAckIsRecordedBeforeDurableCursorPersistence() {
        val store = FakeInfluxStore(rows = listOf(row(id = 10, fieldKey = "soc")))
        val events = mutableListOf<InfluxDiagnosticEvent>()
        val coordinator = InfluxExportCoordinator(
            store = store,
            client = FakeInfluxClient(),
            configProvider = { config() },
            clock = FakeClock(),
            diagnostics = { events += it }
        )

        assertTrue(coordinator.runOneCycle(force = true).ok)
        val ack = events.indexOfFirst { it.type == "influx_server_ack" }
        val cursorStart = events.indexOfFirst { it.type == "influx_cursor_persistence_start" }
        assertTrue(ack >= 0)
        assertTrue(cursorStart > ack)
    }

    private fun coordinator(
        store: FakeInfluxStore,
        client: InfluxClient,
        clock: Clock = FakeClock(),
        influxConfig: InfluxConfig = config()
    ): InfluxExportCoordinator {
        return InfluxExportCoordinator(
            store = store,
            client = client,
            configProvider = { influxConfig },
            clock = clock
        )
    }

    private fun config(
        alternativeHost: String? = null,
        alternativePort: Int? = null
    ): InfluxConfig = InfluxConfig(
        enabled = true,
        host = "influx.local",
        port = 8086,
        database = "bydcollector",
        username = null,
        password = null,
        measurement = "byd_state",
        enabledCategories = setOf("battery"),
        alternativeHost = alternativeHost,
        alternativePort = alternativePort
    )

    private fun row(id: Long, fieldKey: String): InfluxPendingHistoryRow = InfluxPendingHistoryRow(
        id = id,
        fieldKey = fieldKey,
        category = "battery",
        valueType = "NUMBER",
        valueText = null,
        valueNumber = 73.0,
        valueBool = null,
        quality = "OK",
        unit = "%",
        sourcePollId = 42,
        sourceKeys = fieldKey,
        observedAt = "2026-06-15T10:20:30Z",
        changedAt = "2026-06-15T10:20:31Z"
    )

    private class FakeInfluxClient(
        private val writeResult: InfluxActionResult = InfluxActionResult.ok(),
        private val testResult: InfluxActionResult = InfluxActionResult.ok(),
        private val writeError: RuntimeException? = null
    ) : InfluxClient {
        val writtenLines = mutableListOf<List<String>>()
        var testCalls = 0

        override fun test(config: InfluxConfig): InfluxActionResult {
            testCalls += 1
            return testResult
        }

        override fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult {
            writeError?.let { throw it }
            writtenLines += lines
            return writeResult
        }
    }

    private class ScriptedInfluxClient(
        vararg results: InfluxActionResult
    ) : InfluxClient {
        private val results = ArrayDeque(results.toList())
        var writeCalls = 0
        var afterWrite: ((Int) -> Unit)? = null
        val writtenLineSizes = mutableListOf<Int>()
        val configs = mutableListOf<InfluxConfig>()
        val writtenLines = mutableListOf<List<String>>()

        override fun test(config: InfluxConfig): InfluxActionResult = InfluxActionResult.ok()

        override fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult {
            writeCalls += 1
            writtenLineSizes += lines.size
            configs += config
            writtenLines += lines
            return results.removeFirst().also { afterWrite?.invoke(writeCalls) }
        }
    }

    private class AlwaysDataFailureClient(
        private val firstTransportFailure: Boolean = false
    ) : InfluxClient {
        var writeCalls = 0
        val writtenLineSizes = mutableListOf<Int>()

        override fun test(config: InfluxConfig): InfluxActionResult = InfluxActionResult.ok()

        override fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult {
            writeCalls += 1
            writtenLineSizes += lines.size
            if (firstTransportFailure && writeCalls == 1) {
                return InfluxActionResult.fail(
                    "influx_network_error",
                    "primary offline",
                    failureKind = InfluxFailureKind.TRANSPORT
                )
            }
            return InfluxActionResult.fail(
                "influx_http_error",
                "partial write: points beyond retention policy dropped=${lines.size}",
                httpStatus = 422,
                failureKind = InfluxFailureKind.DATA
            )
        }
    }

    private class PoisonThenInterruptedClient : InfluxClient {
        private var writeCalls = 0

        override fun test(config: InfluxConfig): InfluxActionResult = InfluxActionResult.ok()

        override fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult {
            writeCalls += 1
            if (writeCalls == 4) throw InterruptedException("stop")
            return InfluxActionResult.fail(
                "influx_http_error",
                "partial write: field type conflict",
                httpStatus = 400,
                failureKind = InfluxFailureKind.DATA
            )
        }
    }

    private class FakeInfluxStore(
        rows: List<InfluxPendingHistoryRow>,
        private val cursorFailureField: String? = null,
        private val eventFailure: RuntimeException? = null
    ) : InfluxExportStore {
        private val rows = rows.toMutableList()
        val cursors = linkedMapOf<String, InfluxCursor>()
        val cursorErrors = linkedMapOf<String, String>()
        val pendingBatchLimits = mutableListOf<Int>()
        val influxEvents = mutableListOf<InfluxEvent>()
        var summaryCalls = 0
        var ensureCalls = 0
        var afterPendingSummary: ((InfluxPendingSummary) -> Unit)? = null
        private var state = InfluxExportStateSnapshot(
            status = "stopped",
            mode = null,
            pendingRows = 0,
            oldestPendingAt = null,
            nextRetryAt = null,
            lastSuccessAt = null,
            lastErrorAt = null,
            lastError = null,
            exportedRowsTotal = 0
        )

        override fun ensureInfluxCursors(fieldKeys: Set<String>) {
            ensureCalls++
            fieldKeys.forEach { fieldKey -> cursors.putIfAbsent(fieldKey, InfluxCursor(fieldKey, 0)) }
        }

        override fun pendingInfluxSummary(fieldKeys: Set<String>): InfluxPendingSummary {
            summaryCalls++
            ensureInfluxCursors(fieldKeys)
            val pending = rows.filter { row ->
                fieldKeys.contains(row.fieldKey) && row.id > cursor(row.fieldKey).lastExportedHistoryId
            }
            return InfluxPendingSummary(
                rows = pending.size.toLong(),
                oldestObservedAt = pending.minByOrNull { it.id }?.observedAt
            ).also { afterPendingSummary?.invoke(it) }
        }

        override fun pendingInfluxRows(fieldKeys: Set<String>, limit: Int): List<InfluxPendingHistoryRow> {
            ensureInfluxCursors(fieldKeys)
            pendingBatchLimits += limit
            return rows.asSequence()
                .filter { it.fieldKey in fieldKeys && it.id > cursor(it.fieldKey).lastExportedHistoryId }
                .sortedBy { it.id }
                .take(limit)
                .toList()
        }

        override fun updateInfluxCursorSuccess(fieldKey: String, historyId: Long, exportedAt: String) {
            if (fieldKey == cursorFailureField) throw IllegalStateException("cursor persistence failed")
            cursors[fieldKey] = InfluxCursor(fieldKey, historyId)
        }

        override fun updateInfluxCursorError(fieldKey: String, error: String, errorAt: String) {
            cursorErrors[fieldKey] = error
        }

        override fun influxExportState(): InfluxExportStateSnapshot = state

        override fun updateInfluxExportState(
            status: String,
            mode: String?,
            pendingRows: Long,
            oldestPendingAt: String?,
            nextRetryAt: String?,
            lastSuccessAt: String?,
            lastErrorAt: String?,
            lastError: String?,
            exportedRowsDelta: Long
        ) {
            state = state.copy(
                status = status,
                mode = mode,
                pendingRows = pendingRows,
                oldestPendingAt = oldestPendingAt,
                nextRetryAt = nextRetryAt,
                lastSuccessAt = lastSuccessAt,
                lastErrorAt = lastErrorAt,
                lastError = lastError,
                exportedRowsTotal = state.exportedRowsTotal + exportedRowsDelta
            )
        }

        override fun recordInfluxEvent(
            eventType: String,
            message: String?,
            batchCount: Int?,
            fromHistoryId: Long?,
            toHistoryId: Long?
        ) {
            eventFailure?.let { throw it }
            influxEvents += InfluxEvent(eventType, message, batchCount, fromHistoryId, toHistoryId)
        }

        fun setNextRetryAt(nextRetryAt: String) {
            state = state.copy(
                status = "backoff",
                nextRetryAt = nextRetryAt,
                lastErrorAt = "2026-06-15T12:00:00Z",
                lastError = "offline"
            )
        }

        fun addRow(row: InfluxPendingHistoryRow) {
            rows += row
        }

        fun cursor(fieldKey: String): InfluxCursor = cursors[fieldKey] ?: InfluxCursor(fieldKey, 0)
    }

    private data class InfluxEvent(
        val eventType: String,
        val message: String?,
        val batchCount: Int?,
        val fromHistoryId: Long?,
        val toHistoryId: Long?
    )

    private class FakeClock(var now: String = "2026-06-15T12:00:00Z") : Clock {
        override fun nowIso(): String = now
        override fun elapsedRealtimeMs(): Long = 1_000
    }
}
