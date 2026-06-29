package com.bydcollector.collector.maintenance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DbMaintenanceModelsTest {
    @Test
    fun operationsResolveFromKeys() {
        assertEquals(DbMaintenanceOperation.COMPACT, DbMaintenanceOperation.fromKey("compact"))
        assertEquals(DbMaintenanceOperation.ARCHIVE, DbMaintenanceOperation.fromKey("archive"))
        assertNull(DbMaintenanceOperation.fromKey(null))
        assertNull(DbMaintenanceOperation.fromKey("other"))
    }

    @Test
    fun compactStepsAreLocalized() {
        assertEquals("compact", DbMaintenanceOperation.COMPACT.key)
        assertEquals(5, DbMaintenanceOperation.COMPACT.stepsUk.size)
        assertEquals(5, DbMaintenanceOperation.COMPACT.stepsEn.size)
        assertEquals("Зупиняємо збір та експорт", DbMaintenanceOperation.COMPACT.stepsUk.first())
        assertEquals("Відновлюємо попередній стан", DbMaintenanceOperation.COMPACT.stepsUk.last())
        assertEquals("Stopping collection and export", DbMaintenanceOperation.COMPACT.stepsEn.first())
        assertEquals("Restoring previous state", DbMaintenanceOperation.COMPACT.stepsEn.last())
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
    }
}
