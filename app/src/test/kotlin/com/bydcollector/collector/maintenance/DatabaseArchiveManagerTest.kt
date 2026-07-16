package com.bydcollector.collector.maintenance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseArchiveManagerTest {
    @Test
    fun sidecarFilesReturnDatabaseWalShmJournal() {
        val database = File("bydcollector_telemetry.db")

        assertEquals(
            listOf(
                File("bydcollector_telemetry.db"),
                File("bydcollector_telemetry.db-wal"),
                File("bydcollector_telemetry.db-shm"),
                File("bydcollector_telemetry.db-journal")
            ),
            DatabaseArchiveManager.sidecarFiles(database)
        )
    }

    @Test
    fun archiveMovesOnlyExistingSidecars() {
        val root = createTempDirectory().toFile()
        val database = File(root, "bydcollector_telemetry.db").apply { writeText("db") }
        val wal = File(root, "bydcollector_telemetry.db-wal").apply { writeText("wal") }
        val archiveRoot = File(root, "archive")

        val result = DatabaseArchiveManager.archive(database, archiveRoot, "20260629_180000")

        assertTrue(result.ok, result.error)
        assertTrue(result.rollbackOk)
        assertEquals(File(archiveRoot, "bydcollector_telemetry_20260629_180000"), result.archiveDirectory)
        assertEquals(listOf(File(result.archiveDirectory, database.name), File(result.archiveDirectory, wal.name)), result.movedFiles)
        assertFalse(database.exists())
        assertFalse(wal.exists())
        assertTrue(File(result.archiveDirectory, database.name).exists())
        assertTrue(File(result.archiveDirectory, wal.name).exists())
        assertFalse(File(result.archiveDirectory, "bydcollector_telemetry.db-shm").exists())
    }

    @Test
    fun archiveFailsWhenDatabaseIsMissing() {
        val root = createTempDirectory().toFile()
        val database = File(root, "bydcollector_telemetry.db")
        val archiveRoot = File(root, "archive")

        val result = DatabaseArchiveManager.archive(database, archiveRoot, "20260629_180000")

        assertFalse(result.ok)
        assertTrue(result.rollbackOk)
        assertEquals(emptyList(), result.movedFiles)
        assertFalse(result.archiveDirectory.exists())
    }

    @Test
    fun archiveFailsWhenTimestampDirectoryAlreadyExists() {
        val root = createTempDirectory().toFile()
        val database = File(root, "bydcollector_telemetry.db").apply { writeText("db") }
        val archiveRoot = File(root, "archive")
        File(archiveRoot, "bydcollector_telemetry_20260629_180000").mkdirs()

        val result = DatabaseArchiveManager.archive(database, archiveRoot, "20260629_180000")

        assertFalse(result.ok)
        assertTrue(result.rollbackOk)
        assertEquals(emptyList(), result.movedFiles)
        assertTrue(database.exists())
    }

    @Test
    fun debugDatabaseUsesItsOwnArchivePrefix() {
        val root = createTempDirectory().toFile()
        val database = File(root, "bydcollector_debug_round_robin.db").apply { writeText("debug") }

        val result = DatabaseArchiveManager.archive(database, File(root, "archive"), "20260713_120000")

        assertTrue(result.ok, result.error)
        assertEquals(
            File(root, "archive/bydcollector_debug_round_robin_20260713_120000"),
            result.archiveDirectory
        )
    }

    @Test
    fun rollbackAttemptsEveryMovedFileAfterRestoreFailure() {
        val database = File("active/bydcollector_telemetry.db")
        val movedFiles = listOf(
            File("archive/bydcollector_telemetry.db"),
            File("archive/bydcollector_telemetry.db-wal"),
            File("archive/bydcollector_telemetry.db-shm")
        )
        val attempts = mutableListOf<String>()

        val rollbackOk = DatabaseArchiveManager.rollback(database, movedFiles) { source, _ ->
            attempts += source.name
            source.name != "bydcollector_telemetry.db-wal"
        }

        assertFalse(rollbackOk)
        assertEquals(
            listOf(
                "bydcollector_telemetry.db-shm",
                "bydcollector_telemetry.db-wal",
                "bydcollector_telemetry.db"
            ),
            attempts
        )
    }
}
