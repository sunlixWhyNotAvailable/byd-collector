package com.bydcollector.collector.data.trips

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Separate, app-private trip history and durable power-session state database. */
class TripDatabaseHelper(context: Context, databaseName: String = DATABASE_NAME) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName,
    null,
    DATABASE_VERSION
) {
    private val databasePath = context.applicationContext.getDatabasePath(databaseName)

    /** Resolves the file without opening SQLite; required while preparing a snapshot/candidate. */
    val databaseFile: File get() = databasePath
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trip_sessions (
                trip_id TEXT PRIMARY KEY NOT NULL,
                state TEXT NOT NULL CHECK (state IN ('open', 'closed')),
                started_at TEXT NOT NULL,
                ended_at TEXT,
                start_elapsed_ms INTEGER,
                end_elapsed_ms INTEGER,
                start_boot_id TEXT,
                end_boot_id TEXT,
                start_segment_id TEXT,
                end_segment_id TEXT,
                movement_observed INTEGER NOT NULL DEFAULT 0 CHECK (movement_observed IN (0, 1)),
                start_soc REAL,
                end_soc REAL,
                start_odometer_km REAL,
                last_odometer_km REAL,
                start_trip_energy_kwh REAL,
                last_trip_energy_kwh REAL,
                duration_ms INTEGER,
                distance_km REAL,
                energy_kwh REAL,
                average_consumption_kwh_per_100km REAL,
                discharged_kwh REAL,
                regenerated_kwh REAL,
                net_kwh REAL,
                energy_covered_ms INTEGER,
                energy_uncovered_ms INTEGER,
                energy_partial INTEGER CHECK (energy_partial IS NULL OR energy_partial IN (0, 1)),
                energy_observed_at TEXT,
                termination TEXT,
                quality TEXT NOT NULL DEFAULT 'ok',
                telegram_eligible INTEGER NOT NULL DEFAULT 0 CHECK (telegram_eligible IN (0, 1)),
                telegram_enqueued INTEGER NOT NULL DEFAULT 0 CHECK (telegram_enqueued IN (0, 1))
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS route_points (
                trip_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                kind TEXT NOT NULL CHECK (kind IN ('valid', 'gap')),
                observed_at TEXT NOT NULL,
                elapsed_ms INTEGER,
                receive_wall_time_ms INTEGER,
                boot_id TEXT,
                segment_id TEXT,
                latitude REAL,
                longitude REAL,
                accuracy_m REAL,
                speed_kmh REAL,
                instantaneous_consumption_kwh_per_100km REAL,
                altitude_m REAL,
                bearing_deg REAL,
                quality TEXT NOT NULL,
                is_first INTEGER NOT NULL DEFAULT 0 CHECK (is_first IN (0, 1)),
                is_final INTEGER NOT NULL DEFAULT 0 CHECK (is_final IN (0, 1)),
                CHECK ((kind = 'valid' AND latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN -90.0 AND 90.0 AND longitude BETWEEN -180.0 AND 180.0) OR (kind = 'gap' AND latitude IS NULL AND longitude IS NULL)),
                PRIMARY KEY (trip_id, sequence),
                FOREIGN KEY (trip_id) REFERENCES trip_sessions(trip_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trip_sessions_started_at ON trip_sessions(started_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trip_sessions_state_movement ON trip_sessions(state, movement_observed, started_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_route_points_trip_order ON route_points(trip_id, sequence)")
        createRouteChunks(db)
        createEnergyRuntimeState(db)
        createHistoricalEnergyBackfill(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion <= DATABASE_VERSION) { "Trip database downgrade is unsupported" }
        if (oldVersion < 2) db.execSQL("ALTER TABLE route_points ADD COLUMN receive_wall_time_ms INTEGER")
        if (oldVersion < 3) createRouteChunks(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE trip_sessions ADD COLUMN discharged_kwh REAL")
            db.execSQL("ALTER TABLE trip_sessions ADD COLUMN regenerated_kwh REAL")
            db.execSQL("ALTER TABLE trip_sessions ADD COLUMN net_kwh REAL")
            db.execSQL("ALTER TABLE trip_sessions ADD COLUMN energy_covered_ms INTEGER")
            db.execSQL("ALTER TABLE trip_sessions ADD COLUMN energy_uncovered_ms INTEGER")
            db.execSQL("ALTER TABLE trip_sessions ADD COLUMN energy_partial INTEGER CHECK (energy_partial IS NULL OR energy_partial IN (0, 1))")
            db.execSQL("ALTER TABLE trip_sessions ADD COLUMN energy_observed_at TEXT")
            createEnergyRuntimeState(db)
        }
        if (oldVersion < 5) createHistoricalEnergyBackfill(db)
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Trip database downgrade $oldVersion->$newVersion is unsupported; archive first")
    }

    private fun createRouteChunks(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS route_chunks (
                trip_id TEXT NOT NULL,
                chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
                first_sequence INTEGER NOT NULL CHECK (first_sequence >= 0),
                last_sequence INTEGER NOT NULL CHECK (last_sequence >= first_sequence),
                point_count INTEGER NOT NULL CHECK (point_count > 0),
                first_observed_at TEXT NOT NULL,
                last_observed_at TEXT NOT NULL,
                uncompressed_size INTEGER NOT NULL CHECK (uncompressed_size BETWEEN 1 AND 32768),
                compressed_size INTEGER NOT NULL CHECK (compressed_size BETWEEN 1 AND 65536),
                payload BLOB NOT NULL CHECK (length(payload) BETWEEN 1 AND 65536 AND length(payload) = compressed_size),
                PRIMARY KEY (trip_id, chunk_index),
                CHECK (last_sequence = first_sequence + point_count - 1),
                FOREIGN KEY (trip_id) REFERENCES trip_sessions(trip_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    private fun createEnergyRuntimeState(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS energy_runtime_state (
                singleton_id INTEGER PRIMARY KEY NOT NULL CHECK (singleton_id = 1),
                state_json TEXT NOT NULL,
                pending_projection_json TEXT,
                updated_at TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createHistoricalEnergyBackfill(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS historical_energy_backfill (
                trip_id TEXT PRIMARY KEY NOT NULL,
                started_at TEXT NOT NULL,
                ended_at TEXT NOT NULL,
                source_identity TEXT NOT NULL,
                outcome TEXT NOT NULL CHECK (outcome IN ('complete', 'rejected')),
                reason TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY (trip_id) REFERENCES trip_sessions(trip_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    companion object {
        const val DATABASE_NAME = "bydcollector_trips.db"
        const val DATABASE_VERSION = 5
    }
}
