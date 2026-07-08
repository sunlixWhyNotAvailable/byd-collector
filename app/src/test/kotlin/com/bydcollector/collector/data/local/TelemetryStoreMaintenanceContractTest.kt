package com.bydcollector.collector.data.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryStoreMaintenanceContractTest {
    @Test
    fun compactRawHistoryIsNotExposedAsProductionMaintenance() {
        val source = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()

        assertFalse(source.contains("fun compactRawHistory"))
        assertFalse(source.contains("DbMaintenanceResult"))
        assertFalse(source.contains("VACUUM"))
    }

    @Test
    fun storeExposesCheckpointAndWritableQuickCheck() {
        val source = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()

        assertTrue(source.contains("fun checkpointForArchive()"))
        assertTrue(source.contains("PRAGMA wal_checkpoint(TRUNCATE)"))
        assertTrue(source.contains("fun verifyWritableDatabase()"))
        assertTrue(source.contains("PRAGMA quick_check"))
        assertTrue(source.contains("== \"ok\""))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
