package com.bydcollector.collector.data.debug

import android.content.ContentValues
import android.content.Context
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.data.direct.DirectHelperReadResult
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import java.io.Closeable

data class DirectDebugObserved(
    val status: Int,
    val rawPresent: Boolean,
    val raw: Int?,
    val error: String?
)

data class DirectDebugPrevious(
    val status: Int?,
    val rawPresent: Boolean,
    val raw: Int?,
    val error: String?
)

object DirectDebugChangeDetector {
    fun reason(previous: DirectDebugPrevious?, current: DirectDebugObserved): String? {
        if (previous == null) return "initial"
        val changed = previous.status != current.status ||
            previous.rawPresent != current.rawPresent ||
            previous.raw != current.raw ||
            previous.error != current.error
        if (!changed) return null
        return if (current.status == 0) {
            "change"
        } else {
            "error_change"
        }
    }
}

data class DirectDebugCycleSummary(
    val cycleId: Long,
    val attemptedCount: Int,
    val okCount: Int,
    val changedCount: Int,
    val errorCount: Int,
    val elapsedMs: Long
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

class DirectDebugStore(
    private val context: Context,
    private val helper: DirectDebugDatabaseHelper = DirectDebugDatabaseHelper(context),
    private val clock: Clock = SystemClockAdapter()
) : Closeable {
    private val candidateIds = mutableMapOf<String, Long>()

    fun openSession(parameters: List<DirectDebugParameter>, batchSize: Int): Long {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ensureCandidates(parameters)
            val sessionId = db.insertOrThrow(
                "debug_direct_sessions",
                null,
                ContentValues().apply {
                    put("started_at", clock.nowIso())
                    put("source_version", DirectDebugParameterAsset.SOURCE_VERSION)
                    put("candidate_count", parameters.size)
                    put("poll_mode", "leftover_round_robin")
                    put("batch_size", batchSize.coerceAtLeast(1))
                    put("interval_ms", DirectDebugRoundRobinPoller.INTERVAL_MS)
                }
            )
            db.setTransactionSuccessful()
            return sessionId
        } finally {
            db.endTransaction()
        }
    }

    fun endSession(sessionId: Long, reason: String) {
        helper.writableDatabase.update(
            "debug_direct_sessions",
            ContentValues().apply {
                put("ended_at", clock.nowIso())
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
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val cycleId = db.insertOrThrow(
                "debug_direct_cycles",
                null,
                ContentValues().apply {
                    put("session_id", sessionId)
                    put("cycle_number", cycleNumber)
                    put("started_at", startedAt)
                    put("elapsed_ms", elapsedMs)
                    put("attempted_count", batch.size)
                }
            )
            var okCount = 0
            var changedCount = 0
            var errorCount = 0
            reads.forEach { (parameter, result) ->
                if (result.status == 0 && result.raw != null) okCount++ else errorCount++
                val wrote = recordReadLocked(sessionId, cycleId, parameter, result, startedAt)
                if (wrote) changedCount++
            }
            if (changedCount == 0) {
                db.delete("debug_direct_cycles", "id = ?", arrayOf(cycleId.toString()))
            } else {
                db.update(
                    "debug_direct_cycles",
                    ContentValues().apply {
                        put("ok_count", okCount)
                        put("changed_count", changedCount)
                        put("error_count", errorCount)
                    },
                    "id = ?",
                    arrayOf(cycleId.toString())
                )
            }
            db.setTransactionSuccessful()
            return DirectDebugCycleSummary(cycleId, batch.size, okCount, changedCount, errorCount, elapsedMs)
        } finally {
            db.endTransaction()
        }
    }

    fun status(): DirectDebugStatus {
        val db = helper.readableDatabase
        val dbFile = context.getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME)
        val session = db.rawQuery(
            """
            SELECT id, started_at, ended_at, batch_size, candidate_count
            FROM debug_direct_sessions
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                arrayOf(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getInt(3),
                    cursor.getInt(4)
                )
            } else {
                null
            }
        }
        val readingCount = scalarLong("SELECT COUNT(*) FROM debug_direct_readings")
        val lastReadingAt = db.rawQuery(
            "SELECT sampled_at FROM debug_direct_readings ORDER BY id DESC LIMIT 1",
            emptyArray()
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val lastError = latestDebugError(db)
        val errorCount = scalarLong("SELECT COALESCE(SUM(error_count), 0) FROM debug_direct_candidate_state")

        return DirectDebugStatus(
            databasePath = dbFile.absolutePath,
            databaseSizeBytes = dbFile.takeIf { it.exists() }?.length() ?: 0L,
            lastSessionId = session?.get(0) as? Long,
            lastSessionStartedAt = session?.get(1) as? String,
            lastSessionEndedAt = session?.get(2) as? String,
            lastBatchSize = session?.get(3) as? Int,
            candidateCount = (session?.get(4) as? Int) ?: DirectDebugParameterAsset.load(context).size,
            readingCount = readingCount,
            lastReadingAt = lastReadingAt,
            lastErrorAt = lastError?.first,
            lastError = lastError?.second,
            errorCount = errorCount
        )
    }

    fun pullCommand(): String {
        return DirectDebugStore.pullCommand()
    }

    private fun ensureCandidates(parameters: List<DirectDebugParameter>) {
        parameters.forEach { parameter ->
            val key = signature(parameter)
            if (candidateIds.containsKey(key)) return@forEach
            helper.writableDatabase.insertWithOnConflict(
                "debug_direct_candidates",
                null,
                ContentValues().apply {
                    put("source_key", parameter.key)
                    put("dev", parameter.dev)
                    put("fid", parameter.fid)
                    put("tx", parameter.tx)
                    put("feature_group", parameter.featureGroup)
                    put("feature_names", parameter.featureNames)
                    put("feature_refs", parameter.featureRefs)
                    put("candidate_source", parameter.candidateSource)
                    put("decoder", parameter.toDirectFidEntry().decoder.name)
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
            )
            candidateIds[key] = findCandidateId(parameter)
        }
    }

    private fun recordReadLocked(
        sessionId: Long,
        cycleId: Long,
        parameter: DirectDebugParameter,
        result: DirectHelperReadResult,
        sampledAt: String
    ): Boolean {
        val candidateId = candidateIds.getOrPut(signature(parameter)) { findCandidateId(parameter) }
        val observed = DirectDebugObserved(
            status = result.status,
            rawPresent = result.raw != null,
            raw = result.raw,
            error = result.error
        )
        val previous = previousState(sessionId, candidateId)
        val reason = DirectDebugChangeDetector.reason(previous, observed)
        val db = helper.writableDatabase
        db.insertWithOnConflict(
            "debug_direct_candidate_state",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("candidate_id", candidateId)
                put("last_sampled_at", sampledAt)
                put("last_status", result.status)
                put("last_raw_present", if (result.raw != null) 1 else 0)
                put("last_raw_int", result.raw)
                put("last_error", result.error)
                put("read_count", 0)
                put("write_count", 0)
                put("change_count", 0)
                put("error_count", 0)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        db.execSQL(
            """
            UPDATE debug_direct_candidate_state
            SET last_sampled_at = ?,
                last_status = ?,
                last_raw_present = ?,
                last_raw_int = ?,
                last_error = ?,
                read_count = read_count + 1,
                error_count = error_count + ?
            WHERE session_id = ? AND candidate_id = ?
            """.trimIndent(),
            arrayOf(
                sampledAt,
                result.status,
                if (result.raw != null) 1 else 0,
                result.raw,
                result.error,
                if (result.status == 0) 0 else 1,
                sessionId,
                candidateId
            )
        )
        if (reason == null) return false

        db.insertOrThrow(
            "debug_direct_readings",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("cycle_id", cycleId)
                put("candidate_id", candidateId)
                put("sampled_at", sampledAt)
                put("reason", reason)
                put("status", result.status)
                put("raw_present", if (result.raw != null) 1 else 0)
                put("raw_int", result.raw)
                put("raw_hex", result.raw?.let { "0x" + Integer.toUnsignedString(it, 16) })
                put("float_value", result.raw?.let { Float.fromBits(it).toDouble() })
                put("error", result.error)
            }
        )
        db.execSQL(
            """
            UPDATE debug_direct_candidate_state
            SET last_written_at = ?,
                write_count = write_count + 1,
                change_count = change_count + ?
            WHERE session_id = ? AND candidate_id = ?
            """.trimIndent(),
            arrayOf(sampledAt, if (reason == "change") 1 else 0, sessionId, candidateId)
        )
        return true
    }

    private fun previousState(sessionId: Long, candidateId: Long): DirectDebugPrevious? {
        helper.readableDatabase.rawQuery(
            """
            SELECT last_status, last_raw_present, last_raw_int, last_error
            FROM debug_direct_candidate_state
            WHERE session_id = ? AND candidate_id = ?
            """.trimIndent(),
            arrayOf(sessionId.toString(), candidateId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return DirectDebugPrevious(
                status = cursor.getInt(0),
                rawPresent = cursor.getInt(1) == 1,
                raw = if (cursor.isNull(2)) null else cursor.getInt(2),
                error = if (cursor.isNull(3)) null else cursor.getString(3)
            )
        }
    }

    private fun findCandidateId(parameter: DirectDebugParameter): Long {
        helper.readableDatabase.rawQuery(
            "SELECT id FROM debug_direct_candidates WHERE dev = ? AND fid = ? AND tx = ?",
            arrayOf(parameter.dev.toString(), parameter.fid.toString(), parameter.tx.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        error("Debug candidate missing for ${parameter.key}")
    }

    private fun scalarLong(sql: String): Long {
        helper.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun latestDebugError(db: android.database.sqlite.SQLiteDatabase): Pair<String, String>? {
        val current = db.rawQuery(
            """
            SELECT last_sampled_at,
                   COALESCE(NULLIF(last_error, ''), 'status=' || last_status)
            FROM debug_direct_candidate_state
            WHERE last_status IS NOT NULL
              AND last_status != 0
            ORDER BY last_sampled_at DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0) to cursor.getString(1)
            } else {
                null
            }
        }
        if (current != null) return current

        return db.rawQuery(
            """
            SELECT sampled_at,
                   COALESCE(NULLIF(error, ''), 'status=' || status)
            FROM debug_direct_readings
            WHERE status != 0
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0) to cursor.getString(1)
            } else {
                null
            }
        }
    }

    private fun signature(parameter: DirectDebugParameter): String {
        return "${parameter.dev}:${parameter.fid}:${parameter.tx}"
    }

    override fun close() {
        helper.close()
    }

    companion object {
        private const val WINDOWS_PULL_ROOT = "D:\\Work_folder\\!test\\byd web\\manual_pulls"

        fun pullCommand(): String {
            return "adb exec-out run-as ${BuildConfig.APPLICATION_ID} cat databases/${DirectDebugDatabaseHelper.DATABASE_NAME} > \"$WINDOWS_PULL_ROOT\\bydcollector_debug_db\\${DirectDebugDatabaseHelper.DATABASE_NAME}\""
        }
    }
}
