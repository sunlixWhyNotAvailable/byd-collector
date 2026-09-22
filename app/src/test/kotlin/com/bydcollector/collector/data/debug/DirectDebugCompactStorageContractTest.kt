package com.bydcollector.collector.data.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectDebugCompactStorageContractTest {
    @Test
    fun rawRepresentationsAreDerivedWithoutPersistedDuplicates() {
        val minusOne = DirectDebugObserved(status = 0, rawPresent = true, raw = -1, error = null)
        val floatBits = DirectDebugObserved(
            status = 0,
            rawPresent = true,
            raw = 12.5f.toRawBits(),
            error = null
        )
        val missing = DirectDebugObserved(status = -1, rawPresent = false, raw = null, error = "missing")

        assertEquals("0xffffffff", minusOne.rawHex)
        assertEquals(12.5, floatBits.rawFloat)
        assertNull(missing.rawHex)
        assertNull(missing.rawFloat)
    }

    @Test
    fun schemaUsesMarkerGlobalCatalogStateAndOnlyOneExplicitHistoryIndex() {
        val source = sourceFile("DirectDebugDatabaseHelper.kt").readText()

        assertTrue(source.contains("CREATE TABLE storage_meta"))
        assertTrue(source.contains("const val FORMAT_VERSION = 2"))
        assertTrue(source.contains("PRIMARY KEY(catalog_version_id, candidate_id)"))
        assertTrue(source.contains(") WITHOUT ROWID"))
        assertTrue(source.contains("idx_debug_direct_readings_candidate_id"))
        assertEquals(1, Regex("CREATE INDEX ").findAll(source).count())
        assertFalse(source.contains("raw_hex TEXT"))
        assertFalse(source.contains("float_value REAL"))
        assertFalse(source.contains("PRIMARY KEY(session_id, candidate_id)"))
        assertFalse(source.contains("DROP TABLE"))
    }

    @Test
    fun hotWriterBulkLoadsStateAndWritesOnlyTransitionsPlusOneSweepAggregate() {
        val source = sourceFile("DirectDebugStore.kt").readText()

        assertTrue(source.contains("loadCandidateState(db, catalogVersionId)"))
        assertTrue(source.contains("val transitions = ArrayList<DebugTransition>()"))
        assertTrue(source.contains("transitions.forEach { transition ->"))
        assertTrue(source.contains("insertTransition("))
        assertTrue(source.contains("cycle_count = cycle_count + 1"))
        assertTrue(source.contains("attempted_count = attempted_count + ?"))
        assertTrue(source.contains("candidateState[transition.candidateId] = transition.observed.toPrevious()"))
        assertFalse(source.contains("last_sampled_at"))
        assertFalse(source.contains("read_count = read_count + 1"))
        assertFalse(source.contains("raw_hex\""))
        assertFalse(source.contains("float_value\""))
    }

    @Test
    fun sourceVersionChangeReconcilesMetadataAndKeepsTransitionOnlyState() {
        val source = sourceFile("DirectDebugStore.kt").readText()

        assertTrue(source.contains("put(\"feature_names\", parameter.featureNames)"))
        assertTrue(source.contains("put(\"feature_refs\", parameter.featureRefs)"))
        assertTrue(source.contains("ensureCandidates(db, definitions, sourceVersionChanged)"))
        assertTrue(source.contains("existingId != null && reconcileMetadata"))
        assertTrue(source.contains("db.update(\"debug_direct_candidates\", values, \"id = ?\""))
        assertTrue(source.contains("WHERE catalog_version_id = ?"))
        assertTrue(source.contains("if (previous == null) return \"initial\""))
        assertFalse(source.contains("UPDATE debug_direct_candidate_state SET catalog_version_id"))
    }

    @Test
    fun legacyDatabaseRemainsInspectableButPollingFailsClosed() {
        val helper = sourceFile("DirectDebugDatabaseHelper.kt").readText()
        val store = sourceFile("DirectDebugStore.kt").readText()

        assertTrue(helper.contains("private const val DATABASE_VERSION = 1"))
        assertTrue(helper.contains("fun isCompactV2(db: SQLiteDatabase): Boolean"))
        assertTrue(store.contains("fun isCompactV2(): Boolean"))
        assertTrue(store.contains("if (!DirectDebugDatabaseHelper.isCompactV2(db)) return legacyStatus"))
        assertTrue(store.contains("Legacy debug database must be archived before round-robin polling starts"))
        assertFalse(store.contains("fun checkpointForArchive()"))
        assertTrue(store.contains("fun verifyWritableDatabase(): Boolean"))
    }

    @Test
    fun dashboardReadingCountIncludesRawCallbacksOnlyForCompactStorage() {
        val store = sourceFile("DirectDebugStore.kt").readText()
        val count = store.substringAfter("fun dashboardReadingCount(cancellationSignal: CancellationSignal? = null): Long")
            .substringBefore("fun status(")

        assertTrue(count.contains("DirectDebugDatabaseHelper.isCompactV2"))
        assertTrue(count.contains("COUNT(*) FROM debug_direct_readings"))
        assertTrue(count.contains("COUNT(*) FROM raw_callback_events"))
    }

    private fun sourceFile(name: String): File {
        val relative = "com/bydcollector/collector/data/debug/$name"
        return listOf(
            File("src/main/kotlin/$relative"),
            File("app/src/main/kotlin/$relative")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $relative")
    }
}
