package com.bydcollector.collector.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bydcollector.collector.BuildConfig

class TelemetryDatabaseHelper(
    private val appContext: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(appContext, databaseName, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        executeSqlAsset(db, SCHEMA_ASSET)
        createCollectorEvents(db)
        ensureSchemaCompatibility(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        executeSqlAsset(db, SCHEMA_ASSET)
        createCollectorEvents(db)
        ensureSchemaCompatibility(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        recreateDatabase(db)
    }

    private fun recreateDatabase(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            dropKnownTables(db)
            executeSqlAsset(db, SCHEMA_ASSET)
            createCollectorEvents(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun dropKnownTables(db: SQLiteDatabase) {
        listOf(
            "influx_export_events",
            "influx_export_state",
            "influx_export_cursor",
            "mqtt_retry_state",
            "mqtt_outbox",
            "mqtt_publish_state",
            "vehicle_state_history",
            "vehicle_state_current",
            "normalized_field_catalog",
            "collector_events",
            "ec_import_runs",
            "ec_energy_consumption",
            "parameter_observations",
            "vehicle_snapshots",
            "poll_values",
            "readings",
            "polls",
            "collection_sessions",
            "parameter_catalog",
            "catalog_versions"
        ).forEach { tableName ->
            db.execSQL("DROP TABLE IF EXISTS $tableName")
        }
    }

    private fun executeSqlAsset(db: SQLiteDatabase, assetName: String) {
        val sql = appContext.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        splitStatements(sql).forEach { statement ->
            if (statement.isNotBlank()) db.execSQL(statement)
        }
    }

    private fun splitStatements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        sql.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("--")) {
                current.appendLine(line)
                if (trimmed.endsWith(";")) {
                    statements += current.toString()
                    current.setLength(0)
                }
            }
        }
        if (current.isNotBlank()) statements += current.toString()
        return statements
    }

    private fun createCollectorEvents(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS collector_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts TEXT NOT NULL,
                category TEXT NOT NULL,
                message TEXT NOT NULL,
                detail TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_collector_events_ts
                ON collector_events(ts)
            """.trimIndent()
        )
    }

    private fun ensureSchemaCompatibility(db: SQLiteDatabase) {
        ensureColumns(
            db = db,
            tableName = "catalog_versions",
            columns = mapOf(
                "version" to "TEXT",
                "seed_file" to "TEXT",
                "imported_at" to "TEXT",
                "notes" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "parameter_catalog",
            columns = mapOf(
                "catalog_version_id" to "INTEGER",
                "source_id" to "TEXT",
                "key" to "TEXT",
                "name" to "TEXT",
                "group_name" to "TEXT",
                "include_desc" to "INTEGER NOT NULL DEFAULT 1",
                "note" to "TEXT",
                "created_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "collection_sessions",
            columns = mapOf(
                "catalog_version_id" to "INTEGER",
                "session_type" to "TEXT",
                "source_file" to "TEXT",
                "source_folder" to "TEXT",
                "scenario_hint" to "TEXT",
                "started_at" to "TEXT",
                "ended_at" to "TEXT",
                "di_plus_version" to "TEXT",
                "vehicle_model" to "TEXT",
                "import_quality" to "TEXT",
                "created_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "polls",
            columns = mapOf(
                "session_id" to "INTEGER",
                "ts" to "TEXT",
                "ok" to "INTEGER",
                "elapsed_ms" to "INTEGER",
                "request_count" to "INTEGER",
                "errors" to "TEXT",
                "error_category" to "TEXT",
                "error_message" to "TEXT",
                "requested_parameter_count" to "INTEGER",
                "received_parameter_count" to "INTEGER",
                "missing_parameter_count" to "INTEGER",
                "raw_response_body" to "TEXT",
                "import_quality" to "TEXT",
                "created_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "poll_values",
            columns = mapOf(
                "poll_id" to "INTEGER",
                "created_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "vehicle_snapshots",
            columns = mapOf(
                "session_id" to "INTEGER",
                "poll_id" to "INTEGER",
                "ts" to "TEXT",
                "snapshot_json" to "TEXT",
                "data_quality" to "TEXT",
                "created_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "parameter_observations",
            columns = mapOf(
                "parameter_id" to "INTEGER",
                "catalog_version_id" to "INTEGER",
                "session_id" to "INTEGER",
                "di_plus_version" to "TEXT",
                "raw_status" to "TEXT NOT NULL DEFAULT 'unknown'",
                "desc_status" to "TEXT",
                "notes" to "TEXT",
                "observed_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "ec_import_runs",
            columns = mapOf(
                "session_id" to "INTEGER",
                "ts" to "TEXT",
                "source_path" to "TEXT",
                "ok" to "INTEGER NOT NULL DEFAULT 0",
                "source_row_count" to "INTEGER NOT NULL DEFAULT 0",
                "source_max_id" to "INTEGER",
                "inserted_count" to "INTEGER NOT NULL DEFAULT 0",
                "updated_count" to "INTEGER NOT NULL DEFAULT 0",
                "error_category" to "TEXT",
                "error_message" to "TEXT",
                "created_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "ec_energy_consumption",
            columns = mapOf(
                "source_id" to "INTEGER",
                "month" to "TEXT",
                "date" to "TEXT",
                "start_timestamp" to "INTEGER",
                "end_timestamp" to "INTEGER",
                "is_deleted" to "INTEGER",
                "duration" to "INTEGER",
                "trip" to "REAL",
                "electricity" to "REAL",
                "fuel" to "REAL",
                "source_path" to "TEXT",
                "first_seen_session_id" to "INTEGER",
                "last_seen_session_id" to "INTEGER",
                "first_seen_at" to "TEXT",
                "last_seen_at" to "TEXT",
                "updated_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "normalized_field_catalog",
            columns = mapOf(
                "field_key" to "TEXT",
                "category" to "TEXT",
                "value_type" to "TEXT",
                "unit" to "TEXT",
                "display_name" to "TEXT",
                "device_class" to "TEXT",
                "state_class" to "TEXT",
                "entity_platform" to "TEXT",
                "source_keys" to "TEXT",
                "normalizer_id" to "TEXT",
                "stale_after_ms" to "INTEGER NOT NULL DEFAULT 30000",
                "mqtt_default_enabled" to "INTEGER NOT NULL DEFAULT 1",
                "catalog_version" to "TEXT",
                "created_at" to "TEXT",
                "updated_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "vehicle_state_current",
            columns = mapOf(
                "field_key" to "TEXT",
                "category" to "TEXT",
                "value_type" to "TEXT",
                "value_text" to "TEXT",
                "value_number" to "REAL",
                "value_bool" to "INTEGER",
                "quality" to "TEXT",
                "unit" to "TEXT",
                "source_poll_id" to "INTEGER",
                "source_keys" to "TEXT",
                "observed_at" to "TEXT",
                "changed_at" to "TEXT",
                "updated_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "vehicle_state_history",
            columns = mapOf(
                "field_key" to "TEXT",
                "category" to "TEXT",
                "value_type" to "TEXT",
                "value_text" to "TEXT",
                "value_number" to "REAL",
                "value_bool" to "INTEGER",
                "quality" to "TEXT",
                "unit" to "TEXT",
                "source_poll_id" to "INTEGER",
                "source_keys" to "TEXT",
                "observed_at" to "TEXT",
                "changed_at" to "TEXT",
                "created_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "mqtt_publish_state",
            columns = mapOf(
                "target_key" to "TEXT",
                "target_type" to "TEXT",
                "payload_hash" to "TEXT",
                "last_published_at" to "TEXT",
                "last_error_at" to "TEXT",
                "last_error" to "TEXT",
                "settings_hash" to "TEXT",
                "catalog_hash" to "TEXT",
                "created_at" to "TEXT",
                "updated_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "mqtt_outbox",
            columns = mapOf(
                "target_key" to "TEXT",
                "target_type" to "TEXT",
                "topic" to "TEXT",
                "payload" to "TEXT",
                "payload_hash" to "TEXT",
                "retained" to "INTEGER NOT NULL DEFAULT 1",
                "qos" to "INTEGER NOT NULL DEFAULT 1",
                "priority" to "INTEGER NOT NULL DEFAULT 100",
                "created_at" to "TEXT",
                "updated_at" to "TEXT",
                "next_attempt_at" to "TEXT",
                "attempt_count" to "INTEGER NOT NULL DEFAULT 0",
                "last_attempt_at" to "TEXT",
                "last_error_at" to "TEXT",
                "last_error" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "mqtt_retry_state",
            columns = mapOf(
                "failure_count" to "INTEGER NOT NULL DEFAULT 0",
                "next_attempt_at" to "TEXT",
                "last_failure_at" to "TEXT",
                "last_success_at" to "TEXT",
                "last_error" to "TEXT",
                "updated_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "influx_export_cursor",
            columns = mapOf(
                "field_key" to "TEXT",
                "last_exported_history_id" to "INTEGER NOT NULL DEFAULT 0",
                "last_success_at" to "TEXT",
                "last_error_at" to "TEXT",
                "last_error" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "influx_export_state",
            columns = mapOf(
                "status" to "TEXT NOT NULL DEFAULT 'stopped'",
                "mode" to "TEXT",
                "pending_rows" to "INTEGER NOT NULL DEFAULT 0",
                "oldest_pending_at" to "TEXT",
                "next_retry_at" to "TEXT",
                "last_success_at" to "TEXT",
                "last_error_at" to "TEXT",
                "last_error" to "TEXT",
                "exported_rows_total" to "INTEGER NOT NULL DEFAULT 0",
                "updated_at" to "TEXT"
            )
        )
        ensureColumns(
            db = db,
            tableName = "influx_export_events",
            columns = mapOf(
                "ts" to "TEXT",
                "event_type" to "TEXT",
                "message" to "TEXT",
                "batch_count" to "INTEGER",
                "from_history_id" to "INTEGER",
                "to_history_id" to "INTEGER"
            )
        )
    }

    private fun ensureColumns(
        db: SQLiteDatabase,
        tableName: String,
        columns: Map<String, String>
    ) {
        if (!tableExists(db, tableName)) return
        val existingColumns = tableColumns(db, tableName)
        columns.forEach { (columnName, definition) ->
            if (!existingColumns.contains(columnName)) {
                addColumnIfMissing(db, tableName, columnName, definition)
            }
        }
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        tableName: String,
        columnName: String,
        definition: String
    ) {
        db.execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $definition")
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(tableName)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun tableColumns(db: SQLiteDatabase, tableName: String): Set<String> {
        db.rawQuery("PRAGMA table_info($tableName)", emptyArray()).use { cursor ->
            return buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
    }

    companion object {
        val DATABASE_NAME: String = BuildConfig.COLLECTOR_DATABASE_NAME
        const val DATABASE_VERSION = 6
        const val SCHEMA_ASSET = "schema.sql"
    }
}
