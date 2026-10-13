package com.bydcollector.collector.maintenance

import android.app.Application
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
class ArchiveRetryRetentionTest {
    @Test fun failedRawDoesNotBlockReadyArchives() {
        val context = RuntimeEnvironment.getApplication()
        val source = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
        val root = File(context.filesDir, "db_archive")
        var now = System.currentTimeMillis()
        val manager = ArchiveStorageManager(root, source, File(source.parentFile, "secondary.db"), clock = { now })
        val settings = CollectorSettings(context)
        val operation = "real-archive-run"
        assertTrue(settings.beginArchiveStorageOperation(operation, ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION)))
        val observed = mutableListOf<ArchiveStorageJobStatus>()
        val publish: (ArchiveStorageJobStatus) -> Unit = { status ->
            observed += status
            val state = status.itemId?.let { id -> status.phase?.let { phase ->
                ArchiveStorageItemState(id.removeSuffix(".zip"), operation, phase,
                    status.stepIndex, status.stepCount, now, now, error = status.error)
            } }
            assertTrue(settings.publishArchiveStorageProgress(operation, status, state))
        }
        fun archive(name: String): File {
            TelemetryDatabaseHelper(context).use { it.writableDatabase }
            val moved = DatabaseArchiveManager.archive(source, root, name)
            assertTrue(moved.ok, moved.error)
            return moved.archiveDirectory
        }
        val older = archive("older")
        assertTrue(manager.compressRawArchiveDirectory(older, publish))
        val olderZip = File(root, "${older.name}.zip")
        assertTrue(olderZip.setLastModified(now - 60_000))
        val corrupt = File(root, "bydcollector_telemetry_corrupt").apply { mkdirs() }
        val evidence = File(corrupt, source.name).apply { writeText("damaged sqlite input") }
        assertFalse(manager.compressRawArchiveDirectory(corrupt, publish))
        val persistedFailure = CollectorSettings(context).archiveStorageItems().single { it.archiveId == corrupt.name }
        assertEquals(ArchiveStorageItemPhase.FAILED, persistedFailure.phase)
        assertNotNull(persistedFailure.error)

        val newer = archive("newer")
        now = persistedFailure.updatedAtMs + 299_999
        val deferred = manager.deferredRetryArchiveIds(CollectorSettings(context).archiveStorageItems())
        assertEquals(setOf(corrupt.name), deferred)
        observed.clear()
        assertEquals(1, manager.compressPendingRawArchives(publish, deferred))
        assertTrue(observed.none { it.itemId == corrupt.name })
        assertTrue(File(root, "${newer.name}.zip").isFile)
        assertEquals(1, manager.enforceRetention(0, publish))
        assertFalse(olderZip.exists())
        assertTrue(File(root, "${newer.name}.zip").isFile) // newest family archive stays protected
        assertEquals("damaged sqlite input", evidence.readText())
        assertEquals(persistedFailure, CollectorSettings(context).archiveStorageItems().single { it.archiveId == corrupt.name })

        now = persistedFailure.updatedAtMs + 300_000
        assertTrue(manager.deferredRetryArchiveIds(listOf(persistedFailure)).isEmpty())
        // Explicit direct retry is not subject to automatic scan backoff; it still preserves bad input.
        assertFalse(manager.compressRawArchiveDirectory(corrupt, publish))
        assertEquals("damaged sqlite input", evidence.readText())
        TelemetryDatabaseHelper(context).use { it.writableDatabase }
        source.copyTo(evidence, overwrite = true)
        assertTrue(manager.compressRawArchiveDirectory(corrupt, publish))
        assertFalse(corrupt.exists())
        assertTrue(File(root, "${corrupt.name}.zip").isFile)
    }
}
