package com.bydcollector.collector.data.debug

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DirectDebugDatabaseHelper(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        createCompactSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Legacy debug database must be archived before compact-v2 is opened")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Debug database downgrade is not supported")
    }

    private fun createCompactSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE storage_meta (
                schema_family TEXT PRIMARY KEY,
                format_version INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO storage_meta(schema_family, format_version) VALUES (?, ?)",
            arrayOf(SCHEMA_FAMILY, FORMAT_VERSION)
        )
        db.execSQL(
            """
            CREATE TABLE debug_direct_catalog_versions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_version TEXT NOT NULL UNIQUE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE debug_direct_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at_ms INTEGER NOT NULL,
                ended_at_ms INTEGER,
                catalog_version_id INTEGER NOT NULL,
                candidate_count INTEGER NOT NULL,
                poll_mode TEXT NOT NULL,
                batch_size INTEGER NOT NULL,
                interval_ms INTEGER NOT NULL,
                cycle_count INTEGER NOT NULL DEFAULT 0,
                attempted_count INTEGER NOT NULL DEFAULT 0,
                ok_count INTEGER NOT NULL DEFAULT 0,
                changed_count INTEGER NOT NULL DEFAULT 0,
                error_count INTEGER NOT NULL DEFAULT 0,
                last_scan_at_ms INTEGER,
                stop_reason TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE debug_direct_candidates (
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
                UNIQUE(dev, fid, tx)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE debug_direct_cycles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                cycle_number INTEGER NOT NULL,
                started_at_ms INTEGER NOT NULL,
                elapsed_ms INTEGER NOT NULL,
                attempted_count INTEGER NOT NULL,
                ok_count INTEGER NOT NULL,
                changed_count INTEGER NOT NULL,
                error_count INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE debug_direct_readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                cycle_id INTEGER NOT NULL,
                candidate_id INTEGER NOT NULL,
                sampled_at_ms INTEGER NOT NULL,
                reason INTEGER NOT NULL,
                status INTEGER NOT NULL,
                raw_present INTEGER NOT NULL,
                raw_int INTEGER,
                error TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE debug_direct_candidate_state (
                catalog_version_id INTEGER NOT NULL,
                candidate_id INTEGER NOT NULL,
                last_changed_at_ms INTEGER NOT NULL,
                last_status INTEGER NOT NULL,
                last_raw_present INTEGER NOT NULL,
                last_raw_int INTEGER,
                last_error TEXT,
                PRIMARY KEY(catalog_version_id, candidate_id)
            ) WITHOUT ROWID
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_debug_direct_readings_candidate_id ON debug_direct_readings(candidate_id, id)"
        )
    }

    companion object {
        const val DATABASE_NAME = "bydcollector_debug_round_robin.db"
        const val SCHEMA_FAMILY = "debug_round_robin"
        const val FORMAT_VERSION = 2
        // Format evolution is marker-based so a legacy v1 file can still be checkpointed/verified
        // after a failed archive without SQLiteOpenHelper converting it in place.
        private const val DATABASE_VERSION = 1

        fun isCompactV2(db: SQLiteDatabase): Boolean {
            return runCatching {
                db.rawQuery(
                    "SELECT format_version FROM storage_meta WHERE schema_family = ?",
                    arrayOf(SCHEMA_FAMILY)
                ).use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) == FORMAT_VERSION
                }
            }.getOrDefault(false)
        }
    }
}
