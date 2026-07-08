package com.bydcollector.collector.maintenance

import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveStorageManagerTest {
    @Test
    fun compressArchiveDirectoryWritesTmpThenZipAndDeletesRawDirectoryOnSuccess() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive")
        val raw = File(archiveRoot, "bydcollector_telemetry_20260707_120000").apply { mkdirs() }
        File(raw, "bydcollector_telemetry.db").writeText("db")
        File(raw, "bydcollector_telemetry.db-wal").writeText("wal")
        val manager = ArchiveStorageManager(archiveRoot, active)

        assertTrue(manager.compressRawArchiveDirectory(raw))

        val zip = File(archiveRoot, "bydcollector_telemetry_20260707_120000.zip")
        assertTrue(zip.exists())
        assertFalse(File(archiveRoot, "bydcollector_telemetry_20260707_120000.zip.tmp").exists())
        assertFalse(raw.exists())
        ZipFile(zip).use { archive ->
            assertTrue(archive.getEntry("bydcollector_telemetry.db") != null)
            assertTrue(archive.getEntry("bydcollector_telemetry.db-wal") != null)
        }
    }

    @Test
    fun scanIgnoresTmpZipAsValidArchive() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        File(archiveRoot, "bydcollector_telemetry_20260707_120000.zip.tmp").writeText("partial")
        val manager = ArchiveStorageManager(archiveRoot, active)

        val snapshot = manager.snapshot(limitBytes = 1024L)

        assertEquals(1, snapshot.entries.size)
        assertEquals(ArchiveEntryStatus.TMP, snapshot.entries.single().status)
        assertFalse(snapshot.entries.single().deletable)
    }

    @Test
    fun retentionDeletesOldestArchivesButKeepsNewestArchive() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val oldest = File(archiveRoot, "bydcollector_telemetry_20260707_010000.zip").apply {
            writeText("old-old-old")
            setLastModified(1_000L)
        }
        val newest = File(archiveRoot, "bydcollector_telemetry_20260707_020000.zip").apply {
            writeText("new-new-new")
            setLastModified(2_000L)
        }
        val manager = ArchiveStorageManager(archiveRoot, active)

        assertEquals(1, manager.enforceRetention(limitBytes = newest.length()))

        assertFalse(oldest.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun deleteByIdRejectsPathTraversalAndOnlyDeletesArchiveRootChildren() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val valid = File(archiveRoot, "bydcollector_telemetry_20260707_120000.zip").apply { writeText("zip") }
        val outside = File(root, "bydcollector_telemetry_20260707_130000.zip").apply { writeText("outside") }
        val manager = ArchiveStorageManager(archiveRoot, active)

        assertEquals(1, manager.deleteArchiveIds(listOf("../${outside.name}", valid.name)))

        assertFalse(valid.exists())
        assertTrue(outside.exists())
    }
}
