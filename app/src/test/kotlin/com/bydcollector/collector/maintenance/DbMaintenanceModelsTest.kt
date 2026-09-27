package com.bydcollector.collector.maintenance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DbMaintenanceModelsTest {
    @Test
    fun archiveAdmissionIsSharedAcrossFamiliesAndRejectDoesNotReleaseOwner() {
        try {
            assertTrue(DbMaintenanceCoordinator.tryClaim(DbMaintenanceOperation.ARCHIVE))
            assertFalse(DbMaintenanceCoordinator.tryClaim(DbMaintenanceOperation.DEBUG_ARCHIVE))
            assertFalse(DbMaintenanceCoordinator.tryClaim(DbMaintenanceOperation.ARCHIVE))
            DbMaintenanceCoordinator.releaseClaim(DbMaintenanceOperation.DEBUG_ARCHIVE)
            assertEquals(DbMaintenanceOperation.ARCHIVE, DbMaintenanceCoordinator.currentOperation())
            DbMaintenanceCoordinator.releaseClaim(DbMaintenanceOperation.ARCHIVE)
            assertTrue(DbMaintenanceCoordinator.tryClaim(DbMaintenanceOperation.DEBUG_ARCHIVE))
        } finally {
            DbMaintenanceCoordinator.releaseClaim(DbMaintenanceOperation.ARCHIVE)
            DbMaintenanceCoordinator.releaseClaim(DbMaintenanceOperation.DEBUG_ARCHIVE)
        }
        assertNull(DbMaintenanceCoordinator.currentOperation())
    }

    @Test
    fun operationsResolveFromKeys() {
        assertNull(DbMaintenanceOperation.fromKey("compact"))
        assertEquals(DbMaintenanceOperation.ARCHIVE, DbMaintenanceOperation.fromKey("archive"))
        assertEquals(DbMaintenanceOperation.DEBUG_ARCHIVE, DbMaintenanceOperation.fromKey("debug_archive"))
        assertNull(DbMaintenanceOperation.fromKey(null))
        assertNull(DbMaintenanceOperation.fromKey("other"))
    }

    @Test
    fun archiveStepsAreLocalized() {
        assertEquals("archive", DbMaintenanceOperation.ARCHIVE.key)
        assertEquals(
            listOf(
                "Завершуємо поточний запис",
                "Записуємо накопичену чергу",
                "Контрольна точка та закриття бази",
                "Переносимо базу в архів",
                "Створюємо нову базу даних",
                "Перевіряємо нову базу",
                "Відновлюємо попередній стан"
            ),
            DbMaintenanceOperation.ARCHIVE.stepsUk
        )
        assertEquals(
            listOf(
                "Finishing the current write",
                "Draining the queued records",
                "Checkpointing and closing database",
                "Moving database to archive",
                "Creating new database",
                "Verifying new database",
                "Restoring previous state"
            ),
            DbMaintenanceOperation.ARCHIVE.stepsEn
        )
    }

    @Test
    fun uiStateDefaultsToOperationStepCount() {
        assertEquals(7, DbMaintenanceUiState(DbMaintenanceOperation.ARCHIVE).stepCount)
        assertEquals(7, DbMaintenanceUiState(DbMaintenanceOperation.DEBUG_ARCHIVE).stepCount)
    }

    @Test
    fun debugArchiveStepsDescribeSecondaryCollectionOnly() {
        assertEquals("Завершуємо поточний вторинний запис", DbMaintenanceOperation.DEBUG_ARCHIVE.stepsUk.first())
        assertEquals("Записуємо накопичену вторинну чергу", DbMaintenanceOperation.DEBUG_ARCHIVE.stepsUk[1])
        assertEquals("Checkpointing and closing secondary database", DbMaintenanceOperation.DEBUG_ARCHIVE.stepsEn[2])
        assertEquals("Restoring secondary collection", DbMaintenanceOperation.DEBUG_ARCHIVE.stepsEn.last())
    }

    @Test
    fun mainArchivePreflightBlocksAutomaticCutoverForEveryPendingSource() {
        assertFalse(MainArchivePreflight().blocksAutomaticCutover)
        assertTrue(MainArchivePreflight(telegramPending = 1).blocksAutomaticCutover)
        assertTrue(MainArchivePreflight(mqttPending = 1).blocksAutomaticCutover)
        assertTrue(MainArchivePreflight(influxPending = 1).blocksAutomaticCutover)
        assertTrue(MainArchivePreflight(telegramStatePresent = true).blocksAutomaticCutover)
        assertFalse(MainArchivePreflight(telegramStorageWarning = "manual warning").blocksAutomaticCutover)
    }

    @Test
    fun cutoverJournalCarriesTheExactSourceFormat() {
        val journal = StorageCutoverJournal(
            family = "main_telemetry",
            archivePath = null,
            phase = "CREATING",
            sourceFormat = StorageFormat.ABSENT
        )

        assertEquals(StorageFormat.ABSENT, journal.sourceFormat)
        assertNull(journal.archivePath)
        assertEquals(StorageFormat.COMPACT_V2, journal.copy(sourceFormat = StorageFormat.COMPACT_V2).sourceFormat)
    }
}
