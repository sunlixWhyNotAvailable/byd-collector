package com.bydcollector.collector.data.debug

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.bydcollector.collector.data.direct.DirectBatchDiagnostics
import com.bydcollector.collector.data.direct.DirectHelperReadResult
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
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
    val reason: String
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
    private val helper: DirectDebugDatabaseHelper = DirectDebugDatabaseHelper(context),
    private val clock: Clock = SystemClockAdapter()
) : Closeable {
    private val candidateIdsByKey = HashMap<String, Long>()
    private val candidateState = HashMap<Long, DirectDebugPrevious>()
    private var activeCatalogVersionId: Long? = null

    fun openSession(parameters: List<DirectDebugParameter>, batchSize: Int): Long {
        val db = helper.writableDatabase
        check(DirectDebugDatabaseHelper.isCompactV2(db)) {
            "Legacy debug database must be archived before round-robin polling starts"
        }
        db.beginTransaction()
        try {
            val (catalogVersionId, sourceVersionChanged) = ensureCatalogVersion(db)
            ensureCandidates(db, parameters, sourceVersionChanged)
            loadCandidateState(db, catalogVersionId)
            val sessionId = db.insertOrThrow(
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
            activeCatalogVersionId = catalogVersionId
            return sessionId
        } finally {
            db.endTransaction()
        }
    }

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
    }

    fun recordCycle(
        sessionId: Long,
        cycleNumber: Long,
        batch: List<DirectDebugParameter>,
        reads: List<Pair<DirectDebugParameter, DirectHelperReadResult>>,
        startedAt: String,
        elapsedMs: Long
    ): DirectDebugCycleSummary {
        val catalogVersionId = checkNotNull(activeCatalogVersionId) { "Debug session is not open" }
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
                transitions += DebugTransition(candidateId, observed, reason)
            }
        }

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
                    put("changed_count", transitions.size)
                    put("error_count", errorCount)
                }
            )
            transitions.forEach { transition ->
                insertTransition(db, sessionId, cycleId, catalogVersionId, sampledAtMs, transition)
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
                arrayOf(batch.size, okCount, transitions.size, errorCount, sampledAtMs, sessionId)
            )
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
            changedCount = transitions.size,
            errorCount = errorCount,
            elapsedMs = elapsedMs
        )
    }

    fun dashboardReadingCount(): Long = scalarLong("SELECT COUNT(*) FROM debug_direct_readings")

    fun status(readingCount: Long = UNKNOWN_READING_COUNT): DirectDebugStatus {
        val db = helper.readableDatabase
        val dbFile = context.getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME)
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

    fun checkpointForArchive() {
        runCatching {
            helper.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
                while (cursor.moveToNext()) Unit
            }
        }.onFailure { error ->
            Log.w(TAG, "debug database WAL checkpoint failed before archive", error)
        }
    }

    fun verifyWritableDatabase(): Boolean {
        helper.writableDatabase.rawQuery("PRAGMA quick_check", emptyArray()).use { cursor ->
            return cursor.moveToFirst() && cursor.getString(0) == "ok"
        }
    }

    fun databaseFile() = context.getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME)

    fun isCompactV2(): Boolean = DirectDebugDatabaseHelper.isCompactV2(helper.readableDatabase)

    private fun ensureCatalogVersion(db: SQLiteDatabase): Pair<Long, Boolean> {
        val sourceVersionChanged = db.insertWithOnConflict(
            "debug_direct_catalog_versions",
            null,
            ContentValues().apply { put("source_version", DirectDebugParameterAsset.SOURCE_VERSION) },
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        db.rawQuery(
            "SELECT id FROM debug_direct_catalog_versions WHERE source_version = ?",
            arrayOf(DirectDebugParameterAsset.SOURCE_VERSION)
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

    private fun loadCandidateState(db: SQLiteDatabase, catalogVersionId: Long) {
        candidateState.clear()
        db.rawQuery(
            """
            SELECT candidate_id, last_status, last_raw_present, last_raw_int, last_error
            FROM debug_direct_candidate_state
            WHERE catalog_version_id = ?
            """.trimIndent(),
            arrayOf(catalogVersionId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                candidateState[cursor.getLong(0)] = DirectDebugPrevious(
                    status = cursor.getInt(1),
                    rawPresent = cursor.getInt(2) == 1,
                    raw = if (cursor.isNull(3)) null else cursor.getInt(3),
                    error = if (cursor.isNull(4)) null else cursor.getString(4)
                )
            }
        }
    }

    private fun insertTransition(
        db: SQLiteDatabase,
        sessionId: Long,
        cycleId: Long,
        catalogVersionId: Long,
        sampledAtMs: Long,
        transition: DebugTransition
    ) {
        val observed = transition.observed
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
        val values = ContentValues().apply {
            put("catalog_version_id", catalogVersionId)
            put("candidate_id", transition.candidateId)
            put("last_changed_at_ms", sampledAtMs)
            put("last_status", observed.status)
            put("last_raw_present", if (observed.rawPresent) 1 else 0)
            put("last_raw_int", observed.raw)
            put("last_error", observed.error)
        }
        if (candidateState.containsKey(transition.candidateId)) {
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

    private fun scalarLong(sql: String): Long {
        helper.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
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

    override fun close() {
        helper.close()
    }

    companion object {
        private const val TAG = "BYDCollectorDebugStore"
        private const val UNKNOWN_READING_COUNT = -1L
    }
}
