package com.bydcollector.collector.data.trips

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TripCompletionPersistenceContractTest {
    @Test
    fun schemaAddsDurableOutboxAndIndependentHighWaterWithinUnreleasedV6() {
        val schema = source("TripDatabaseHelper.kt")

        assertTrue(schema.contains("const val DATABASE_VERSION = 6"))
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS trip_completion_state"))
        assertTrue(schema.contains("high_water_sequence INTEGER NOT NULL"))
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS trip_completion_outbox"))
        assertTrue(schema.contains("identity TEXT NOT NULL UNIQUE"))
        assertTrue(schema.contains("if (oldVersion < 6) createTripCompletions(db)"))
    }

    @Test
    fun closeAndIntentInsertShareOneTransactionAndRetryReturnsExistingIdentity() {
        val store = source("TripStore.kt")
        val close = store.substringAfter("fun closeSessionWithCompletion(")
            .substringBefore("fun completionWatermark()")

        assertTrue(close.contains("db.beginTransaction()"))
        assertTrue(close.contains("session?.let { upsertSession(db, it, preserveDurableEnergy = true) }"))
        assertTrue(close.contains("completionByIdentity(db, completion.identity)"))
        assertTrue(close.contains("db.insertOrThrow("))
        assertTrue(close.contains("\"trip_completion_outbox\""))
        assertTrue(close.contains("db.update("))
        assertTrue(close.contains("db.setTransactionSuccessful()"))
        assertTrue(close.contains("db.endTransaction()"))
        assertFalse(close.contains("runCatching"))
    }

    @Test
    fun fifoReadAndExactAckRetainMonotonicWatermark() {
        val store = source("TripStore.kt")

        assertTrue(store.contains("WHERE sequence <= ? ORDER BY sequence LIMIT ?"))
        assertTrue(store.contains("sequence = ? AND identity = ?"))
        assertTrue(store.contains("fun completionWatermark(): Long"))
        assertTrue(store.contains("fun acknowledgeCompletion(sequence: Long, identity: String): Boolean"))
        assertFalse(
            store.substringAfter("fun acknowledgeCompletion(sequence: Long, identity: String): Boolean")
                .substringBefore("internal fun allPendingCompletions")
                .contains("high_water_sequence")
        )
    }

    @Test
    fun compressionCopiesRefreshesAndReopenVerifiesOutboxAndHighWater() {
        val compression = source("TripCompression.kt")

        assertTrue(compression.contains("snapshotStore.completionWatermark()"))
        assertTrue(compression.contains("snapshotStore.allPendingCompletions()"))
        assertTrue(compression.contains("if (store.tripCompletionsChanged())"))
        assertTrue(compression.contains("store.completionWatermark()"))
        assertTrue(compression.contains("store.allPendingCompletions()"))
        assertTrue(compression.contains("sameTripCompletions(store, candidateStore)"))
        assertTrue(compression.contains("store.completionWatermark() == expectedCompletionWatermark"))
        assertTrue(compression.contains("store.allPendingCompletions() == expectedCompletions"))
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/bydcollector/collector/data/trips/$name"),
        File("app/src/main/kotlin/com/bydcollector/collector/data/trips/$name")
    ).firstOrNull(File::isFile)?.readText() ?: error("Missing $name")
}
