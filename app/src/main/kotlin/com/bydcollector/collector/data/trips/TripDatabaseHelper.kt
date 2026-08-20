package com.bydcollector.collector.data.trips

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Separate, app-private trip history database. It deliberately has no outbox/meta domain table. */
class TripDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    val databaseFile: File get() = File(readableDatabase.path)
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion <= DATABASE_VERSION) { "Trip database downgrade is unsupported" }
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Trip database downgrade $oldVersion->$newVersion is unsupported; archive first")
    }

    companion object {
        const val DATABASE_NAME = "bydcollector_trips.db"
        const val DATABASE_VERSION = 1
    }
}
