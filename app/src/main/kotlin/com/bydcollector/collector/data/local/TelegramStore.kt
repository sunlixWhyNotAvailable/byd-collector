package com.bydcollector.collector.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.service.TelegramEventState
import java.io.Closeable

/** Durable Telegram outbox/state facade. The database lives outside Main archives. */
class TelegramStore(
    context: Context,
    private val helper: TelegramDatabaseHelper = TelegramDatabaseHelper(context),
    private val clockMs: () -> Long = System::currentTimeMillis
) : Closeable {

    override fun close() = helper.close()

    fun databaseFile() = helper.databaseFile()

    fun commitTelegramEvents(
        messages: List<TelegramOutboxMessage>,
        stateJson: String?,
        nowMs: Long = clockMs()
    ): List<TelegramEnqueueResult> {
        if (messages.isEmpty() && stateJson == null) return emptyList()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val results = messages.map { enqueueTelegramMessage(db, it, nowMs) }
            stateJson?.let { saveTelegramRuntimeState(db, it, nowMs) }
            db.setTransactionSuccessful()
            return results
        } finally {
            db.endTransaction()
        }
    }

    private fun enqueueTelegramMessage(
        db: SQLiteDatabase,
        message: TelegramOutboxMessage,
        nowMs: Long
    ): TelegramEnqueueResult {
        require(message.dedupeKey.isNotBlank()) { "Telegram dedupe key must not be blank" }
        require(message.eventType.isNotBlank()) { "Telegram event type must not be blank" }
        require(message.payload.isNotBlank()) { "Telegram payload must not be blank" }
        val expired = db.delete(
            "telegram_outbox",
            "created_at_ms < ? AND attempt_count > 0",
            arrayOf((nowMs - TelegramDatabaseHelper.RETENTION_MS).toString())
        )
        if (telegramMessageExists(db, message.dedupeKey)) {
            return TelegramEnqueueResult(inserted = false, expiredCount = expired, overflowCount = 0)
        }
        val pending = db.rawQuery("SELECT COUNT(*) FROM telegram_outbox", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        val overflow = (pending - TelegramDatabaseHelper.MAX_PENDING + 1L).coerceAtLeast(0L).toInt()
        if (overflow > 0) {
            db.delete(
                "telegram_outbox",
                "id IN (SELECT id FROM telegram_outbox ORDER BY id LIMIT ?)",
                arrayOf(overflow.toString())
            )
        }
        val inserted = db.insertWithOnConflict(
            "telegram_outbox",
            null,
            ContentValues().apply {
                put("dedupe_key", message.dedupeKey)
                put("event_type", message.eventType)
                put("payload", message.payload)
                message.waitsForSummaryKey?.let { put("waits_for_summary_key", it) }
                put("created_at_ms", nowMs)
                put("next_attempt_at_ms", nowMs)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        return TelegramEnqueueResult(inserted, expired, overflow)
    }

    fun oldestUnblockedTelegramMessage(eventType: String? = null): TelegramOutboxEntry? {
        val filter = if (eventType == null) "" else " AND event_type = ?"
        return queryTelegramMessage(
            """
            SELECT id, dedupe_key, event_type, payload, attempt_count, next_attempt_at_ms, blocked,
                   waits_for_summary_key
            FROM telegram_outbox
            WHERE blocked = 0 AND waits_for_summary_key IS NULL$filter
            ORDER BY id
            LIMIT 1
            """.trimIndent(),
            if (eventType == null) emptyArray() else arrayOf(eventType)
        )
    }

    fun telegramMessageByDedupeKey(dedupeKey: String): TelegramOutboxEntry? = queryTelegramMessage(
        """
            SELECT id, dedupe_key, event_type, payload, attempt_count, next_attempt_at_ms, blocked,
                   waits_for_summary_key
            FROM telegram_outbox
            WHERE dedupe_key = ?
            LIMIT 1
        """.trimIndent(),
        arrayOf(dedupeKey)
    )

    private fun queryTelegramMessage(sql: String, args: Array<String>): TelegramOutboxEntry? =
        helper.readableDatabase.rawQuery(sql, args).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            TelegramOutboxEntry(
                id = cursor.getLong(0),
                dedupeKey = cursor.getString(1),
                eventType = cursor.getString(2),
                payload = cursor.getString(3),
                attemptCount = cursor.getInt(4),
                nextAttemptAtMs = cursor.getLong(5),
                blocked = cursor.getInt(6) != 0,
                waitsForSummaryKey = if (cursor.isNull(7)) null else cursor.getString(7)
            )
        }

    fun pruneTelegramMessages(nowMs: Long = clockMs()): Int = helper.writableDatabase.delete(
        "telegram_outbox",
        "created_at_ms < ? AND attempt_count > 0",
        arrayOf((nowMs - TelegramDatabaseHelper.RETENTION_MS).toString())
    )

    fun markTelegramDelivered(id: Long, stateJson: String?, deliveredAtMs: Long = clockMs()) {
        val db = helper.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val dedupeKey = db.rawQuery(
                "SELECT dedupe_key FROM telegram_outbox WHERE id = ?",
                arrayOf(id.toString())
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Telegram outbox row disappeared before delivery commit" }
                cursor.getString(0)
            }
            check(db.delete("telegram_outbox", "id = ?", arrayOf(id.toString())) == 1) {
                "Telegram outbox row disappeared before delivery commit"
            }
            db.update(
                "telegram_outbox",
                ContentValues().apply { putNull("waits_for_summary_key") },
                "waits_for_summary_key = ?",
                arrayOf(dedupeKey)
            )
            stateJson?.let { saveTelegramRuntimeState(db, it, deliveredAtMs) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markTelegramRetry(
        id: Long,
        error: String,
        attemptedAtMs: Long,
        nextAttemptAtMs: Long
    ) {
        helper.writableDatabase.update(
            "telegram_outbox",
            ContentValues().apply {
                put("attempt_count", telegramAttemptCount(id) + 1)
                put("last_attempt_at_ms", attemptedAtMs)
                put("next_attempt_at_ms", nextAttemptAtMs)
                put("last_error", error.truncateForStorage(MAX_ERROR_TEXT_LENGTH))
                put("blocked", 0)
            },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    fun markTelegramBlocked(id: Long, error: String, attemptedAtMs: Long) {
        helper.writableDatabase.update(
            "telegram_outbox",
            ContentValues().apply {
                put("attempt_count", telegramAttemptCount(id) + 1)
                put("last_attempt_at_ms", attemptedAtMs)
                put("last_error", error.truncateForStorage(MAX_ERROR_TEXT_LENGTH))
                put("blocked", 1)
            },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    fun unblockTelegramMessages(nowMs: Long = clockMs()) {
        helper.writableDatabase.execSQL(
            """
            UPDATE telegram_outbox
            SET blocked = 0,
                next_attempt_at_ms = MAX(next_attempt_at_ms, ?)
            WHERE blocked = 1
            """.trimIndent(),
            arrayOf(nowMs)
        )
    }

    fun telegramRuntimeState(): String? = helper.readableDatabase.rawQuery(
        "SELECT state_json FROM telegram_runtime_state WHERE id = 1",
        emptyArray()
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** Reads only bounded, redacted delivery metadata; payloads and errors never leave this store. */
    @Synchronized
    internal fun diagnosticSnapshot(limit: Int = 64): TelegramDiagnosticSnapshot {
        require(limit in 1..64) { "Diagnostic Telegram row limit must be 1..64" }
        val db = helper.readableDatabase
        var runtimePresent = false
        var runtimeUpdatedAtMs: Long? = null
        var pendingTripId: String? = null
        var summaryDelivered = false
        var runtimeValid = true
        db.rawQuery(
            "SELECT state_json, updated_at_ms FROM telegram_runtime_state WHERE id = 1",
            emptyArray()
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                runtimePresent = true
                runtimeUpdatedAtMs = cursor.getLong(1)
                val stateJson = cursor.getString(0)
                val state = TelegramEventState.fromJsonOrNull(stateJson)
                runtimeValid = state != null
                pendingTripId = state?.pendingPowerOffLocationTripId
                summaryDelivered = state?.pendingPowerOffLocationSummaryDelivered == true
            }
        }
        val relevantWhere = "(event_type = ? OR waits_for_summary_key IS NOT NULL)"
        val relevantArgs = arrayOf("trip_summary")
        val outboxTotal = db.rawQuery("SELECT COUNT(*) FROM telegram_outbox", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        val relevantTotal = db.rawQuery(
            "SELECT COUNT(*) FROM telegram_outbox WHERE $relevantWhere",
            relevantArgs
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        val rows = queryDiagnosticRows(
            db,
            "WHERE $relevantWhere ORDER BY id DESC LIMIT ?",
            arrayOf("trip_summary", limit.toString())
        ).toMutableList()
        val pendingKeys = pendingTripId?.takeIf(String::isNotBlank)?.let {
            listOf("$it:summary", "$it:location")
        }.orEmpty()
        if (pendingKeys.isNotEmpty()) {
            val placeholders = pendingKeys.joinToString(",") { "?" }
            val targeted = queryDiagnosticRows(
                db,
                "WHERE dedupe_key IN ($placeholders) ORDER BY id DESC",
                pendingKeys.toTypedArray()
            )
            targeted.forEach { row ->
                if (rows.none { it.id == row.id }) {
                    if (rows.size >= limit) rows.removeAt(rows.lastIndex)
                    rows += row
                }
            }
            rows.sortByDescending { it.id }
        }
        return TelegramDiagnosticSnapshot(
            status = "ok",
            runtimeStatePresent = runtimePresent,
            runtimeStateValid = runtimeValid,
            runtimeStateUpdatedAtMs = runtimeUpdatedAtMs,
            pendingPowerOffLocationTripId = pendingTripId,
            pendingPowerOffLocationSummaryDelivered = summaryDelivered,
            outboxTotal = outboxTotal,
            relevantRowsTotal = relevantTotal,
            rows = rows,
            rowsTruncated = relevantTotal > rows.size
        )
    }

    private fun queryDiagnosticRows(
        db: SQLiteDatabase,
        suffix: String,
        args: Array<String>
    ): List<TelegramDiagnosticRow> = db.rawQuery(
        "SELECT id, dedupe_key, event_type, created_at_ms, next_attempt_at_ms, attempt_count, blocked, waits_for_summary_key FROM telegram_outbox $suffix",
        args
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    TelegramDiagnosticRow(
                        id = cursor.getLong(0),
                        dedupeKey = cursor.getString(1),
                        eventType = cursor.getString(2),
                        createdAtMs = cursor.getLong(3),
                        nextAttemptAtMs = cursor.getLong(4),
                        attemptCount = cursor.getInt(5),
                        blocked = cursor.getInt(6) != 0,
                        waitsForSummaryKey = if (cursor.isNull(7)) null else cursor.getString(7)
                    )
                )
            }
        }
    }

    fun verifyRuntimeState(): Boolean = helper.readableDatabase.rawQuery(
        "SELECT state_json, updated_at_ms FROM telegram_runtime_state WHERE id = 1",
        emptyArray()
    ).use { cursor ->
        if (!cursor.moveToFirst()) true
        else !cursor.isNull(0) && !cursor.isNull(1) &&
            cursor.getString(0).isNotBlank() && cursor.getLong(1) >= 0L &&
            TelegramEventState.fromJsonOrNull(cursor.getString(0)) != null
    }

    fun saveTelegramRuntimeState(stateJson: String, nowMs: Long = clockMs()) {
        require(stateJson.isNotBlank()) { "Telegram runtime state must not be blank" }
        saveTelegramRuntimeState(helper.writableDatabase, stateJson, nowMs)
    }

    private fun saveTelegramRuntimeState(db: SQLiteDatabase, stateJson: String, nowMs: Long) {
        db.insertWithOnConflict(
            "telegram_runtime_state",
            null,
            ContentValues().apply {
                put("id", 1)
                put("state_json", stateJson)
                put("updated_at_ms", nowMs)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** Import the bounded Main snapshot and commit its completion marker atomically. */
    @Synchronized
    fun importLegacySnapshot(
        snapshot: TelegramLegacySnapshot,
        nowMs: Long = clockMs()
    ): TelegramMigrationResult {
        if (!snapshot.validForImport || snapshot.outbox.size > TelegramDatabaseHelper.MAX_PENDING || snapshot.runtimeStateJson?.isBlank() == true) {
            return TelegramMigrationResult(
                status = TelegramMigrationResult.Status.SKIPPED_INVALID,
                expectedOutboxCount = snapshot.outbox.size,
                errorMessage = snapshot.readError ?: "Legacy Telegram snapshot is invalid"
            )
        }
        if ((snapshot.runtimeStateJson == null) != (snapshot.runtimeStateUpdatedAtMs == null) ||
            snapshot.runtimeStateUpdatedAtMs?.let { it < 0L } == true
        ) {
            return TelegramMigrationResult(
                status = TelegramMigrationResult.Status.SKIPPED_INVALID,
                expectedOutboxCount = snapshot.outbox.size,
                errorMessage = "Legacy Telegram runtime-state timestamp is invalid"
            )
        }
        snapshot.outbox.firstOrNull { invalidLegacyRow(it) }?.let { row ->
            return TelegramMigrationResult(
                status = TelegramMigrationResult.Status.SKIPPED_INVALID,
                expectedOutboxCount = snapshot.outbox.size,
                errorMessage = "Legacy Telegram outbox row ${row.id} is invalid"
            )
        }
        snapshot.runtimeStateJson?.let { state ->
            if (TelegramEventState.fromJsonOrNull(state) == null) {
                return TelegramMigrationResult(
                    status = TelegramMigrationResult.Status.SKIPPED_INVALID,
                    expectedOutboxCount = snapshot.outbox.size,
                    errorMessage = "Legacy Telegram runtime state is not parseable"
                )
            }
        }

        return runCatching {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                if (mainImportComplete(db)) {
                    val copiedCount = countOutbox(db)
                    // Once Main has been cleaned, an empty legacy snapshot is the
                    // expected steady state; the sidecar is allowed to contain
                    // newer rows/state created after the original migration.
                    val exact = snapshot.outbox.isEmpty() && snapshot.runtimeStateJson == null ||
                        exactSnapshot(db, snapshot, nowMs)
                    db.setTransactionSuccessful()
                    return@runCatching TelegramMigrationResult(
                        status = TelegramMigrationResult.Status.ALREADY_COMPLETE,
                        copiedOutboxCount = copiedCount,
                        expectedOutboxCount = snapshot.outbox.size,
                        stateCopied = snapshot.runtimeStateJson != null && exactRuntimeState(db, snapshot, nowMs),
                        errorMessage = if (exact) null else "Completed Telegram migration does not match the Main snapshot",
                        sidecarVerified = exact
                    )
                }
                // A previous process may have inserted rows before dying. IGNORE
                // makes replay safe; the count checks below reject partial/corrupt copies.
                snapshot.outbox.forEach { row ->
                    db.insertWithOnConflict(
                        "telegram_outbox",
                        null,
                        row.toContentValues(),
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
                snapshot.runtimeStateJson?.let { state ->
                    db.insertWithOnConflict(
                        "telegram_runtime_state",
                        null,
                        ContentValues().apply {
                            put("id", 1)
                            put("state_json", state)
                            put("updated_at_ms", snapshot.runtimeStateUpdatedAtMs ?: nowMs)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                val copiedCount = countOutbox(db)
                check(copiedCount == snapshot.outbox.size) {
                    "Telegram outbox migration copied $copiedCount/${snapshot.outbox.size} rows"
                }
                check(exactRuntimeState(db, snapshot, nowMs)) {
                    "Telegram runtime state migration verification failed"
                }
                val stateCopied = snapshot.runtimeStateJson != null
                check(exactSnapshot(db, snapshot, nowMs)) {
                    "Telegram outbox migration column verification failed"
                }
                db.update(
                    "telegram_migration_state",
                    ContentValues().apply {
                        put("main_import_complete", 1)
                        put("imported_outbox_count", copiedCount)
                        put("imported_state_present", if (stateCopied) 1 else 0)
                        put("completed_at_ms", nowMs)
                    },
                    "id = 1",
                    emptyArray()
                ).also { check(it == 1) { "Telegram migration marker is missing" } }
                db.setTransactionSuccessful()
                TelegramMigrationResult(
                    status = TelegramMigrationResult.Status.COMMITTED,
                    copiedOutboxCount = copiedCount,
                    expectedOutboxCount = snapshot.outbox.size,
                    stateCopied = stateCopied
                )
            } finally {
                db.endTransaction()
            }
        }.getOrElse { error ->
            TelegramMigrationResult(
                status = TelegramMigrationResult.Status.FAILED,
                expectedOutboxCount = snapshot.outbox.size,
                errorMessage = error.message ?: error::class.java.simpleName
            )
        }
    }

    fun migrateFromMain(mainStore: TelemetryStore, nowMs: Long = clockMs()): TelegramMigrationResult =
        importLegacySnapshot(mainStore.readLegacyTelegramSnapshot(), nowMs)

    fun isMainImportComplete(): Boolean = helper.readableDatabase.rawQuery(
        "SELECT main_import_complete FROM telegram_migration_state WHERE id = 1",
        emptyArray()
    ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }

    private fun mainImportComplete(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT main_import_complete FROM telegram_migration_state WHERE id = 1",
        emptyArray()
    ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }

    private fun countOutbox(): Int = countOutbox(helper.readableDatabase)

    private fun countOutbox(db: SQLiteDatabase): Int = db.rawQuery(
        "SELECT COUNT(*) FROM telegram_outbox", emptyArray()
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun telegramAttemptCount(id: Long): Int = helper.readableDatabase.rawQuery(
        "SELECT attempt_count FROM telegram_outbox WHERE id = ?",
        arrayOf(id.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun telegramMessageExists(db: SQLiteDatabase, dedupeKey: String): Boolean = db.rawQuery(
        "SELECT 1 FROM telegram_outbox WHERE dedupe_key = ? LIMIT 1",
        arrayOf(dedupeKey)
    ).use { cursor -> cursor.moveToFirst() }

    private fun invalidLegacyRow(row: TelegramLegacyOutboxRow): Boolean =
        row.id <= 0L || row.dedupeKey.isBlank() || row.eventType.isBlank() || row.payload.isBlank() ||
            row.createdAtMs < 0L || row.nextAttemptAtMs < 0L || row.attemptCount < 0 ||
            row.lastAttemptAtMs?.let { it < 0L } == true || row.blockedCode !in 0..1

    private fun exactSnapshot(
        db: SQLiteDatabase,
        snapshot: TelegramLegacySnapshot,
        nowMs: Long
    ): Boolean {
        if (countOutbox(db) != snapshot.outbox.size) return false
        if (!snapshot.outbox.all { row -> exactOutboxRow(db, row) }) return false
        return exactRuntimeState(db, snapshot, nowMs)
    }

    private fun exactOutboxRow(db: SQLiteDatabase, row: TelegramLegacyOutboxRow): Boolean = db.rawQuery(
        """
        SELECT dedupe_key, event_type, payload, created_at_ms, next_attempt_at_ms,
               attempt_count, last_attempt_at_ms, last_error, blocked
        FROM telegram_outbox WHERE id = ?
        """.trimIndent(),
        arrayOf(row.id.toString())
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use false
        cursor.getString(0) == row.dedupeKey &&
            cursor.getString(1) == row.eventType &&
            cursor.getString(2) == row.payload &&
            cursor.getLong(3) == row.createdAtMs &&
            cursor.getLong(4) == row.nextAttemptAtMs &&
            cursor.getInt(5) == row.attemptCount &&
            (if (cursor.isNull(6)) null else cursor.getLong(6)) == row.lastAttemptAtMs &&
            (if (cursor.isNull(7)) null else cursor.getString(7)) == row.lastError &&
            cursor.getInt(8) == row.blockedCode
    }

    private fun exactRuntimeState(
        db: SQLiteDatabase,
        snapshot: TelegramLegacySnapshot,
        nowMs: Long
    ): Boolean {
        val expected = snapshot.runtimeStateJson
        val expectedTimestamp = snapshot.runtimeStateUpdatedAtMs ?: nowMs
        return db.rawQuery(
            "SELECT state_json, updated_at_ms FROM telegram_runtime_state WHERE id = 1",
            emptyArray()
        ).use { cursor ->
            if (expected == null) {
                !cursor.moveToFirst()
            } else {
                cursor.moveToFirst() && cursor.getString(0) == expected && cursor.getLong(1) == expectedTimestamp
            }
        }
    }

    private fun TelegramLegacyOutboxRow.toContentValues() = ContentValues().apply {
        put("id", id)
        put("dedupe_key", dedupeKey)
        put("event_type", eventType)
        put("payload", payload)
        put("created_at_ms", createdAtMs)
        put("next_attempt_at_ms", nextAttemptAtMs)
        put("attempt_count", attemptCount)
        if (lastAttemptAtMs == null) putNull("last_attempt_at_ms") else put("last_attempt_at_ms", lastAttemptAtMs)
        if (lastError == null) putNull("last_error") else put("last_error", lastError)
        put("blocked", if (blocked) 1 else 0)
    }

    private fun String.truncateForStorage(maxLength: Int): String {
        if (length <= maxLength) return this
        return take(maxLength) + "...[truncated ${length - maxLength} chars]"
    }

    companion object {
        private const val MAX_ERROR_TEXT_LENGTH = 2_048
    }
}
