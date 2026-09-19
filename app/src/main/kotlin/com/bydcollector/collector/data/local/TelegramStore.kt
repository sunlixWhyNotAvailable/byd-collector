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
) : Closeable, TelegramDeliveryStore {

    override fun close() = helper.close()

    fun databaseFile() = helper.databaseFile()

    fun commitTelegramEvents(
        messages: List<TelegramOutboxMessage>,
        stateJson: String?,
        nowMs: Long = clockMs(),
        completionSequence: Long? = null,
        completionIdentity: String? = null
    ): List<TelegramEnqueueResult> {
        require((completionSequence == null) == (completionIdentity == null))
        if (messages.isEmpty() && stateJson == null && completionSequence == null) return emptyList()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            if (completionSequence != null && hasTripCompletionReceipt(db, completionSequence, completionIdentity!!)) {
                db.setTransactionSuccessful()
                return emptyList()
            }
            val results = messages.map { enqueueTelegramMessage(db, it, nowMs) }
            stateJson?.let { saveTelegramRuntimeState(db, it, nowMs) }
            if (completionSequence != null) {
                db.delete("telegram_trip_completion_receipt", "id = 1", emptyArray())
                db.insertOrThrow("telegram_trip_completion_receipt", null, ContentValues().apply {
                    put("id", 1)
                    put("sequence", completionSequence)
                    put("identity", completionIdentity)
                })
            }
            db.setTransactionSuccessful()
            return results
        } finally {
            db.endTransaction()
        }
    }

    fun hasTripCompletionReceipt(sequence: Long, identity: String): Boolean =
        hasTripCompletionReceipt(helper.readableDatabase, sequence, identity)

    private fun hasTripCompletionReceipt(db: SQLiteDatabase, sequence: Long, identity: String): Boolean {
        require(sequence > 0L && identity.isNotBlank())
        return db.rawQuery("SELECT sequence, identity FROM telegram_trip_completion_receipt WHERE id = 1", emptyArray()).use {
            if (!it.moveToFirst()) return@use false
            check(it.getLong(0) <= sequence) { "Trip completion handoff is out of order" }
            if (it.getLong(0) != sequence) return@use false
            check(it.getString(1) == identity) { "Trip completion identity changed" }
            true
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
                   waits_for_summary_key, created_at_ms, failure_count, last_error
            FROM telegram_outbox
            WHERE blocked = 0 AND waits_for_summary_key IS NULL$filter
            ORDER BY id
            LIMIT 1
            """.trimIndent(),
            if (eventType == null) emptyArray() else arrayOf(eventType)
        )
    }

    override fun oldestDueTelegramMessage(nowMs: Long, eventType: String?): TelegramOutboxEntry? {
        val filter = if (eventType == null) "" else " AND event_type = ?"
        val args = if (eventType == null) {
            arrayOf(nowMs.toString())
        } else {
            arrayOf(nowMs.toString(), eventType)
        }
        return queryTelegramMessage(
            """
            SELECT id, dedupe_key, event_type, payload, attempt_count, next_attempt_at_ms, blocked,
                   waits_for_summary_key, created_at_ms, failure_count, last_error
            FROM telegram_outbox
            WHERE blocked = 0 AND waits_for_summary_key IS NULL
              AND next_attempt_at_ms <= ?$filter
            ORDER BY id
            LIMIT 1
            """.trimIndent(),
            args
        )
    }

    override fun nextTelegramAttemptAtMs(eventType: String?): Long? {
        val filter = if (eventType == null) "" else " AND event_type = ?"
        val args = if (eventType == null) emptyArray() else arrayOf(eventType)
        return helper.readableDatabase.rawQuery(
            """
            SELECT MIN(next_attempt_at_ms)
            FROM telegram_outbox
            WHERE blocked = 0 AND waits_for_summary_key IS NULL$filter
            """.trimIndent(),
            args
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
        }
    }

    override fun telegramMessageByDedupeKey(dedupeKey: String): TelegramOutboxEntry? = queryTelegramMessage(
        """
            SELECT id, dedupe_key, event_type, payload, attempt_count, next_attempt_at_ms, blocked,
                   waits_for_summary_key, created_at_ms, failure_count, last_error
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
                waitsForSummaryKey = if (cursor.isNull(7)) null else cursor.getString(7),
                createdAtMs = cursor.getLong(8),
                failureCount = cursor.getInt(9),
                lastError = if (cursor.isNull(10)) null else cursor.getString(10)
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

    override fun markTelegramRetry(
        id: Long,
        error: String,
        attemptedAtMs: Long,
        nextAttemptAtMs: Long
    ) {
        helper.writableDatabase.execSQL(
            """
            UPDATE telegram_outbox
            SET attempt_count = attempt_count + 1,
                failure_count = failure_count + 1,
                last_attempt_at_ms = ?,
                next_attempt_at_ms = ?,
                last_error = ?,
                blocked = 0
            WHERE id = ?
            """.trimIndent(),
            arrayOf(
                attemptedAtMs,
                nextAttemptAtMs,
                error.truncateForStorage(MAX_ERROR_TEXT_LENGTH),
                id
            )
        )
    }

    override fun markTelegramBlocked(id: Long, error: String, attemptedAtMs: Long) {
        helper.writableDatabase.execSQL(
            """
            UPDATE telegram_outbox
            SET attempt_count = attempt_count + 1,
                failure_count = failure_count + 1,
                last_attempt_at_ms = ?,
                last_error = ?,
                blocked = 1
            WHERE id = ?
            """.trimIndent(),
            arrayOf(
                attemptedAtMs,
                error.truncateForStorage(MAX_ERROR_TEXT_LENGTH),
                id
            )
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

    /** Returns the durable server cooldown for one bot scope, binding legacy state once. */
    override fun telegramServerNotBefore(botScope: String): Long {
        val scope = botScope.trim().takeIf(String::isNotEmpty) ?: return 0L
        // The common steady state has no unclaimed migration row.  Keep that
        // read path read-only; only the one-time legacy bind needs a write lock.
        val readable = helper.readableDatabase
        if (senderGateDeadline(readable, TelegramDatabaseHelper.LEGACY_UNCLAIMED_BOT_SCOPE) == null) {
            return senderGateDeadline(readable, scope) ?: 0L
        }
        val db = helper.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            bindLegacyCooldown(db, scope)
            val deadline = senderGateDeadline(db, scope) ?: 0L
            db.setTransactionSuccessful()
            return deadline
        } finally {
            db.endTransaction()
        }
    }

    /** Extends a bot's server cooldown monotonically and keeps expired bot history bounded. */
    override fun extendTelegramServerNotBefore(botScope: String, notBeforeMs: Long) {
        val scope = botScope.trim().takeIf(String::isNotEmpty) ?: return
        if (scope == TelegramDatabaseHelper.LEGACY_UNCLAIMED_BOT_SCOPE) return
        val requested = notBeforeMs.coerceAtLeast(0L)
        val db = helper.writableDatabase
        db.beginTransactionNonExclusive()
        try {
            bindLegacyCooldown(db, scope)
            val current = senderGateDeadline(db, scope)
            if (current == null) {
                db.insertWithOnConflict(
                    "telegram_sender_gate",
                    null,
                    ContentValues().apply {
                        put("bot_scope", scope)
                        put("not_before_ms", requested)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            } else if (requested > current) {
                db.update(
                    "telegram_sender_gate",
                    ContentValues().apply { put("not_before_ms", requested) },
                    "bot_scope = ?",
                    arrayOf(scope)
                )
            }
            pruneExpiredSenderGates(db, clockMs(), scope)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Makes only transport-failed, unblocked rows immediately eligible after network recovery.
     * Historical attempt_count is intentionally untouched; failure_count is the retry streak.
     */
    override fun expediteNetworkRetries(nowMs: Long, excludeIds: Set<Long>): Int {
        val selection = StringBuilder(
            "blocked = 0 AND (next_attempt_at_ms > ? OR failure_count > 0) AND (last_error = ? OR last_error GLOB ?)"
        )
        // GLOB keeps the prefix case-sensitive; SQLite LIKE is ASCII
        // case-insensitive unless a connection-wide PRAGMA changes it.
        val args = mutableListOf(nowMs.toString(), "network_error", "network_error:*")
        if (excludeIds.isNotEmpty()) {
            selection.append(" AND id NOT IN (")
            // IDs originate from SQLite rows and are numeric by type.  Using
            // numeric literals keeps this safe for a recovery set at or above
            // SQLite's 999 bind-parameter limit.
            selection.append(excludeIds.joinToString(",") { it.toString() })
            selection.append(')')
        }
        return helper.writableDatabase.update(
            "telegram_outbox",
            ContentValues().apply {
                put("failure_count", 0)
                put("next_attempt_at_ms", nowMs)
            },
            selection.toString(),
            args.toTypedArray()
        )
    }

    private fun senderGateDeadline(db: SQLiteDatabase, botScope: String): Long? = db.rawQuery(
        "SELECT not_before_ms FROM telegram_sender_gate WHERE bot_scope = ? LIMIT 1",
        arrayOf(botScope)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    /** Must be called inside the same write transaction as the first bot-scope lookup. */
    private fun bindLegacyCooldown(db: SQLiteDatabase, botScope: String) {
        if (botScope == TelegramDatabaseHelper.LEGACY_UNCLAIMED_BOT_SCOPE) return
        val legacy = senderGateDeadline(db, TelegramDatabaseHelper.LEGACY_UNCLAIMED_BOT_SCOPE) ?: return
        val current = senderGateDeadline(db, botScope)
        if (current == null) {
            db.insertWithOnConflict(
                "telegram_sender_gate",
                null,
                ContentValues().apply {
                    put("bot_scope", botScope)
                    put("not_before_ms", legacy)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        } else if (legacy > current) {
            db.update(
                "telegram_sender_gate",
                ContentValues().apply { put("not_before_ms", legacy) },
                "bot_scope = ?",
                arrayOf(botScope)
            )
        }
        // The deadline has been transferred to the first real bot scope.  No
        // row deadline is discarded: the larger value is retained above.
        db.delete(
            "telegram_sender_gate",
            "bot_scope = ?",
            arrayOf(TelegramDatabaseHelper.LEGACY_UNCLAIMED_BOT_SCOPE)
        )
    }

    private fun pruneExpiredSenderGates(db: SQLiteDatabase, nowMs: Long, protectedScope: String) {
        val count = db.rawQuery(
            "SELECT COUNT(*) FROM telegram_sender_gate WHERE bot_scope <> ?",
            arrayOf(TelegramDatabaseHelper.LEGACY_UNCLAIMED_BOT_SCOPE)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        if (count <= TelegramDatabaseHelper.MAX_SERVER_GATE_HISTORY) return
        db.delete(
            "telegram_sender_gate",
            "bot_scope <> ? AND bot_scope <> ? AND not_before_ms <= ?",
            arrayOf(
                TelegramDatabaseHelper.LEGACY_UNCLAIMED_BOT_SCOPE,
                protectedScope,
                nowMs.toString()
            )
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
        var pendingPowerSessionId: String? = null
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
                pendingPowerSessionId = state?.pendingPowerOffLocationPowerSessionId
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
            pendingPowerOffLocationPowerSessionId = pendingPowerSessionId,
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
        return try {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                // The sidecar marker is authoritative even when Main is temporarily
                // unreadable or has already been cleaned/archived.
                val marker = mainImportMarker(db)
                if (marker.complete) {
                    val copiedCount = countOutbox(db)
                    val cleanupVerified = snapshot.completedImportCleanupVerified(
                        exactSidecarSnapshot = snapshot.validForImport &&
                            !snapshot.provenEmpty &&
                            exactSnapshot(db, snapshot, nowMs)
                    )
                    db.setTransactionSuccessful()
                    return TelegramMigrationResult(
                        status = TelegramMigrationResult.Status.ALREADY_COMPLETE,
                        copiedOutboxCount = copiedCount,
                        expectedOutboxCount = snapshot.outbox.size,
                        stateCopied = marker.importedStatePresent,
                        errorMessage = if (cleanupVerified) null else "Completed Telegram migration is authoritative; legacy cleanup is not verified",
                        sidecarVerified = true,
                        cleanupVerified = cleanupVerified
                    )
                }
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
                legacyRateLimitedDeadline(snapshot)?.let { deadline ->
                    seedLegacyCooldown(db, deadline)
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
                return TelegramMigrationResult(
                    status = TelegramMigrationResult.Status.COMMITTED,
                    copiedOutboxCount = copiedCount,
                    expectedOutboxCount = snapshot.outbox.size,
                    stateCopied = stateCopied
                )
            } finally {
                db.endTransaction()
            }
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
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

    private fun mainImportMarker(db: SQLiteDatabase): MainImportMarker = db.rawQuery(
        "SELECT main_import_complete, imported_state_present " +
            "FROM telegram_migration_state WHERE id = 1",
        emptyArray()
    ).use { cursor ->
        if (!cursor.moveToFirst()) MainImportMarker(false, false)
        else MainImportMarker(
            complete = cursor.getInt(0) == 1,
            importedStatePresent = cursor.getInt(1) == 1
        )
    }

    private data class MainImportMarker(
        val complete: Boolean,
        val importedStatePresent: Boolean
    )

    private fun countOutbox(): Int = countOutbox(helper.readableDatabase)

    private fun countOutbox(db: SQLiteDatabase): Int = db.rawQuery(
        "SELECT COUNT(*) FROM telegram_outbox", emptyArray()
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
        // Main has no separate retry-streak column; preserve its historical
        // attempt count as the initial failure streak during the cutover.
        put("failure_count", attemptCount)
    }

    private fun legacyRateLimitedDeadline(snapshot: TelegramLegacySnapshot): Long? = snapshot.outbox
        .asSequence()
        .filter { row ->
            row.lastError == "rate_limited" ||
                row.lastError?.startsWith("rate_limited:") == true
        }
        .map { it.nextAttemptAtMs }
        .maxOrNull()

    private fun seedLegacyCooldown(db: SQLiteDatabase, deadline: Long) {
        val current = senderGateDeadline(db, TelegramDatabaseHelper.LEGACY_UNCLAIMED_BOT_SCOPE)
        if (current == null) {
            db.insertWithOnConflict(
                "telegram_sender_gate",
                null,
                ContentValues().apply {
                    put("bot_scope", TelegramDatabaseHelper.LEGACY_UNCLAIMED_BOT_SCOPE)
                    put("not_before_ms", deadline)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        } else if (deadline > current) {
            db.update(
                "telegram_sender_gate",
                ContentValues().apply { put("not_before_ms", deadline) },
                "bot_scope = ?",
                arrayOf(TelegramDatabaseHelper.LEGACY_UNCLAIMED_BOT_SCOPE)
            )
        }
    }

    private fun String.truncateForStorage(maxLength: Int): String {
        if (length <= maxLength) return this
        return take(maxLength) + "...[truncated ${length - maxLength} chars]"
    }

    companion object {
        private const val MAX_ERROR_TEXT_LENGTH = 2_048
    }
}
