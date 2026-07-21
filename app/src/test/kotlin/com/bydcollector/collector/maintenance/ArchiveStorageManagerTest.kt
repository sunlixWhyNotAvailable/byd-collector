package com.bydcollector.collector.maintenance

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        val manager = manager(archiveRoot, active)

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
        val manager = manager(archiveRoot, active)

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
        val manager = manager(archiveRoot, active)

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
        val manager = manager(archiveRoot, active)

        assertEquals(1, manager.deleteArchiveIds(listOf("../${outside.name}", valid.name)))

        assertFalse(valid.exists())
        assertTrue(outside.exists())
    }

    @Test
    fun snapshotAndCompressionIncludeDebugArchivesAndBothActiveDatabases() {
        val root = createTempDirectory().toFile()
        val main = File(root, "bydcollector_telemetry.db").apply { writeText("main") }
        val debug = File(root, "bydcollector_debug_round_robin.db").apply { writeText("debug") }
        val archiveRoot = File(root, "db_archive")
        val raw = File(archiveRoot, "bydcollector_debug_round_robin_20260713_120000").apply { mkdirs() }
        File(raw, debug.name).writeText("archived-debug")
        val manager = ArchiveStorageManager(archiveRoot, main, debug)

        assertTrue(manager.compressRawArchiveDirectory(raw))
        val snapshot = manager.snapshot(1024L)

        assertEquals(main.length(), snapshot.mainDatabaseSizeBytes)
        assertEquals(debug.length(), snapshot.debugDatabaseSizeBytes)
        assertEquals(main.length() + debug.length(), snapshot.activeDatabaseSizeBytes)
        assertEquals(1, snapshot.entries.size)
        assertTrue(snapshot.entries.single().id.startsWith(ArchiveStorageManager.DEBUG_ARCHIVE_PREFIX))
    }

    @Test
    fun retentionKeepsNewestArchivePerDatabaseFamily() {
        val root = createTempDirectory().toFile()
        val main = File(root, "bydcollector_telemetry.db").apply { writeText("main") }
        val debug = File(root, "bydcollector_debug_round_robin.db").apply { writeText("debug") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val oldMain = archive(archiveRoot, "bydcollector_telemetry_20260713_010000.zip", 1_000L)
        val newMain = archive(archiveRoot, "bydcollector_telemetry_20260713_020000.zip", 2_000L)
        val oldDebug = archive(archiveRoot, "bydcollector_debug_round_robin_20260713_010000.zip", 1_500L)
        val newDebug = archive(archiveRoot, "bydcollector_debug_round_robin_20260713_020000.zip", 2_500L)
        val manager = ArchiveStorageManager(archiveRoot, main, debug)

        assertEquals(2, manager.enforceRetention(limitBytes = 1L))

        assertFalse(oldMain.exists())
        assertFalse(oldDebug.exists())
        assertTrue(newMain.exists())
        assertTrue(newDebug.exists())
    }

    @Test
    fun retentionSkipsProtectedArchives() {
        val root = createTempDirectory().toFile()
        val main = File(root, "bydcollector_telemetry.db").apply { writeText("main") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val leased = archive(archiveRoot, "bydcollector_telemetry_20260713_010000.zip", 1_000L)
        val deletable = archive(archiveRoot, "bydcollector_telemetry_20260713_020000.zip", 2_000L)
        val newest = archive(archiveRoot, "bydcollector_telemetry_20260713_030000.zip", 3_000L)
        val manager = ArchiveStorageManager(
            archiveRoot,
            main,
            File(root, "bydcollector_debug_round_robin.db"),
            isRetentionProtected = { it == leased.name }
        )

        assertEquals(1, manager.enforceRetention(limitBytes = leased.length() + newest.length()))

        assertTrue(leased.exists())
        assertFalse(deletable.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun retentionRechecksProtectionImmediatelyBeforeDelete() {
        val root = createTempDirectory().toFile()
        val main = File(root, "bydcollector_telemetry.db").apply { writeText("main") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val leasedDuringRetention = archive(archiveRoot, "bydcollector_telemetry_20260713_010000.zip", 1_000L)
        val deletable = archive(archiveRoot, "bydcollector_telemetry_20260713_020000.zip", 2_000L)
        val newest = archive(archiveRoot, "bydcollector_telemetry_20260713_030000.zip", 3_000L)
        var leaseActive = false
        val manager = ArchiveStorageManager(
            archiveRoot,
            main,
            File(root, "bydcollector_debug_round_robin.db"),
            isRetentionProtected = { it == leasedDuringRetention.name && leaseActive }
        )

        assertEquals(
            1,
            manager.enforceRetention(limitBytes = leasedDuringRetention.length() + newest.length()) { status ->
                if (status.itemId == leasedDuringRetention.name) leaseActive = true
            }
        )

        assertTrue(leasedDuringRetention.exists())
        assertFalse(deletable.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun shareZipResolutionIsFreshStrictAndPreservesRequestedOrder() {
        val root = createTempDirectory().toFile()
        val main = File(root, "bydcollector_telemetry.db").apply { writeText("main") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val first = zip(archiveRoot, "bydcollector_telemetry_20260713_010000.zip")
        val second = zip(archiveRoot, "bydcollector_debug_round_robin_20260713_020000.zip")
        val manager = manager(archiveRoot, main)

        assertEquals(listOf(second, first), manager.resolveShareZipFiles(listOf(second.name, first.name)))
        first.delete()
        assertNull(manager.resolveShareZipFiles(listOf(first.name)))
    }

    @Test
    fun shareZipResolutionRejectsInvalidSelections() {
        val root = createTempDirectory().toFile()
        val main = File(root, "bydcollector_telemetry.db").apply { writeText("main") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val valid = zip(archiveRoot, "bydcollector_telemetry_20260713_010000.zip")
        val raw = File(archiveRoot, "bydcollector_telemetry_20260713_020000").apply { mkdirs() }
        val tmp = File(archiveRoot, "bydcollector_telemetry_20260713_030000.zip.tmp").apply { writeText("tmp") }
        val empty = File(archiveRoot, "bydcollector_telemetry_20260713_040000.zip").apply { createNewFile() }
        val corrupt = File(archiveRoot, "bydcollector_telemetry_20260713_050000.zip").apply { writeText("not a zip") }
        val nonZip = File(archiveRoot, "bydcollector_telemetry_20260713_060000.txt").apply { writeText("text") }
        val zipDirectory = File(archiveRoot, "bydcollector_telemetry_20260713_070000.zip").apply { mkdirs() }
        val manager = manager(archiveRoot, main)

        listOf(
            emptyList(),
            listOf(valid.name, valid.name),
            listOf("../${valid.name}"),
            listOf("missing.zip"),
            listOf(raw.name),
            listOf(tmp.name),
            listOf(empty.name),
            listOf(corrupt.name),
            listOf(nonZip.name),
            listOf(zipDirectory.name)
        ).forEach { ids -> assertNull(manager.resolveShareZipFiles(ids), "Expected rejection for $ids") }
    }

    private fun manager(archiveRoot: File, main: File): ArchiveStorageManager {
        return ArchiveStorageManager(archiveRoot, main, File(main.parentFile, "bydcollector_debug_round_robin.db"))
    }

    private fun archive(root: File, name: String, modifiedAt: Long): File {
        return File(root, name).apply {
            writeText("archive-$name")
            setLastModified(modifiedAt)
        }
    }

    private fun zip(root: File, name: String): File {
        return File(root, name).also { file ->
            ZipOutputStream(file.outputStream()).use { archive ->
                archive.putNextEntry(ZipEntry("database.db"))
                archive.write("database".toByteArray())
                archive.closeEntry()
            }
        }
    }
}
