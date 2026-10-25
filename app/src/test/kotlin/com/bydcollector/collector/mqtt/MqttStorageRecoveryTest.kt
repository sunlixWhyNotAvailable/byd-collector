package com.bydcollector.collector.mqtt

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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
import java.util.UUID
import java.net.ServerSocket
import org.json.JSONObject
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MqttStorageRecoveryTest {
    @Test fun countFailureIsNotAnEmptyQueue() {
        val context = RuntimeEnvironment.getApplication()
        val helper = TelemetryDatabaseHelper(context, "mqtt_count_${UUID.randomUUID()}.db")
        val store = TelemetryStore(context, helper, operationalEventJournal = OperationalEventJournal(context))
        val config = config()
        val coordinator = MqttPublishCoordinator(PahoMqttClientFacade(), store, store,
            HaMqttMessageFactory(store, { config }), { config })
        try {
            helper.writableDatabase.execSQL("ALTER TABLE mqtt_outbox RENAME TO mqtt_outbox_saved")
            assertFailsWith<SQLiteException> { store.pendingCount() }
            assertFailsWith<SQLiteException> { coordinator.retryDelayMs() }
            helper.writableDatabase.execSQL("ALTER TABLE mqtt_outbox_saved RENAME TO mqtt_outbox")
            coordinator.resetStorageRecovery()
            assertTrue(coordinator.runWithStorageRecovery("flush") { coordinator.flushPending() }.ok)
            assertEquals(0L, store.pendingCount())
        } finally { store.close(); context.deleteDatabase(helper.databaseName) }
    }

    @Test fun exclusiveSqliteLockGetsThreeTimedRetriesAndPreservesPendingMessages() {
        val context = RuntimeEnvironment.getApplication()
        val name = "mqtt_lock_${UUID.randomUUID()}.db"
        val helper = TelemetryDatabaseHelper(context, name)
        var now = Instant.parse("2026-10-25T12:00:00Z")
        val clock = object : Clock {
            override fun nowIso() = now.toString()
            override fun elapsedRealtimeMs() = now.toEpochMilli()
        }
        val store = TelemetryStore(context, helper, clock, operationalEventJournal = OperationalEventJournal(context))
        val config = config()
        val diagnostics = mutableListOf<String>()
        val coordinator = MqttPublishCoordinator(PahoMqttClientFacade(), store, store,
            HaMqttMessageFactory(store, { config }, clock), { config }, clock = clock,
            storageDiagnostic = { _, detail -> diagnostics += detail })
        try {
            store.upsertPending(HaMqttMessage("bydcollector/state/battery", "{\"soc\":55}", true), "state", 1)
            val pending = store.pendingMessages(10)
            assertEquals(1, pending.size)
            helper.writableDatabase.disableWriteAheadLogging()
            helper.writableDatabase.rawQuery("PRAGMA busy_timeout=1", emptyArray()).use { it.moveToFirst() }
            SQLiteDatabase.openDatabase(helper.writableDatabase.path, null, SQLiteDatabase.OPEN_READWRITE).use { blocker ->
                blocker.beginTransaction()
                try {
                    assertEquals(30_000L, coordinator.retryDelayMs())
                    repeat(5) { assertEquals(30_000L, coordinator.retryDelayMs()) }
                    assertEquals(1, diagnostics.size)
                    for (attempt in 2..3) {
                        now = now.plusSeconds(30)
                        val result = coordinator.runWithStorageRecovery("flush") { coordinator.flushPending() }
                        assertEquals("mqtt_sqlite_busy", result.category)
                        assertTrue(result.retryable)
                        assertEquals(30_000L, coordinator.retryDelayMs())
                    }
                    now = now.plusSeconds(30)
                    val terminal = coordinator.runWithStorageRecovery("flush") { coordinator.flushPending() }
                    assertEquals("mqtt_storage_error", terminal.category)
                    assertFalse(terminal.retryable)
                    assertEquals(4, diagnostics.size)
                    assertContains(diagnostics.first(), "mqtt_retry_preparation")
                    assertTrue(diagnostics.all { "SQLite" in it })
                } finally { blocker.endTransaction() }
            }
            assertEquals(pending, store.pendingMessages(10))
            assertEquals(1L, store.pendingCount())
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun lockedStatusSurvivesResync() {
        val context = RuntimeEnvironment.getApplication()
        val name = "mqtt_status_${UUID.randomUUID()}.db"
        val helper = TelemetryDatabaseHelper(context, name)
        var now = Instant.parse("2026-10-25T12:00:00Z")
        val clock = object : Clock {
            override fun nowIso() = now.toString()
            override fun elapsedRealtimeMs() = now.toEpochMilli()
        }
        val store = TelemetryStore(context, helper, clock, operationalEventJournal = OperationalEventJournal(context))
        // A real closed local port leaves the recovered messages in SQLite; no broker result is substituted.
        val port = ServerSocket(0).use { it.localPort }
        val config = config().copy(port = port)
        val coordinator = MqttPublishCoordinator(PahoMqttClientFacade(), store, store,
            HaMqttMessageFactory(store, { config }, clock), { config }, clock = clock)
        val status = HaMqttStatus("online", false, "polling_error", "not_authorized", "unavailable",
            null, "telemetry_reader_unavailable", mapOf("battery" to "enabled"))
        try {
            helper.writableDatabase.disableWriteAheadLogging()
            helper.writableDatabase.rawQuery("PRAGMA busy_timeout=1", emptyArray()).use { it.moveToFirst() }
            SQLiteDatabase.openDatabase(helper.writableDatabase.path, null, SQLiteDatabase.OPEN_READWRITE).use { blocker ->
                blocker.beginTransaction()
                try {
                    val blocked = coordinator.runWithStorageRecovery("status") {
                        coordinator.queueStatusAndFlush(status, force = true)
                    }
                    assertEquals("mqtt_sqlite_busy", blocked.category)
                } finally { blocker.endTransaction() }
            }
            now = now.plusSeconds(30)
            coordinator.runWithStorageRecovery("retry") { coordinator.flushPending(force = true) }
            val pending = store.pendingMessages(1000).single { it.message.topic == "bydcollector/status" }
            val payload = JSONObject(pending.message.payload)
            assertFalse(payload.getBoolean("polling"))
            assertEquals(status.collectorStatus, payload.getString("collector_status"))
            assertEquals(status.lastError, payload.getString("last_error"))
            assertEquals(status.adb, payload.getString("adb"))
            assertEquals(status.helper, payload.getString("helper"))
        } finally {
            coordinator.disconnectForMaintenance()
            store.close()
            context.deleteDatabase(name)
        }
    }

    private fun config() = HaMqttConfig(true, true, "127.0.0.1", 1883, null, null,
        "collector-storage-check", "bydcollector", "homeassistant", enabledCategories = setOf("battery"))
}
