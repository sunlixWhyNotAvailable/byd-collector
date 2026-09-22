package com.bydcollector.collector.data.callback

import android.database.sqlite.SQLiteDatabase

object CallbackRawSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL(BATCHES)
        db.execSQL(EVENTS)
        db.execSQL(RECEIPTS)
    }

    private const val BATCHES = """
        CREATE TABLE IF NOT EXISTS raw_callback_batches (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            boot_id TEXT NOT NULL,
            helper_generation TEXT NOT NULL,
            stream INTEGER NOT NULL CHECK(stream IN (1, 2)),
            epoch INTEGER NOT NULL,
            batch_sequence INTEGER NOT NULL,
            digest TEXT NOT NULL,
            acquisition TEXT NOT NULL CHECK(acquisition = 'callback'),
            delivery TEXT NOT NULL CHECK(delivery IN ('live', 'replay')),
            event_count INTEGER NOT NULL,
            first_event_sequence INTEGER NOT NULL,
            last_event_sequence INTEGER NOT NULL,
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
}
