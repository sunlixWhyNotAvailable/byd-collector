package com.bydcollector.collector.data.debug

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.bydcollector.collector.data.callback.CallbackDelivery
import com.bydcollector.collector.data.callback.CallbackImportResult
import com.bydcollector.collector.data.callback.CallbackRawStore
import com.bydcollector.collector.data.direct.DirectBatchDiagnostics
import com.bydcollector.collector.data.direct.DirectHelperReadResult
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.direct.SecondaryTelemetrySpool
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import com.bydcollector.collector.util.sqliteFootprintBytes
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime

data class DirectDebugObserved(
    val status: Int,
    val rawPresent: Boolean,
    val raw: Int?,
    val error: String?
) {
    val rawHex: String?
        get() = raw?.let { "0x" + Integer.toUnsignedString(it, 16) }

    val rawFloat: Double?
        get() = raw?.let { Float.fromBits(it).toDouble() }
}

data class DirectDebugPrevious(
    val status: Int?,
    val rawPresent: Boolean,
    val raw: Int?,
    val error: String?
)

//records only meaningful round-robin changes so debug db stays small enough for live use
object DirectDebugChangeDetector {
    fun reason(previous: DirectDebugPrevious?, current: DirectDebugObserved): String? {
        if (previous == null) return "initial"
        val changed = previous.status != current.status ||
            previous.rawPresent != current.rawPresent ||
            previous.raw != current.raw ||
            previous.error != current.error
        if (!changed) return null
        return if (current.status == 0 && current.rawPresent) "change" else "error_change"
    }
}

data class DirectDebugCycleSummary(
    val cycleId: Long,
    val attemptedCount: Int,
    val okCount: Int,
    val changedCount: Int,
    val errorCount: Int,
    val elapsedMs: Long,
    val batchDiagnostics: DirectBatchDiagnostics? = null
)

sealed interface SecondaryImportResult {
    data class Committed(val cycleId: Long, val duplicate: Boolean) : SecondaryImportResult
    data class Rejected(val reason: String) : SecondaryImportResult
}

data class DirectDebugStatus(
    val databasePath: String,
    val databaseSizeBytes: Long,
    val lastSessionId: Long?,
    val lastSessionStartedAt: String?,
    val lastSessionEndedAt: String?,
    val lastBatchSize: Int?,
    val candidateCount: Int,
    val readingCount: Long,
    val lastReadingAt: String?,
    val lastErrorAt: String?,
    val lastError: String?,
    val errorCount: Long
)

private data class DebugTransition(
    val candidateId: Long,
    val observed: DirectDebugObserved,
    val reason: String,
    val callbackCached: Boolean
)

private data class DebugSessionSnapshot(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val batchSize: Int,
    val candidateCount: Int,
    val errorCount: Long
)

