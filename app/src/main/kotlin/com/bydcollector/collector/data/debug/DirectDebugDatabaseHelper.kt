package com.bydcollector.collector.data.debug

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DirectDebugDatabaseHelper(
    context: Context,
    databaseName: String = DirectDebugDatabaseResolver.databaseFile(context).name
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        createCompactSchema(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // The replay tables are an additive extension of compact-v2. Keep the SQLiteOpenHelper
        // version and the storage marker unchanged so existing compact databases open in place.
        if (!db.isReadOnly && isCompactV2(db)) createSecondaryReplaySchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("The existing All data database must be archived before the current format is opened")
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
        createSecondaryReplaySchema(db)
    }

    private fun createSecondaryReplaySchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS debug_secondary_receipts (
                boot_id TEXT NOT NULL,
                helper_generation TEXT NOT NULL,
                gap_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                digest TEXT NOT NULL,
                spool_order INTEGER NOT NULL,
                catalog_version TEXT NOT NULL,
                record_kind TEXT NOT NULL,
                cycle_id INTEGER NOT NULL,
                committed_at_ms INTEGER NOT NULL,
                PRIMARY KEY(boot_id, helper_generation, gap_id, sequence)
            ) WITHOUT ROWID
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS debug_secondary_cycle_metadata (
                cycle_id INTEGER PRIMARY KEY,
                source_wall_ms INTEGER NOT NULL,
                source_elapsed_ms INTEGER NOT NULL,
                source_cycle_elapsed_ms INTEGER NOT NULL,
                batch_status INTEGER NOT NULL,
                batch_mode INTEGER NOT NULL,
                native_available INTEGER NOT NULL,
                native_group_count INTEGER NOT NULL,
                fallback_group_count INTEGER NOT NULL,
                fallback_read_count INTEGER NOT NULL,
                group_failure_count INTEGER NOT NULL,
                source_error TEXT,
                loss_count INTEGER,
                loss_first_boot_id TEXT,
                loss_first_helper_generation TEXT,
                loss_first_gap_id TEXT,
                loss_first_sequence INTEGER,
                loss_last_boot_id TEXT,
                loss_last_helper_generation TEXT,
                loss_last_gap_id TEXT,
                loss_last_sequence INTEGER,
                loss_first_wall_ms INTEGER,
                loss_last_wall_ms INTEGER,
                loss_first_elapsed_ms INTEGER,
                loss_last_elapsed_ms INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS debug_secondary_replay_cursor (
                catalog_version_id INTEGER PRIMARY KEY,
                boot_id TEXT NOT NULL,
                helper_generation TEXT NOT NULL,
                gap_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                digest TEXT NOT NULL,
                cycle_id INTEGER NOT NULL,
                field_count INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS debug_secondary_rejections (
                boot_id TEXT NOT NULL,
                helper_generation TEXT NOT NULL,
                gap_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                digest TEXT NOT NULL,
                catalog_version TEXT NOT NULL,
                spool_order INTEGER NOT NULL,
                reason TEXT NOT NULL,
                detected_at_ms INTEGER NOT NULL,
                PRIMARY KEY(boot_id, helper_generation, gap_id, sequence, digest)
            ) WITHOUT ROWID
            """.trimIndent()
        )
    }

    companion object {
        const val DATABASE_NAME = "bydcollector_secondary.db"
        const val LEGACY_DATABASE_NAME = "bydcollector_debug_round_robin.db"
        val DATABASE_NAMES = setOf(DATABASE_NAME, LEGACY_DATABASE_NAME)
        const val SCHEMA_FAMILY = "debug_round_robin"
        const val FORMAT_VERSION = 2
        // Marker-based format detection keeps an older file checkpointable and verifiable
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
