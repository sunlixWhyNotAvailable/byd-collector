package com.bydcollector.collector.data.callback

import android.database.sqlite.SQLiteDatabase

object CallbackRawSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL(BATCHES)
        db.execSQL(EVENTS)
        db.execSQL(RECEIPTS)
        ensureColumns(db, "raw_callback_batches", mapOf(
            "source_epoch" to "INTEGER",
            "record_count" to "INTEGER",
            "first_record_sequence" to "INTEGER",
            "last_record_sequence" to "INTEGER",
            "first_capture_wall_ms" to "INTEGER",
            "last_capture_wall_ms" to "INTEGER",
            "first_capture_elapsed_ms" to "INTEGER",
            "last_capture_elapsed_ms" to "INTEGER"
        ))
        ensureColumns(db, "raw_callback_events", mapOf("source_epoch" to "INTEGER"))
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_raw_callback_event_source_order " +
            "ON raw_callback_events(boot_id, helper_generation, stream, source_epoch, event_sequence) " +
            "WHERE source_epoch IS NOT NULL")
        db.execSQL(BOUNDARIES)
        ensureColumns(db, "raw_callback_boundaries", mapOf("seed_source_key" to "TEXT"))
    }

    private fun ensureColumns(db: SQLiteDatabase, table: String, columns: Map<String, String>) {
        val existing = db.rawQuery("PRAGMA table_info($table)", emptyArray()).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        columns.forEach { (column, type) ->
            if (column !in existing) db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }

    private const val BATCHES = """
        CREATE TABLE IF NOT EXISTS raw_callback_batches (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            boot_id TEXT NOT NULL,
            helper_generation TEXT NOT NULL,
            stream INTEGER NOT NULL CHECK(stream IN (1, 2)),
            epoch INTEGER NOT NULL,
            source_epoch INTEGER,
            batch_sequence INTEGER NOT NULL,
            digest TEXT NOT NULL,
            acquisition TEXT NOT NULL CHECK(acquisition = 'callback'),
            delivery TEXT NOT NULL CHECK(delivery IN ('live', 'replay')),
            event_count INTEGER NOT NULL,
            first_event_sequence INTEGER NOT NULL,
            last_event_sequence INTEGER NOT NULL,
            record_count INTEGER,
            first_record_sequence INTEGER,
            last_record_sequence INTEGER,
            first_capture_wall_ms INTEGER,
            last_capture_wall_ms INTEGER,
            first_capture_elapsed_ms INTEGER,
            last_capture_elapsed_ms INTEGER,
            imported_at_ms INTEGER NOT NULL,
            UNIQUE(boot_id, helper_generation, stream, epoch, batch_sequence)
        )
    """

    private const val EVENTS = """
        CREATE TABLE IF NOT EXISTS raw_callback_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            batch_id INTEGER NOT NULL,
            boot_id TEXT NOT NULL,
            helper_generation TEXT NOT NULL,
            stream INTEGER NOT NULL CHECK(stream IN (1, 2)),
            epoch INTEGER NOT NULL,
            source_epoch INTEGER,
            event_sequence INTEGER NOT NULL,
            device INTEGER NOT NULL,
            fid INTEGER NOT NULL,
            native_type INTEGER NOT NULL,
            raw_bits INTEGER NOT NULL,
            raw_bytes BLOB,
            received_wall_ms INTEGER NOT NULL,
            received_elapsed_ms INTEGER NOT NULL,
            source_wall_ms INTEGER,
            quality TEXT NOT NULL,
            UNIQUE(boot_id, helper_generation, stream, epoch, event_sequence),
            UNIQUE(batch_id, event_sequence),
            FOREIGN KEY(batch_id) REFERENCES raw_callback_batches(id) ON DELETE CASCADE
        )
    """

    private const val RECEIPTS = """
        CREATE TABLE IF NOT EXISTS raw_callback_normalization_receipts (
            event_id INTEGER PRIMARY KEY,
            normalized_at_ms INTEGER NOT NULL,
            FOREIGN KEY(event_id) REFERENCES raw_callback_events(id) ON DELETE CASCADE
        ) WITHOUT ROWID
    """

    private const val BOUNDARIES = """
        CREATE TABLE IF NOT EXISTS raw_callback_boundaries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            batch_id INTEGER NOT NULL,
            boot_id TEXT NOT NULL,
            helper_generation TEXT NOT NULL,
            stream INTEGER NOT NULL CHECK(stream IN (1, 2)),
            epoch INTEGER NOT NULL,
            source_epoch INTEGER NOT NULL,
            record_sequence INTEGER NOT NULL,
            scope INTEGER NOT NULL CHECK(scope IN (1, 2, 3)),
            state INTEGER NOT NULL CHECK(state IN (1, 2)),
            device INTEGER NOT NULL,
            fid INTEGER NOT NULL,
            tx INTEGER NOT NULL,
            capture_wall_ms INTEGER NOT NULL,
            capture_elapsed_ms INTEGER NOT NULL,
            reason TEXT NOT NULL,
            diagnostic TEXT,
            seed_kind TEXT,
            seed_identity TEXT,
            seed_record_sequence INTEGER,
            seed_source_key TEXT,
            seed_valid INTEGER NOT NULL DEFAULT 0 CHECK(seed_valid IN (0, 1)),
            seed_error TEXT,
            UNIQUE(boot_id, helper_generation, stream, source_epoch, record_sequence),
            UNIQUE(batch_id, record_sequence),
            FOREIGN KEY(batch_id) REFERENCES raw_callback_batches(id) ON DELETE CASCADE
        )
    """
}
