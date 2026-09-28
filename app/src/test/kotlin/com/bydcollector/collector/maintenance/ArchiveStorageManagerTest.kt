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
    fun failedDirectoryScanIsNotReportedAsAnEmptyArchiveList() {
        val root = createTempDirectory().toFile()
        try {
            val blocked = File(root, "db_archive").apply { writeText("not a directory") }
            val result = runCatching { manager(blocked, File(root, "active.db")).snapshot(1024L) }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals("not a directory", blocked.readText())
        } finally { root.deleteRecursively() }
    }

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
            assertEquals(
                "state=PASSED",
                archive.getInputStream(archive.getEntry(ArchiveStorageManager.ARCHIVE_VERIFICATION_MARKER))
                    .bufferedReader().use { it.readLine() }
            )
        }
    }

    @Test
    fun archiveAuditRunsBeforeZipCreationAndRawDeletion() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive")
        val raw = File(archiveRoot, "bydcollector_telemetry_20260707_120000").apply { mkdirs() }
        File(raw, active.name).writeText("db")
        val events = mutableListOf<String>()
        val phases = mutableListOf<ArchiveStorageItemPhase>()
        val manager = manager(archiveRoot, active) { family, databaseFile, directory ->
            assertEquals("main_telemetry", family)
            assertEquals(active.name, databaseFile.name)
            assertTrue(File(directory, ArchiveStorageManager.ARCHIVE_VERIFICATION_MARKER).readText().startsWith("state=IN_PROGRESS"))
            events += "audit"
            null
        }

        assertTrue(manager.compressRawArchiveDirectory(raw) { status ->
            status.phase?.let(phases::add)
            when (status.messageEn) {
                "Preparing archive" -> events += "zip"
                "Deleting raw archive" -> events += "delete"
            }
        })

        assertEquals(listOf("audit", "zip", "delete"), events)
        assertEquals(
            listOf(
                ArchiveStorageItemPhase.VERIFYING_DATABASE,
                ArchiveStorageItemPhase.CREATING_ZIP,
                ArchiveStorageItemPhase.VERIFYING_ZIP,
                ArchiveStorageItemPhase.FINALIZING,
                ArchiveStorageItemPhase.READY
            ),
            phases
        )
    }

    @Test
    fun failedAuditKeepsRawAndExistingZipAndPersistsFailure() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val raw = rawArchive(archiveRoot, active.name)
        val zip = zipRaw(archiveRoot, raw)
        val statuses = mutableListOf<ArchiveStorageJobStatus>()
        val manager = manager(archiveRoot, active) { _, _, _ -> "archive_verification_failed" }

        assertFalse(manager.compressRawArchiveDirectory(raw, statuses::add))

        assertTrue(raw.exists())
        assertTrue(zip.exists())
        assertTrue(File(raw, ArchiveStorageManager.ARCHIVE_VERIFICATION_MARKER).readText().contains("state=FAILED"))
        assertEquals("archive_verification_failed", statuses.last().error)
        assertFalse(statuses.last().running)
        assertEquals(ArchiveStorageItemPhase.FAILED, statuses.last().phase)
    }

    @Test
    fun interruptedAuditKeepsRawAndExistingZipAndRestoresInterruptFlag() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val raw = rawArchive(archiveRoot, active.name)
        val zip = zipRaw(archiveRoot, raw)
        val statuses = mutableListOf<ArchiveStorageJobStatus>()
        val manager = manager(archiveRoot, active) { _, _, _ -> throw InterruptedException("test interruption") }

        try {
            assertFalse(manager.compressRawArchiveDirectory(raw, statuses::add))
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(raw.exists())
            assertTrue(zip.exists())
            assertTrue(File(raw, ArchiveStorageManager.ARCHIVE_VERIFICATION_MARKER).readText().contains("state=FAILED"))
            assertEquals("archive_verification_interrupted", statuses.last().error)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun successfulRetryReauditsAndCleansUpAgainstExistingCompleteZip() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val raw = rawArchive(archiveRoot, active.name)
        val zip = zipRaw(archiveRoot, raw)
        var attempts = 0
        val manager = manager(archiveRoot, active) { _, _, _ ->
            attempts += 1
            if (attempts == 1) "archive_verification_unavailable:format" else null
        }

        assertFalse(manager.compressRawArchiveDirectory(raw))
        assertTrue(raw.exists())
        assertTrue(manager.compressRawArchiveDirectory(raw))

        assertEquals(2, attempts)
        assertFalse(raw.exists())
        assertTrue(zip.exists())
    }

    @Test
    fun failedNewZipVerificationKeepsRawAndAllowsCleanRetry() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val raw = rawArchive(archiveRoot, active.name)
        val target = File(archiveRoot, "${raw.name}.zip")
        val temporary = File(archiveRoot, "${raw.name}.zip.tmp")
        val statuses = mutableListOf<ArchiveStorageJobStatus>()
        val manager = manager(archiveRoot, active)

        assertFalse(manager.compressRawArchiveDirectory(raw) { status ->
            statuses += status
            if (status.phase == ArchiveStorageItemPhase.VERIFYING_ZIP) {
                assertFalse(target.exists())
                temporary.writeText("corrupted generated ZIP")
            }
        })
        assertTrue(raw.isDirectory)
        assertFalse(target.exists())
        assertFalse(temporary.exists())
        assertEquals("archive_zip_verify_failed", statuses.last().error)

        assertTrue(manager.compressRawArchiveDirectory(raw))
        assertFalse(raw.exists())
        assertTrue(target.isFile)
    }

    @Test
    fun existingNonMatchingZipNeverAuthorizesRawDeletion() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val raw = rawArchive(archiveRoot, active.name)
        val target = File(archiveRoot, "${raw.name}.zip").apply { writeText("nonempty but incomplete") }
        val statuses = mutableListOf<ArchiveStorageJobStatus>()
        val manager = manager(archiveRoot, active)

        assertFalse(manager.compressRawArchiveDirectory(raw, statuses::add))

        assertTrue(raw.exists())
        assertTrue(target.exists())
        assertEquals("archive_zip_mismatch", statuses.last().error)
    }

    @Test
    fun defaultVerifierFailsClosedForUnrecognizedDatabaseAndRetentionNeverDeletesRaw() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val raw = rawArchive(archiveRoot, active.name)
        val statuses = mutableListOf<ArchiveStorageJobStatus>()
        val manager = ArchiveStorageManager(
            archiveRoot,
            active,
            File(root, "bydcollector_debug_round_robin.db")
        )

        assertFalse(manager.compressRawArchiveDirectory(raw, statuses::add))
        assertEquals(0, manager.enforceRetention(limitBytes = 0L))

        assertTrue(raw.exists())
        assertTrue(statuses.last().error?.startsWith("archive_verification_") == true)
        assertTrue(File(raw, ArchiveStorageManager.ARCHIVE_VERIFICATION_MARKER).readText().contains("state=FAILED"))
        assertEquals(1, manager.deleteArchiveIds(listOf(raw.name)))
        assertFalse(raw.exists())
    }

    @Test
    fun onlyExactDatabaseAndSidecarNamesAreAcceptedAndBothSecondaryNamesWork() {
        val root = createTempDirectory().toFile()
        val main = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val invalidRaw = rawArchive(archiveRoot, main.name).apply { File(this, "extra.txt").writeText("extra") }
        var verifierCalls = 0
        val mainManager = manager(archiveRoot, main) { _, _, _ -> verifierCalls += 1; null }
        assertFalse(mainManager.compressRawArchiveDirectory(invalidRaw))
        assertEquals(0, verifierCalls)
        assertTrue(invalidRaw.exists())

        val verified = mutableListOf<String>()
        listOf(
            "bydcollector_secondary_20260707_120000" to "bydcollector_secondary.db",
            "bydcollector_debug_round_robin_20260707_120000" to "bydcollector_debug_round_robin.db"
        ).forEach { (archiveName, databaseName) ->
            val raw = File(archiveRoot, archiveName).apply { mkdirs() }
            File(raw, databaseName).writeText("db")
            val manager = manager(archiveRoot, main) { family, databaseFile, _ ->
                assertEquals("debug_round_robin", family)
                assertEquals(databaseName, databaseFile.name)
                verified += databaseFile.name
                null
            }
            assertTrue(manager.compressRawArchiveDirectory(raw))
        }

        assertEquals(
            listOf("bydcollector_secondary.db", "bydcollector_debug_round_robin.db"),
            verified
        )
    }

    @Test
    fun activeCutoverArchiveIsProtectedFromCompressionAndExplicitDeletion() {
        val root = createTempDirectory().toFile()
        val main = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val raw = rawArchive(archiveRoot, main.name)
        val manager = manager(archiveRoot, main, isArchiveInUse = { it == raw.name })

        assertFalse(manager.compressRawArchiveDirectory(raw))
        assertEquals(0, manager.deleteArchiveIds(listOf(raw.name)))

        assertTrue(raw.exists())
        assertFalse(File(raw, ArchiveStorageManager.ARCHIVE_VERIFICATION_MARKER).exists())
    }

    @Test
    fun archiveBecomingActiveAfterZipCreationIsRetainedAndRetryable() {
        val root = createTempDirectory().toFile()
        val main = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val raw = rawArchive(archiveRoot, main.name)
        val zip = File(archiveRoot, "${raw.name}.zip")
        var inUse = false
        val manager = manager(archiveRoot, main, isArchiveInUse = { inUse })
        val statuses = mutableListOf<ArchiveStorageJobStatus>()

        assertFalse(manager.compressRawArchiveDirectory(raw) { status ->
            statuses += status
            if (status.messageEn == "Deleting raw archive") inUse = true
        })

        assertTrue(raw.exists())
        assertTrue(zip.exists())
        assertEquals("archive_in_use", statuses.last().error)
        inUse = false
        assertTrue(manager.compressRawArchiveDirectory(raw))
        assertFalse(raw.exists())
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
    fun pendingCompressionPreservesOrphanTmpWhenRawSourceIsAbsent() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val tmp = File(archiveRoot, "bydcollector_telemetry_20260707_120000.zip.tmp").apply {
            writeText("partial zip")
        }
        val manager = manager(archiveRoot, active)

        assertEquals(0, manager.compressPendingRawArchives())

        assertTrue(tmp.exists(), "An orphan tmp ZIP must not be discarded without its raw source")
    }

    @Test
    fun finalizingRestartPromotesOnlyZipWithVerifiedEntriesAndPassedRawAuditMarker() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val completeRaw = rawArchive(archiveRoot, active.name)
        val completeZip = zipRaw(archiveRoot, completeRaw)
        assertTrue(completeRaw.deleteRecursively())

        val corruptRaw = File(archiveRoot, "bydcollector_telemetry_20260707_130000").apply {
            mkdirs()
            File(this, active.name).writeText("db")
        }
        val corruptZip = zipRaw(archiveRoot, corruptRaw)
        assertTrue(corruptRaw.deleteRecursively())
        corruptCentralDirectoryCrc(corruptZip)
        ZipFile(corruptZip).use { archive ->
            assertEquals("db", archive.getInputStream(archive.getEntry(active.name)).bufferedReader().use { it.readText() })
        }

        val manager = manager(archiveRoot, active)
        val reconciled = manager.reconcilePersistedItems(
            listOf(
                finalizingState(completeRaw.name),
                finalizingState(corruptRaw.name)
            )
        ).itemStates.associateBy { it.archiveId }

        assertTrue(completeZip.exists())
        assertEquals(ArchiveStorageItemPhase.READY, reconciled.getValue(completeRaw.name).phase)
        assertEquals(ArchiveStorageItemPhase.FAILED, reconciled.getValue(corruptRaw.name).phase)
        assertEquals("archive_operation_interrupted:archive_unverified", reconciled.getValue(corruptRaw.name).error)
    }

    @Test
    fun readyRestartReconciliationUsesPersistedVerificationWithoutReadingZipPayloads() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val raw = rawArchive(archiveRoot, active.name)
        val zip = zipRaw(archiveRoot, raw)
        assertTrue(raw.deleteRecursively())
        corruptCentralDirectoryCrc(zip)
        val ready = finalizingState(raw.name).copy(
            phase = ArchiveStorageItemPhase.READY,
            completedAtMs = 3L
        )

        val reconciled = manager(archiveRoot, active).reconcilePersistedItems(listOf(ready))

        assertEquals(ready, reconciled.itemStates.single())
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
    fun retentionCountsRawBytesButNeverSelectsRawDirectoriesForDeletion() {
        val root = createTempDirectory().toFile()
        val main = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val oldest = archive(archiveRoot, "bydcollector_telemetry_20260707_010000.zip", 1_000L)
        val newest = archive(archiveRoot, "bydcollector_telemetry_20260707_020000.zip", 2_000L)
        val raw = File(archiveRoot, "bydcollector_telemetry_20260707_000000").apply {
            mkdirs()
            File(this, main.name).writeText("raw database")
        }
        val manager = manager(archiveRoot, main)

        assertEquals(1, manager.enforceRetention(limitBytes = oldest.length() + newest.length()))

        assertFalse(oldest.exists())
        assertTrue(newest.exists())
        assertTrue(raw.exists())
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
    fun deleteReportsPerItemProgressAndContinuesAfterMissingArchive() {
        val root = createTempDirectory().toFile()
        val active = File(root, "bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = File(root, "db_archive").apply { mkdirs() }
        val valid = File(archiveRoot, "bydcollector_telemetry_20260707_120000.zip").apply { writeText("zip") }
        val statuses = mutableListOf<ArchiveStorageJobStatus>()
        val manager = manager(archiveRoot, active)

        assertEquals(
            1,
            manager.deleteArchiveIds(
                listOf(valid.name, "bydcollector_telemetry_20260707_130000.zip"),
                statuses::add
            )
        )

        assertFalse(valid.exists())
        assertEquals(5, statuses.size) // 0/N, before/after for each item
        assertEquals(0, statuses.first().stepIndex)
        assertTrue(statuses.first().running)
        assertEquals("Archive deleted", statuses[2].messageEn)
        assertTrue(statuses[3].running)
        assertEquals("Archive deletion failed", statuses[4].messageEn)
        assertEquals("archive_missing", statuses[4].error)
    }

    @Test
    fun snapshotAndCompressionIncludeDebugArchivesAndAllActiveDatabaseFootprints() {
        val root = createTempDirectory().toFile()
        val main = File(root, "bydcollector_telemetry.db").apply { writeText("main") }
        val debug = File(root, "bydcollector_debug_round_robin.db").apply { writeText("debug") }
        val trips = File(root, "bydcollector_trips.db").apply { writeText("trips") }
        File(root, trips.name + "-wal").writeText("wal")
        File(root, main.name + "-journal").writeText("journal")
        val archiveRoot = File(root, "db_archive")
        val raw = File(archiveRoot, "bydcollector_debug_round_robin_20260713_120000").apply { mkdirs() }
        File(raw, debug.name).writeText("archived-debug")
        val manager = ArchiveStorageManager(
            archiveRoot,
            main,
            debug,
            archivedDatabaseVerifier = { _, _, _ -> null }
        )

        assertTrue(manager.compressRawArchiveDirectory(raw))
        val snapshot = manager.snapshot(1024L)

        assertEquals(main.length() + 7L, snapshot.mainDatabaseSizeBytes)
        assertEquals(debug.length(), snapshot.debugDatabaseSizeBytes)
        assertEquals(8L, snapshot.tripsDatabaseSizeBytes)
        assertEquals(main.length() + debug.length() + 7L + 8L, snapshot.activeDatabaseSizeBytes)
        assertEquals(1, snapshot.entries.size)
        assertTrue(ArchiveStorageManager.isSecondaryArchiveName(snapshot.entries.single().id))
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
        val newDebug = archive(archiveRoot, "bydcollector_secondary_20260713_020000.zip", 2_500L)
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

    private fun manager(
        archiveRoot: File,
        main: File,
        isArchiveInUse: (String) -> Boolean = { false },
        archivedDatabaseVerifier: (String, File, File) -> String? = { _, _, _ -> null }
    ): ArchiveStorageManager {
        return ArchiveStorageManager(
            archiveRoot,
            main,
            File(main.parentFile, "bydcollector_debug_round_robin.db"),
            archivedDatabaseVerifier = archivedDatabaseVerifier,
            isArchiveInUse = isArchiveInUse
        )
    }

    private fun rawArchive(root: File, databaseName: String): File =
        File(root, "bydcollector_telemetry_20260707_120000").apply {
            mkdirs()
            File(this, databaseName).writeText("db")
        }

    private fun zipRaw(root: File, raw: File): File {
        val target = File(root, "${raw.name}.zip")
        File(raw, ArchiveStorageManager.ARCHIVE_VERIFICATION_MARKER)
            .writeText("state=PASSED\nupdated_at_ms=1\n")
        ZipOutputStream(target.outputStream()).use { zip ->
            raw.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return target
    }

    private fun finalizingState(archiveId: String) = ArchiveStorageItemState(
        archiveId = archiveId,
        operationId = "archive-op-test",
        phase = ArchiveStorageItemPhase.FINALIZING,
        startedAtMs = 1L,
        updatedAtMs = 2L
    )

    private fun corruptCentralDirectoryCrc(zip: File) {
        val bytes = zip.readBytes()
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val offset = (0..bytes.size - signature.size).firstOrNull { index ->
            signature.indices.all { bytes[index + it] == signature[it] }
        } ?: error("ZIP central directory entry not found")
        bytes[offset + 16] = (bytes[offset + 16].toInt() xor 0x01).toByte()
        zip.writeBytes(bytes)
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
