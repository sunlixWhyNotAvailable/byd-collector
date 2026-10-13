package com.bydcollector.collector.maintenance

import android.app.Application
import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.service.CollectorSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MainArchiveEnergyRecoveryTest {
    @Test fun cutoverPreservesQueue() {
        val context = RuntimeEnvironment.getApplication()
        val source = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
        val settings = CollectorSettings(context)
        val expected = TelemetryDatabaseHelper(context).use { helper ->
            listOf(receipt(10), receipt(20, nullableInput = true))
                .forEach { helper.writableDatabase.insertOrThrow(TABLE, null, it) }
            rows(helper.readableDatabase)
        }
        assertEquals(2L, DeferredEnergyArchive.count(source))
        val sourceNames = DatabaseArchiveManager.sidecarFiles(source).filter { it.isFile }.map { it.name }.toSet()
        val archived = DatabaseArchiveManager.archive(source, File(context.filesDir, "db_archive"), "energy_recovery")
        assertTrue(archived.ok, archived.error)
        val oldFile = File(archived.archiveDirectory, source.name)
        val oldBytes = oldFile.readBytes()
        val journal = StorageCutoverJournal(TelemetryDatabaseHelper.SCHEMA_FAMILY,
            archived.archiveDirectory.absolutePath, StorageCutoverRecovery.PHASE_CREATING,
            StorageFormat.COMPACT_V2, manual = true, sourceNames = sourceNames,
            sourceDatabaseName = source.name, pendingEnergyCount = 2L)
        assertTrue(settings.setStorageCutoverJournal(journal))
        assertEquals(journal, settings.storageCutoverJournal())
        TelemetryDatabaseHelper(context).use { it.writableDatabase }

        // Durable CREATING input is what a process restart sees before receipt import.
        assertTrue(StorageFormatCutoverCoordinator(context, settings).ensureMainReady())
        assertNull(settings.storageCutoverJournal())
        TelemetryDatabaseHelper(context).use { helper ->
            assertEquals(expected, rows(helper.readableDatabase))
            DeferredEnergyArchive.restore(oldFile, helper.writableDatabase, 2L)
            assertEquals(expected, rows(helper.readableDatabase))
            helper.readableDatabase.rawQuery("SELECT voltage FROM $TABLE WHERE id = 10", emptyArray()).use {
                assertTrue(it.moveToFirst())
                assertEquals(401.12345678912345, it.getDouble(0))
            }
        }
        // A committed import with the old journal still present is safe to repeat on restart.
        assertTrue(settings.setStorageCutoverJournal(journal.copy(phase = StorageCutoverRecovery.PHASE_VERIFYING)))
        assertTrue(StorageFormatCutoverCoordinator(context, settings).ensureMainReady())
        TelemetryDatabaseHelper(context).use { assertEquals(expected, rows(it.readableDatabase)) }
        assertContentEquals(oldBytes, oldFile.readBytes())

        // Real conflicting persisted data, not an injected dependency failure.
        TelemetryDatabaseHelper(context, "energy-conflict.db").use { helper ->
            val conflicting = receipt(20, nullableInput = true).apply { put("last_error", "different source") }
            helper.writableDatabase.insertOrThrow(TABLE, null, conflicting)
            val before = rows(helper.readableDatabase)
            assertFailsWith<IllegalStateException> {
                DeferredEnergyArchive.restore(oldFile, helper.writableDatabase, 2L)
            }
            assertEquals(before, rows(helper.readableDatabase))
        }
    }

    @Test fun unreadableSourceIsPreserved() {
        val context = RuntimeEnvironment.getApplication()
        val source = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
        source.parentFile!!.mkdirs()
        source.writeBytes("not a SQLite database".toByteArray())
        assertFails { DeferredEnergyArchive.count(source) }
        val archived = DatabaseArchiveManager.archive(source, File(context.filesDir, "db_archive"), "unreadable")
        assertTrue(archived.ok)
        val oldFile = File(archived.archiveDirectory, source.name)
        val oldBytes = oldFile.readBytes()
        val settings = CollectorSettings(context)
        // Persisted manual-cutover input with an uninspectable source; live workers are outside this test.
        assertTrue(settings.setStorageCutoverJournal(StorageCutoverJournal(
            TelemetryDatabaseHelper.SCHEMA_FAMILY, archived.archiveDirectory.absolutePath,
            StorageCutoverRecovery.PHASE_CREATING, StorageFormat.UNKNOWN, manual = true,
            sourceNames = setOf(source.name), sourceDatabaseName = source.name, pendingEnergyCount = -1L)))
        TelemetryDatabaseHelper(context).use { it.writableDatabase }
        assertTrue(StorageFormatCutoverCoordinator(context, settings).ensureMainReady())
        assertNull(settings.storageCutoverJournal())
        assertEquals(DeferredEnergyArchive.UNVERIFIED_WARNING, CollectorSettings(context).dbMaintenanceStatus().warning)
        assertContentEquals(oldBytes, oldFile.readBytes())
        assertEquals(0L, DeferredEnergyArchive.count(source))
    }

    private fun receipt(id: Long, nullableInput: Boolean = false) = ContentValues().apply {
        put("id", id)
        put("source_identity", "archive-input-$id")
        put("observed_at", "2026-10-12T08:00:00Z")
        put("boot_id", "same-boot")
        put("elapsed_ms", id * 1_000L)
        if (nullableInput) putNull("voltage") else put("voltage", 401.12345678912345)
        put("current_a", -12.5)
        put("power_on", 1L)
        put("gun_disconnected", 1L)
        if (nullableInput) putNull("external_charging") else put("external_charging", 0L)
        put("retry_count", 3L)
        put("next_retry_at_ms", 1_792_000_030_000L)
        put("last_error", "stored diagnostic $id")
        put("created_at_ms", 1_792_000_000_000L)
    }

    private fun rows(db: SQLiteDatabase): List<ContentValues> = db.rawQuery(
        "SELECT * FROM $TABLE ORDER BY id", emptyArray()
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) })
    } }

    companion object { private const val TABLE = "energy_deferred_receipts" }
}