//sqlite store for exploratory direct parameters that are intentionally kept out of the main telemetry db
class DirectDebugStore(
    private val context: Context,
    helper: DirectDebugDatabaseHelper? = null,
    private val clock: Clock = SystemClockAdapter()
) : Closeable {
    private val helperDelegate = lazy { helper ?: DirectDebugDatabaseHelper(context) }
    private val helper by helperDelegate
    private val callbackRawStore = CallbackRawStore(
        database = { helperDelegate.value.writableDatabase },
        nowMs = { OffsetDateTime.parse(clock.nowIso()).toInstant().toEpochMilli() }
    )
    private val candidateIdsByKey = HashMap<String, Long>()
    private val candidateIdsByOrdinal = ArrayList<Long>()
    private val candidateIdsByCatalogVersion = HashMap<String, List<Long>>()
    private val catalogVersionIdsBySource = HashMap<String, Long>()
    private val candidateState = HashMap<Long, DirectDebugPrevious>()
    private var activeCatalogVersionId: Long? = null
    private var activeSessionId: Long? = null

    @Synchronized
    fun openSession(parameters: List<DirectDebugParameter>, batchSize: Int): Long {
        val db = helper.writableDatabase
        check(DirectDebugDatabaseHelper.isCompactV2(db)) {
            "Legacy debug database must be archived before round-robin polling starts"
        }
        var openedSessionId = -1L
        try {
            db.beginTransaction()
            val catalogVersionId: Long
            try {
                val (ensuredCatalogVersionId, sourceVersionChanged) =
                    ensureCatalogVersion(db, DirectDebugParameterAsset.SOURCE_VERSION)
                catalogVersionId = ensuredCatalogVersionId
                catalogVersionIdsBySource[DirectDebugParameterAsset.SOURCE_VERSION] = catalogVersionId
                catalogVersionIdsBySource[DirectDebugParameterAsset.LEGACY_SOURCE_VERSION] =
                    ensureCatalogVersion(db, DirectDebugParameterAsset.LEGACY_SOURCE_VERSION).first
                val definitions = DirectDebugParameterAsset.loadDefinitions(context)
                ensureCandidates(db, definitions, sourceVersionChanged)
                candidateIdsByOrdinal += parameters.map { candidateIdsByKey.getValue(it.key) }
                candidateIdsByCatalogVersion[DirectDebugParameterAsset.SOURCE_VERSION] =
                    parameters.map { candidateIdsByKey.getValue(it.key) }
                candidateIdsByCatalogVersion[DirectDebugParameterAsset.LEGACY_SOURCE_VERSION] =
                    definitions.map { candidateIdsByKey.getValue(it.key) }
                loadCandidateState(db, catalogVersionId)
                openedSessionId = db.insertOrThrow(
                    "debug_direct_sessions",
                    null,
                    ContentValues().apply {
                        put("started_at_ms", epochMs(clock.nowIso()))
                        put("catalog_version_id", catalogVersionId)
                        put("candidate_count", parameters.size)
                        put("poll_mode", "leftover_round_robin")
                        put("batch_size", batchSize.coerceAtLeast(1))
                        put("interval_ms", DirectDebugRoundRobinPoller.INTERVAL_MS)
                    }
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            activeCatalogVersionId = catalogVersionId
            activeSessionId = openedSessionId
            return openedSessionId
        } catch (error: Throwable) {
            activeCatalogVersionId = null
            activeSessionId = null
            candidateIdsByKey.clear()
            candidateIdsByOrdinal.clear()
            candidateIdsByCatalogVersion.clear()
            catalogVersionIdsBySource.clear()
            candidateState.clear()
            throw error
        }
    }

    @Synchronized
    fun endSession(sessionId: Long, reason: String) {
        helper.writableDatabase.update(
            "debug_direct_sessions",
            ContentValues().apply {
                put("ended_at_ms", epochMs(clock.nowIso()))
                put("stop_reason", reason)
            },
            "id = ?",
            arrayOf(sessionId.toString())
        )
        if (activeSessionId == sessionId) activeSessionId = null
    }

    @Synchronized
    fun recordCycle(
        sessionId: Long,
        cycleNumber: Long,
        batch: List<DirectDebugParameter>,
        reads: List<Pair<DirectDebugParameter, DirectHelperReadResult>>,
        startedAt: String,
        elapsedMs: Long
    ): DirectDebugCycleSummary {
        val catalogVersionId = checkNotNull(activeCatalogVersionId) { "Debug session is not open" }
        check(activeSessionId == sessionId) { "Debug cycle session is not active" }
        val sampledAtMs = epochMs(startedAt)
        var okCount = 0
        var errorCount = 0
        val transitions = ArrayList<DebugTransition>()
        reads.forEach { (parameter, result) ->
            val observed = DirectDebugObserved(
                status = result.status,
                rawPresent = result.raw != null,
                raw = result.raw,
                error = result.error
            )
            if (observed.status == 0 && observed.rawPresent) okCount++ else errorCount++
            val candidateId = checkNotNull(candidateIdsByKey[parameter.key]) {
                "Debug candidate missing for ${parameter.key}"
            }
            DirectDebugChangeDetector.reason(candidateState[candidateId], observed)?.let { reason ->
                transitions += DebugTransition(
                    candidateId,
                    observed,
                    reason,
                    callbackCached = result.callbackCached
                )
            }
        }
        val recordedTransitions = transitions.filterNot(DebugTransition::callbackCached)

        val db = helper.writableDatabase
        val cycleId: Long
        db.beginTransaction()
        try {
            cycleId = db.insertOrThrow(
                "debug_direct_cycles",
                null,
                ContentValues().apply {
                    put("session_id", sessionId)
                    put("cycle_number", cycleNumber)
                    put("started_at_ms", sampledAtMs)
                    put("elapsed_ms", elapsedMs.coerceAtLeast(0L))
                    put("attempted_count", batch.size)
                    put("ok_count", okCount)
                    put("changed_count", recordedTransitions.size)
                    put("error_count", errorCount)
                }
            )
            transitions.forEach { transition ->
                insertTransition(
                    db,
                    sessionId,
                    cycleId,
                    catalogVersionId,
                    sampledAtMs,
                    transition,
                    recordReading = !transition.callbackCached
                )
            }
            db.execSQL(
                """
                UPDATE debug_direct_sessions
                SET cycle_count = cycle_count + 1,
                    attempted_count = attempted_count + ?,
                    ok_count = ok_count + ?,
                    changed_count = changed_count + ?,
                    error_count = error_count + ?,
                    last_scan_at_ms = ?
                WHERE id = ?
                """.trimIndent(),
                arrayOf(batch.size, okCount, recordedTransitions.size, errorCount, sampledAtMs, sessionId)
            )
            // APP-live values are now the materialized truth. A queued DELTA must not be
            // interpreted against them after this transaction commits.
            deleteSecondaryCursor(db, "catalog_version_id = ?", arrayOf(catalogVersionId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        transitions.forEach { transition ->
            candidateState[transition.candidateId] = transition.observed.toPrevious()
        }
        return DirectDebugCycleSummary(
            cycleId = cycleId,
            attemptedCount = batch.size,
            okCount = okCount,
            changedCount = recordedTransitions.size,
            errorCount = errorCount,
            elapsedMs = elapsedMs
        )
    }

    /**
     * Imports one decoded helper-spool record atomically. [openSession] must be called first.
     * A successful duplicate is safe to ACK; a rejection is durable and must be quarantined,
     * never acknowledged. In-memory transition state advances only after the transaction commits.
     */
    @Synchronized
    fun importSecondaryRecord(
        sessionId: Long,
        record: SecondaryTelemetrySpool.Record,
        digest: String
    ): SecondaryImportResult {
        val catalogVersionId = checkNotNull(activeCatalogVersionId) { "Debug session is not open" }
        check(activeSessionId == sessionId) { "Secondary import session is not active" }
        require(digest.matches(Regex("[0-9A-F]{64}"))) { "Invalid secondary record digest" }
        val db = helper.writableDatabase
        var committedState: Map<Long, DirectDebugPrevious>? = null
        lateinit var result: SecondaryImportResult
        db.beginTransaction()
        try {
            val existing = findSecondaryReceipt(db, record.identity)
            if (existing != null) {
                result = if (existing.second == digest) {
                    SecondaryImportResult.Committed(existing.first, duplicate = true)
                } else {
                    val reason = "identity digest mismatch"
                    persistSecondaryRejection(db, record, digest, reason)
                    deleteSecondaryCursor(db)
                    SecondaryImportResult.Rejected(reason)
                }
            } else if (candidateIdsFor(record) == null) {
                val reason = "catalog mismatch: ${record.catalogVersion}/${record.fieldCount}"
                persistSecondaryRejection(db, record, digest, reason)
                deleteSecondaryCursor(db)
                result = SecondaryImportResult.Rejected(reason)
            } else {
                val recordCandidateIds = checkNotNull(candidateIdsFor(record))
                val recordCatalogVersionId = catalogVersionIdsBySource.getValue(record.catalogVersion)
                val persistedState = readCandidateState(db, recordCatalogVersionId)
                val materialized = materializeSecondaryRecord(
                    db,
                    recordCatalogVersionId,
                    record,
                    recordCandidateIds,
                    persistedState
                )
                if (materialized == null) {
                    val reason = "orphan DELTA: exact persisted predecessor unavailable"
                    persistSecondaryRejection(db, record, digest, reason)
                    deleteSecondaryCursor(
                        db,
                        "catalog_version_id = ?",
                        arrayOf(recordCatalogVersionId.toString())
                    )
                    result = SecondaryImportResult.Rejected(reason)
                } else {
                    val transitions = materialized.mapIndexedNotNull { ordinal, value ->
                        val candidateId = recordCandidateIds[ordinal]
                        val observed = DirectDebugObserved(value.status, value.rawPresent, value.raw, value.error)
                        DirectDebugChangeDetector.reason(persistedState[candidateId], observed)?.let { reason ->
                            DebugTransition(
                                candidateId,
                                observed,
                                reason,
                                callbackCached = value.cached
                            )
                        }
                    }
                    val recordedTransitions = transitions.filterNot(DebugTransition::callbackCached)
                    val cycleId = db.insertOrThrow(
                        "debug_direct_cycles",
                        null,
                        ContentValues().apply {
                            put("session_id", sessionId)
                            put("cycle_number", record.identity.sequence)
                            put("started_at_ms", record.capturedWallMs)
                            put("elapsed_ms", record.cycleElapsedMs)
                            put("attempted_count", record.fieldCount)
                            put("ok_count", record.okCount)
                            put("changed_count", recordedTransitions.size)
                            put("error_count", record.errorCount)
                        }
                    )
                    transitions.forEach { transition ->
                        insertTransition(
                            db,
                            sessionId,
                            cycleId,
                            recordCatalogVersionId,
                            record.capturedWallMs,
                            transition,
                            persistedState.containsKey(transition.candidateId),
                            recordReading = !transition.callbackCached
                        )
                    }
                    insertSecondaryMetadata(db, cycleId, record)
                    db.execSQL(
                        """
                        UPDATE debug_direct_sessions
                        SET cycle_count = cycle_count + 1,
                            attempted_count = attempted_count + ?,
                            ok_count = ok_count + ?,
                            changed_count = changed_count + ?,
                            error_count = error_count + ?,
                            last_scan_at_ms = ?
                        WHERE id = ?
                        """.trimIndent(),
                        arrayOf(
                            record.fieldCount,
                            record.okCount,
                            recordedTransitions.size,
                            record.errorCount,
                            record.capturedWallMs,
                            sessionId
                        )
                    )
                    insertSecondaryReceipt(db, record, digest, cycleId)
                    replaceSecondaryCursor(
                        db,
                        recordCatalogVersionId,
                        record,
                        digest,
                        cycleId,
                        materialized
                    )
                    committedState = materialized.mapIndexed { ordinal, value ->
                        recordCandidateIds[ordinal] to DirectDebugPrevious(
                            value.status,
                            value.rawPresent,
                            value.raw,
                            value.error
                        )
                    }.toMap()
                    result = SecondaryImportResult.Committed(cycleId, duplicate = false)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (record.catalogVersion == DirectDebugParameterAsset.SOURCE_VERSION &&
            result is SecondaryImportResult.Committed && !(result as SecondaryImportResult.Committed).duplicate
        ) {
            candidateState.clear()
            candidateState.putAll(checkNotNull(committedState))
        }
        return result
    }

    fun dashboardReadingCount(cancellationSignal: CancellationSignal? = null): Long = scalarLong(
        if (DirectDebugDatabaseHelper.isCompactV2(helper.readableDatabase)) {
            "SELECT (SELECT COUNT(*) FROM debug_direct_readings) + (SELECT COUNT(*) FROM raw_callback_events)"
        } else {
            "SELECT COUNT(*) FROM debug_direct_readings"
        },
        cancellationSignal
    )

    fun status(readingCount: Long = UNKNOWN_READING_COUNT): DirectDebugStatus {
        val db = helper.readableDatabase
        val dbFile = DirectDebugDatabaseResolver.databaseFile(context)
        if (!DirectDebugDatabaseHelper.isCompactV2(db)) return legacyStatus(db, dbFile, readingCount)
        val session = db.rawQuery(
            """
            SELECT id, started_at_ms, ended_at_ms, batch_size, candidate_count, error_count
            FROM debug_direct_sessions
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            DebugSessionSnapshot(
                id = cursor.getLong(0),
                startedAtMs = cursor.getLong(1),
                endedAtMs = if (cursor.isNull(2)) null else cursor.getLong(2),
                batchSize = cursor.getInt(3),
                candidateCount = cursor.getInt(4),
                errorCount = cursor.getLong(5)
            )
        }
        val lastReadingAtMs = nullableLong("SELECT sampled_at_ms FROM debug_direct_readings ORDER BY id DESC LIMIT 1")
        val lastError = latestDebugError(db)

        return DirectDebugStatus(
            databasePath = dbFile.absolutePath,
            databaseSizeBytes = sqliteFootprintBytes(dbFile),
            lastSessionId = session?.id,
            lastSessionStartedAt = session?.startedAtMs?.let(::iso),
            lastSessionEndedAt = session?.endedAtMs?.let(::iso),
            lastBatchSize = session?.batchSize,
            candidateCount = session?.candidateCount ?: DirectDebugParameterAsset.load(context).size,
            readingCount = readingCount,
            lastReadingAt = lastReadingAtMs?.let(::iso),
            lastErrorAt = lastError?.first?.let(::iso),
            lastError = lastError?.second,
            errorCount = session?.errorCount ?: 0L
        )
    }

    fun verifyWritableDatabase(): Boolean {
        helper.writableDatabase.rawQuery("PRAGMA quick_check", emptyArray()).use { cursor ->
            return cursor.moveToFirst() && cursor.getString(0) == "ok"
        }
    }

    fun importCallbackBatch(
        batch: TelemetryCallbackBatch,
        digest: String,
        delivery: CallbackDelivery
    ): CallbackImportResult {
        if (batch.stream != CallbackRawStore.SECONDARY_STREAM) {
            return CallbackImportResult.Rejected("Secondary store requires Secondary callback stream")
        }
        return callbackRawStore.importBatch(batch, digest, delivery)
    }

    fun databaseFile() = DirectDebugDatabaseResolver.databaseFile(context)

    fun isCompactV2(): Boolean = DirectDebugDatabaseHelper.isCompactV2(helper.readableDatabase)

    private fun ensureCatalogVersion(db: SQLiteDatabase, sourceVersion: String): Pair<Long, Boolean> {
        val sourceVersionChanged = db.insertWithOnConflict(
            "debug_direct_catalog_versions",
            null,
            ContentValues().apply { put("source_version", sourceVersion) },
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        db.rawQuery(
            "SELECT id FROM debug_direct_catalog_versions WHERE source_version = ?",
            arrayOf(sourceVersion)
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Debug catalog version was not persisted" }
            return cursor.getLong(0) to sourceVersionChanged
        }
    }

    private fun ensureCandidates(
        db: SQLiteDatabase,
        parameters: List<DirectDebugParameter>,
        reconcileMetadata: Boolean
    ) {
        candidateIdsByKey.clear()
        candidateIdsByOrdinal.clear()
        candidateIdsByCatalogVersion.clear()
        val existingIds = HashMap<String, Long>()
        db.rawQuery("SELECT id, dev, fid, tx FROM debug_direct_candidates", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                existingIds[signature(cursor.getInt(1), cursor.getInt(2), cursor.getInt(3))] = cursor.getLong(0)
            }
        }
        parameters.forEach { parameter ->
            val signature = signature(parameter.dev, parameter.fid, parameter.tx)
            val values = ContentValues().apply {
                put("source_key", parameter.key)
                put("dev", parameter.dev)
                put("fid", parameter.fid)
                put("tx", parameter.tx)
                put("feature_group", parameter.featureGroup)
                put("feature_names", parameter.featureNames)
                put("feature_refs", parameter.featureRefs)
                put("candidate_source", parameter.candidateSource)
                put("decoder", parameter.toDirectFidEntry().decoder.name)
            }
            val existingId = existingIds[signature]
            val id = existingId ?: db.insertOrThrow("debug_direct_candidates", null, values).also {
                existingIds[signature] = it
            }
            if (existingId != null && reconcileMetadata) {
                check(db.update("debug_direct_candidates", values, "id = ?", arrayOf(id.toString())) == 1) {
                    "Debug candidate metadata was not reconciled for $signature"
                }
            }
            candidateIdsByKey[parameter.key] = id
        }
    }

    private fun candidateIdsFor(record: SecondaryTelemetrySpool.Record): List<Long>? =
        candidateIdsByCatalogVersion[record.catalogVersion]?.takeIf { it.size == record.fieldCount }

    private fun findSecondaryReceipt(
        db: SQLiteDatabase,
        identity: SecondaryTelemetrySpool.CycleIdentity
    ): Pair<Long, String>? {
        db.rawQuery(
            """
            SELECT cycle_id, digest
            FROM debug_secondary_receipts
            WHERE boot_id = ? AND helper_generation = ? AND gap_id = ? AND sequence = ?
            """.trimIndent(),
            arrayOf(identity.bootId, identity.helperGeneration, identity.gapId, identity.sequence.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
        }
    }

    private fun materializeSecondaryRecord(
        db: SQLiteDatabase,
        catalogVersionId: Long,
        record: SecondaryTelemetrySpool.Record,
        recordCandidateIds: List<Long>,
        persistedState: Map<Long, DirectDebugPrevious>
    ): List<SecondaryTelemetrySpool.Value>? {
        if (record.kind == SecondaryTelemetrySpool.Kind.FULL) {
            return record.materialize(null, null)
        }
        val cursorState = db.rawQuery(
            """
            SELECT c.boot_id, c.helper_generation, c.gap_id, c.sequence, c.field_count,
                   r.catalog_version, f.field_count, f.cached_bits
            FROM debug_secondary_replay_cursor c
            JOIN debug_secondary_receipts r
              ON r.boot_id = c.boot_id
             AND r.helper_generation = c.helper_generation
             AND r.gap_id = c.gap_id
             AND r.sequence = c.sequence
            JOIN debug_secondary_replay_cached_flags f
              ON f.catalog_version_id = c.catalog_version_id
            WHERE c.catalog_version_id = ?
            """.trimIndent(),
            arrayOf(catalogVersionId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getInt(4) != record.fieldCount ||
                cursor.getString(5) != record.catalogVersion || cursor.getInt(6) != record.fieldCount
            ) return@use null
            val cachedBits = cursor.getBlob(7)
            if (cachedBits.size != (record.fieldCount + 7) / 8) return@use null
            SecondaryTelemetrySpool.CycleIdentity(
                cursor.getString(0),
                cursor.getString(1),
                cursor.getString(2),
                cursor.getLong(3)
            ) to cachedBits
        } ?: return null
        val cursorIdentity = cursorState.first
        if (record.predecessorIdentity != cursorIdentity) return null
        val previous = recordCandidateIds.mapIndexed { ordinal, candidateId ->
            val state = persistedState[candidateId] ?: return null
            SecondaryTelemetrySpool.Value(
                ordinal,
                checkNotNull(state.status),
                state.rawPresent,
                state.raw,
                state.error,
                callbackCached(cursorState.second, ordinal)
            )
        }
        return runCatching { record.materialize(cursorIdentity, previous) }.getOrNull()
    }

    private fun insertSecondaryReceipt(
        db: SQLiteDatabase,
        record: SecondaryTelemetrySpool.Record,
        digest: String,
        cycleId: Long
    ) {
        val identity = record.identity
        db.insertOrThrow(
            "debug_secondary_receipts",
            null,
            ContentValues().apply {
                put("boot_id", identity.bootId)
                put("helper_generation", identity.helperGeneration)
                put("gap_id", identity.gapId)
                put("sequence", identity.sequence)
                put("digest", digest)
                put("spool_order", record.spoolOrder)
                put("catalog_version", record.catalogVersion)
                put("record_kind", record.kind.name)
                put("cycle_id", cycleId)
                put("committed_at_ms", epochMs(clock.nowIso()))
            }
        )
    }

    private fun replaceSecondaryCursor(
        db: SQLiteDatabase,
        catalogVersionId: Long,
        record: SecondaryTelemetrySpool.Record,
        digest: String,
        cycleId: Long,
        materialized: List<SecondaryTelemetrySpool.Value>
    ) {
        val identity = record.identity
        check(db.insertWithOnConflict(
            "debug_secondary_replay_cursor",
            null,
            ContentValues().apply {
                put("catalog_version_id", catalogVersionId)
                put("boot_id", identity.bootId)
                put("helper_generation", identity.helperGeneration)
                put("gap_id", identity.gapId)
                put("sequence", identity.sequence)
                put("digest", digest)
                put("cycle_id", cycleId)
                put("field_count", record.fieldCount)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L) { "Secondary replay cursor was not persisted" }
        check(db.insertWithOnConflict(
            "debug_secondary_replay_cached_flags",
            null,
            ContentValues().apply {
                put("catalog_version_id", catalogVersionId)
                put("field_count", record.fieldCount)
                put("cached_bits", cachedBits(record.fieldCount, materialized))
            },
            SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L) { "Secondary replay cached flags were not persisted" }
    }

    private fun deleteSecondaryCursor(
        db: SQLiteDatabase,
        where: String? = null,
        args: Array<String>? = null
    ) {
        db.delete("debug_secondary_replay_cached_flags", where, args)
        db.delete("debug_secondary_replay_cursor", where, args)
    }

    private fun cachedBits(
        fieldCount: Int,
        values: List<SecondaryTelemetrySpool.Value>
    ): ByteArray = ByteArray((fieldCount + 7) / 8).also { bits ->
        check(values.size == fieldCount) { "Secondary cached flags require materialized values" }
        values.forEach { value ->
            check(value.ordinal in 0 until fieldCount) { "Secondary cached ordinal is out of range" }
            if (value.cached) bits[value.ordinal / 8] =
                (bits[value.ordinal / 8].toInt() or (1 shl (value.ordinal % 8))).toByte()
        }
    }

    private fun callbackCached(bits: ByteArray, ordinal: Int): Boolean =
        bits[ordinal / 8].toInt() and (1 shl (ordinal % 8)) != 0

    private fun persistSecondaryRejection(
        db: SQLiteDatabase,
        record: SecondaryTelemetrySpool.Record,
        digest: String,
        reason: String
    ) {
        val identity = record.identity
        db.insertWithOnConflict(
            "debug_secondary_rejections",
            null,
            ContentValues().apply {
                put("boot_id", identity.bootId)
                put("helper_generation", identity.helperGeneration)
                put("gap_id", identity.gapId)
                put("sequence", identity.sequence)
                put("digest", digest)
                put("catalog_version", record.catalogVersion)
                put("spool_order", record.spoolOrder)
                put("reason", reason.take(MAX_REJECTION_CHARS))
                put("detected_at_ms", epochMs(clock.nowIso()))
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        check(hasSecondaryRejection(db, identity, digest)) {
            "Secondary rejection metadata was not persisted"
        }
    }

    private fun hasSecondaryRejection(
        db: SQLiteDatabase,
        identity: SecondaryTelemetrySpool.CycleIdentity,
        digest: String
    ): Boolean = db.rawQuery(
        """
        SELECT 1 FROM debug_secondary_rejections
        WHERE boot_id = ? AND helper_generation = ? AND gap_id = ? AND sequence = ? AND digest = ?
        """.trimIndent(),
        arrayOf(identity.bootId, identity.helperGeneration, identity.gapId, identity.sequence.toString(), digest)
    ).use { it.moveToFirst() }

    private fun insertSecondaryMetadata(
        db: SQLiteDatabase,
        cycleId: Long,
        record: SecondaryTelemetrySpool.Record
    ) {
        db.insertOrThrow(
            "debug_secondary_cycle_metadata",
            null,
            ContentValues().apply {
                put("cycle_id", cycleId)
                put("source_wall_ms", record.capturedWallMs)
                put("source_elapsed_ms", record.capturedElapsedMs)
                put("source_cycle_elapsed_ms", record.cycleElapsedMs)
                put("batch_status", record.batchStatus)
                put("batch_mode", record.batchMode)
                put("native_available", if (record.nativeAvailable) 1 else 0)
                put("native_group_count", record.nativeGroupCount)
                put("fallback_group_count", record.fallbackGroupCount)
                put("fallback_read_count", record.fallbackReadCount)
                put("group_failure_count", record.groupFailureCount)
                put("source_error", record.error)
                record.lossBefore?.let { loss ->
                    put("loss_count", loss.count)
                    put("loss_first_boot_id", loss.firstIdentity.bootId)
                    put("loss_first_helper_generation", loss.firstIdentity.helperGeneration)
                    put("loss_first_gap_id", loss.firstIdentity.gapId)
                    put("loss_first_sequence", loss.firstIdentity.sequence)
                    put("loss_last_boot_id", loss.lastIdentity.bootId)
                    put("loss_last_helper_generation", loss.lastIdentity.helperGeneration)
                    put("loss_last_gap_id", loss.lastIdentity.gapId)
                    put("loss_last_sequence", loss.lastIdentity.sequence)
                    put("loss_first_wall_ms", loss.firstWallMs)
                    put("loss_last_wall_ms", loss.lastWallMs)
                    put("loss_first_elapsed_ms", loss.firstElapsedMs)
                    put("loss_last_elapsed_ms", loss.lastElapsedMs)
                }
            }
        )
    }

    private fun loadCandidateState(db: SQLiteDatabase, catalogVersionId: Long) {
        candidateState.clear()
        candidateState.putAll(readCandidateState(db, catalogVersionId))
    }

    private fun readCandidateState(
        db: SQLiteDatabase,
        catalogVersionId: Long
    ): Map<Long, DirectDebugPrevious> {
        val result = HashMap<Long, DirectDebugPrevious>()
        db.rawQuery(
            """
            SELECT candidate_id, last_status, last_raw_present, last_raw_int, last_error
            FROM debug_direct_candidate_state
            WHERE catalog_version_id = ?
            """.trimIndent(),
            arrayOf(catalogVersionId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getLong(0)] = DirectDebugPrevious(
                    status = cursor.getInt(1),
                    rawPresent = cursor.getInt(2) == 1,
                    raw = if (cursor.isNull(3)) null else cursor.getInt(3),
                    error = if (cursor.isNull(4)) null else cursor.getString(4)
                )
            }
        }
        return result
    }

    private fun insertTransition(
        db: SQLiteDatabase,
        sessionId: Long,
        cycleId: Long,
        catalogVersionId: Long,
        sampledAtMs: Long,
        transition: DebugTransition,
        stateExists: Boolean = candidateState.containsKey(transition.candidateId),
        recordReading: Boolean = true
    ) {
        val observed = transition.observed
        if (recordReading) {
            db.insertOrThrow(
                "debug_direct_readings",
                null,
                ContentValues().apply {
                    put("session_id", sessionId)
                    put("cycle_id", cycleId)
                    put("candidate_id", transition.candidateId)
                    put("sampled_at_ms", sampledAtMs)
                    put("reason", reasonCode(transition.reason))
                    put("status", observed.status)
                    put("raw_present", if (observed.rawPresent) 1 else 0)
                    put("raw_int", observed.raw)
                    put("error", observed.error)
                }
            )
        }
        val values = ContentValues().apply {
            put("catalog_version_id", catalogVersionId)
            put("candidate_id", transition.candidateId)
            put("last_changed_at_ms", sampledAtMs)
            put("last_status", observed.status)
            put("last_raw_present", if (observed.rawPresent) 1 else 0)
            put("last_raw_int", observed.raw)
            put("last_error", observed.error)
        }
        if (stateExists) {
            db.update(
                "debug_direct_candidate_state",
                values,
                "catalog_version_id = ? AND candidate_id = ?",
                arrayOf(catalogVersionId.toString(), transition.candidateId.toString())
            )
        } else {
            db.insertOrThrow("debug_direct_candidate_state", null, values)
        }
    }

    private fun scalarLong(sql: String, cancellationSignal: CancellationSignal? = null): Long {
        val query = if (cancellationSignal == null) {
            helper.readableDatabase.rawQuery(sql, emptyArray())
        } else {
            helper.readableDatabase.rawQuery(sql, emptyArray(), cancellationSignal)
        }
        query.use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun legacyStatus(db: SQLiteDatabase, dbFile: File, readingCount: Long): DirectDebugStatus {
        val session = db.rawQuery(
            """
            SELECT id, started_at, ended_at, batch_size, candidate_count
            FROM debug_direct_sessions
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            arrayOf(
                cursor.getLong(0),
                cursor.getString(1),
                if (cursor.isNull(2)) null else cursor.getString(2),
                cursor.getInt(3),
                cursor.getInt(4)
            )
        }
        val lastReadingAt = db.rawQuery(
            "SELECT sampled_at FROM debug_direct_readings ORDER BY id DESC LIMIT 1",
            emptyArray()
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val lastError = db.rawQuery(
            """
            SELECT sampled_at, COALESCE(NULLIF(error, ''), 'status=' || status)
            FROM debug_direct_readings
            WHERE status != 0
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
        }
        return DirectDebugStatus(
            databasePath = dbFile.absolutePath,
            databaseSizeBytes = sqliteFootprintBytes(dbFile),
            lastSessionId = session?.get(0) as? Long,
            lastSessionStartedAt = session?.get(1) as? String,
            lastSessionEndedAt = session?.get(2) as? String,
            lastBatchSize = session?.get(3) as? Int,
            candidateCount = (session?.get(4) as? Int) ?: DirectDebugParameterAsset.load(context).size,
            readingCount = readingCount,
            lastReadingAt = lastReadingAt,
            lastErrorAt = lastError?.first,
            lastError = lastError?.second,
            errorCount = db.rawQuery(
                "SELECT COALESCE(SUM(error_count), 0) FROM debug_direct_candidate_state",
                emptyArray()
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        )
    }

    private fun nullableLong(sql: String): Long? {
        helper.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }

    private fun latestDebugError(db: SQLiteDatabase): Pair<Long, String>? {
        val current = db.rawQuery(
            """
            SELECT last_changed_at_ms,
                   COALESCE(NULLIF(last_error, ''),
                       CASE WHEN last_raw_present = 0 THEN 'missing raw' ELSE 'status=' || last_status END)
            FROM debug_direct_candidate_state
            WHERE last_status != 0 OR last_raw_present = 0
            ORDER BY last_changed_at_ms DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
        }
        if (current != null) return current

        return db.rawQuery(
            """
            SELECT sampled_at_ms,
                   COALESCE(NULLIF(error, ''),
                       CASE WHEN raw_present = 0 THEN 'missing raw' ELSE 'status=' || status END)
            FROM debug_direct_readings
            WHERE status != 0 OR raw_present = 0
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
        }
    }

    private fun DirectDebugObserved.toPrevious() = DirectDebugPrevious(status, rawPresent, raw, error)

    private fun signature(dev: Int, fid: Int, tx: Int): String = "$dev:$fid:$tx"

    private fun epochMs(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    private fun reasonCode(reason: String): Int = when (reason) {
        "initial" -> 0
        "change" -> 1
        "error_change" -> 2
        else -> error("Unsupported debug transition reason: $reason")
    }

    @Synchronized
    override fun close() {
        activeCatalogVersionId = null
        activeSessionId = null
        candidateIdsByKey.clear()
        candidateIdsByOrdinal.clear()
        candidateIdsByCatalogVersion.clear()
        catalogVersionIdsBySource.clear()
        candidateState.clear()
        if (helperDelegate.isInitialized()) helper.close()
    }

    companion object {
        private const val UNKNOWN_READING_COUNT = -1L
        private const val MAX_REJECTION_CHARS = 512
    }
}
