package com.bydcollector.collector.data.callback

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.data.direct.DirectFidEntry
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectValueDecoders
import com.bydcollector.collector.data.local.PollValueColumns
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.TelemetryCallbackBatch

enum class CallbackDelivery(val storageValue: String) {
    LIVE("live"),
    REPLAY("replay")
}

sealed interface CallbackImportResult {
    data class Committed(
        val batchId: Long,
        val eventCount: Int,
        val duplicate: Boolean,
        val boundaryCount: Int = 0
    ) : CallbackImportResult
    data class Rejected(val reason: String) : CallbackImportResult
}

data class StoredCallbackEvent(
    val id: Long,
    val batchId: Long,
    val bootId: String,
    val helperGeneration: String,
    val stream: Int,
    val epoch: Long,
    val sourceEpoch: Long?,
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

data class StoredSourceBoundary(
    val id: Long,
    val batchId: Long,
    val bootId: String,
    val helperGeneration: String,
    val stream: Int,
    val transportEpoch: Long,
    val sourceEpoch: Long,
    val recordSequence: Long,
    val scope: Int,
    val state: Int,
    val device: Int,
    val fid: Int,
    val tx: Int,
    val captureWallMs: Long,
    val captureElapsedMs: Long,
    val reason: String,
    val diagnostic: String?,
    val seedKind: String?,
    val seedIdentity: String?,
    val seedRecordSequence: Long?,
    val seedSourceKey: String?,
    val seedValid: Boolean,
    val seedError: String?
)

class CallbackRawStore(
    private val database: () -> SQLiteDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onMainBatchInserted: (
        SQLiteDatabase, Long, TelemetryCallbackBatch, List<Long>, List<Long>, CallbackDelivery
    ) -> Unit = { _, _, _, _, _, _ -> }
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
                    CallbackImportResult.Committed(existing.first, batch.events.size, duplicate = true,
                        boundaryCount = batch.boundaries.size)
                } else {
                    CallbackImportResult.Rejected("callback identity digest mismatch")
                }
            } else {
                val batchId = insertBatch(db, batch, digest, delivery)
                val eventIds = batch.events.map { event -> insertEvent(db, batchId, batch, event) }
                val boundaryIds = batch.boundaries.map { boundary -> insertBoundary(db, batchId, batch, boundary) }
                if (batch.stream == MAIN_STREAM) onMainBatchInserted(db, batchId, batch, eventIds, boundaryIds, delivery)
                result = CallbackImportResult.Committed(batchId, batch.events.size, duplicate = false,
                    boundaryCount = batch.boundaries.size)
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
        val capturedMainFence = maxMainEventId(db).coerceAtLeast(normalizationScanFloor)
        val result = db.rawQuery(
            """
            SELECT e.id, e.batch_id, e.boot_id, e.helper_generation, e.stream, e.epoch, e.source_epoch,
                   b.batch_sequence, e.event_sequence, e.device, e.fid, e.native_type,
                   e.raw_bits, e.raw_bytes, e.received_wall_ms, e.received_elapsed_ms,
                   e.source_wall_ms, e.quality, b.delivery
            FROM raw_callback_events e
            JOIN raw_callback_batches b ON b.id = e.batch_id
            LEFT JOIN raw_callback_normalization_receipts r ON r.event_id = e.id
            WHERE r.event_id IS NULL AND e.stream = ? AND e.id > ? AND e.id <= ?
            ORDER BY e.id
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                MAIN_STREAM.toString(), normalizationScanFloor.toString(),
                capturedMainFence.toString(), limit.toString()
            )
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
                            sourceEpoch = if (cursor.isNull(6)) null else cursor.getLong(6),
                            batchSequence = cursor.getLong(7),
                            eventSequence = cursor.getLong(8),
                            device = cursor.getInt(9),
                            fid = cursor.getInt(10),
                            nativeType = cursor.getInt(11),
                            rawBits = cursor.getInt(12),
                            rawBytes = if (cursor.isNull(13)) null else cursor.getBlob(13),
                            receivedWallMs = cursor.getLong(14),
                            receivedElapsedMs = cursor.getLong(15),
                            sourceWallMs = if (cursor.isNull(16)) null else cursor.getLong(16),
                            quality = cursor.getString(17),
                            delivery = CallbackDelivery.entries.first { it.storageValue == cursor.getString(18) }
                        )
                    )
                }
            }
        }
        normalizationScanFloor = result.firstOrNull()?.id?.minus(1L) ?: capturedMainFence
        return result
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
            if (batch.formatVersion() >= TelemetryCallbackBatch.VERSION) put("source_epoch", batch.sourceEpoch)
            put("batch_sequence", batch.batchSequence)
            put("digest", digest)
            put("acquisition", "callback")
            put("delivery", delivery.storageValue)
            put("event_count", batch.events.size)
            put("first_event_sequence", batch.events.firstOrNull()?.sequence ?: -1L)
            put("last_event_sequence", batch.events.lastOrNull()?.sequence ?: -1L)
            put("record_count", batch.recordCount)
            put("first_record_sequence", batch.firstRecordSequence)
            put("last_record_sequence", batch.lastRecordSequence)
            put("first_capture_wall_ms", batch.firstCaptureWallMs)
            put("last_capture_wall_ms", batch.lastCaptureWallMs)
            put("first_capture_elapsed_ms", batch.firstCaptureElapsedMs)
            put("last_capture_elapsed_ms", batch.lastCaptureElapsedMs)
            put("imported_at_ms", nowMs())
        }
    )

    private fun insertEvent(
        db: SQLiteDatabase,
        batchId: Long,
        batch: TelemetryCallbackBatch,
        event: TelemetryCallbackBatch.Event
    ): Long = db.insertOrThrow(
            "raw_callback_events",
            null,
            ContentValues().apply {
                put("batch_id", batchId)
                put("boot_id", batch.bootId)
                put("helper_generation", batch.helperGeneration)
                put("stream", batch.stream)
                put("epoch", batch.epoch)
                if (batch.formatVersion() >= TelemetryCallbackBatch.VERSION) put("source_epoch", batch.sourceEpoch)
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

    private fun insertBoundary(
        db: SQLiteDatabase,
        batchId: Long,
        batch: TelemetryCallbackBatch,
        boundary: TelemetryCallbackBatch.SourceBoundary
    ): Long {
        val seed = if (boundary.state == TelemetryCallbackBatch.SourceBoundary.STATE_AVAILABLE) {
            validateSeed(db, batch, boundary)
        } else {
            SeedValidation(false, null, null)
        }
        return db.insertOrThrow(
            "raw_callback_boundaries",
            null,
            ContentValues().apply {
                put("batch_id", batchId)
                put("boot_id", batch.bootId)
                put("helper_generation", batch.helperGeneration)
                put("stream", batch.stream)
                put("epoch", batch.epoch)
                put("source_epoch", batch.sourceEpoch)
                put("record_sequence", boundary.recordSequence)
                put("scope", boundary.scope)
                put("state", boundary.state)
                put("device", boundary.device)
                put("fid", boundary.fid)
                put("tx", boundary.tx)
                put("capture_wall_ms", boundary.captureWallMs)
                put("capture_elapsed_ms", boundary.captureElapsedMs)
                put("reason", boundary.reason)
                put("diagnostic", boundary.diagnostic)
                put("seed_kind", boundary.seedKind)
                put("seed_identity", boundary.seedIdentity)
                put("seed_record_sequence", boundary.seedRecordSequence)
                put("seed_source_key", seed.sourceKey)
                put("seed_valid", if (seed.valid) 1 else 0)
                put("seed_error", seed.error)
            }
        )
    }

    private fun validateSeed(
        db: SQLiteDatabase,
        batch: TelemetryCallbackBatch,
        boundary: TelemetryCallbackBatch.SourceBoundary
    ): SeedValidation = when (boundary.seedKind) {
        "callback" -> {
            val sequence = checkNotNull(boundary.seedRecordSequence)
            val event = batch.events.firstOrNull { it.sequence == sequence }
                ?: findCallbackSeed(db, batch, sequence)
            when {
                event == null -> SeedValidation(false, "callback_seed_not_found", null)
                batch.eventIdentity(event) != boundary.seedIdentity -> SeedValidation(false, "callback_seed_identity_mismatch", null)
                event.rawBytes() != null || !usableSeedQuality(event.quality) ->
                    SeedValidation(false, "callback_seed_not_usable", null)
                else -> {
                    val tx = nativeTx(event.nativeType)
                    val entry = DirectFidRegistry.entries.singleOrNull {
                        it.dev == event.device && it.fid == event.fid && it.tx == tx
                    }
                    if (tx == null || entry == null || !matchesScope(event.device, event.fid, tx, boundary)) {
                        SeedValidation(false, "callback_seed_address_mismatch", null)
                    } else SeedValidation(true, null, entry.key)
                }
            }
        }
        "get" -> validateGetSeed(db, batch, boundary)
        else -> SeedValidation(false, "unsupported_seed_kind", null)
    }

    private fun findCallbackSeed(
        db: SQLiteDatabase,
        batch: TelemetryCallbackBatch,
        sequence: Long
    ): TelemetryCallbackBatch.Event? {
        val sourceEpoch = if (batch.formatVersion() >= TelemetryCallbackBatch.VERSION) batch.sourceEpoch else return null
        return db.rawQuery(
            "SELECT device, fid, native_type, raw_bits, raw_bytes, received_wall_ms, received_elapsed_ms, source_wall_ms, quality " +
                "FROM raw_callback_events WHERE boot_id = ? AND helper_generation = ? AND stream = ? AND source_epoch = ? AND event_sequence = ? LIMIT 1",
            arrayOf(batch.bootId, batch.helperGeneration, batch.stream.toString(), sourceEpoch.toString(), sequence.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            TelemetryCallbackBatch.Event(
                sequence, cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3),
                if (cursor.isNull(4)) null else cursor.getBlob(4), cursor.getLong(5), cursor.getLong(6),
                if (cursor.isNull(7)) null else cursor.getLong(7), cursor.getString(8)
            )
        }
    }

    private fun validateGetSeed(
        db: SQLiteDatabase,
        batch: TelemetryCallbackBatch,
        boundary: TelemetryCallbackBatch.SourceBoundary
    ): SeedValidation {
        if (batch.stream == SECONDARY_STREAM) {
            return validateSecondaryGetSeed(
                db, batch.bootId, batch.helperGeneration, batch.stream, batch.sourceEpoch,
                boundary.seedIdentity, boundary.seedRecordSequence, boundary.scope,
                boundary.device, boundary.fid, boundary.tx
            )
        }
        if (!tableExists(db, "poll_get_lineage")) return SeedValidation(false, "get_seed_pending", null)
        val sequence = checkNotNull(boundary.seedRecordSequence)
        val match = db.rawQuery(
            "SELECT poll_id, source_key, helper_boot_id, helper_generation, stream, source_epoch, " +
                "record_sequence, device, fid, tx, superseded_by_callback " +
                "FROM poll_get_lineage WHERE read_identity = ? AND record_sequence = ? LIMIT 1",
            arrayOf(boundary.seedIdentity, sequence.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            GetSeedRow(
                pollId = cursor.getLong(0),
                sourceKey = cursor.getString(1),
                helperBootId = cursor.getString(2),
                helperGeneration = cursor.getString(3),
                stream = cursor.getInt(4),
                sourceEpoch = cursor.getLong(5),
                recordSequence = cursor.getLong(6),
                device = cursor.getInt(7),
                fid = cursor.getInt(8),
                tx = cursor.getInt(9),
                supersededByCallback = cursor.getInt(10) != 0
            ).also { check(!cursor.moveToNext()) { "GET seed lineage identity is not unique: ${boundary.seedIdentity}/$sequence" } }
        } ?: return SeedValidation(false, "get_seed_pending", null)
        val entry = DirectFidRegistry.entries.singleOrNull { it.key == match.sourceKey }
            ?: return SeedValidation(false, "get_seed_address_unknown", null)
        if (match.helperBootId != batch.bootId || match.helperGeneration != batch.helperGeneration ||
            match.stream != batch.stream || match.sourceEpoch != batch.sourceEpoch ||
            match.recordSequence != sequence || match.device != entry.dev || match.fid != entry.fid ||
            match.tx != entry.tx || match.supersededByCallback || !matchesGetSeedScope(entry, boundary)
        ) return SeedValidation(false, "get_seed_lineage_mismatch", null)
        val rawBits = db.rawQuery(
            "SELECT ${PollValueColumns.raw(entry.key)} FROM poll_values WHERE poll_id = ? LIMIT 1",
            arrayOf(match.pollId.toString())
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0).toIntOrNull() else null }
            ?: return SeedValidation(false, "get_seed_value_missing", null)
        return if (DirectValueDecoders.decode(entry, rawBits) != null) SeedValidation(true, null, entry.key)
        else SeedValidation(false, "get_seed_value_invalid", null)
    }

    private fun validateSecondaryGetSeed(
        db: SQLiteDatabase,
        bootId: String,
        helperGeneration: String,
        stream: Int,
        sourceEpoch: Long,
        readIdentity: String?,
        recordSequence: Long?,
        scope: Int,
        device: Int,
        fid: Int,
        tx: Int
    ): SeedValidation {
        if (readIdentity == null || recordSequence == null ||
            !tableExists(db, "debug_secondary_get_lineage")) {
            return SeedValidation(false, "get_seed_pending", null)
        }
        val row = db.rawQuery(
            "SELECT source_key, helper_boot_id, helper_generation, stream, source_epoch, device, fid, tx, " +
                "superseded_by_callback, status, raw_present, raw_int " +
                "FROM debug_secondary_get_lineage WHERE read_identity = ? AND record_sequence = ? LIMIT 1",
            arrayOf(readIdentity, recordSequence.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            SecondaryGetSeedRow(
                sourceKey = cursor.getString(0),
                helperBootId = cursor.getString(1),
                helperGeneration = cursor.getString(2),
                stream = cursor.getInt(3),
                sourceEpoch = cursor.getLong(4),
                device = cursor.getInt(5),
                fid = cursor.getInt(6),
                tx = cursor.getInt(7),
                supersededByCallback = cursor.getInt(8) != 0,
                status = cursor.getInt(9),
                rawPresent = cursor.getInt(10) != 0,
                raw = if (cursor.isNull(11)) null else cursor.getInt(11)
            ).also { check(!cursor.moveToNext()) { "Secondary GET seed lineage is not unique: $readIdentity/$recordSequence" } }
        } ?: return SeedValidation(false, "get_seed_pending", null)
        if (row.helperBootId != bootId || row.helperGeneration != helperGeneration || row.stream != stream ||
            row.sourceEpoch != sourceEpoch || !matchesGetSeedScope(row.device, row.fid, row.tx, scope, device, fid, tx)) {
            return SeedValidation(false, "get_seed_lineage_mismatch", null)
        }
        if (row.supersededByCallback || row.status != CollectorHelperProtocol.STATUS_OK || !row.rawPresent || row.raw == null) {
            return SeedValidation(false, "get_seed_value_invalid", null)
        }
        return SeedValidation(true, null, row.sourceKey)
    }

    fun resolvePendingSecondaryGetSeedsInTransaction(db: SQLiteDatabase, readIdentity: String) {
        check(db.inTransaction()) { "secondary GET seed resolution requires a transaction" }
        if (!tableExists(db, "debug_secondary_get_lineage")) return
        val pending = db.rawQuery(
            "SELECT id, batch_id, boot_id, helper_generation, stream, epoch, source_epoch, record_sequence, " +
                "scope, state, device, fid, tx, capture_wall_ms, capture_elapsed_ms, reason, diagnostic, " +
                "seed_kind, seed_identity, seed_record_sequence, seed_source_key, seed_valid, seed_error " +
                "FROM raw_callback_boundaries WHERE stream = ? AND state = ? AND seed_kind = 'get' " +
                "AND seed_error = 'get_seed_pending' AND seed_identity = ? AND seed_record_sequence IS NOT NULL " +
                "ORDER BY id",
            arrayOf(SECONDARY_STREAM.toString(), TelemetryCallbackBatch.SourceBoundary.STATE_AVAILABLE.toString(), readIdentity)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    StoredSourceBoundary(
                        id = cursor.getLong(0), batchId = cursor.getLong(1), bootId = cursor.getString(2),
                        helperGeneration = cursor.getString(3), stream = cursor.getInt(4),
                        transportEpoch = cursor.getLong(5), sourceEpoch = cursor.getLong(6),
                        recordSequence = cursor.getLong(7), scope = cursor.getInt(8), state = cursor.getInt(9),
                        device = cursor.getInt(10), fid = cursor.getInt(11), tx = cursor.getInt(12),
                        captureWallMs = cursor.getLong(13), captureElapsedMs = cursor.getLong(14),
                        reason = cursor.getString(15), diagnostic = cursor.getNullableString(16),
                        seedKind = cursor.getNullableString(17), seedIdentity = cursor.getNullableString(18),
                        seedRecordSequence = cursor.getNullableLong(19), seedSourceKey = cursor.getNullableString(20),
                        seedValid = cursor.getInt(21) != 0, seedError = cursor.getNullableString(22)
                    )
                )
            }
        }
        pending.forEach { boundary ->
            val result = validateSecondaryGetSeed(
                db, boundary.bootId, boundary.helperGeneration, boundary.stream, boundary.sourceEpoch,
                boundary.seedIdentity, boundary.seedRecordSequence, boundary.scope,
                boundary.device, boundary.fid, boundary.tx
            )
            if (result.error == "get_seed_pending") return@forEach
            db.update(
                "raw_callback_boundaries",
                ContentValues().apply {
                    put("seed_source_key", result.sourceKey)
                    put("seed_valid", if (result.valid) 1 else 0)
                    put("seed_error", result.error)
                },
                "id = ? AND seed_valid = 0 AND seed_error = 'get_seed_pending'",
                arrayOf(boundary.id.toString())
            )
        }
    }

    private fun matchesGetSeedScope(
        device: Int,
        fid: Int,
        tx: Int,
        scope: Int,
        expectedDevice: Int,
        expectedFid: Int,
        expectedTx: Int
    ): Boolean = when (scope) {
        TelemetryCallbackBatch.SourceBoundary.SCOPE_ADDRESS ->
            device == expectedDevice && fid == expectedFid && tx == expectedTx
        TelemetryCallbackBatch.SourceBoundary.SCOPE_NATIVE_KEY ->
            device == expectedDevice && fid == expectedFid
        TelemetryCallbackBatch.SourceBoundary.SCOPE_STREAM -> true
        else -> false
    }

    private fun matchesGetSeedScope(
        entry: DirectFidEntry,
        boundary: TelemetryCallbackBatch.SourceBoundary
    ): Boolean = when (boundary.scope) {
        TelemetryCallbackBatch.SourceBoundary.SCOPE_ADDRESS ->
            entry.dev == boundary.device && entry.fid == boundary.fid && entry.tx == boundary.tx
        TelemetryCallbackBatch.SourceBoundary.SCOPE_NATIVE_KEY ->
            entry.dev == boundary.device && entry.fid == boundary.fid
        TelemetryCallbackBatch.SourceBoundary.SCOPE_STREAM -> true
        else -> false
    }

    private fun matchesScope(device: Int, fid: Int, tx: Int?, boundary: TelemetryCallbackBatch.SourceBoundary): Boolean = when (boundary.scope) {
        TelemetryCallbackBatch.SourceBoundary.SCOPE_ADDRESS ->
            device == boundary.device && fid == boundary.fid && tx != null && tx == boundary.tx
        TelemetryCallbackBatch.SourceBoundary.SCOPE_NATIVE_KEY -> device == boundary.device && fid == boundary.fid
        TelemetryCallbackBatch.SourceBoundary.SCOPE_STREAM -> true
        else -> false
    }

    private fun nativeTx(nativeType: Int): Int? = when (nativeType) {
        TelemetryCallbackBatch.TYPE_INT -> CollectorHelperProtocol.AUTO_TX_INT
        TelemetryCallbackBatch.TYPE_FLOAT -> CollectorHelperProtocol.AUTO_TX_FLOAT
        else -> null
    }

    private fun usableSeedQuality(quality: String): Boolean = quality == "callback" || quality == "usable"

    private data class GetSeedRow(
        val pollId: Long,
        val sourceKey: String,
        val helperBootId: String,
        val helperGeneration: String,
        val stream: Int,
        val sourceEpoch: Long,
        val recordSequence: Long,
        val device: Int,
        val fid: Int,
        val tx: Int,
        val supersededByCallback: Boolean
    )

    private data class SecondaryGetSeedRow(
        val sourceKey: String,
        val helperBootId: String,
        val helperGeneration: String,
        val stream: Int,
        val sourceEpoch: Long,
        val device: Int,
        val fid: Int,
        val tx: Int,
        val supersededByCallback: Boolean,
        val status: Int,
        val rawPresent: Boolean,
        val raw: Int?
    )

    private data class SeedValidation(val valid: Boolean, val error: String?, val sourceKey: String?)

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1", arrayOf(table)
    ).use { it.moveToFirst() }

    private fun Cursor.getNullableString(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.getNullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)

    companion object {
        const val MAIN_STREAM = 1
        const val SECONDARY_STREAM = 2
        const val MAX_PENDING_PAGE = 512
        private val DIGEST = Regex("[0-9a-f]{64}")
    }
}
