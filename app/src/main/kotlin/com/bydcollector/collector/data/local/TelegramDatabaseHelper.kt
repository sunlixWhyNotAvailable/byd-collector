package com.bydcollector.collector.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Small app-private database that is not replaced by Main telemetry archives. */
class TelegramDatabaseHelper(
    private val context: Context,
    private val databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    init {
        // WAL keeps the independent sender readable while its short writes commit.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS telegram_outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dedupe_key TEXT NOT NULL UNIQUE,
                event_type TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                next_attempt_at_ms INTEGER NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_attempt_at_ms INTEGER,
                last_error TEXT,
                blocked INTEGER NOT NULL DEFAULT 0 CHECK (blocked IN (0, 1)),
                waits_for_summary_key TEXT,
                failure_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_telegram_outbox_due
            ON telegram_outbox(blocked, next_attempt_at_ms, id)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS telegram_runtime_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                state_json TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // Written in the same transaction as the first Main import.  Keeping this
        // marker in the sidecar makes migration idempotent across process death.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS telegram_migration_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                main_import_complete INTEGER NOT NULL DEFAULT 0 CHECK (main_import_complete IN (0, 1)),
                imported_outbox_count INTEGER NOT NULL DEFAULT 0,
                imported_state_present INTEGER NOT NULL DEFAULT 0 CHECK (imported_state_present IN (0, 1)),
                completed_at_ms INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS telegram_sender_gate (
                bot_scope TEXT PRIMARY KEY NOT NULL,
                not_before_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // FIFO completion handoff: advance only together with the rendered batch/state.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS telegram_trip_completion_receipt (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                sequence INTEGER NOT NULL,
                identity TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO telegram_migration_state(id, main_import_complete)
            VALUES (1, 0)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE telegram_outbox ADD COLUMN waits_for_summary_key TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE telegram_outbox ADD COLUMN failure_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE telegram_outbox SET failure_count = attempt_count")
        }
        onCreate(db)
        if (oldVersion < 3) seedLegacyRateLimitCooldown(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Telegram database downgrade $oldVersion->$newVersion is not supported")
    }

    fun databaseFile() = context.getDatabasePath(databaseName)

    companion object {
        const val DATABASE_NAME = "bydcollector_telegram.db"
        const val DATABASE_VERSION = 4
        const val MAX_PENDING = 1_000L
        const val RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
        const val LEGACY_UNCLAIMED_BOT_SCOPE = "__legacy_unclaimed__"
        const val MAX_SERVER_GATE_HISTORY = 32

        private fun seedLegacyRateLimitCooldown(db: SQLiteDatabase) {
            db.rawQuery(
                """
                SELECT MAX(next_attempt_at_ms)
                FROM telegram_outbox
                WHERE last_error = 'rate_limited' OR last_error LIKE 'rate_limited:%'
                """.trimIndent(),
                emptyArray()
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return
                val deadline = cursor.getLong(0)
                val updated = db.update(
                    "telegram_sender_gate",
                    ContentValues().apply { put("not_before_ms", deadline) },
                    "bot_scope = ? AND not_before_ms < ?",
                    arrayOf(LEGACY_UNCLAIMED_BOT_SCOPE, deadline.toString())
                )
                if (updated == 0) {
                    db.insertWithOnConflict(
                        "telegram_sender_gate",
                        null,
                        ContentValues().apply {
                            put("bot_scope", LEGACY_UNCLAIMED_BOT_SCOPE)
                            put("not_before_ms", deadline)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
            }
        }
    }
}
