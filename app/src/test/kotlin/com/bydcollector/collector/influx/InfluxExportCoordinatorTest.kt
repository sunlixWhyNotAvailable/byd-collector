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
    fun reExportCreatesMissingCursorsWithoutResettingExisting() {
        val store = FakeInfluxStore(rows = emptyList())
        store.ensureInfluxCursors(setOf("soc"))
        store.updateInfluxCursorSuccess("soc", 99, "2026-06-15T12:00:00Z")
        val coordinator = coordinator(store, FakeInfluxClient())

        val result = coordinator.reExportNewCategories()

        assertTrue(result.ok)
        assertEquals(99, store.cursor("soc").lastExportedHistoryId)
        assertTrue(store.cursors.isNotEmpty())
    }

    private fun coordinator(
        store: FakeInfluxStore,
        client: FakeInfluxClient
    ): InfluxExportCoordinator {
        return InfluxExportCoordinator(
            store = store,
            client = client,
            configProvider = { config() },
            clock = FakeClock()
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
        private val writeResult: InfluxActionResult = InfluxActionResult.ok()
    ) : InfluxClient {
        val writtenLines = mutableListOf<List<String>>()

        override fun test(config: InfluxConfig): InfluxActionResult = InfluxActionResult.ok()

        override fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult {
            writtenLines += lines
            return writeResult
        }
    }

    private class FakeInfluxStore(
        private val rows: List<InfluxPendingHistoryRow>
    ) : InfluxExportStore {
        val cursors = linkedMapOf<String, InfluxCursor>()
        val cursorErrors = linkedMapOf<String, String>()
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

        override fun pendingInfluxRows(fieldKey: String, afterHistoryId: Long, limit: Int): List<InfluxPendingHistoryRow> {
            return rows.filter { it.fieldKey == fieldKey && it.id > afterHistoryId }.take(limit)
        }

        override fun influxCursors(fieldKeys: Set<String>): List<InfluxCursor> {
            ensureInfluxCursors(fieldKeys)
            return fieldKeys.map { cursor(it) }
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

        fun cursor(fieldKey: String): InfluxCursor = cursors[fieldKey] ?: InfluxCursor(fieldKey, 0)
    }

    private class FakeClock : Clock {
        override fun nowIso(): String = "2026-06-15T12:00:00Z"
        override fun elapsedRealtimeMs(): Long = 1_000
    }
}
