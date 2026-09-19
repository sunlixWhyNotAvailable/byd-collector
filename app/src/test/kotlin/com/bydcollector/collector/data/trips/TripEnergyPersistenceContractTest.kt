package com.bydcollector.collector.data.trips

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TripEnergyPersistenceContractTest {
    @Test
    fun schemaIsAdditiveAndKeepsCheckpointAndPendingInOneSingletonRow() {
        val schema = source("TripDatabaseHelper.kt")
        assertTrue(schema.contains("const val DATABASE_VERSION = 6"))
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS energy_runtime_quarantine"))
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS energy_runtime_state"))
        assertTrue(schema.contains("singleton_id INTEGER PRIMARY KEY NOT NULL CHECK (singleton_id = 1)"))
        assertTrue(schema.contains("state_json TEXT NOT NULL"))
        assertTrue(schema.contains("pending_projection_json TEXT"))
        listOf("discharged_kwh", "regenerated_kwh", "net_kwh", "energy_covered_ms", "energy_uncovered_ms", "energy_partial", "energy_observed_at")
            .forEach { assertTrue(schema.contains("ALTER TABLE trip_sessions ADD COLUMN $it"), it) }
    }

    @Test
    fun storeCommitsStateAndPendingAtomicallyAndClearsWithCompareAndSet() {
        val store = source("TripStore.kt")
        assertTrue(store.contains("override fun commitEnergyRuntimeRow"))
        assertTrue(store.contains("db.beginTransaction()"))
        assertTrue(store.contains("pendingProjectionJson"))
        assertTrue(store.contains("override fun clearEnergyPending"))
        assertTrue(store.contains("singleton_id = 1 AND pending_projection_json = ?"))
    }

    @Test
    fun compressionCopiesSnapshotThenRefreshesCurrentRowUnderFinalLease() {
        val compression = source("TripCompression.kt")
        val store = source("TripStore.kt")
        assertTrue(compression.contains("candidateStore.copySessionFrom(snapshotStore, session.tripId)"))
        assertTrue(compression.contains("candidateStore.copySessionFrom(store, tripId)"))
        assertTrue(store.contains("internal fun copySessionFrom(source: TripStore, tripId: String)"))
        assertTrue(store.contains("preserveDurableEnergy = false"))
        assertTrue(compression.contains("candidateStore.replaceEnergyRuntimeRow(snapshotStore.readEnergyRuntimeRow())"))
        assertTrue(compression.contains("candidateStore.replaceEnergyRuntimeRow(store.readEnergyRuntimeRow())"))
        assertTrue(compression.contains("sameEnergyRuntime(store, candidateStore)"))
        assertTrue(compression.contains("store.readEnergyRuntimeRow() == expectedEnergyRuntime"))
        assertTrue(compression.contains("candidateStore.replaceEnergyQuarantineRecords(snapshotStore.energyQuarantineRecords())"))
        assertTrue(compression.contains("candidateStore.replaceEnergyQuarantineRecords(store.energyQuarantineRecords())"))
        assertTrue(compression.contains("store.energyQuarantineRecords() == expectedEnergyQuarantine"))
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/bydcollector/collector/data/trips/$name"),
        File("app/src/main/kotlin/com/bydcollector/collector/data/trips/$name")
    ).firstOrNull(File::isFile)?.readText() ?: error("Missing $name")
}
