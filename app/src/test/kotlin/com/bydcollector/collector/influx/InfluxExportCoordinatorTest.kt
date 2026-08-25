package com.bydcollector.collector.influx

import com.bydcollector.collector.data.local.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfluxExportCoordinatorTest {
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
    fun modeUsesTotalRemainingBacklogInsteadOfBoundedBatchSize() {
        val catchUpStore = FakeInfluxStore((1L..1_300L).map { id -> row(id, "soc") })
        val realtimeStore = FakeInfluxStore((1L..1_299L).map { id -> row(id, "soc") })

        coordinator(catchUpStore, FakeInfluxClient()).runOneCycle(force = true)
        coordinator(realtimeStore, FakeInfluxClient()).runOneCycle(force = true)

        assertEquals(1_000L, catchUpStore.influxExportState().pendingRows)
        assertEquals("catch_up", catchUpStore.influxExportState().mode)
        assertEquals(999L, realtimeStore.influxExportState().pendingRows)
        assertEquals("realtime", realtimeStore.influxExportState().mode)
    }

    @Test
    fun reExportCreatesMissingCursorsWithoutResettingExisting() {
        val store = FakeInfluxStore(rows = emptyList())
        store.ensureInfluxCursors(setOf("soc"))
        store.updateInfluxCursorSuccess("soc", 99, "2026-06-15T12:00:00Z")
        val coordinator = coordinator(store, FakeInfluxClient())

        val result = coordinator.reExportNewCategories()

        assertTrue(result.ok)
        assertEquals(99, store.cursor("soc").lastExportedHistoryId)
        assertTrue(store.cursors.keys.containsAll(setOf(
            "soc_internal",
            "battery_remaining_energy_kwh",
            "trip_energy_kwh",
            "cumulative_energy_kwh"
        )))
    }

    private fun coordinator(
        store: FakeInfluxStore,
        client: InfluxClient,
        clock: Clock = FakeClock()
    ): InfluxExportCoordinator {
        return InfluxExportCoordinator(
            store = store,
            client = client,
            configProvider = { config() },
            clock = clock
        )
    }

    private fun config(): InfluxConfig = InfluxConfig(
        enabled = true,
        host = "influx.local",
        port = 8086,
        database = "bydcollector",
        username = null,
        password = null,
        measurement = "byd_state",
        enabledCategories = setOf("battery")
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

        override fun test(config: InfluxConfig): InfluxActionResult = InfluxActionResult.ok()

        override fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult {
            writeCalls += 1
            return results.removeFirst()
        }
    }

    private class FakeInfluxStore(
        rows: List<InfluxPendingHistoryRow>
    ) : InfluxExportStore {
        private val rows = rows.toMutableList()
        val cursors = linkedMapOf<String, InfluxCursor>()
        val cursorErrors = linkedMapOf<String, String>()
        val pendingBatchLimits = mutableListOf<Int>()
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
            fieldKeys.forEach { fieldKey -> cursors.putIfAbsent(fieldKey, InfluxCursor(fieldKey, 0)) }
        }

        override fun pendingInfluxSummary(fieldKeys: Set<String>): InfluxPendingSummary {
            ensureInfluxCursors(fieldKeys)
            val pending = rows.filter { row ->
                fieldKeys.contains(row.fieldKey) && row.id > cursor(row.fieldKey).lastExportedHistoryId
            }
            return InfluxPendingSummary(
                rows = pending.size.toLong(),
                oldestObservedAt = pending.minByOrNull { it.id }?.observedAt
            )
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
        ) = Unit

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

    private class FakeClock(var now: String = "2026-06-15T12:00:00Z") : Clock {
        override fun nowIso(): String = now
        override fun elapsedRealtimeMs(): Long = 1_000
    }
}
