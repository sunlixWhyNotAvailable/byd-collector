package com.bydcollector.collector.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import java.io.File

data class EcImportResult(
    val ok: Boolean,
    val sourcePath: String?,
    val sourceRowCount: Int,
    val sourceMaxId: Long?,
    val insertedCount: Int,
    val updatedCount: Int,
    val errorCategory: String? = null,
    val errorMessage: String? = null
)

class EcDatabaseImporter(
    private val context: Context,
    private val helper: TelemetryDatabaseHelper,
    private val clock: Clock = SystemClockAdapter(),
    private val sourceCandidates: List<File> = DEFAULT_SOURCE_CANDIDATES
) {
    fun importAtSessionStart(sessionId: Long): EcImportResult {
        val timestamp = clock.nowIso()
        return try {
            val sourceFile = findSourceFile()
            val rows = readSourceRows(sourceFile)
            val result = writeRows(sessionId, timestamp, sourceFile, rows)
            recordImportRun(sessionId, timestamp, result)
            result
        } catch (error: EcImportException) {
            val result = EcImportResult(
                ok = false,
                sourcePath = error.sourcePath,
                sourceRowCount = 0,
                sourceMaxId = null,
                insertedCount = 0,
                updatedCount = 0,
                errorCategory = error.category,
                errorMessage = error.message
            )
            recordImportRun(sessionId, timestamp, result)
            result
        } catch (error: RuntimeException) {
            val result = EcImportResult(
                ok = false,
                sourcePath = null,
                sourceRowCount = 0,
                sourceMaxId = null,
                insertedCount = 0,
                updatedCount = 0,
                errorCategory = "unexpected_error",
                errorMessage = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
            recordImportRun(sessionId, timestamp, result)
            result
        }
    }

    private fun findSourceFile(): File {
        val existing = sourceCandidates.firstOrNull { it.exists() && it.isFile }
        if (existing != null) return existing

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            throw EcImportException(
                category = "source_permission_missing",
                sourcePath = sourceCandidates.joinToString(separator = ";") { it.absolutePath },
                detail = "All files access is not granted for EC_database.db import"
            )
        }

        throw EcImportException(
            category = "source_missing",
            sourcePath = sourceCandidates.joinToString(separator = ";") { it.absolutePath },
            detail = "EC_database.db not found in expected energydata paths"
        )
    }

    private fun readSourceRows(sourceFile: File): List<EcSourceRow> {
        try {
            SQLiteDatabase.openDatabase(sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val fuelColumn = findFuelColumn(db)
                val fuelSelect = fuelColumn?.let { "${quoteIdentifier(it)} AS fuel" } ?: "NULL AS fuel"
                db.rawQuery(
                    """
                    SELECT _id, month, date, start_timestamp, end_timestamp, is_deleted,
                           duration, trip, electricity, $fuelSelect
                    FROM EnergyConsumption
                    ORDER BY _id
                    """.trimIndent(),
                    emptyArray()
                ).use { cursor ->
                    return buildList {
                        while (cursor.moveToNext()) add(cursor.toEcSourceRow(sourceFile.absolutePath))
                    }
                }
            }
        } catch (error: RuntimeException) {
            throw EcImportException(
                category = "source_read_failed",
                sourcePath = sourceFile.absolutePath,
                detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                cause = error
            )
        }
    }

    private fun writeRows(
        sessionId: Long,
        timestamp: String,
        sourceFile: File,
        rows: List<EcSourceRow>
    ): EcImportResult {
        val db = helper.writableDatabase
        var inserted = 0
        var updated = 0
        val maxSourceId = rows.maxOfOrNull { it.sourceId }
        val existingRows = readExistingRows(db)

        db.beginTransaction()
        try {
            rows.forEach { row ->
                val existingRow = existingRows[row.sourceId]
                if (existingRow == null) {
                    db.insertOrThrow(
                        "ec_energy_consumption",
                        null,
                        row.toContentValues(sessionId, timestamp, isInsert = true)
                    )
                    inserted++
                } else if (!existingRow.hasSameData(row)) {
                    db.update(
                        "ec_energy_consumption",
                        row.toContentValues(sessionId, timestamp, isInsert = false),
                        "source_id = ?",
                        arrayOf(row.sourceId.toString())
                    )
                    updated++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return EcImportResult(
            ok = true,
            sourcePath = sourceFile.absolutePath,
            sourceRowCount = rows.size,
            sourceMaxId = maxSourceId,
            insertedCount = inserted,
            updatedCount = updated
        )
    }

    private fun recordImportRun(sessionId: Long, timestamp: String, result: EcImportResult) {
        helper.writableDatabase.insert(
            "ec_import_runs",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("ts", timestamp)
                put("source_path", result.sourcePath)
                put("ok", if (result.ok) 1 else 0)
                put("source_row_count", result.sourceRowCount)
                result.sourceMaxId?.let { put("source_max_id", it) }
                put("inserted_count", result.insertedCount)
                put("updated_count", result.updatedCount)
                put("error_category", result.errorCategory)
                put("error_message", result.errorMessage)
            }
        )
    }

    private fun readExistingRows(db: SQLiteDatabase): Map<Long, StoredEcRow> {
        db.rawQuery(
            """
            SELECT source_id, month, date, start_timestamp, end_timestamp, is_deleted,
                   duration, trip, electricity, fuel
            FROM ec_energy_consumption
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            return buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getLong(0),
                        StoredEcRow(
                            month = cursor.getStringOrNull(1),
                            date = cursor.getStringOrNull(2),
                            startTimestamp = cursor.getLongOrNull(3),
                            endTimestamp = cursor.getLongOrNull(4),
                            isDeleted = cursor.getIntOrNull(5),
                            duration = cursor.getLongOrNull(6),
                            trip = cursor.getDoubleOrNull(7),
                            electricity = cursor.getDoubleOrNull(8),
                            fuel = cursor.getDoubleOrNull(9)
                        )
                    )
                }
            }
        }
    }

    private fun findFuelColumn(db: SQLiteDatabase): String? {
        db.rawQuery("PRAGMA table_info(EnergyConsumption)", emptyArray()).use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
            return FUEL_COLUMN_CANDIDATES.firstOrNull { it in columns }
        }
    }

    private fun Cursor.toEcSourceRow(sourcePath: String): EcSourceRow {
        return EcSourceRow(
            sourceId = getLong(0),
            month = getStringOrNull(1),
            date = getStringOrNull(2),
            startTimestamp = getLongOrNull(3),
            endTimestamp = getLongOrNull(4),
            isDeleted = getIntOrNull(5),
            duration = getLongOrNull(6),
            trip = getDoubleOrNull(7),
            electricity = getDoubleOrNull(8),
            fuel = getDoubleOrNull(9),
            sourcePath = sourcePath
        )
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)
    private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun Cursor.getDoubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    private fun quoteIdentifier(value: String): String {
        require(SAFE_IDENTIFIER.matches(value)) { "Unsafe SQLite identifier: $value" }
        return "\"$value\""
    }

    companion object {
        private val DEFAULT_SOURCE_CANDIDATES = listOf(
            File("/storage/emulated/0/energydata/EC_database.db"),
            File("/sdcard/energydata/EC_database.db")
        )
        private val FUEL_COLUMN_CANDIDATES = listOf("fuel", "fuel_liters", "fuel_l", "oil")
        private val SAFE_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}

private data class EcSourceRow(
    val sourceId: Long,
    val month: String?,
    val date: String?,
    val startTimestamp: Long?,
    val endTimestamp: Long?,
    val isDeleted: Int?,
    val duration: Long?,
    val trip: Double?,
    val electricity: Double?,
    val fuel: Double?,
    val sourcePath: String
) {
    fun toContentValues(sessionId: Long, timestamp: String, isInsert: Boolean): ContentValues {
        return ContentValues().apply {
            if (isInsert) {
                put("source_id", sourceId)
                put("first_seen_session_id", sessionId)
                put("first_seen_at", timestamp)
            }
            put("month", month)
            put("date", date)
            put("start_timestamp", startTimestamp)
            put("end_timestamp", endTimestamp)
            put("is_deleted", isDeleted)
            put("duration", duration)
            put("trip", trip)
            put("electricity", electricity)
            put("fuel", fuel)
            put("source_path", sourcePath)
            put("last_seen_session_id", sessionId)
            put("last_seen_at", timestamp)
            put("updated_at", timestamp)
        }
    }
}

private data class StoredEcRow(
    val month: String?,
    val date: String?,
    val startTimestamp: Long?,
    val endTimestamp: Long?,
    val isDeleted: Int?,
    val duration: Long?,
    val trip: Double?,
    val electricity: Double?,
    val fuel: Double?
) {
    fun hasSameData(row: EcSourceRow): Boolean {
        return month == row.month &&
            date == row.date &&
            startTimestamp == row.startTimestamp &&
            endTimestamp == row.endTimestamp &&
            isDeleted == row.isDeleted &&
            duration == row.duration &&
            trip == row.trip &&
            electricity == row.electricity &&
            fuel == row.fuel
    }
}

private class EcImportException(
    val category: String,
    val sourcePath: String?,
    detail: String,
    cause: Throwable? = null
) : RuntimeException(detail, cause)
