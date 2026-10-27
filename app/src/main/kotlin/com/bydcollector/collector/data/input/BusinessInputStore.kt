package com.bydcollector.collector.data.input

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.data.callback.CallbackDelivery
import com.bydcollector.collector.data.callback.StoredCallbackEvent
import com.bydcollector.collector.data.callback.StoredSourceBoundary
import com.bydcollector.collector.data.direct.DirectGetLineage
import com.bydcollector.collector.data.direct.DirectFidEntry
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectValueDecoders
import com.bydcollector.collector.data.local.PersistedPollInput
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.local.PollValueColumns
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceOrder
import com.bydcollector.collector.data.normalized.NormalizedSourceOrdering
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.data.normalized.normalizedCallbackSourceStamp
import com.bydcollector.collector.data.normalized.normalizedHelperSourceGeneratorId
import com.bydcollector.collector.data.normalized.normalizedSourceInputForPollReading
import com.bydcollector.collector.data.normalized.usableNormalizedCallbackQuality
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.direct.CallbackValueSource
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import org.json.JSONArray
import java.time.Instant
import java.time.OffsetDateTime

class BusinessInputStore(
    private val database: () -> SQLiteDatabase,
    private val currentBootId: () -> String? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val businessInputKeys: Set<String> = BusinessInputCheckpointKeys.ALL
) {
    private data class PollValueLayout(
        val database: SQLiteDatabase,
        val catalogVersionId: Long,
        val fields: Set<String>,
        val columns: Set<String>,
        val hasDecodedDictionary: Boolean
    )

    private data class OrderedCallbackRecord(
        val sequence: Long,
        val rawId: Long,
        val event: TelemetryCallbackBatch.Event?,
        val boundary: TelemetryCallbackBatch.SourceBoundary?
    )

    private data class PendingGetLineage(
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

    private val pollValueLayoutLock = Any()
    @Volatile private var pollValueLayoutCache: PollValueLayout? = null
    private val entriesByAddress = DirectFidRegistry.entries
        .groupBy { Triple(it.dev, it.fid, it.tx) }
        .mapValues { (_, entries) -> entries.singleOrNull() }
    private val businessDeviceFids = DirectFidRegistry.entries
        .filter { it.key in businessInputKeys }
        .mapTo(mutableSetOf()) { it.dev to it.fid }
    private val businessTypedAddresses = DirectFidRegistry.entries
        .filter { it.key in businessInputKeys }
        .mapTo(mutableSetOf()) { Triple(it.dev, it.fid, it.tx) }
    private val entriesByKey = DirectFidRegistry.entries.associateBy { it.key }

    fun enqueueCallbackBatchInTransaction(
        db: SQLiteDatabase,
        batchId: Long,
        batch: TelemetryCallbackBatch,
        eventIds: List<Long>,
        boundaryIds: List<Long>,
        delivery: CallbackDelivery
    ) {
        check(db.inTransaction()) { "business callback enqueue requires a transaction" }
        if (batch.stream != 1) return
        require(eventIds.size == batch.events.size) { "callback journal references do not match batch" }
        require(boundaryIds.size == batch.boundaries.size) { "continuity journal references do not match batch" }
        val sessionId = activeSessionId(db)
        val ordered = buildList {
            batch.events.zip(eventIds).forEach { (event, id) -> add(OrderedCallbackRecord(event.sequence, id, event, null)) }
            batch.boundaries.zip(boundaryIds).forEach { (boundary, id) ->
                add(OrderedCallbackRecord(boundary.recordSequence, id, null, boundary))
            }
        }.sortedBy { it.sequence }
        ordered.forEach { record ->
            val event = record.event
            if (event != null) {
                if (!isBusinessCallbackEvent(event)) return@forEach
                db.insertOrThrow(
                    JOURNAL,
                    null,
                    ContentValues().apply {
                        put("input_kind", BusinessInputKind.CALLBACK.storageValue)
                        put("raw_ref_id", record.rawId)
                        put("source_identity", batch.eventIdentity(event))
                        put("source_boot_id", batch.bootId)
                        put("source_generation", batch.helperGeneration)
                        put("source_sequence", event.sequence)
                        putNullable("source_epoch", batch.sourceEpoch.takeIf {
                            batch.formatVersion() >= TelemetryCallbackBatch.VERSION
                        })
                        put("acquired_wall_ms", event.receivedWallMs)
                        put("acquired_elapsed_ms", event.receivedElapsedMs)
                        putNullable("session_id", sessionId)
                        put("origin", delivery.storageValue)
                        put("ok", 1)
                        put("quality", event.quality)
                        put("created_at_ms", nowMs())
                    }
                ).also { require(it != -1L) { "failed to enqueue callback business input ${record.rawId}" } }
            } else {
                val boundary = checkNotNull(record.boundary)
                val persisted = loadBoundary(db, record.rawId)
                val seedValid = persisted.seedValid
                db.insertOrThrow(
                    JOURNAL,
                    null,
                    ContentValues().apply {
                        put("input_kind", BusinessInputKind.CONTINUITY.storageValue)
                        put("raw_ref_id", record.rawId)
                        put("source_identity", continuityIdentity(batch, boundary.recordSequence))
                        put("source_boot_id", batch.bootId)
                        put("source_generation", batch.helperGeneration)
                        put("source_sequence", boundary.recordSequence)
                        put("source_epoch", batch.sourceEpoch)
                        put("acquired_wall_ms", boundary.captureWallMs)
                        put("acquired_elapsed_ms", boundary.captureElapsedMs)
                        putNullable("session_id", sessionId)
                        put("origin", delivery.storageValue)
                        put("ok", if (boundary.state == TelemetryCallbackBatch.SourceBoundary.STATE_LOST || seedValid) 1 else 0)
                        put("quality", if (boundary.state == TelemetryCallbackBatch.SourceBoundary.STATE_LOST) "lost" else if (seedValid) "available" else "available_seed_invalid")
                        if (boundary.state == TelemetryCallbackBatch.SourceBoundary.STATE_AVAILABLE && !seedValid) {
                            put("error_category", "seed_invalid")
                            putNullable("error_message", persisted.seedError)
                        }
                        put("created_at_ms", nowMs())
                    }
                ).also { require(it != -1L) { "failed to enqueue continuity business input ${record.rawId}" } }
            }
        }
        check(batchId > 0L) { "callback batch was not committed before journal enqueue" }
    }

    fun enqueueGetInTransaction(
        db: SQLiteDatabase,
        pollId: Long,
        sessionId: Long,
        input: PersistedPollInput
    ) {
        check(db.inTransaction()) { "business GET enqueue requires a transaction" }
        val readingKeys = input.readings.map { it.rawKey }
        require(readingKeys.size == readingKeys.toSet().size) {
            "GET input contains duplicate reading keys: ${readingKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.joinToString(",")}"
        }
        resolvePendingGetSeedsInTransaction(db, pollId, input.origin ?: PollOrigin.LIVE)
        val businessReadingKeys = readingKeys.filter { it in businessInputKeys }
        // A failure with known attempted keys outside every business consumer is not
        // an unscoped transport failure and must not invalidate retained inputs.
        if (businessReadingKeys.isEmpty() && (input.ok || readingKeys.isNotEmpty())) return
        val source = input.source
        val quality = when {
            !input.ok -> "failure_recorded"
            input.errorCategory != null -> "partial_success"
            else -> "full_success"
        }
        db.insertOrThrow(
            JOURNAL,
            null,
            ContentValues().apply {
                put("input_kind", BusinessInputKind.GET.storageValue)
                put("raw_ref_id", pollId)
                put("reading_keys_json", JSONArray().apply {
                    businessReadingKeys.forEach(::put)
                }.toString())
                put("source_identity", source?.identity ?: "get:poll:$pollId")
                putNullable("source_boot_id", source?.bootId)
                putNullable("source_generation", source?.generatorId)
                putNullable("source_sequence", source?.sequence)
                putNullable("acquired_wall_ms", epochMillisOrNull(input.timestamp))
                putNullable("acquired_elapsed_ms", source?.capturedElapsedMs)
                put("session_id", sessionId)
                put("poll_id", pollId)
                put("origin", if (input.origin == PollOrigin.REPLAY) "replay" else "live")
                put("ok", if (input.ok) 1 else 0)
                put("quality", quality)
                putNullable("error_category", input.errorCategory)
                putNullable("error_message", input.errorMessage)
                put("created_at_ms", nowMs())
            }
        ).also { require(it != -1L) { "failed to enqueue GET business input for poll $pollId" } }
    }

    fun enqueueEvaluation(source: BusinessInputTimerSource, sessionId: Long? = null): Long {
        require(source.identity.isNotBlank() && source.bootId.isNotBlank() && source.generation.isNotBlank()) {
            "timer source identity, boot and generation must be present"
        }
        require(source.sequence >= 0L && source.wallMs >= 0L && source.elapsedMs >= 0L) {
            "timer source clocks and sequence must be non-negative"
        }
        val db = database()
        var id = -1L
        db.beginTransaction()
        try {
            id = db.insertOrThrow(
                JOURNAL,
                null,
                ContentValues().apply {
                    put("input_kind", BusinessInputKind.TIMER.storageValue)
                    put("source_identity", source.identity)
                    put("source_boot_id", source.bootId)
                    put("source_generation", source.generation)
                    put("source_sequence", source.sequence)
                    put("acquired_wall_ms", source.wallMs)
                    put("acquired_elapsed_ms", source.elapsedMs)
                    putNullable("session_id", sessionId)
                    put("origin", BusinessInputOrigin.TIMER.storageValue)
                    put("ok", 1)
                    put("quality", "timer")
                    put("created_at_ms", nowMs())
                }
            )
            require(id != -1L) { "failed to enqueue timer business input ${source.identity}" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return id
    }

    /**
     * Records an APP-observed helper death as scoped continuity loss. The supplied clocks and boot ID
     * belong to the APP observation; this row deliberately has no fabricated helper sequence/epoch.
     */
    fun recordAppObservedSourceLoss(
        lostHelperGeneration: String,
        stream: Int,
        affectedReadingKeys: Collection<String>,
        reason: String,
        diagnostic: String?,
        appBootId: String,
        observedWallMs: Long,
        observedElapsedMs: Long
    ): Long {
        require(lostHelperGeneration.isNotBlank()) { "lost helper generation is blank" }
        require(stream >= 0) { "lost helper stream is negative" }
        require(affectedReadingKeys.isNotEmpty() && affectedReadingKeys.size == affectedReadingKeys.toSet().size) {
            "APP-observed source loss requires unique affected reading keys"
        }
        require(affectedReadingKeys.all { it in businessInputKeys }) {
            "APP-observed source loss contains an unsupported reading key"
        }
        require(reason.isNotBlank()) { "APP-observed source loss reason is blank" }
        require(appBootId.isNotBlank() && observedWallMs >= 0L && observedElapsedMs >= 0L) {
            "APP-observed source loss requires a boot ID and non-negative APP capture clocks"
        }

        val db = database()
        var journalId = -1L
        db.beginTransaction()
        try {
            val localSequence = db.rawQuery(
                "SELECT COALESCE(MAX(-raw_ref_id), 0) + 1 FROM $JOURNAL " +
                    "WHERE input_kind = ? AND raw_ref_id < 0 AND source_boot_id = ?",
                arrayOf(BusinessInputKind.CONTINUITY.storageValue, appBootId)
            ).use { cursor ->
                check(cursor.moveToFirst()) { "APP source-loss sequence query returned no row" }
                cursor.getLong(0)
            }
            val identity = "app-source-loss:${appBootId.length}:$appBootId:$localSequence"
            journalId = db.insertOrThrow(
                JOURNAL,
                null,
                ContentValues().apply {
                    put("input_kind", BusinessInputKind.CONTINUITY.storageValue)
                    put("raw_ref_id", -localSequence)
                    put("reading_keys_json", JSONArray().apply {
                        affectedReadingKeys.sorted().forEach(::put)
                    }.toString())
                    put("source_identity", identity)
                    put("source_boot_id", appBootId)
                    put("source_generation", lostHelperGeneration)
                    put("source_sequence", localSequence)
                    putNull("source_epoch")
                    put("acquired_wall_ms", observedWallMs)
                    put("acquired_elapsed_ms", observedElapsedMs)
                    putNullable("session_id", activeSessionId(db))
                    put("origin", BusinessInputOrigin.LIVE.storageValue)
                    put("ok", 0)
                    put("quality", "$APP_SOURCE_LOSS_QUALITY_PREFIX$stream")
                    put("error_category", reason)
                    putNullable("error_message", diagnostic)
                    put("created_at_ms", nowMs())
                }
            )
            check(journalId > 0L) { "failed to enqueue APP-observed source loss" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return journalId
    }

    fun nextPendingBusinessInput(): BusinessInputRef? = pendingBusinessInputs(1).firstOrNull()

    fun pendingBusinessInputs(limit: Int = MAX_PENDING_PAGE): List<BusinessInputRef> {
        require(limit in 1..MAX_PENDING_PAGE) { "business input pending page must be 1..$MAX_PENDING_PAGE" }
        return pendingBusinessInputs(database(), limit)
    }

    fun businessInputGeneration(db: SQLiteDatabase = database()): String = db.rawQuery(
        "SELECT generation FROM $META WHERE singleton = 1",
        emptyArray()
    ).use { cursor ->
        check(cursor.moveToFirst()) { "business input generation is missing" }
        cursor.getString(0)
    }

    fun loadBusinessInputDelta(ref: BusinessInputRef): BusinessInputDelta {
        val db = database()
        check(loadBusinessInputRef(db, ref.id) == ref) { "business input reference changed or disappeared: ${ref.id}" }
        return when (ref.kind) {
            BusinessInputKind.CALLBACK -> loadCallbackDelta(db, ref)
            BusinessInputKind.GET -> loadGetDelta(db, ref)
            BusinessInputKind.CONTINUITY -> loadContinuityDelta(db, ref)
            BusinessInputKind.TIMER -> BusinessInputDelta(ref)
        }
    }

    fun loadBusinessInputCheckpoint(): Map<String, NormalizedSourceInput> =
        loadSourceCheckpoint(database(), SOURCE_CHECKPOINTS)

    fun loadBusinessInputInitialCheckpoint(): Map<String, NormalizedSourceInput> =
        loadSourceCheckpoint(database(), SOURCE_SEED)

    fun loadBusinessInputContinuityCheckpoint(): Map<String, BusinessInputContinuity> =
        loadContinuityCheckpoint(database())

    private fun loadContinuityCheckpoint(db: SQLiteDatabase): Map<String, BusinessInputContinuity> = db.rawQuery(
        "SELECT source_key, state, source_kind, source_identity, source_boot_id, source_generation, source_stream, " +
            "source_epoch, source_sequence, source_wall_ms, source_elapsed_ms, seed_kind, seed_identity, " +
            "seed_record_sequence, seed_valid, reason, diagnostic, raw_boundary_id " +
            "FROM $CONTINUITY_CHECKPOINTS ORDER BY source_key",
        emptyArray()
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val kind = NormalizedSourceKind.entries.first { it.storageValue == cursor.getString(2) }
                val helperGeneration = cursor.getNullableString(5)
                val stream = cursor.getNullableLong(6)?.toInt()
                val sourceEpoch = cursor.getNullableLong(7)
                put(cursor.getString(0), BusinessInputContinuity(
                    state = BusinessContinuityState.entries.first { it.storageValue == cursor.getString(1) },
                    stamp = NormalizedSourceStamp(
                        kind = kind,
                        identity = cursor.getString(3),
                        bootId = cursor.getString(4),
                        generatorId = when {
                            kind == NormalizedSourceKind.CALLBACK && helperGeneration != null && stream != null && sourceEpoch != null ->
                                normalizedHelperSourceGeneratorId(helperGeneration, stream, sourceEpoch)
                            cursor.getString(3).startsWith(APP_SOURCE_LOSS_IDENTITY_PREFIX) ->
                                appObservedLossGeneratorId(cursor.getString(4))
                            else -> helperGeneration
                        },
                        sequence = cursor.getLong(8),
                        wallMs = cursor.getLong(9),
                        elapsedMs = cursor.getLong(10),
                        sourceEpoch = sourceEpoch
                    ),
                    seedKind = cursor.getNullableString(11),
                    seedIdentity = cursor.getNullableString(12),
                    seedRecordSequence = cursor.getNullableLong(13),
                    seedValid = cursor.getInt(14) != 0,
                    reason = cursor.getString(15),
                    diagnostic = cursor.getNullableString(16),
                    rawBoundaryId = cursor.getNullableLong(17),
                    helperGeneration = helperGeneration,
                    stream = stream,
                    sourceEpoch = sourceEpoch
                ))
            }
        }
    }

    private fun loadSourceCheckpoint(db: SQLiteDatabase, table: String): Map<String, NormalizedSourceInput> = db.rawQuery(
        "SELECT source_key, raw_value, desc_value, source_kind, source_identity, source_boot_id, " +
            "source_generator_id, source_sequence, source_epoch, source_wall_ms, source_elapsed_ms, source_poll_id " +
            "FROM $table ORDER BY source_key",
        emptyArray()
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                check(key in BusinessInputCheckpointKeys.ALL) { "unexpected business checkpoint key: $key" }
                put(
                    key,
                    NormalizedSourceInput(
                        reading = PollReading(key, cursor.getNullableString(1), cursor.getNullableString(2)),
                        stamp = NormalizedSourceStamp(
                            kind = NormalizedSourceKind.entries.first { it.storageValue == cursor.getString(3) },
                            identity = cursor.getString(4),
                            bootId = cursor.getString(5),
                            generatorId = cursor.getNullableString(6),
                            sequence = cursor.getNullableLong(7),
                            wallMs = cursor.getLong(9),
                            elapsedMs = cursor.getLong(10),
                            sourceEpoch = cursor.getNullableLong(8)
                        ),
                        sourcePollId = cursor.getNullableLong(11)
                    )
                )
            }
        }
    }

    /** Advances accepted per-field source state and its shared queue receipt in one transaction. */
    fun completeBusinessInput(
        ref: BusinessInputRef,
        acceptedCheckpointUpdates: Map<String, NormalizedSourceInput>
    ): Boolean = completeBusinessInputs(listOf(ref), acceptedCheckpointUpdates)

    /** Completes an ordered input page with one checkpoint update and receipt transaction. */
    fun completeBusinessInputs(
        refs: List<BusinessInputRef>,
        acceptedCheckpointUpdates: Map<String, NormalizedSourceInput>,
        acceptedContinuityUpdates: Map<String, BusinessInputContinuity> = emptyMap()
    ): Boolean {
        require(refs.isNotEmpty() && refs.size <= MAX_PENDING_PAGE) {
            "business input completion batch must be 1..$MAX_PENDING_PAGE"
        }
        require(refs.all { it.id > 0L } && refs.map { it.id }.distinct().size == refs.size &&
            refs.zipWithNext().all { (left, right) -> left.id < right.id }
        ) { "business input completion refs must have unique ascending ids" }
        require(acceptedCheckpointUpdates.size <= BusinessInputCheckpointKeys.ALL.size) {
            "too many business source checkpoint updates"
        }
        acceptedCheckpointUpdates.forEach { (key, input) ->
            require(key in BusinessInputCheckpointKeys.ALL && input.reading.rawKey == key) {
                "invalid business source checkpoint key: $key"
            }
            require(input.stamp.identity.isNotBlank() && input.stamp.bootId.isNotBlank() &&
                input.stamp.wallMs >= 0L && input.stamp.elapsedMs >= 0L
            ) { "invalid business source checkpoint identity or clocks: $key" }
        }
        acceptedContinuityUpdates.forEach { (key, continuity) ->
            require(key in businessInputKeys && continuity.stamp.identity.isNotBlank() &&
                continuity.stamp.bootId.isNotBlank() && continuity.stamp.wallMs >= 0L &&
                continuity.stamp.elapsedMs >= 0L
            ) { "invalid business continuity checkpoint: $key" }
            require(continuity.state != BusinessContinuityState.AVAILABLE || continuity.seedValid) {
                "unvalidated source cannot be checkpointed as available: $key"
            }
        }

        val db = database()
        var completed = false
        db.beginTransaction()
        try {
            val persistedRefs = loadBusinessInputRefs(db, refs.map { it.id })
            check(persistedRefs == refs) { "business input references changed or disappeared: ${refs.first().id}" }
            val completedInputId = db.rawQuery(
                "SELECT completed_input_id FROM $META WHERE singleton = 1", emptyArray()
            ).use { cursor ->
                check(cursor.moveToFirst()) { "business input completion frontier is missing" }
                cursor.getLong(0)
            }
            if (refs.all { it.id <= completedInputId }) {
                val placeholders = refs.joinToString(",") { "?" }
                val receiptCount = db.rawQuery(
                    "SELECT COUNT(*) FROM $RECEIPTS WHERE input_id IN ($placeholders)",
                    refs.map { it.id.toString() }.toTypedArray()
                ).use { cursor ->
                    check(cursor.moveToFirst()) { "business input receipt count is missing" }
                    cursor.getInt(0)
                }
                check(receiptCount == refs.size) { "completed business input batch is missing receipts" }
                db.setTransactionSuccessful()
                return false
            }
            check(refs.all { it.id > completedInputId }) {
                "business input completion batch overlaps the completed frontier $completedInputId"
            }
            val expected = pendingBusinessInputs(db, refs.size)
            check(expected == refs) {
                "business input batch does not match the next pending prefix: expected=${expected.map { it.id }} actual=${refs.map { it.id }}"
            }
            acceptedCheckpointUpdates.toSortedMap().forEach { (key, incoming) ->
                updateCheckpoint(db, key, incoming)
            }
            acceptedContinuityUpdates.toSortedMap().forEach { (key, incoming) ->
                updateContinuityCheckpoint(db, key, incoming)
            }
            refs.forEach { ref ->
                db.insertOrThrow(
                    RECEIPTS,
                    null,
                    ContentValues().apply {
                        put("input_id", ref.id)
                        put("completed_at_ms", nowMs())
                    }
                )
            }
            val lastId = refs.last().id
            check(db.update(
                META,
                ContentValues().apply { put("completed_input_id", lastId) },
                "singleton = 1 AND completed_input_id < ?",
                arrayOf(lastId.toString())
            ) == 1) { "business input checkpoint frontier changed during completion: $lastId" }
            db.setTransactionSuccessful()
            completed = true
        } finally {
            db.endTransaction()
        }
        return completed
    }

    fun businessInputEventHistory(afterId: Long = 0L, limit: Int = MAX_HISTORY_PAGE): List<BusinessInputRef> {
        require(afterId >= 0L) { "business input history cursor must be non-negative" }
        require(limit in 1..MAX_HISTORY_PAGE) { "business input history limit must be 1..$MAX_HISTORY_PAGE" }
        return database().rawQuery(
            "SELECT ${REF_COLUMNS.joinToString(", ")} FROM $JOURNAL j WHERE j.id > ? ORDER BY j.id LIMIT ?",
            arrayOf(afterId.toString(), limit.toString())
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toBusinessInputRef()) } }
    }

    fun businessInputEventHistory(
        bootId: String,
        fromElapsedMs: Long,
        throughElapsedMs: Long,
        afterId: Long = 0L,
        limit: Int = MAX_HISTORY_PAGE
    ): List<BusinessInputRef> {
        require(bootId.isNotBlank()) { "business input history boot ID is blank" }
        require(fromElapsedMs >= 0L && throughElapsedMs >= fromElapsedMs) {
            "business input history elapsed bounds are invalid"
        }
        require(afterId >= 0L) { "business input history cursor must be non-negative" }
        require(limit in 1..MAX_HISTORY_PAGE) { "business input history limit must be 1..$MAX_HISTORY_PAGE" }
        return database().rawQuery(
            "SELECT ${REF_COLUMNS.joinToString(", ")} FROM $JOURNAL j " +
                "WHERE j.source_boot_id = ? AND j.acquired_elapsed_ms >= ? AND j.acquired_elapsed_ms <= ? " +
                "AND j.id > ? ORDER BY j.id LIMIT ?",
            arrayOf(bootId, fromElapsedMs.toString(), throughElapsedMs.toString(), afterId.toString(), limit.toString())
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toBusinessInputRef()) } }
    }

    fun hasBusinessInputAt(bootId: String, elapsedMs: Long): Boolean {
        require(bootId.isNotBlank()) { "business input history boot ID is blank" }
        require(elapsedMs >= 0L) { "business input history elapsed time is negative" }
        return database().rawQuery(
            "SELECT 1 FROM $JOURNAL WHERE source_boot_id = ? AND acquired_elapsed_ms = ? LIMIT 1",
            arrayOf(bootId, elapsedMs.toString())
        ).use { it.moveToFirst() }
    }

    private fun loadContinuityDelta(db: SQLiteDatabase, ref: BusinessInputRef): BusinessInputDelta {
        val boundaryId = checkNotNull(ref.rawRefId) { "continuity business input has no raw reference: ${ref.id}" }
        if (boundaryId < 0L) return loadAppObservedSourceLossDelta(ref, -boundaryId)
        val boundary = loadBoundary(db, boundaryId)
        val stamp = NormalizedSourceStamp(
            kind = NormalizedSourceKind.CALLBACK,
            identity = continuityIdentity(
                boundary.bootId, boundary.helperGeneration, boundary.stream,
                boundary.sourceEpoch, boundary.recordSequence
            ),
            bootId = boundary.bootId,
            generatorId = normalizedHelperSourceGeneratorId(
                boundary.helperGeneration, boundary.stream, boundary.sourceEpoch
            ),
            sequence = boundary.recordSequence,
            wallMs = boundary.captureWallMs,
            elapsedMs = boundary.captureElapsedMs,
            sourceEpoch = boundary.sourceEpoch
        )
        val scopedKeys = when (boundary.scope) {
            TelemetryCallbackBatch.SourceBoundary.SCOPE_ADDRESS -> DirectFidRegistry.entries
                .filter { it.dev == boundary.device && it.fid == boundary.fid && it.tx == boundary.tx }
            TelemetryCallbackBatch.SourceBoundary.SCOPE_NATIVE_KEY -> DirectFidRegistry.entries
                .filter { it.dev == boundary.device && it.fid == boundary.fid }
            TelemetryCallbackBatch.SourceBoundary.SCOPE_STREAM -> DirectFidRegistry.entries
            else -> emptyList()
        }.map { it.key }.filter { it in businessInputKeys }.toSet()
        val available = boundary.state == TelemetryCallbackBatch.SourceBoundary.STATE_AVAILABLE && boundary.seedValid
        val pendingGetSeed = boundary.state == TelemetryCallbackBatch.SourceBoundary.STATE_AVAILABLE &&
            boundary.seedKind == "get" && boundary.seedError == "get_seed_pending"
        val continuity = scopedKeys.associateWith { key ->
            val seedForKey = available && key == boundary.seedSourceKey
            BusinessInputContinuity(
                state = if (seedForKey) BusinessContinuityState.AVAILABLE else BusinessContinuityState.LOST,
                stamp = stamp,
                seedKind = if (seedForKey) boundary.seedKind else null,
                seedIdentity = if (seedForKey) boundary.seedIdentity else null,
                seedRecordSequence = if (seedForKey) boundary.seedRecordSequence else null,
                seedValid = seedForKey,
                reason = when {
                    seedForKey -> boundary.reason
                    pendingGetSeed -> "availability_seed_pending"
                    boundary.state == TelemetryCallbackBatch.SourceBoundary.STATE_AVAILABLE ->
                        "availability_seed_did_not_cover_source"
                    else -> boundary.reason
                },
                diagnostic = boundary.seedError ?: boundary.diagnostic,
                rawBoundaryId = boundary.id,
                helperGeneration = boundary.helperGeneration,
                stream = boundary.stream,
                sourceEpoch = boundary.sourceEpoch
            )
        }
        val seedMaterial = if (available && boundary.seedKind == "get") loadGetSeedMaterial(db, boundary) else null
        val sourceInputs = seedMaterial?.let { (reading, stamp, pollId) ->
            normalizedSourceInputForPollReading(reading, stamp, pollId)
                ?.takeIf { reading.rawKey in BusinessInputCheckpointKeys.ALL }
                ?.let { mapOf(reading.rawKey to it) }
        } ?: emptyMap()
        return BusinessInputDelta(
            ref,
            continuityBoundary = boundary,
            readings = listOfNotNull(seedMaterial?.first),
            sourceInputs = sourceInputs,
            continuityUpdates = continuity
        )
    }

    private fun loadGetSeedMaterial(
        db: SQLiteDatabase,
        boundary: StoredSourceBoundary
    ): Triple<PollReading, NormalizedSourceStamp, Long>? {
        val key = boundary.seedSourceKey ?: return null
        val entry = entriesByKey[key] ?: return null
        val sequence = boundary.seedRecordSequence ?: return null
        val lineage = loadPendingGetLineage(db, checkNotNull(boundary.seedIdentity), sequence) ?: return null
        if (lineage.pollId <= 0L || lineage.helperBootId != boundary.bootId ||
            lineage.helperGeneration != boundary.helperGeneration || lineage.stream != boundary.stream ||
            lineage.sourceEpoch != boundary.sourceEpoch || lineage.sourceKey != key ||
            lineage.device != entry.dev || lineage.fid != entry.fid || lineage.tx != entry.tx ||
            lineage.supersededByCallback || !matchesGetSeedScope(entry, boundary)
        ) return null
        val getLineage = loadPollGetLineages(db, lineage.pollId)[key] ?: return null
        val rawBits = db.rawQuery(
            "SELECT ${quoteIdentifier(PollValueColumns.raw(key))} FROM poll_values WHERE poll_id = ? LIMIT 1",
            arrayOf(lineage.pollId.toString())
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0).toIntOrNull() else null }
            ?: return null
        val desc = DirectValueDecoders.decode(entry, rawBits) ?: return null
        val reading = PollReading(key, DirectValueDecoders.rawString(rawBits), desc, getLineage = getLineage)
        return Triple(reading, getLineage.sourceStamp(), lineage.pollId)
    }

    private fun loadAppObservedSourceLossDelta(ref: BusinessInputRef, localSequence: Long): BusinessInputDelta {
        check(ref.quality.startsWith(APP_SOURCE_LOSS_QUALITY_PREFIX)) {
            "negative continuity reference is not an APP-observed source loss: ${ref.id}"
        }
        val stream = ref.quality.removePrefix(APP_SOURCE_LOSS_QUALITY_PREFIX).toIntOrNull()
        check(stream != null && stream >= 0) { "APP-observed source loss has invalid stream metadata: ${ref.id}" }
        val bootId = checkNotNull(ref.bootId) { "APP-observed source loss has no APP boot ID: ${ref.id}" }
        val wallMs = checkNotNull(ref.acquiredWallMs) { "APP-observed source loss has no APP wall clock: ${ref.id}" }
        val elapsedMs = checkNotNull(ref.acquiredElapsedMs) { "APP-observed source loss has no APP elapsed clock: ${ref.id}" }
        check(ref.sequence == localSequence && localSequence > 0L) {
            "APP-observed source loss local sequence does not match its reference: ${ref.id}"
        }
        val stamp = NormalizedSourceStamp(
            kind = NormalizedSourceKind.POLL,
            identity = ref.sourceIdentity,
            bootId = bootId,
            generatorId = appObservedLossGeneratorId(bootId),
            sequence = localSequence,
            wallMs = wallMs,
            elapsedMs = elapsedMs
        )
        val continuity = ref.readingKeys.filter { it in businessInputKeys }.associateWith { key ->
            BusinessInputContinuity(
                state = BusinessContinuityState.LOST,
                stamp = stamp,
                seedKind = null,
                seedIdentity = null,
                seedRecordSequence = null,
                seedValid = false,
                reason = ref.errorCategory ?: "app_observed_source_loss",
                diagnostic = ref.errorMessage,
                helperGeneration = ref.generation,
                stream = stream
            )
        }
        check(continuity.isNotEmpty()) { "APP-observed source loss has no supported affected keys: ${ref.id}" }
        return BusinessInputDelta(ref, continuityUpdates = continuity)
    }

    private fun loadBoundary(db: SQLiteDatabase, id: Long): StoredSourceBoundary = db.rawQuery(
        "SELECT id, batch_id, boot_id, helper_generation, stream, epoch, source_epoch, record_sequence, " +
            "scope, state, device, fid, tx, capture_wall_ms, capture_elapsed_ms, reason, diagnostic, " +
            "seed_kind, seed_identity, seed_record_sequence, seed_source_key, seed_valid, seed_error " +
            "FROM raw_callback_boundaries WHERE id = ? LIMIT 1",
        arrayOf(id.toString())
    ).use { cursor ->
        check(cursor.moveToFirst()) { "callback continuity raw reference disappeared: $id" }
        StoredSourceBoundary(
            id = cursor.getLong(0), batchId = cursor.getLong(1), bootId = cursor.getString(2),
            helperGeneration = cursor.getString(3), stream = cursor.getInt(4), transportEpoch = cursor.getLong(5),
            sourceEpoch = cursor.getLong(6), recordSequence = cursor.getLong(7), scope = cursor.getInt(8),
            state = cursor.getInt(9), device = cursor.getInt(10), fid = cursor.getInt(11), tx = cursor.getInt(12),
            captureWallMs = cursor.getLong(13), captureElapsedMs = cursor.getLong(14), reason = cursor.getString(15),
            diagnostic = cursor.getNullableString(16), seedKind = cursor.getNullableString(17),
            seedIdentity = cursor.getNullableString(18), seedRecordSequence = cursor.getNullableLong(19),
            seedSourceKey = cursor.getNullableString(20), seedValid = cursor.getInt(21) != 0,
            seedError = cursor.getNullableString(22)
        )
    }

    private fun loadCallbackDelta(db: SQLiteDatabase, ref: BusinessInputRef): BusinessInputDelta {
        val eventId = checkNotNull(ref.rawRefId) { "callback business input has no raw reference: ${ref.id}" }
        val event = db.rawQuery(
            """
            SELECT e.id, e.batch_id, e.boot_id, e.helper_generation, e.stream, e.epoch, e.source_epoch,
                   b.batch_sequence, e.event_sequence, e.device, e.fid, e.native_type,
                   e.raw_bits, e.raw_bytes, e.received_wall_ms, e.received_elapsed_ms,
                   e.source_wall_ms, e.quality, b.delivery
            FROM raw_callback_events e
            JOIN raw_callback_batches b ON b.id = e.batch_id
            WHERE e.id = ? AND e.stream = 1
            """.trimIndent(),
            arrayOf(eventId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst()) { "callback raw reference disappeared: $eventId" }
            StoredCallbackEvent(
                id = cursor.getLong(0),
                batchId = cursor.getLong(1),
                bootId = cursor.getString(2),
                helperGeneration = cursor.getString(3),
                stream = cursor.getInt(4),
                epoch = cursor.getLong(5),
                sourceEpoch = cursor.getNullableLong(6),
                batchSequence = cursor.getLong(7),
                eventSequence = cursor.getLong(8),
                device = cursor.getInt(9),
                fid = cursor.getInt(10),
                nativeType = cursor.getInt(11),
                rawBits = cursor.getInt(12),
                rawBytes = if (cursor.isNull(13)) null else cursor.getBlob(13),
                receivedWallMs = cursor.getLong(14),
                receivedElapsedMs = cursor.getLong(15),
                sourceWallMs = cursor.getNullableLong(16),
                quality = cursor.getString(17),
                delivery = CallbackDelivery.entries.first { it.storageValue == cursor.getString(18) }
            )
        }
        val actualTx = when (event.nativeType) {
            TelemetryCallbackBatch.TYPE_INT -> DirectFidRegistry.TX_GET_INT
            TelemetryCallbackBatch.TYPE_FLOAT -> DirectFidRegistry.TX_GET_FLOAT
            else -> null
        }
        val entry = actualTx?.let { entriesByAddress[Triple(event.device, event.fid, it)] }
            ?: DirectFidRegistry.entries.singleOrNull {
                it.dev == event.device && it.fid == event.fid && it.key in businessInputKeys
            }
            ?: return BusinessInputDelta(ref, callbackEvent = event)
        if (entry.key !in businessInputKeys) return BusinessInputDelta(ref, callbackEvent = event)
        val validValue = actualTx == entry.tx && event.rawBytes == null &&
            usableNormalizedCallbackQuality(event.quality) && DirectValueDecoders.decode(entry, event.rawBits) != null
        val callbackSource = if (event.nativeType == TelemetryCallbackBatch.TYPE_INT ||
            event.nativeType == TelemetryCallbackBatch.TYPE_FLOAT
        ) CallbackValueSource(
            event.bootId,
            event.helperGeneration,
            event.stream,
            event.epoch,
            event.sourceEpoch,
            event.eventSequence,
            event.device,
            event.fid,
            event.nativeType,
            event.rawBits,
            event.receivedWallMs,
            event.receivedElapsedMs,
            event.sourceWallMs,
            event.quality
        ) else null
        val reading = PollReading(
            rawKey = entry.key,
            rawValue = if (validValue) DirectValueDecoders.rawString(event.rawBits) else null,
            descValue = if (validValue) DirectValueDecoders.decode(entry, event.rawBits) else null,
            callbackSource = callbackSource
        )
        val sourceStamp = normalizedCallbackSourceStamp(
            event.bootId, event.helperGeneration, event.stream, event.epoch,
            event.eventSequence, event.receivedWallMs, event.receivedElapsedMs, event.sourceEpoch
        )
        val sourceInput = NormalizedSourceInput(reading = reading, stamp = sourceStamp, sourcePollId = null)
        val continuity = BusinessInputContinuity(
            state = if (validValue) BusinessContinuityState.AVAILABLE else BusinessContinuityState.LOST,
            stamp = sourceStamp,
            seedKind = if (validValue) "callback" else null,
            seedIdentity = if (validValue) "callback:${event.bootId.length}:${event.bootId}:${event.helperGeneration.length}:${event.helperGeneration}:${event.stream}:${event.sourceEpoch ?: event.epoch}:${event.eventSequence}" else null,
            seedRecordSequence = if (validValue) event.eventSequence else null,
            seedValid = validValue,
            reason = if (validValue) "callback_value" else "invalid_callback_value",
            diagnostic = if (validValue) null else "callback quality=${event.quality}, nativeType=${event.nativeType}, expectedTx=${entry.tx}",
            helperGeneration = event.helperGeneration,
            stream = event.stream,
            sourceEpoch = event.sourceEpoch
        )
        return BusinessInputDelta(
            ref = ref,
            callbackEvent = event,
            readings = listOf(reading),
            sourceInputs = mapOf(entry.key to sourceInput),
            continuityUpdates = if (event.sourceEpoch != null) mapOf(entry.key to continuity) else emptyMap()
        )
    }

    private fun isBusinessCallbackEvent(event: TelemetryCallbackBatch.Event): Boolean {
        val tx = when (event.nativeType) {
            TelemetryCallbackBatch.TYPE_INT -> DirectFidRegistry.TX_GET_INT
            TelemetryCallbackBatch.TYPE_FLOAT -> DirectFidRegistry.TX_GET_FLOAT
            else -> null
        }
        return (tx != null && Triple(event.device, event.fid, tx) in businessTypedAddresses) ||
            (event.device to event.fid) in businessDeviceFids
    }

    private fun loadGetDelta(db: SQLiteDatabase, ref: BusinessInputRef): BusinessInputDelta {
        val pollId = checkNotNull(ref.pollId) { "GET business input has no poll id: ${ref.id}" }
        val header = db.rawQuery(
            """
            SELECT p.ts, p.raw_response_body, s.catalog_version_id
            FROM polls p LEFT JOIN collection_sessions s ON s.id = p.session_id
            WHERE p.id = ?
            """.trimIndent(),
            arrayOf(pollId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst()) { "poll raw reference disappeared: $pollId" }
            PollHeader(cursor.getString(0), cursor.getNullableString(1), cursor.getNullableLong(2))
        }
        if (ref.readingKeys.isEmpty()) return BusinessInputDelta(ref, rawResponseBody = header.rawResponseBody)
        val catalogVersionId = checkNotNull(header.catalogVersionId) {
            "poll $pollId has input keys but no catalog version"
        }
        val layout = pollValueLayout(db, catalogVersionId)
        val missingCatalogFields = ref.readingKeys.filterNot { it in layout.fields }
        check(missingCatalogFields.isEmpty()) {
            "poll $pollId journal keys are absent from its catalog: ${missingCatalogFields.joinToString(",")}"
        }
        val selected = ref.readingKeys
        val missingRawColumns = selected.filter { PollValueColumns.raw(it) !in layout.columns }
        check(missingRawColumns.isEmpty()) {
            "poll $pollId journal keys are absent from poll_values: ${missingRawColumns.joinToString(",")}"
        }
        val sourceStamp = if (ref.bootId != null && ref.acquiredWallMs != null && ref.acquiredElapsedMs != null) {
            NormalizedSourceStamp(
                kind = NormalizedSourceKind.POLL,
                identity = ref.sourceIdentity,
                bootId = ref.bootId,
                generatorId = ref.generation,
                sequence = ref.sequence,
                wallMs = ref.acquiredWallMs,
                elapsedMs = ref.acquiredElapsedMs
            )
        } else null
        val getLineages = loadPollGetLineages(db, pollId)
        if (!ref.ok) {
            val readings = selected.map { key -> PollReading(key, null, null, getLineage = getLineages[key]) }
            val checkpointKeys = selected.filter {
                it in BusinessInputCheckpointKeys.ALL && getLineages[it]?.supersededByCallback != true
            }.toSet()
            val continuity = checkpointKeys.mapNotNull { key ->
                val lineage = getLineages[key]
                val stamp = lineage?.sourceStamp() ?: sourceStamp
                if (stamp == null) return@mapNotNull null
                key to BusinessInputContinuity(
                    state = BusinessContinuityState.LOST,
                    stamp = stamp,
                    seedKind = null,
                    seedIdentity = null,
                    seedRecordSequence = null,
                    seedValid = false,
                    reason = "get_failed",
                    diagnostic = ref.errorMessage ?: ref.errorCategory,
                    helperGeneration = lineage?.helperGeneration ?: stamp.generatorId,
                    stream = lineage?.stream,
                    sourceEpoch = lineage?.sourceEpoch ?: stamp.sourceEpoch
                )
            }.toMap()
            check(continuity.size == checkpointKeys.size) {
                "failed GET input $pollId has scoped business keys without acquisition clocks"
            }
            return BusinessInputDelta(
                ref,
                readings = readings,
                rawResponseBody = header.rawResponseBody,
                continuityUpdates = continuity
            )
        }
        val callbacks = loadPollCallbackSources(db, pollId)
        val expressions = mutableListOf<String>()
        selected.forEachIndexed { index, key ->
            expressions += "v.${quoteIdentifier(PollValueColumns.raw(key))} AS raw_$index"
            expressions += when {
                PollValueColumns.descId(key) in layout.columns && layout.hasDecodedDictionary ->
                    "(SELECT d.value FROM decoded_value_dictionary d WHERE d.id = v.${quoteIdentifier(PollValueColumns.descId(key))}) AS desc_$index"
                PollValueColumns.descId(key) in layout.columns ->
                    error("poll $pollId has descriptor IDs but no decoded value dictionary")
                PollValueColumns.desc(key) in layout.columns ->
                    "v.${quoteIdentifier(PollValueColumns.desc(key))} AS desc_$index"
                else -> "NULL AS desc_$index"
            }
        }
        val readings = db.rawQuery(
            "SELECT ${expressions.joinToString(", ")} FROM poll_values v WHERE v.poll_id = ?",
            arrayOf(pollId.toString())
        ).use { cursor ->
            check(cursor.moveToFirst()) { "poll_values row is missing for GET input $pollId" }
            buildList {
                selected.forEachIndexed { index, key ->
                    val callback = callbacks[key]
                    val entry = entriesByKey[key]
                    val callbackUsable = callback == null || (entry != null &&
                        usableNormalizedCallbackQuality(callback.quality) &&
                        callback.matches(entry.tx, entry.dev, entry.fid, callback.rawBits) &&
                        DirectValueDecoders.decode(entry, callback.rawBits) != null)
                    val raw = when {
                        callback != null && callbackUsable -> DirectValueDecoders.rawString(callback.rawBits)
                        callback != null -> null
                        else -> cursor.getNullableString(index * 2)
                    }
                    val desc = when {
                        callback != null && callbackUsable -> entry?.let { DirectValueDecoders.decode(it, callback.rawBits) }
                        callback != null -> null
                        else -> cursor.getNullableString(index * 2 + 1)
                    }
                    add(PollReading(key, raw, desc, callback, getLineages[key]))
                }
            }
        }
        val sourceInputs = linkedMapOf<String, NormalizedSourceInput>()
        val continuity = linkedMapOf<String, BusinessInputContinuity>()
        readings.forEach { reading ->
            if (reading.rawKey !in businessInputKeys) return@forEach
            val entry = entriesByKey[reading.rawKey]
            val callback = reading.callbackSource
            val lineage = reading.getLineage
            val readingStamp = lineage?.sourceStamp() ?: sourceStamp
            if (readingStamp == null) return@forEach
            val callbackUsable = callback != null && entry != null && usableNormalizedCallbackQuality(callback.quality) &&
                callback.matches(entry.tx, entry.dev, entry.fid, callback.rawBits) &&
                DirectValueDecoders.decode(entry, callback.rawBits) != null
            val actualGetUsable = callback == null && lineage?.supersededByCallback != true && entry != null && reading.rawInt?.let {
                DirectValueDecoders.decode(entry, it) != null
            } == true
            if (callback != null) {
                val callbackStamp = normalizedCallbackSourceStamp(
                    callback.bootId, callback.helperGeneration, callback.stream, callback.epoch,
                    callback.eventSequence, callback.receivedWallMs, callback.receivedElapsedMs,
                    callback.sourceEpoch.takeIf { callback.hasSourceEpoch }
                )
                val callbackInput = normalizedSourceInputForPollReading(reading, readingStamp, pollId)
                if (callback.hasSourceEpoch) {
                    continuity[reading.rawKey] = BusinessInputContinuity(
                        state = if (callbackUsable) BusinessContinuityState.AVAILABLE else BusinessContinuityState.LOST,
                        stamp = callbackStamp,
                        seedKind = if (callbackUsable) "callback" else null,
                        seedIdentity = if (callbackUsable) callbackStamp.identity else null,
                        seedRecordSequence = if (callbackUsable) callback.eventSequence else null,
                        seedValid = callbackUsable,
                        reason = if (callbackUsable) "callback_value" else "invalid_callback_value",
                        diagnostic = if (callbackUsable) null else "callback quality=${callback.quality}, nativeType=${callback.nativeType}",
                        helperGeneration = callback.helperGeneration,
                        stream = callback.stream,
                        sourceEpoch = callback.sourceEpoch
                    )
                }
                if (callbackInput != null && reading.rawKey in BusinessInputCheckpointKeys.ALL) {
                    sourceInputs[reading.rawKey] = callbackInput
                }
            } else if (actualGetUsable) {
                normalizedSourceInputForPollReading(reading, readingStamp, pollId)?.let { input ->
                    if (reading.rawKey in BusinessInputCheckpointKeys.ALL) sourceInputs[reading.rawKey] = input
                }
            } else if (lineage?.supersededByCallback != true) {
                continuity[reading.rawKey] = BusinessInputContinuity(
                    state = BusinessContinuityState.LOST,
                    stamp = readingStamp,
                    seedKind = null,
                    seedIdentity = null,
                    seedRecordSequence = null,
                    seedValid = false,
                    reason = "invalid_get_value",
                    diagnostic = "GET returned an invalid or unsupported value for ${reading.rawKey}",
                    helperGeneration = lineage?.helperGeneration ?: readingStamp.generatorId,
                    stream = lineage?.stream,
                    sourceEpoch = lineage?.sourceEpoch ?: readingStamp.sourceEpoch
                )
            }
        }
        return BusinessInputDelta(
            ref, readings = readings, rawResponseBody = header.rawResponseBody,
            sourceInputs = sourceInputs, continuityUpdates = continuity
        )
    }

    private fun pollValueLayout(db: SQLiteDatabase, catalogVersionId: Long): PollValueLayout {
        pollValueLayoutCache?.let { cached ->
            if (cached.database === db && cached.catalogVersionId == catalogVersionId) return cached
        }
        return synchronized(pollValueLayoutLock) {
            pollValueLayoutCache?.let { cached ->
                if (cached.database === db && cached.catalogVersionId == catalogVersionId) return@synchronized cached
            }
            val fields = db.rawQuery(
                "SELECT key FROM parameter_catalog WHERE catalog_version_id = ? ORDER BY id",
                arrayOf(catalogVersionId.toString())
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            PollValueLayout(
                database = db,
                catalogVersionId = catalogVersionId,
                fields = fields,
                columns = tableColumns(db, "poll_values"),
                hasDecodedDictionary = "decoded_value_dictionary" in tables(db)
            ).also { pollValueLayoutCache = it }
        }
    }

    private fun loadPollCallbackSources(db: SQLiteDatabase, pollId: Long): Map<String, com.bydcollector.collector.direct.CallbackValueSource> =
        db.rawQuery(
            """
            SELECT source_key, boot_id, helper_generation, stream, epoch, source_epoch, event_sequence,
                   device, fid, native_type, raw_bits, received_wall_ms, received_elapsed_ms,
                   source_wall_ms, quality
            FROM poll_callback_sources WHERE poll_id = ?
            """.trimIndent(),
            arrayOf(pollId.toString())
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(0),
                        com.bydcollector.collector.direct.CallbackValueSource(
                            cursor.getString(1), cursor.getString(2), cursor.getInt(3), cursor.getLong(4),
                            cursor.getNullableLong(5), cursor.getLong(6), cursor.getInt(7), cursor.getInt(8),
                            cursor.getInt(9), cursor.getInt(10), cursor.getLong(11), cursor.getLong(12),
                            cursor.getNullableLong(13), cursor.getString(14)
                        )
                    )
                }
            }
        }

    private fun loadPollGetLineages(db: SQLiteDatabase, pollId: Long): Map<String, DirectGetLineage> =
        db.rawQuery(
            """
            SELECT source_key, helper_boot_id, helper_generation, stream, source_epoch, read_identity,
                   record_sequence, started_wall_ms, started_elapsed_ms, completed_wall_ms,
                   completed_elapsed_ms, callback_watermark_start, callback_watermark_end,
                   superseded_by_callback, device, fid, tx
            FROM poll_get_lineage WHERE poll_id = ?
            """.trimIndent(),
            arrayOf(pollId.toString())
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val entry = checkNotNull(entriesByKey[key]) {
                        "GET lineage references a key outside the direct registry: $key"
                    }
                    check(entry.dev == cursor.getInt(14) && entry.fid == cursor.getInt(15) && entry.tx == cursor.getInt(16)) {
                        "GET lineage address does not match direct key $key"
                    }
                    val lineage = DirectGetLineage(
                        helperBootId = cursor.getString(1),
                        helperGeneration = cursor.getString(2),
                        stream = cursor.getInt(3),
                        sourceEpoch = cursor.getLong(4),
                        readIdentity = cursor.getString(5),
                        recordSequence = cursor.getLong(6),
                        startedWallMs = cursor.getLong(7),
                        startedElapsedMs = cursor.getLong(8),
                        completedWallMs = cursor.getLong(9),
                        completedElapsedMs = cursor.getLong(10),
                        callbackWatermarkStart = cursor.getLong(11),
                        callbackWatermarkEnd = cursor.getLong(12),
                        supersededByCallback = cursor.getInt(13) != 0
                    )
                    check(put(key, lineage) == null) { "poll $pollId has duplicate GET lineage for $key" }
                }
            }
        }

    private fun resolvePendingGetSeedsInTransaction(db: SQLiteDatabase, pollId: Long, origin: PollOrigin) {
        check(db.inTransaction()) { "GET seed resolution requires a transaction" }
        val readIdentities = db.rawQuery(
            "SELECT DISTINCT read_identity FROM poll_get_lineage WHERE poll_id = ?",
            arrayOf(pollId.toString())
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        readIdentities.forEach { identity ->
            val boundaryIds = db.rawQuery(
                "SELECT id FROM raw_callback_boundaries WHERE state = ? AND seed_kind = 'get' " +
                    "AND seed_valid = 0 AND seed_error = 'get_seed_pending' AND seed_identity = ? ORDER BY id",
                arrayOf(TelemetryCallbackBatch.SourceBoundary.STATE_AVAILABLE.toString(), identity)
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            boundaryIds.forEach { boundaryId ->
                val boundary = loadBoundary(db, boundaryId)
                val seedSequence = boundary.seedRecordSequence ?: return@forEach
                val lineage = loadPendingGetLineage(db, identity, seedSequence) ?: return@forEach
                val entry = entriesByKey[lineage.sourceKey]
                val error = when {
                    entry == null -> "get_seed_address_unknown"
                    lineage.pollId != pollId -> "get_seed_poll_mismatch"
                    lineage.helperBootId != boundary.bootId || lineage.helperGeneration != boundary.helperGeneration ||
                        lineage.stream != boundary.stream || lineage.sourceEpoch != boundary.sourceEpoch ||
                        lineage.recordSequence != seedSequence -> "get_seed_lineage_mismatch"
                    lineage.device != entry.dev || lineage.fid != entry.fid || lineage.tx != entry.tx ||
                        !matchesGetSeedScope(entry, boundary) -> "get_seed_address_mismatch"
                    lineage.supersededByCallback -> "get_seed_superseded_by_callback"
                    else -> {
                        val rawBits = db.rawQuery(
                            "SELECT ${quoteIdentifier(PollValueColumns.raw(entry.key))} FROM poll_values WHERE poll_id = ? LIMIT 1",
                            arrayOf(pollId.toString())
                        ).use { cursor ->
                            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0).toIntOrNull() else null
                        }
                        if (rawBits == null) "get_seed_value_missing"
                        else if (DirectValueDecoders.decode(entry, rawBits) == null) "get_seed_value_invalid"
                        else null
                    }
                }
                val changed = db.update(
                    "raw_callback_boundaries",
                    ContentValues().apply {
                        putNullable("seed_source_key", entry?.key)
                        put("seed_valid", if (error == null) 1 else 0)
                        putNullable("seed_error", error)
                    },
                    "id = ? AND seed_valid = 0 AND seed_error = 'get_seed_pending'",
                    arrayOf(boundaryId.toString())
                )
                check(changed in 0..1) { "GET seed resolution updated multiple boundaries: $boundaryId" }
                if (changed == 1 && error == null && boundary.stream == 1) {
                    enqueueResolvedGetAvailabilityInTransaction(db, boundary, origin)
                }
            }
        }
    }

    private fun loadPendingGetLineage(
        db: SQLiteDatabase,
        identity: String,
        sequence: Long
    ): PendingGetLineage? = db.rawQuery(
        "SELECT poll_id, source_key, helper_boot_id, helper_generation, stream, source_epoch, " +
            "record_sequence, device, fid, tx, superseded_by_callback FROM poll_get_lineage " +
            "WHERE read_identity = ? AND record_sequence = ? LIMIT 2",
        arrayOf(identity, sequence.toString())
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val result = PendingGetLineage(
            pollId = cursor.getLong(0), sourceKey = cursor.getString(1), helperBootId = cursor.getString(2),
            helperGeneration = cursor.getString(3), stream = cursor.getInt(4), sourceEpoch = cursor.getLong(5),
            recordSequence = cursor.getLong(6), device = cursor.getInt(7), fid = cursor.getInt(8),
            tx = cursor.getInt(9), supersededByCallback = cursor.getInt(10) != 0
        )
        check(!cursor.moveToNext()) { "GET seed lineage identity is not unique: $identity/$sequence" }
        result
    }

    private fun matchesGetSeedScope(
        entry: DirectFidEntry,
        boundary: StoredSourceBoundary
    ): Boolean = when (boundary.scope) {
        TelemetryCallbackBatch.SourceBoundary.SCOPE_ADDRESS ->
            entry.dev == boundary.device && entry.fid == boundary.fid && entry.tx == boundary.tx
        TelemetryCallbackBatch.SourceBoundary.SCOPE_NATIVE_KEY ->
            entry.dev == boundary.device && entry.fid == boundary.fid
        TelemetryCallbackBatch.SourceBoundary.SCOPE_STREAM -> true
        else -> false
    }

    private fun enqueueResolvedGetAvailabilityInTransaction(
        db: SQLiteDatabase,
        boundary: StoredSourceBoundary,
        origin: PollOrigin
    ) {
        check(db.inTransaction()) { "resolved GET availability enqueue requires a transaction" }
        val identity = "continuity-seed-resolution:${boundary.id}:${boundary.seedRecordSequence}"
        val alreadyQueued = db.rawQuery(
            "SELECT 1 FROM $JOURNAL WHERE source_identity = ? LIMIT 1", arrayOf(identity)
        ).use { it.moveToFirst() }
        if (alreadyQueued) return
        db.insertOrThrow(
            JOURNAL,
            null,
            ContentValues().apply {
                put("input_kind", BusinessInputKind.CONTINUITY.storageValue)
                put("raw_ref_id", boundary.id)
                put("source_identity", identity)
                put("source_boot_id", boundary.bootId)
                put("source_generation", boundary.helperGeneration)
                put("source_sequence", boundary.recordSequence)
                put("source_epoch", boundary.sourceEpoch)
                put("acquired_wall_ms", boundary.captureWallMs)
                put("acquired_elapsed_ms", boundary.captureElapsedMs)
                putNullable("session_id", activeSessionId(db))
                put("origin", if (origin == PollOrigin.REPLAY) BusinessInputOrigin.REPLAY.storageValue else BusinessInputOrigin.LIVE.storageValue)
                put("ok", 1)
                put("quality", "available_seed_resolved")
                put("created_at_ms", nowMs())
            }
        ).also { require(it != -1L) { "failed to enqueue resolved GET availability for boundary ${boundary.id}" } }
    }

    private fun updateCheckpoint(db: SQLiteDatabase, key: String, incoming: NormalizedSourceInput) {
        val previous = loadCheckpoint(db, key)
        if (previous != null) {
            when (NormalizedSourceOrdering.compare(incoming.stamp, previous.stamp, currentBootId())) {
                NormalizedSourceOrder.NEWER -> Unit
                NormalizedSourceOrder.EQUAL -> {
                    require(incoming.reading.rawValue == previous.reading.rawValue &&
                        incoming.reading.descValue == previous.reading.descValue
                    ) { "business source identity reused with different value: $key" }
                    return
                }
                NormalizedSourceOrder.OLDER,
                NormalizedSourceOrder.INCOMPARABLE -> return
            }
        }
        db.insertWithOnConflict(
            SOURCE_CHECKPOINTS,
            null,
            ContentValues().apply {
                put("source_key", key)
                putNullable("raw_value", incoming.reading.rawValue)
                putNullable("desc_value", incoming.reading.descValue)
                put("source_kind", incoming.stamp.kind.storageValue)
                put("source_identity", incoming.stamp.identity)
                put("source_boot_id", incoming.stamp.bootId)
                putNullable("source_generator_id", incoming.stamp.generatorId)
                putNullable("source_sequence", incoming.stamp.sequence)
                putNullable("source_epoch", incoming.stamp.sourceEpoch)
                put("source_wall_ms", incoming.stamp.wallMs)
                put("source_elapsed_ms", incoming.stamp.elapsedMs)
                putNullable("source_poll_id", incoming.sourcePollId)
                put("updated_at_ms", nowMs())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        ).also { require(it != -1L) { "failed to update business source checkpoint: $key" } }
    }

    private fun updateContinuityCheckpoint(db: SQLiteDatabase, key: String, incoming: BusinessInputContinuity) {
        val previous = loadContinuityCheckpoint(db).get(key)
        val currentSource = loadCheckpoint(db, key)
        if (BusinessInputReducer.isAppObservedSourceLoss(incoming) &&
            !BusinessInputReducer.appObservedSourceLossTargetsCurrentSource(incoming, previous, currentSource)
        ) return
        val recoveryFromUnrelatedAppLoss =
            BusinessInputReducer.shouldAcceptCurrentSeedAfterAppObservedSourceLoss(previous, incoming, currentSource)
        if (previous != null && !recoveryFromUnrelatedAppLoss) {
            when (NormalizedSourceOrdering.compare(incoming.stamp, previous.stamp, currentBootId())) {
                NormalizedSourceOrder.NEWER -> Unit
                NormalizedSourceOrder.EQUAL -> {
                    val resolvedPendingAvailability = previous.state == BusinessContinuityState.LOST &&
                        previous.reason == "availability_seed_pending" &&
                        previous.rawBoundaryId != null && previous.rawBoundaryId == incoming.rawBoundaryId &&
                        incoming.state == BusinessContinuityState.AVAILABLE && incoming.seedValid &&
                        incoming.seedIdentity != null && incoming.seedRecordSequence != null
                    if (!resolvedPendingAvailability) {
                        require(incoming.state == previous.state && incoming.seedIdentity == previous.seedIdentity &&
                            incoming.seedRecordSequence == previous.seedRecordSequence
                        ) { "business continuity identity reused with different state: $key" }
                        return
                    }
                }
                NormalizedSourceOrder.OLDER,
                NormalizedSourceOrder.INCOMPARABLE -> return
            }
        }
        db.insertWithOnConflict(
            CONTINUITY_CHECKPOINTS,
            null,
            ContentValues().apply {
                put("source_key", key)
                put("state", incoming.state.storageValue)
                put("source_identity", incoming.stamp.identity)
                put("source_boot_id", incoming.stamp.bootId)
                put("source_kind", incoming.stamp.kind.storageValue)
                put("source_generation", incoming.helperGeneration ?: incoming.stamp.generatorId ?: "unknown")
                putNullable("source_stream", incoming.stream?.toLong())
                putNullable("source_epoch", incoming.sourceEpoch ?: incoming.stamp.sourceEpoch)
                put("source_sequence", incoming.stamp.sequence ?: 0L)
                put("source_wall_ms", incoming.stamp.wallMs)
                put("source_elapsed_ms", incoming.stamp.elapsedMs)
                putNullable("seed_kind", incoming.seedKind)
                putNullable("seed_identity", incoming.seedIdentity)
                putNullable("seed_record_sequence", incoming.seedRecordSequence)
                put("seed_valid", if (incoming.seedValid) 1 else 0)
                put("reason", incoming.reason)
                putNullable("diagnostic", incoming.diagnostic)
                putNullable("raw_boundary_id", incoming.rawBoundaryId)
                put("updated_at_ms", nowMs())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        ).also { require(it != -1L) { "failed to update business continuity checkpoint: $key" } }
    }

    private fun loadCheckpoint(db: SQLiteDatabase, key: String): NormalizedSourceInput? = db.rawQuery(
        "SELECT raw_value, desc_value, source_kind, source_identity, source_boot_id, source_generator_id, " +
            "source_sequence, source_epoch, source_wall_ms, source_elapsed_ms, source_poll_id " +
            "FROM $SOURCE_CHECKPOINTS WHERE source_key = ?",
        arrayOf(key)
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        NormalizedSourceInput(
            reading = PollReading(key, cursor.getNullableString(0), cursor.getNullableString(1)),
            stamp = NormalizedSourceStamp(
                kind = NormalizedSourceKind.entries.first { it.storageValue == cursor.getString(2) },
                identity = cursor.getString(3),
                bootId = cursor.getString(4),
                generatorId = cursor.getNullableString(5),
                sequence = cursor.getNullableLong(6),
                wallMs = cursor.getLong(8),
                elapsedMs = cursor.getLong(9),
                sourceEpoch = cursor.getNullableLong(7)
            ),
            sourcePollId = cursor.getNullableLong(10)
        )
    }

    private fun appObservedLossGeneratorId(appBootId: String): String =
        "app-source-loss:${appBootId.length}:$appBootId"

    private fun continuityIdentity(batch: TelemetryCallbackBatch, recordSequence: Long): String =
        continuityIdentity(batch.bootId, batch.helperGeneration, batch.stream, batch.sourceEpoch, recordSequence)

    private fun continuityIdentity(
        bootId: String,
        helperGeneration: String,
        stream: Int,
        sourceEpoch: Long,
        recordSequence: Long
    ): String = "continuity:${bootId.length}:$bootId:${helperGeneration.length}:$helperGeneration:" +
        "$stream:$sourceEpoch:$recordSequence"

    private fun loadBusinessInputRef(db: SQLiteDatabase, id: Long): BusinessInputRef? = db.rawQuery(
        "SELECT ${REF_COLUMNS.joinToString(", ")} FROM $JOURNAL j WHERE j.id = ?",
        arrayOf(id.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toBusinessInputRef() else null }

    private fun loadBusinessInputRefs(db: SQLiteDatabase, ids: List<Long>): List<BusinessInputRef> {
        val placeholders = ids.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT ${REF_COLUMNS.joinToString(", ")} FROM $JOURNAL j " +
                "WHERE j.id IN ($placeholders) ORDER BY j.id",
            ids.map { it.toString() }.toTypedArray()
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toBusinessInputRef()) } }
    }

    private fun pendingBusinessInputs(db: SQLiteDatabase, limit: Int): List<BusinessInputRef> = db.rawQuery(
        "SELECT ${REF_COLUMNS.joinToString(", ")} FROM $JOURNAL j " +
            "WHERE j.id > (SELECT completed_input_id FROM $META WHERE singleton = 1) " +
            "ORDER BY j.id LIMIT ?",
        arrayOf(limit.toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toBusinessInputRef()) } }

    private fun activeSessionId(db: SQLiteDatabase): Long? = db.rawQuery(
        "SELECT id FROM collection_sessions WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1",
        emptyArray()
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> = db.rawQuery(
        "PRAGMA table_info(${quoteIdentifier(table)})", emptyArray()
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) } }

    private fun tables(db: SQLiteDatabase): Set<String> = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type = 'table'", emptyArray()
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun quoteIdentifier(value: String): String {
        require(PollValueColumns.isSafeIdentifier(value)) { "unsafe SQLite identifier: $value" }
        return "\"$value\""
    }

    private fun epochMillisOrNull(value: String): Long? = runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.recoverCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun Cursor.toBusinessInputRef(): BusinessInputRef = BusinessInputRef(
        id = getLong(0),
        kind = BusinessInputKind.entries.first { it.storageValue == getString(1) },
        rawRefId = getNullableLong(2),
        sourceIdentity = getString(3),
        bootId = getNullableString(4),
        generation = getNullableString(5),
        sequence = getNullableLong(6),
        epoch = getNullableLong(7),
        acquiredWallMs = getNullableLong(8),
        acquiredElapsedMs = getNullableLong(9),
        sessionId = getNullableLong(10),
        pollId = getNullableLong(11),
        origin = BusinessInputOrigin.entries.first { it.storageValue == getString(12) },
        ok = getInt(13) != 0,
        quality = getString(14),
        errorCategory = getNullableString(15),
        errorMessage = getNullableString(16),
        readingKeys = getNullableString(17)?.let(::JSONArray).let { json ->
            if (json == null) emptyList() else buildList { for (index in 0 until json.length()) add(json.getString(index)) }
        }
    )

    private fun Cursor.getNullableString(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.getNullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun ContentValues.putNullable(key: String, value: String?) { if (value == null) putNull(key) else put(key, value) }
    private fun ContentValues.putNullable(key: String, value: Long?) { if (value == null) putNull(key) else put(key, value) }

    private data class PollHeader(val timestamp: String, val rawResponseBody: String?, val catalogVersionId: Long?)
    companion object {
        private const val APP_SOURCE_LOSS_QUALITY_PREFIX = "app_observed_source_loss:"
        private const val APP_SOURCE_LOSS_IDENTITY_PREFIX = "app-source-loss:"
        private const val JOURNAL = "business_input_journal"
        private const val RECEIPTS = "business_input_receipts"
        private const val META = "business_input_meta"
        private const val SOURCE_CHECKPOINTS = "business_input_source_checkpoints"
        private const val SOURCE_SEED = "business_input_source_seed"
        private const val CONTINUITY_CHECKPOINTS = "business_input_source_continuity"
        const val MAX_PENDING_PAGE = 64
        private const val MAX_HISTORY_PAGE = 512
        private val REF_COLUMNS = listOf(
            "j.id", "j.input_kind", "j.raw_ref_id", "j.source_identity", "j.source_boot_id",
            "j.source_generation", "j.source_sequence", "j.source_epoch", "j.acquired_wall_ms",
            "j.acquired_elapsed_ms", "j.session_id", "j.poll_id", "j.origin", "j.ok",
            "j.quality", "j.error_category", "j.error_message", "j.reading_keys_json"
        )
    }
}
