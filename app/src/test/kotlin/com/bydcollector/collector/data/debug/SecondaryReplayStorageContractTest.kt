package com.bydcollector.collector.data.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecondaryReplayStorageContractTest {
    @Test
    fun schemaIsAdditiveAndKeepsCompactMarkerVersions() {
        val source = sourceFile("DirectDebugDatabaseHelper.kt").readText()

        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS debug_secondary_receipts"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS debug_secondary_cycle_metadata"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS debug_secondary_replay_cursor"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS debug_secondary_rejections"))
        assertTrue(source.contains("if (!db.isReadOnly && isCompactV2(db)) createSecondaryReplaySchema(db)"))
        assertTrue(source.contains("const val FORMAT_VERSION = 2"))
        assertTrue(source.contains("private const val DATABASE_VERSION = 1"))
        assertFalse(source.contains("ALTER TABLE"))
        assertFalse(source.contains("DROP TABLE"))
    }

    @Test
    fun receiptIdentityAndReplayCursorAreDurableAndSeparateFromCandidateState() {
        val source = sourceFile("DirectDebugDatabaseHelper.kt").readText()

        assertTrue(source.contains("PRIMARY KEY(boot_id, helper_generation, gap_id, sequence)"))
        assertTrue(source.contains("catalog_version_id INTEGER PRIMARY KEY"))
        assertTrue(source.contains("digest TEXT NOT NULL"))
        assertTrue(source.contains("source_wall_ms INTEGER NOT NULL"))
        assertTrue(source.contains("source_elapsed_ms INTEGER NOT NULL"))
        assertTrue(source.contains("loss_count INTEGER"))
        assertEquals(1, Regex("CREATE TABLE debug_direct_candidate_state").findAll(source).count())
    }

    @Test
    fun importCommitsCycleTransitionsCountersReceiptAndCursorTogether() {
        val source = sourceFile("DirectDebugStore.kt").readText()
        val method = source.substringAfter("fun importSecondaryRecord(").substringBefore("fun dashboardReadingCount")

        assertTrue(method.contains("db.beginTransaction()"))
        assertTrue(method.contains("insertTransition("))
        assertTrue(method.contains("insertSecondaryMetadata(db, cycleId, record)"))
        assertTrue(method.contains("insertSecondaryReceipt(db, record, digest, cycleId)"))
        assertTrue(method.contains("replaceSecondaryCursor(db, catalogVersionId, record, digest, cycleId)"))
        assertTrue(method.contains("db.setTransactionSuccessful()"))
        assertTrue(method.indexOf("db.setTransactionSuccessful()") < method.indexOf("candidateState.clear()"))
        assertTrue(method.contains("existing.second == digest"))
        assertTrue(method.contains("duplicate = true"))
        assertTrue(source.contains("Secondary replay cursor was not persisted"))
        assertTrue(source.contains("hasSecondaryRejection(db, identity, digest)"))
        assertTrue(source.contains("Secondary rejection metadata was not persisted"))
    }

    @Test
    fun catalogOrOrphanRejectionIsPersistedBeforeCoordinatorCanQuarantine() {
        val store = sourceFile("DirectDebugStore.kt").readText()
        val coordinator = sourceFile("SecondaryReplayCoordinator.kt").readText()

        assertTrue(store.contains("persistSecondaryRejection(db, record, digest, reason)"))
        assertTrue(store.contains("orphan DELTA: exact persisted predecessor unavailable"))
        assertTrue(store.contains("record.catalogVersion != DirectDebugParameterAsset.SOURCE_VERSION"))
        assertTrue(store.contains("identity digest mismatch"))
        assertTrue(coordinator.contains("is SecondaryImportResult.Rejected"))
        assertTrue(coordinator.contains("quarantine(descriptor, imported.reason.take"))
        assertFalse(coordinator.contains("acknowledge(descriptor)\n                    }\n                is SecondaryImportResult.Rejected"))
    }

    @Test
    fun appLiveCommitInvalidatesReplayCursorInsideItsTransaction() {
        val source = sourceFile("DirectDebugStore.kt").readText()
        val live = source.substringAfter("fun recordCycle(").substringBefore("fun importSecondaryRecord(")

        assertTrue(live.contains("db.delete("))
        assertTrue(live.contains("\"debug_secondary_replay_cursor\""))
        assertTrue(live.indexOf("debug_secondary_replay_cursor") < live.indexOf("db.setTransactionSuccessful()"))
        assertTrue(live.indexOf("db.setTransactionSuccessful()") < live.indexOf("candidateState[transition.candidateId]"))
    }

    @Test
    fun deltaUsesExactPersistedPredecessorAndFullMaterializedState() {
        val source = sourceFile("DirectDebugStore.kt").readText()

        assertTrue(source.contains("record.predecessorIdentity != cursorIdentity"))
        assertTrue(source.contains("persistedState[candidateId] ?: return null"))
        assertTrue(source.contains("record.materialize(cursorIdentity, previous)"))
        assertTrue(source.contains("record.materialize(null, null)"))
        assertTrue(source.contains("record.fieldCount != candidateIdsByOrdinal.size"))
        assertTrue(source.contains("val persistedState = readCandidateState(db, catalogVersionId)"))
        assertTrue(source.contains("@Synchronized\n    fun importSecondaryRecord("))
    }

    @Test
    fun sessionAndCacheOwnershipCannotEscapeRollbackOrConcurrentClose() {
        val source = sourceFile("DirectDebugStore.kt").readText()
        val open = source.substringAfter("fun openSession(").substringBefore("fun endSession(")

        assertTrue(open.indexOf("db.endTransaction()") < open.indexOf("activeCatalogVersionId = catalogVersionId"))
        assertTrue(open.contains("candidateIdsByKey.clear()"))
        assertTrue(open.contains("candidateIdsByOrdinal.clear()"))
        assertTrue(open.contains("candidateState.clear()"))
        assertTrue(source.contains("check(activeSessionId == sessionId) { \"Debug cycle session is not active\" }"))
        assertTrue(source.contains("@Synchronized\n    override fun close()"))
    }

    private fun sourceFile(name: String): File {
        val relative = "com/bydcollector/collector/data/debug/$name"
        return listOf(
            File("src/main/kotlin/$relative"),
            File("app/src/main/kotlin/$relative")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $relative")
    }
}
