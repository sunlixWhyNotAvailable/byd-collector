package com.bydcollector.collector.maintenance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DbMaintenanceModelsTest {
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
        assertEquals(6, DbMaintenanceOperation.ARCHIVE.stepsUk.size)
        assertEquals(6, DbMaintenanceOperation.ARCHIVE.stepsEn.size)
        assertEquals("Закриваємо поточну базу даних", DbMaintenanceOperation.ARCHIVE.stepsUk[1])
        assertEquals("Перевіряємо нову базу", DbMaintenanceOperation.ARCHIVE.stepsUk[4])
        assertEquals("Closing current database", DbMaintenanceOperation.ARCHIVE.stepsEn[1])
        assertEquals("Verifying new database", DbMaintenanceOperation.ARCHIVE.stepsEn[4])
    }

    @Test
    fun uiStateDefaultsToOperationStepCount() {
        assertEquals(6, DbMaintenanceUiState(DbMaintenanceOperation.ARCHIVE).stepCount)
        assertEquals(6, DbMaintenanceUiState(DbMaintenanceOperation.DEBUG_ARCHIVE).stepCount)
    }

    @Test
    fun debugArchiveStepsDescribeRoundRobinOnly() {
        assertEquals("Зупиняємо round-robin збір", DbMaintenanceOperation.DEBUG_ARCHIVE.stepsUk.first())
        assertEquals("Restoring round-robin collection", DbMaintenanceOperation.DEBUG_ARCHIVE.stepsEn.last())
    }
}
