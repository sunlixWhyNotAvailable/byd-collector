package com.bydcollector.collector.maintenance

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageCutoverRecoveryTest {
    @Test
    fun renamedSecondaryTargetCompletesForwardOrRestoresExactLegacySource() {
        val sourceName = "bydcollector_debug_round_robin.db"
        val targetName = "bydcollector_secondary.db"
        val expectedSource = setOf(sourceName, "$sourceName-wal")
        val completed = StorageCutoverRecovery.Snapshot(
            phase = StorageCutoverRecovery.PHASE_VERIFYING,
            sourceFormat = StorageFormat.COMPACT_V2,
            activeFormat = StorageFormat.COMPACT_V2,
            activeDatabaseExists = true,
            activeQuickCheck = true,
            archivedDatabasePresent = true,
            archivedFormat = StorageFormat.UNKNOWN,
            archivedQuickCheck = false,
            archivedSidecarNames = expectedSource,
            expectedSidecarNames = expectedSource,
            unknownArchiveFiles = false,
            manual = true,
            activeDatabaseName = targetName,
            activeSidecarNames = setOf(targetName),
            sourceDatabaseName = sourceName
        )

        assertEquals(StorageCutoverRecovery.Action.COMPLETE_FORWARD, StorageCutoverRecovery.decide(completed))
        assertEquals(
            StorageCutoverRecovery.Action.RESTORE_ARCHIVE,
            StorageCutoverRecovery.decide(
                completed.copy(
                    activeFormat = StorageFormat.UNKNOWN,
                    activeQuickCheck = false,
                    activeSidecarNames = setOf(targetName, "$targetName-wal")
                )
            )
        )
    }

    @Test
    fun automaticUnknownJournalFailsClosedButManualArchiveCanFinishWithWarning() {
        val base = StorageCutoverRecovery.Snapshot(
            phase = StorageCutoverRecovery.PHASE_VERIFYING,
            sourceFormat = StorageFormat.UNKNOWN,
            activeFormat = StorageFormat.COMPACT_V2,
            activeDatabaseExists = true,
            activeQuickCheck = true,
            archivedDatabasePresent = true,
            archivedFormat = StorageFormat.UNKNOWN,
            archivedQuickCheck = false,
            archivedSidecarNames = setOf("debug.db"),
            expectedSidecarNames = setOf("debug.db", "debug.db-wal", "debug.db-shm", "debug.db-journal"),
            unknownArchiveFiles = false,
            activeDatabaseName = "debug.db"
        )
        assertEquals(StorageCutoverRecovery.Action.FAIL_CLOSED, StorageCutoverRecovery.decide(base))
        assertEquals(
            StorageCutoverRecovery.Action.COMPLETE_FORWARD,
            StorageCutoverRecovery.decide(
                base.copy(
                    manual = true,
                    expectedSidecarNames = setOf("debug.db"),
                    activeSidecarNames = setOf("debug.db")
                )
            )
        )
    }

    @Test
    fun restoresPowerLossAfterEachMovedSidecarWithoutDeletingRemainingActiveSidecars() {
        for (movedCount in 1..4) {
            val root = createTempDirectory().toFile()
            val database = File(root, "telemetry.db")
            val archive = File(root, "archive").apply { mkdirs() }
            val activeFiles = DatabaseArchiveManager.sidecarFiles(database).mapIndexed { index, file ->
                file.apply { writeText("sidecar-$index") }
            }
            val movedFiles = activeFiles.take(movedCount).map { source ->
                File(archive, source.name).also { check(source.renameTo(it)) }
            }
            val action = StorageCutoverRecovery.decide(
                StorageCutoverRecovery.Snapshot(
                    phase = StorageCutoverRecovery.PHASE_ARCHIVING,
                    sourceFormat = StorageFormat.LEGACY_V1,
                    activeFormat = StorageFormat.UNKNOWN,
                    activeDatabaseExists = false,
                    activeQuickCheck = false,
                    archivedDatabasePresent = true,
                    archivedFormat = StorageFormat.LEGACY_V1,
                    archivedQuickCheck = true,
                    archivedSidecarNames = movedFiles.map { it.name }.toSet(),
                    expectedSidecarNames = activeFiles.map { it.name }.toSet(),
                    unknownArchiveFiles = false
                )
            )
            assertEquals(StorageCutoverRecovery.Action.RESTORE_ARCHIVE, action, "moved=$movedCount")

            val recovered = StorageCutoverRecovery.execute(
                action = action,
                databaseFile = database,
                movedFiles = movedFiles,
                deleteActive = { true },
                restore = { file, moved -> DatabaseArchiveManager.restore(file, moved) },
                verifyActive = { activeFiles.all { it.isFile } },
                clearJournal = { true },
                cleanupArchive = { archive.deleteRecursively() }
            )
            assertTrue(recovered, "moved=$movedCount")
            assertTrue(activeFiles.all { it.isFile }, "moved=$movedCount")
            assertTrue(activeFiles.withIndex().all { (index, file) -> file.readText() == "sidecar-$index" })
        }
    }

    @Test
    fun acceptsForwardCompleteCompactDatabaseOnlyAfterArchiveVerification() {
        val root = createTempDirectory().toFile()
        val database = File(root, "telemetry.db").apply { writeText("compact") }
        val archiveFile = File(root, "archive").apply { mkdirs() }
            .resolve("telemetry.db")
            .apply { writeText("legacy") }
        val action = StorageCutoverRecovery.decide(
            StorageCutoverRecovery.Snapshot(
                phase = StorageCutoverRecovery.PHASE_VERIFYING,
                sourceFormat = StorageFormat.LEGACY_V1,
                activeFormat = StorageFormat.COMPACT_V2,
                activeDatabaseExists = true,
                activeQuickCheck = true,
                archivedDatabasePresent = true,
                archivedFormat = StorageFormat.LEGACY_V1,
                archivedQuickCheck = true,
                archivedSidecarNames = setOf(archiveFile.name),
                expectedSidecarNames = setOf(database.name, "telemetry.db-wal", "telemetry.db-shm", "telemetry.db-journal"),
                unknownArchiveFiles = false
            )
        )
        assertEquals(StorageCutoverRecovery.Action.COMPLETE_FORWARD, action)
        var pending = false
        assertTrue(
            StorageCutoverRecovery.execute(
                action = action,
                databaseFile = database,
                movedFiles = listOf(archiveFile),
                deleteActive = { false },
                restore = { _, _ -> false },
                verifyActive = { database.readText() == "compact" },
                clearJournal = { true },
                markArchivePending = { pending = true; true }
            )
        )
        assertTrue(pending)
        assertTrue(database.isFile)
    }

    @Test
    fun recoversIntegrityCheckedUnknownDebugDatabaseArchive() {
        val expectedSidecars = setOf(
            "debug.db",
            "debug.db-wal",
            "debug.db-shm",
            "debug.db-journal"
        )
        val intact = StorageCutoverRecovery.Snapshot(
            phase = StorageCutoverRecovery.PHASE_ARCHIVING,
            sourceFormat = StorageFormat.UNKNOWN,
            activeFormat = StorageFormat.UNKNOWN,
            activeDatabaseExists = true,
            activeQuickCheck = true,
            archivedDatabasePresent = false,
            archivedFormat = StorageFormat.ABSENT,
            archivedQuickCheck = false,
            archivedSidecarNames = emptySet(),
            expectedSidecarNames = expectedSidecars,
            unknownArchiveFiles = false,
            manual = true,
            activeDatabaseName = "debug.db",
            activeSidecarNames = expectedSidecars
        )
        val moved = intact.copy(
            activeFormat = StorageFormat.ABSENT,
            activeDatabaseExists = false,
            activeQuickCheck = false,
            archivedDatabasePresent = true,
            archivedFormat = StorageFormat.UNKNOWN,
            archivedQuickCheck = true,
            archivedSidecarNames = setOf("debug.db"),
            activeSidecarNames = expectedSidecars - "debug.db"
        )
        val completed = moved.copy(
            phase = StorageCutoverRecovery.PHASE_VERIFYING,
            activeFormat = StorageFormat.COMPACT_V2,
            activeDatabaseExists = true,
            activeQuickCheck = true,
            archivedSidecarNames = expectedSidecars,
            activeSidecarNames = setOf("debug.db")
        )

        assertEquals(StorageCutoverRecovery.Action.CLEAR_INTACT_SOURCE, StorageCutoverRecovery.decide(intact))
        assertEquals(StorageCutoverRecovery.Action.RESTORE_ARCHIVE, StorageCutoverRecovery.decide(moved))
        assertEquals(StorageCutoverRecovery.Action.COMPLETE_FORWARD, StorageCutoverRecovery.decide(completed))
    }

    @Test
    fun manualRecoveryFailsClosedWhenThePersistedExactSourceSetIsMissingOrPartial() {
        val base = StorageCutoverRecovery.Snapshot(
            phase = StorageCutoverRecovery.PHASE_VERIFYING,
            sourceFormat = StorageFormat.UNKNOWN,
            activeFormat = StorageFormat.COMPACT_V2,
            activeDatabaseExists = true,
            activeQuickCheck = true,
            archivedDatabasePresent = true,
            archivedFormat = StorageFormat.UNKNOWN,
            archivedQuickCheck = false,
            archivedSidecarNames = setOf("debug.db"),
            expectedSidecarNames = setOf("debug.db", "debug.db-wal"),
            unknownArchiveFiles = false,
            manual = true,
            activeDatabaseName = "debug.db",
            activeSidecarNames = setOf("debug.db")
        )

        assertEquals(StorageCutoverRecovery.Action.FAIL_CLOSED, StorageCutoverRecovery.decide(base))
        assertEquals(
            StorageCutoverRecovery.Action.FAIL_CLOSED,
            StorageCutoverRecovery.decide(base.copy(expectedSidecarNames = emptySet()))
        )
    }

    @Test
    fun corruptUnknownAndRollbackStatesFailClosedWithoutFileMutation() {
        val cases = listOf(
            StorageCutoverRecovery.Snapshot(
                StorageCutoverRecovery.PHASE_VERIFYING,
                StorageFormat.LEGACY_V1,
                StorageFormat.UNKNOWN,
                true,
                false,
                true,
                StorageFormat.UNKNOWN,
                false,
                setOf("telemetry.db"),
                setOf("telemetry.db", "telemetry.db-wal", "telemetry.db-shm", "telemetry.db-journal"),
                false
            ),
            StorageCutoverRecovery.Snapshot(
                StorageCutoverRecovery.PHASE_VERIFYING,
                StorageFormat.LEGACY_V1,
                StorageFormat.UNKNOWN,
                true,
                false,
                true,
                StorageFormat.LEGACY_V1,
                true,
                setOf("telemetry.db"),
                setOf("telemetry.db", "telemetry.db-wal", "telemetry.db-shm", "telemetry.db-journal"),
                true
            ),
            StorageCutoverRecovery.Snapshot(
                StorageCutoverRecovery.PHASE_ROLLBACK,
                StorageFormat.LEGACY_V1,
                StorageFormat.UNKNOWN,
                false,
                false,
                true,
                StorageFormat.LEGACY_V1,
                true,
                setOf("telemetry.db"),
                setOf("telemetry.db", "telemetry.db-wal", "telemetry.db-shm", "telemetry.db-journal"),
                false
            )
        )
        cases.forEach { snapshot ->
            val root = createTempDirectory().toFile()
            val database = File(root, "telemetry.db").apply { writeText("active") }
            val before = database.readText()
            val action = StorageCutoverRecovery.decide(snapshot)
            assertEquals(StorageCutoverRecovery.Action.FAIL_CLOSED, action)
            assertFalse(
                StorageCutoverRecovery.execute(
                    action = action,
                    databaseFile = database,
                    movedFiles = emptyList(),
                    deleteActive = { error("must not delete on fail-closed") },
                    restore = { _, _ -> error("must not restore on fail-closed") },
                    verifyActive = { true },
                    clearJournal = { error("must not clear on fail-closed") }
                )
            )
            assertEquals(before, database.readText())
        }
    }
}
