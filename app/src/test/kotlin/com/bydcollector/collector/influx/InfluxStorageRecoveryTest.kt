package com.bydcollector.collector.influx

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class InfluxStorageRecoveryTest {
    @Test fun oversizedPointRetainsCursorAndQueueAcrossReopenWithoutHttp() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "influx_oversize_${UUID.randomUUID()}.db"
        val field = NormalizedFieldCatalog.chargingType
        val fields = setOf(field.fieldKey)
        var store = TelemetryStore(context, TelemetryDatabaseHelper(context, databaseName),
            operationalEventJournal = OperationalEventJournal(context))
        try {
            listOf("界".repeat(250_000), "AC").forEachIndexed { index, text ->
                store.applyNormalizedObservations(listOf(NormalizedObservation(
                    field, NormalizedValue(NormalizedValueType.TEXT, text = text), NormalizedQuality.OK,
                    null, field.sourceKeys.firstOrNull(), "2026-10-25T00:00:0${index}Z"
                )))
            }
            val before = store.pendingInfluxRows(fields, 10).map { it.id }
            assertEquals(2L, store.refreshInfluxPendingSummary(fields).rows)
            // A real listening socket observes access; it never fabricates an HTTP result.
            ServerSocket(0).use { listener ->
                val config = InfluxConfig(true, "127.0.0.1", listener.localPort, "test", null, null, "state", setOf("battery"))
                val coordinator = InfluxExportCoordinator(store, HttpInfluxClient(), { config })
                val result = coordinator.startExport()
                assertEquals("influx_point_too_large", result.category)
                assertFalse(result.retryable)
                assertNull(coordinator.retryDelayMs())
                assertEquals(result.message, store.influxExportState().lastError)
                assertEquals(2L, store.influxExportState().pendingRows)
                listener.soTimeout = 100
                assertFailsWith<SocketTimeoutException> { listener.accept().use { error("Unexpected HTTP connection") } }
            }
            store.close()
            store = TelemetryStore(context, TelemetryDatabaseHelper(context, databaseName),
                operationalEventJournal = OperationalEventJournal(context))
            assertEquals(before, store.pendingInfluxRows(fields, 10).map { it.id })
            assertEquals(0L, store.influxExportState().exportedRowsTotal)
            assertEquals("error", store.influxExportState().status)
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun normalizedHistoryCursorAndCappedPagingSurviveReopen() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "influx_batch_policy_${UUID.randomUUID()}.db"
        val field = NormalizedFieldCatalog.soc
        val fields = setOf(field.fieldKey)
        var store = TelemetryStore(
            context,
            TelemetryDatabaseHelper(context, databaseName),
            operationalEventJournal = OperationalEventJournal(context)
        )
        fun addSoc(value: Double, observedAt: String) {
            val write = store.applyNormalizedObservations(
                listOf(
                    NormalizedObservation(
                        field = field,
                        value = NormalizedValue(NormalizedValueType.NUMBER, number = value),
                        quality = NormalizedQuality.OK,
                        sourcePollId = null,
                        sourceKey = field.sourceKeys.firstOrNull(),
                        observedAt = observedAt
                    )
                )
            )
            assertEquals(1, write.historyInsertedCount)
        }

        try {
            addSoc(50.0, "2026-10-12T12:00:00Z")
            addSoc(51.0, "2026-10-12T12:01:00Z")
            assertEquals(2L, store.refreshInfluxPendingSummary(fields).rows)
            val firstRows = store.pendingInfluxRows(fields, limit = 10)
            assertEquals(2, firstRows.size)
            assertTrue(firstRows[0].id < firstRows[1].id)

            store.updateInfluxCursorSuccess(field.fieldKey, firstRows.first().id, "2026-10-12T12:02:00Z")
            assertFalse(store.pendingInfluxSummary(fields).complete)
            assertEquals(1L, store.refreshInfluxPendingSummary(fields).rows)

            addSoc(52.0, "2026-10-12T12:03:00Z")
            assertFalse(store.pendingInfluxSummary(fields).complete)
            assertEquals(2L, store.refreshInfluxPendingSummary(fields).rows)
            val oneRowBatch = store.pendingInfluxRows(fields, limit = 1)
            assertEquals(listOf(firstRows[1].id), oneRowBatch.map { it.id })
            val twoRowBatch = store.pendingInfluxRows(fields, limit = 2)
            assertEquals(2, twoRowBatch.size)
            assertTrue(twoRowBatch[0].id < twoRowBatch[1].id)

            store.close()
            store = TelemetryStore(
                context,
                TelemetryDatabaseHelper(context, databaseName),
                operationalEventJournal = OperationalEventJournal(context)
            )
            assertFalse(store.pendingInfluxSummary(fields).complete)
            assertEquals(2L, store.refreshInfluxPendingSummary(fields).rows)
            val reopenedBatch = store.pendingInfluxRows(fields, limit = 2)
            assertEquals(twoRowBatch.map { it.id }, reopenedBatch.map { it.id })
            assertEquals(listOf(51.0, 52.0), reopenedBatch.map { requireNotNull(it.valueNumber) })
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun rejectionAndCursorCommitTogether() {
        val context = RuntimeEnvironment.getApplication()
        val helper = TelemetryDatabaseHelper(context)
        val store = TelemetryStore(context, helper, operationalEventJournal = OperationalEventJournal(context))
        val time = "2026-10-12T12:00:00Z"
        store.skipInfluxRow("battery_soc", 10, "field_type_conflict", time)
        val db = helper.writableDatabase
        fun scalar(sql: String): Long = db.rawQuery(sql, emptyArray()).use { assertTrue(it.moveToFirst()); it.getLong(0) }
        assertEquals(10L, scalar("SELECT last_exported_history_id FROM influx_export_cursor WHERE field_key='battery_soc'"))
        assertEquals(1L, scalar("SELECT count(*) FROM influx_export_events WHERE from_history_id=10 AND message='field=battery_soc reason=field_type_conflict'"))
        // An incompatible persisted schema must not leave the preceding event insert committed alone.
        db.execSQL("ALTER TABLE influx_export_cursor RENAME COLUMN last_error TO legacy_error")
        assertFails { store.skipInfluxRow("battery_soc", 20, "unable_to_parse", time) }
        assertEquals(10L, scalar("SELECT last_exported_history_id FROM influx_export_cursor WHERE field_key='battery_soc'"))
        assertEquals(1L, scalar("SELECT count(*) FROM influx_export_events"))
        db.execSQL("ALTER TABLE influx_export_cursor RENAME COLUMN legacy_error TO last_error")
        store.close()
        TelemetryDatabaseHelper(context).use { reopened ->
            reopened.readableDatabase.rawQuery("SELECT from_history_id, message FROM influx_export_events", emptyArray()).use {
                assertTrue(it.moveToFirst())
                assertEquals(10L, it.getLong(0))
                assertEquals("field=battery_soc reason=field_type_conflict", it.getString(1))
            }
        }
    }

    @Test fun realSqliteLockRetriesAndRecovers() {
        val context = RuntimeEnvironment.getApplication()
        val helper = TelemetryDatabaseHelper(context)
        var now = Instant.parse("2026-10-12T12:00:00Z")
        val clock = object : Clock {
            override fun nowIso() = now.toString()
            override fun elapsedRealtimeMs() = now.toEpochMilli()
        }
        val store = TelemetryStore(context, helper, clock, operationalEventJournal = OperationalEventJournal(context))
        val config = InfluxConfig(true, "127.0.0.1", 8086, "test", null, null, "state", setOf("battery"))
        store.ensureInfluxCursors(influxEffectiveFields(config))
        helper.writableDatabase.rawQuery("PRAGMA busy_timeout=1", emptyArray()).use { it.moveToFirst() }
        val coordinator = InfluxExportCoordinator(store, HttpInfluxClient(), { config }, clock)
        // A second native SQLite connection really owns the write lock. No HTTP server or substituted result.
        SQLiteDatabase.openDatabase(helper.writableDatabase.path, null, SQLiteDatabase.OPEN_READWRITE).use { blocker ->
            blocker.beginTransaction()
            try {
                val failure = coordinator.startExport()
                assertFalse(failure.ok)
                assertEquals("influx_sqlite_busy", failure.category)
                assertTrue(failure.retryable)
                assertEquals(30_000L, coordinator.retryDelayMs())
                assertEquals("influx_sqlite_busy", coordinator.runOneCycle().category)
                for (attempt in 2..3) {
                    now = now.plusSeconds(30)
                    assertEquals("influx_sqlite_busy", coordinator.runOneCycle().category)
                    assertEquals(30_000L, coordinator.retryDelayMs())
                }
                now = now.plusSeconds(30)
                assertFalse(coordinator.runOneCycle().retryable)
                assertNull(coordinator.retryDelayMs())
            } finally { blocker.endTransaction() }
        }
        // Empty real queue recovers through a fresh permitted run without any network delivery.
        assertTrue(coordinator.startExport().ok)
        assertNull(coordinator.retryDelayMs())
        assertTrue(coordinator.stopExport().ok)
        assertEquals("stopped", store.influxExportState().status)
        store.close()
    }

    @Test fun onlyRecognizedDataErrorsPermitRowIsolation() {
        listOf(400, 422).forEach { status ->
            val unknown = InfluxActionResult.fail("influx_http_error", "unsupported consistency level", status, InfluxFailureKind.DATA)
            assertFalse(unknown.retryable)
            assertNull(influxDataFormatReason(unknown))
            val known = unknown.copy(message = "partial write: field type conflict")
            assertEquals("field_type_conflict", influxDataFormatReason(known))
            assertTrue(isInfluxLineProtocolDataFailure(known))
        }
        assertTrue(InfluxActionResult.fail("influx_http_error", "HTTP 503", 503, InfluxFailureKind.TRANSPORT).retryable)
    }
}
