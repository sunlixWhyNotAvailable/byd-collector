package com.bydcollector.collector.data.debug

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DirectDebugDatabaseHelper(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createSchema(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS debug_direct_candidate_state")
        db.execSQL("DROP TABLE IF EXISTS debug_direct_readings")
        db.execSQL("DROP TABLE IF EXISTS debug_direct_cycles")
        db.execSQL("DROP TABLE IF EXISTS debug_direct_candidates")
        db.execSQL("DROP TABLE IF EXISTS debug_direct_sessions")
        createSchema(db)
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS debug_direct_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at TEXT NOT NULL,
                ended_at TEXT,
                source_version TEXT NOT NULL,
                candidate_count INTEGER NOT NULL,
                poll_mode TEXT NOT NULL,
                batch_size INTEGER NOT NULL,
                interval_ms INTEGER NOT NULL,
                stop_reason TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS debug_direct_candidates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_key TEXT NOT NULL,
                dev INTEGER NOT NULL,
                fid INTEGER NOT NULL,
                tx INTEGER NOT NULL,
                feature_group TEXT,
                feature_names TEXT,
                feature_refs TEXT,
                candidate_source TEXT,
                decoder TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(dev, fid, tx)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS debug_direct_cycles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                cycle_number INTEGER NOT NULL,
                started_at TEXT NOT NULL,
                elapsed_ms INTEGER,
                attempted_count INTEGER NOT NULL DEFAULT 0,
                ok_count INTEGER NOT NULL DEFAULT 0,
                changed_count INTEGER NOT NULL DEFAULT 0,
                error_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS debug_direct_readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                cycle_id INTEGER NOT NULL,
                candidate_id INTEGER NOT NULL,
                sampled_at TEXT NOT NULL,
                reason TEXT NOT NULL,
                status INTEGER NOT NULL,
                raw_present INTEGER NOT NULL,
                raw_int INTEGER,
                raw_hex TEXT,
                float_value REAL,
                elapsed_ms INTEGER,
                error TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS debug_direct_candidate_state (
                session_id INTEGER NOT NULL,
                candidate_id INTEGER NOT NULL,
                last_sampled_at TEXT,
                last_written_at TEXT,
                last_status INTEGER,
                last_raw_present INTEGER,
                last_raw_int INTEGER,
                last_error TEXT,
                read_count INTEGER NOT NULL DEFAULT 0,
                write_count INTEGER NOT NULL DEFAULT 0,
                change_count INTEGER NOT NULL DEFAULT 0,
                error_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(session_id, candidate_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_debug_direct_candidates_sig ON debug_direct_candidates(dev, fid, tx)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_debug_direct_readings_candidate_time ON debug_direct_readings(candidate_id, sampled_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_debug_direct_readings_session_id ON debug_direct_readings(session_id, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_debug_direct_cycles_session_cycle ON debug_direct_cycles(session_id, cycle_number)")
    }

    companion object {
        const val DATABASE_NAME = "bydcollector_debug_round_robin.db"
        private const val DATABASE_VERSION = 1
    }
}
