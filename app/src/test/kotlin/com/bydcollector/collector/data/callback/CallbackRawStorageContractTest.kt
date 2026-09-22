package com.bydcollector.collector.data.callback

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallbackRawStorageContractTest {
    @Test
    fun mainSchemasShareAdditiveRawCallbackIdentityAndPayloadTables() {
        val legacy = projectFile("app/src/main/assets/schema.sql").readText()
        val compact = projectFile("app/src/main/assets/schema_v2.sql").readText()

        listOf(legacy, compact).forEach { schema ->
            assertFalse(schema.lineSequence().any { it.trim() == "+" })
            assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS raw_callback_batches"))
            assertTrue(schema.contains("UNIQUE(boot_id, helper_generation, stream, epoch, batch_sequence)"))
            assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS raw_callback_events"))
            assertTrue(schema.contains("UNIQUE(boot_id, helper_generation, stream, epoch, event_sequence)"))
            assertTrue(schema.contains("raw_bits INTEGER NOT NULL"))
            assertTrue(schema.contains("raw_bytes BLOB"))
            assertTrue(schema.contains("received_elapsed_ms INTEGER NOT NULL"))
            assertTrue(schema.contains("source_wall_ms INTEGER"))
            assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS raw_callback_normalization_receipts"))
            assertTrue(schema.contains("delivery TEXT NOT NULL CHECK(delivery IN ('live', 'replay'))"))
            assertTrue(schema.contains("acquisition TEXT NOT NULL CHECK(acquisition = 'callback')"))
        }

        assertEquals(callbackTail(legacy), callbackTail(compact))
    }

    @Test
    fun helpersInstallCallbacksWithoutChangingSecondaryLegacyArchiveGate() {
        val main = sourceFile("data/local/TelemetryDatabaseHelper.kt").readText()
        val secondary = sourceFile("data/debug/DirectDebugDatabaseHelper.kt").readText()

        assertTrue(main.contains("const val DATABASE_VERSION = 13"))
        assertTrue(main.contains("CallbackRawSchema.create(db)"))
        assertTrue(secondary.contains("if (!db.isReadOnly && isCompactV2(db))"))
        assertTrue(secondary.contains("CallbackRawSchema.create(db)"))
        assertTrue(secondary.contains("private const val DATABASE_VERSION = 1"))
        assertTrue(secondary.contains("existing All data database must be archived"))
    }

    @Test
    fun importIsOneTransactionWithDigestIdempotenceAndEveryEventInsert() {
        val source = sourceFile("data/callback/CallbackRawStore.kt").readText()
        val import = source.substringAfter("fun importBatch(").substringBefore("fun pendingNormalization(")

        assertTrue(import.contains("TelemetryCallbackBatch.digest(batch.encode())"))
        assertTrue(import.contains("db.beginTransaction()"))
        assertTrue(import.contains("existing.second == digest"))
        assertTrue(import.contains("callback identity digest mismatch"))
        assertTrue(import.contains("batch.events.forEach { event -> insertEvent"))
        assertTrue(import.contains("db.setTransactionSuccessful()"))
        assertTrue(import.indexOf("insertBatch") < import.indexOf("batch.events.forEach"))
        assertTrue(import.indexOf("batch.events.forEach") < import.indexOf("db.setTransactionSuccessful()"))
        assertFalse(import.contains("distinct"))
        assertFalse(import.contains("rawBits =="))
    }

    @Test
    fun pendingAndReceiptSeamKeepSourceOrderAndAtomicNormalizedWrites() {
        val raw = sourceFile("data/callback/CallbackRawStore.kt").readText()
        val main = sourceFile("data/local/TelemetryStore.kt").readText()
        val normalized = sourceFile("data/normalized/NormalizedStateStore.kt").readText()
        val apply = main.substringAfter("fun applyCallbackNormalization(")
            .substringBefore("fun ensureCatalogImported(")

        assertTrue(raw.contains("WHERE r.event_id IS NULL AND e.stream = ? AND e.id > ?"))
        assertTrue(raw.contains("ORDER BY e.id"))
        assertTrue(raw.contains("db.beginTransactionNonExclusive()"))
        assertTrue(raw.contains("maxMainEventId(db).coerceAtLeast(normalizationScanFloor)"))
        assertTrue(raw.contains("firstOrNull()?.id?.minus(1L)"))
        assertTrue(raw.contains("require(stream == MAIN_STREAM)"))
        assertTrue(apply.contains("db.beginTransaction()"))
        assertTrue(apply.contains("isNormalizedInTransaction"))
        assertTrue(apply.contains("normalizedStore.applyObservationsInTransaction"))
        assertTrue(apply.contains("callbackRawStore.markNormalizedInTransaction"))
        assertTrue(apply.indexOf("applyObservationsInTransaction") < apply.indexOf("markNormalizedInTransaction"))
        assertTrue(apply.indexOf("markNormalizedInTransaction") < apply.indexOf("db.setTransactionSuccessful()"))
        assertTrue(normalized.contains("check(db.inTransaction())"))
    }

    @Test
    fun adaptersFenceMainAndSecondaryStreams() {
        val main = sourceFile("data/local/TelemetryStore.kt").readText()
        val secondary = sourceFile("data/debug/DirectDebugStore.kt").readText()

        assertTrue(main.contains("batch.stream != CallbackRawStore.MAIN_STREAM"))
        assertTrue(secondary.contains("batch.stream != CallbackRawStore.SECONDARY_STREAM"))
        assertTrue(main.contains("callbackRawStore.importBatch(batch, digest, delivery)"))
        assertTrue(secondary.contains("callbackRawStore.importBatch(batch, digest, delivery)"))
    }

    private fun callbackTail(schema: String): String = schema
        .substringAfter("CREATE TABLE IF NOT EXISTS raw_callback_batches")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun projectFile(path: String): File = File(path).takeIf { it.isFile }
        ?: File(path.removePrefix("app/")).takeIf { it.isFile }
        ?: error("Missing project file: $path")

    private fun sourceFile(relative: String): File = projectFile("app/src/main/kotlin/com/bydcollector/collector/$relative")
}
