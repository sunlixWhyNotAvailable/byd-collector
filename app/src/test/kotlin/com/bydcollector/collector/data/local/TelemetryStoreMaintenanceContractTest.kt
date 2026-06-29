package com.bydcollector.collector.data.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryStoreMaintenanceContractTest {
    @Test
    fun compactRawHistoryDeletesOnlyRawHistoryAndVacuumRunsOutsideTransaction() {
        val source = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()
        val compact = source.substringAfter("fun compactRawHistory").substringBefore("fun checkpointForArchive")

        assertTrue(compact.contains("DbMaintenanceResult"))
        assertTrue(compact.contains("onStage(2)"))
        assertTrue(compact.contains("UPDATE vehicle_state_current SET source_poll_id = NULL"))
        assertTrue(compact.contains("UPDATE vehicle_state_history SET source_poll_id = NULL"))
        assertTrue(compact.contains("onStage(3)"))
        assertTrue(compact.contains("DELETE FROM poll_values"))
        assertTrue(compact.contains("DELETE FROM vehicle_snapshots"))
        assertTrue(compact.contains("DELETE FROM parameter_observations"))
        assertTrue(compact.contains("DELETE FROM polls"))
        assertTrue(compact.contains("UPDATE ec_import_runs SET session_id = NULL"))
        assertTrue(compact.contains("UPDATE ec_energy_consumption SET first_seen_session_id = NULL, last_seen_session_id = NULL"))
        assertTrue(compact.indexOf("UPDATE ec_import_runs SET session_id = NULL") < compact.indexOf("DELETE FROM collection_sessions"))
        assertTrue(compact.indexOf("UPDATE ec_energy_consumption SET first_seen_session_id = NULL, last_seen_session_id = NULL") < compact.indexOf("DELETE FROM collection_sessions"))
        assertTrue(compact.contains("DELETE FROM collection_sessions"))
        assertTrue(compact.contains("setTransactionSuccessful()"))
        assertTrue(compact.contains("endTransaction()"))
        assertTrue(compact.contains("onStage(4)"))
        assertTrue(compact.indexOf("endTransaction()") < compact.indexOf("VACUUM"))
        assertFalse(compact.contains("DELETE FROM normalized_field_catalog"))
        assertFalse(compact.contains("DELETE FROM mqtt_outbox"))
        assertFalse(compact.contains("DELETE FROM influx_export_cursor"))
        assertFalse(compact.contains("DELETE FROM collector_events"))
        assertFalse(compact.contains("DELETE FROM ec_energy_consumption"))
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
