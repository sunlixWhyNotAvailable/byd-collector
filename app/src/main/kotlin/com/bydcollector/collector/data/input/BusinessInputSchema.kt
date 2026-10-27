package com.bydcollector.collector.data.input

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

object BusinessInputSchema {
    /** Rebuilds the v16 journal CHECK constraint without losing IDs or their receipts. */
    fun migrateToV17(db: SQLiteDatabase) {
        val exists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'business_input_journal' LIMIT 1",
            emptyArray()
        ).use { it.moveToFirst() }
        if (!exists) return
        val columns = db.rawQuery("PRAGMA table_info(business_input_journal)", emptyArray()).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        val copyColumns = listOf(
            "id", "input_kind", "raw_ref_id", "reading_keys_json", "source_identity", "source_boot_id",
            "source_generation", "source_sequence", "acquired_wall_ms", "acquired_elapsed_ms", "session_id",
            "poll_id", "origin", "ok", "quality", "error_category", "error_message", "created_at_ms"
        )
        check(columns.containsAll(copyColumns)) {
            "Cannot migrate business input journal with unexpected schema: ${columns.sorted()}"
        }
        val hasReceipts = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'business_input_receipts' LIMIT 1",
            emptyArray()
        ).use { it.moveToFirst() }
        if (hasReceipts) {
            db.execSQL("CREATE TEMP TABLE business_input_receipts_v17 AS SELECT input_id, completed_at_ms FROM business_input_receipts")
        } else {
            db.execSQL("CREATE TEMP TABLE business_input_receipts_v17(input_id INTEGER, completed_at_ms INTEGER)")
        }
        db.execSQL(JOURNAL_V17)
        val targetColumns = copyColumns + "source_epoch"
        val sourceColumns = copyColumns + if ("source_epoch" in columns) "source_epoch" else "NULL"
        db.execSQL(
            "INSERT INTO business_input_journal_v17(${targetColumns.joinToString(", ")}) " +
                "SELECT ${sourceColumns.joinToString(", ")} FROM business_input_journal"
        )
        if (hasReceipts) db.execSQL("DROP TABLE business_input_receipts")
        db.execSQL("DROP TABLE business_input_journal")
        db.execSQL("ALTER TABLE business_input_journal_v17 RENAME TO business_input_journal")
        db.execSQL(RECEIPTS)
        db.execSQL("INSERT INTO business_input_receipts(input_id, completed_at_ms) SELECT input_id, completed_at_ms FROM business_input_receipts_v17")
        db.execSQL("DROP TABLE business_input_receipts_v17")
    }

    fun create(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS business_input_meta (
                singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                generation TEXT NOT NULL,
                completed_input_id INTEGER NOT NULL DEFAULT 0 CHECK(completed_input_id >= 0)
            )
            """.trimIndent()
        )
        db.insertWithOnConflict(
            "business_input_meta",
            null,
            ContentValues().apply {
                put("singleton", 1)
                put("generation", UUID.randomUUID().toString())
                put("completed_input_id", 0L)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS business_input_journal (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                input_kind TEXT NOT NULL CHECK(input_kind IN ('callback', 'get', 'timer', 'continuity')),
                raw_ref_id INTEGER,
                reading_keys_json TEXT,
                source_identity TEXT NOT NULL UNIQUE,
                source_boot_id TEXT,
                source_generation TEXT,
                source_sequence INTEGER,
                source_epoch INTEGER,
                acquired_wall_ms INTEGER,
                acquired_elapsed_ms INTEGER,
                session_id INTEGER,
                poll_id INTEGER,
                origin TEXT NOT NULL CHECK(origin IN ('live', 'replay', 'timer')),
                ok INTEGER NOT NULL CHECK(ok IN (0, 1)),
                quality TEXT NOT NULL,
                error_category TEXT,
                error_message TEXT,
                created_at_ms INTEGER NOT NULL,
                CHECK((input_kind = 'callback' AND raw_ref_id IS NOT NULL AND poll_id IS NULL) OR
                      (input_kind = 'get' AND raw_ref_id IS NOT NULL AND poll_id = raw_ref_id) OR
                      (input_kind = 'timer' AND raw_ref_id IS NULL AND poll_id IS NULL) OR
                      (input_kind = 'continuity' AND raw_ref_id IS NOT NULL AND poll_id IS NULL))
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS business_input_receipts (
                input_id INTEGER PRIMARY KEY,
                completed_at_ms INTEGER NOT NULL,
                FOREIGN KEY(input_id) REFERENCES business_input_journal(id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """.trimIndent()
        )
        val allowedKeys = BusinessInputCheckpointKeys.ALL.sorted().joinToString(", ") { "'$it'" }
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS business_input_source_checkpoints (
                source_key TEXT PRIMARY KEY CHECK(source_key IN ($allowedKeys)),
                raw_value TEXT,
                desc_value TEXT,
                source_kind TEXT NOT NULL CHECK(source_kind IN ('poll', 'callback')),
                source_identity TEXT NOT NULL,
                source_boot_id TEXT NOT NULL,
                source_generator_id TEXT,
                source_sequence INTEGER,
                source_epoch INTEGER,
                source_wall_ms INTEGER NOT NULL,
                source_elapsed_ms INTEGER NOT NULL,
                source_poll_id INTEGER,
                updated_at_ms INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS business_input_source_seed (
                source_key TEXT PRIMARY KEY CHECK(source_key IN ($allowedKeys)),
                raw_value TEXT,
                desc_value TEXT,
                source_kind TEXT NOT NULL CHECK(source_kind IN ('poll', 'callback')),
                source_identity TEXT NOT NULL,
                source_boot_id TEXT NOT NULL,
                source_generator_id TEXT,
                source_sequence INTEGER,
                source_epoch INTEGER,
                source_wall_ms INTEGER NOT NULL,
                source_elapsed_ms INTEGER NOT NULL,
                source_poll_id INTEGER,
                updated_at_ms INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_business_input_raw_ref " +
                "ON business_input_journal(input_kind, raw_ref_id) " +
                "WHERE raw_ref_id IS NOT NULL AND input_kind IN ('callback', 'get')"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_business_input_boot_elapsed_id " +
                "ON business_input_journal(source_boot_id, acquired_elapsed_ms, id)"
        )
        db.execSQL(CONTINUITY_CHECKPOINTS)
        ensureColumn(db, "business_input_source_checkpoints", "source_epoch", "INTEGER")
        ensureColumn(db, "business_input_source_seed", "source_epoch", "INTEGER")
    }

    /** Seeds only the small Energy/Trips source frontier once, during the additive upgrade. */
    fun seedExistingSourceCheckpoint(db: SQLiteDatabase) {
        val hasSourceTable = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'normalized_source_inputs' LIMIT 1",
            emptyArray()
        ).use { it.moveToFirst() }
        if (!hasSourceTable) return
        val keys = BusinessInputCheckpointKeys.ALL.sorted()
        val placeholders = keys.joinToString(",") { "?" }
        val insert = """
            INSERT OR IGNORE INTO business_input_source_checkpoints(
                source_key, raw_value, desc_value, source_kind, source_identity,
                source_boot_id, source_generator_id, source_sequence, source_epoch, source_wall_ms,
                source_elapsed_ms, source_poll_id, updated_at_ms
            )
            SELECT source_key, raw_value, desc_value, source_kind, source_identity,
                   source_boot_id, source_generator_id, source_sequence, source_epoch, source_wall_ms,
                   source_elapsed_ms, source_poll_id, updated_at_ms
            FROM normalized_source_inputs
            WHERE source_key IN ($placeholders)
            """.trimIndent()
        db.execSQL(insert, keys.toTypedArray())
        db.execSQL(
            insert.replace("business_input_source_checkpoints", "business_input_source_seed"),
            keys.toTypedArray()
        )
    }

    private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", emptyArray()).use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(1) == column) found = true
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
    }

    private const val JOURNAL_V17 = """
        CREATE TABLE business_input_journal_v17 (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            input_kind TEXT NOT NULL CHECK(input_kind IN ('callback', 'get', 'timer', 'continuity')),
            raw_ref_id INTEGER,
            reading_keys_json TEXT,
            source_identity TEXT NOT NULL UNIQUE,
            source_boot_id TEXT,
            source_generation TEXT,
            source_sequence INTEGER,
            source_epoch INTEGER,
            acquired_wall_ms INTEGER,
            acquired_elapsed_ms INTEGER,
            session_id INTEGER,
            poll_id INTEGER,
            origin TEXT NOT NULL CHECK(origin IN ('live', 'replay', 'timer')),
            ok INTEGER NOT NULL CHECK(ok IN (0, 1)),
            quality TEXT NOT NULL,
            error_category TEXT,
            error_message TEXT,
            created_at_ms INTEGER NOT NULL,
            CHECK((input_kind = 'callback' AND raw_ref_id IS NOT NULL AND poll_id IS NULL) OR
                  (input_kind = 'get' AND raw_ref_id IS NOT NULL AND poll_id = raw_ref_id) OR
                  (input_kind = 'timer' AND raw_ref_id IS NULL AND poll_id IS NULL) OR
                  (input_kind = 'continuity' AND raw_ref_id IS NOT NULL AND poll_id IS NULL))
        )
    """

    private const val RECEIPTS = """
        CREATE TABLE business_input_receipts (
            input_id INTEGER PRIMARY KEY,
            completed_at_ms INTEGER NOT NULL,
            FOREIGN KEY(input_id) REFERENCES business_input_journal(id) ON DELETE CASCADE
        ) WITHOUT ROWID
    """

    private const val CONTINUITY_CHECKPOINTS = """
        CREATE TABLE IF NOT EXISTS business_input_source_continuity (
            source_key TEXT PRIMARY KEY,
            state TEXT NOT NULL CHECK(state IN ('available', 'lost')),
            source_kind TEXT NOT NULL CHECK(source_kind IN ('poll', 'callback')),
            source_identity TEXT NOT NULL,
            source_boot_id TEXT NOT NULL,
            source_generation TEXT NOT NULL,
            source_stream INTEGER,
            source_epoch INTEGER,
            source_sequence INTEGER NOT NULL,
            source_wall_ms INTEGER NOT NULL,
            source_elapsed_ms INTEGER NOT NULL,
            seed_kind TEXT,
            seed_identity TEXT,
            seed_record_sequence INTEGER,
            seed_valid INTEGER NOT NULL DEFAULT 0 CHECK(seed_valid IN (0, 1)),
            reason TEXT NOT NULL,
            diagnostic TEXT,
            raw_boundary_id INTEGER,
            updated_at_ms INTEGER NOT NULL,
            CHECK(state != 'available' OR seed_valid = 1)
        ) WITHOUT ROWID
    """
}
