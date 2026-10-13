package com.bydcollector.collector.influx

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class InfluxStorageRecoveryTest {
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
