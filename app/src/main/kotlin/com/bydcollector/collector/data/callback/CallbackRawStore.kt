package com.bydcollector.collector.data.callback

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.direct.TelemetryCallbackBatch

enum class CallbackDelivery(val storageValue: String) {
    LIVE("live"),
    REPLAY("replay")
}

sealed interface CallbackImportResult {
    data class Committed(val batchId: Long, val eventCount: Int, val duplicate: Boolean) : CallbackImportResult
    data class Rejected(val reason: String) : CallbackImportResult
}

data class StoredCallbackEvent(
    val id: Long,
    val batchId: Long,
    val bootId: String,
    val helperGeneration: String,
    val stream: Int,
    val epoch: Long,
    val batchSequence: Long,
    val eventSequence: Long,
    val device: Int,
    val fid: Int,
    val nativeType: Int,
    val rawBits: Int,
    val rawBytes: ByteArray?,
    val receivedWallMs: Long,
    val receivedElapsedMs: Long,
    val sourceWallMs: Long?,
    val quality: String,
    val delivery: CallbackDelivery
)

class CallbackRawStore(
    private val database: () -> SQLiteDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private var normalizationScanFloor = 0L

    fun importBatch(
        batch: TelemetryCallbackBatch,
        digest: String,
        delivery: CallbackDelivery
    ): CallbackImportResult {
        if (!digest.matches(DIGEST)) return CallbackImportResult.Rejected("invalid callback digest")
        val actualDigest = runCatching { TelemetryCallbackBatch.digest(batch.encode()) }
            .getOrElse { return CallbackImportResult.Rejected("callback encoding failed: ${it.message}") }
        if (actualDigest != digest) return CallbackImportResult.Rejected("callback payload digest mismatch")

        val db = database()
        var result: CallbackImportResult? = null
        db.beginTransaction()
        try {
            val existing = existingBatch(db, batch)
            if (existing != null) {
                result = if (existing.second == digest) {
                    CallbackImportResult.Committed(existing.first, batch.events.size, duplicate = true)
                } else {
                    CallbackImportResult.Rejected("callback identity digest mismatch")
                }
            } else {
                val batchId = insertBatch(db, batch, digest, delivery)
                batch.events.forEach { event -> insertEvent(db, batchId, batch, event) }
                result = CallbackImportResult.Committed(batchId, batch.events.size, duplicate = false)
            }
            db.setTransactionSuccessful()
        } catch (error: SQLiteConstraintException) {
            if (error.message?.contains("UNIQUE constraint failed: raw_callback_events.") == true) {
                result = CallbackImportResult.Rejected("callback event identity conflict")
            } else {
                throw error
            }
        } finally {
            db.endTransaction()
        }
        return checkNotNull(result)
    }

    @Synchronized
    fun pendingNormalization(limit: Int): List<StoredCallbackEvent> {
        require(limit in 1..MAX_PENDING_PAGE) { "callback normalization page must be 1..$MAX_PENDING_PAGE" }
        val db = database()
        var result: List<StoredCallbackEvent>? = null
        db.beginTransactionNonExclusive()
        try {
            result = db.rawQuery(
                """
                SELECT e.id, e.batch_id, e.boot_id, e.helper_generation, e.stream, e.epoch,
                       b.batch_sequence, e.event_sequence, e.device, e.fid, e.native_type,
                       e.raw_bits, e.raw_bytes, e.received_wall_ms, e.received_elapsed_ms,
                       e.source_wall_ms, e.quality, b.delivery
                FROM raw_callback_events e
                JOIN raw_callback_batches b ON b.id = e.batch_id
                LEFT JOIN raw_callback_normalization_receipts r ON r.event_id = e.id
                WHERE r.event_id IS NULL AND e.stream = ? AND e.id > ?
                ORDER BY e.id
                LIMIT ?
                """.trimIndent(),
                arrayOf(MAIN_STREAM.toString(), normalizationScanFloor.toString(), limit.toString())
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            StoredCallbackEvent(
                                id = cursor.getLong(0),
                                batchId = cursor.getLong(1),
                                bootId = cursor.getString(2),
                                helperGeneration = cursor.getString(3),
                                stream = cursor.getInt(4),
                                epoch = cursor.getLong(5),
                                batchSequence = cursor.getLong(6),
                                eventSequence = cursor.getLong(7),
                                device = cursor.getInt(8),
                                fid = cursor.getInt(9),
                                nativeType = cursor.getInt(10),
                                rawBits = cursor.getInt(11),
                                rawBytes = if (cursor.isNull(12)) null else cursor.getBlob(12),
                                receivedWallMs = cursor.getLong(13),
                                receivedElapsedMs = cursor.getLong(14),
                                sourceWallMs = if (cursor.isNull(15)) null else cursor.getLong(15),
                                quality = cursor.getString(16),
                                delivery = CallbackDelivery.entries.first { it.storageValue == cursor.getString(17) }
                            )
                        )
                    }
                }
            }
            normalizationScanFloor = result!!.firstOrNull()?.id?.minus(1L)
                ?: maxMainEventId(db).coerceAtLeast(normalizationScanFloor)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return checkNotNull(result)
    }

    private fun maxMainEventId(db: SQLiteDatabase): Long = db.rawQuery(
        "SELECT COALESCE(MAX(id), 0) FROM raw_callback_events WHERE stream = ?",
        arrayOf(MAIN_STREAM.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    fun isNormalizedInTransaction(db: SQLiteDatabase, eventId: Long): Boolean {
        check(db.inTransaction()) { "normalization receipt check requires a transaction" }
        return db.rawQuery(
            "SELECT 1 FROM raw_callback_normalization_receipts WHERE event_id = ? LIMIT 1",
            arrayOf(eventId.toString())
        ).use { it.moveToFirst() }
    }

    fun requireMainEventInTransaction(db: SQLiteDatabase, eventId: Long) {
        check(db.inTransaction()) { "callback event check requires a transaction" }
        val stream = db.rawQuery(
            "SELECT stream FROM raw_callback_events WHERE id = ? LIMIT 1",
            arrayOf(eventId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
        require(stream == MAIN_STREAM) { "callback normalization requires an existing Main event" }
    }

    fun markNormalizedInTransaction(db: SQLiteDatabase, eventId: Long) {
        check(db.inTransaction()) { "normalization receipt write requires a transaction" }
        db.insertOrThrow(
            "raw_callback_normalization_receipts",
            null,
            ContentValues().apply {
                put("event_id", eventId)
                put("normalized_at_ms", nowMs())
            }
        )
    }

    private fun existingBatch(db: SQLiteDatabase, batch: TelemetryCallbackBatch): Pair<Long, String>? =
        db.rawQuery(
            """
            SELECT id, digest FROM raw_callback_batches
            WHERE boot_id = ? AND helper_generation = ? AND stream = ? AND epoch = ? AND batch_sequence = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                batch.bootId,
                batch.helperGeneration,
                batch.stream.toString(),
                batch.epoch.toString(),
                batch.batchSequence.toString()
            )
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null }

    private fun insertBatch(
        db: SQLiteDatabase,
        batch: TelemetryCallbackBatch,
        digest: String,
        delivery: CallbackDelivery
    ): Long = db.insertOrThrow(
        "raw_callback_batches",
        null,
        ContentValues().apply {
            put("boot_id", batch.bootId)
            put("helper_generation", batch.helperGeneration)
            put("stream", batch.stream)
            put("epoch", batch.epoch)
            put("batch_sequence", batch.batchSequence)
            put("digest", digest)
            put("acquisition", "callback")
            put("delivery", delivery.storageValue)
            put("event_count", batch.events.size)
            put("first_event_sequence", batch.events.first().sequence)
            put("last_event_sequence", batch.events.last().sequence)
            put("imported_at_ms", nowMs())
        }
    )

    private fun insertEvent(
        db: SQLiteDatabase,
        batchId: Long,
        batch: TelemetryCallbackBatch,
        event: TelemetryCallbackBatch.Event
    ) {
        db.insertOrThrow(
            "raw_callback_events",
            null,
            ContentValues().apply {
                put("batch_id", batchId)
                put("boot_id", batch.bootId)
                put("helper_generation", batch.helperGeneration)
                put("stream", batch.stream)
                put("epoch", batch.epoch)
                put("event_sequence", event.sequence)
                put("device", event.device)
                put("fid", event.fid)
                put("native_type", event.nativeType)
                put("raw_bits", event.rawBits)
                put("raw_bytes", event.rawBytes())
                put("received_wall_ms", event.receivedWallMs)
                put("received_elapsed_ms", event.receivedElapsedMs)
                put("source_wall_ms", event.sourceWallMs)
                put("quality", event.quality)
            }
        )
    }

    companion object {
        const val MAIN_STREAM = 1
        const val SECONDARY_STREAM = 2
        const val MAX_PENDING_PAGE = 512
        private val DIGEST = Regex("[0-9a-f]{64}")
    }
}
