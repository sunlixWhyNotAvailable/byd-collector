package com.bydcollector.collector.maintenance

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Retains FIFO receipts while Main rotates; the cutover journal gates all consumers. */
internal object DeferredEnergyArchive {
    const val UNVERIFIED_WARNING = "Pending energy transfer could not be verified; source files retained in archive"
    private const val TABLE = "energy_deferred_receipts"
    private val columns = arrayOf(
        "id", "source_identity", "observed_at", "boot_id", "elapsed_ms", "voltage", "current_a",
        "power_on", "gun_disconnected", "external_charging", "retry_count", "next_retry_at_ms",
        "last_error", "created_at_ms"
    )

    fun count(databaseFile: File): Long = openSource(databaseFile).use { db ->
        val exists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(TABLE)
        ).use { it.moveToFirst() }
        if (!exists) 0L else db.query(TABLE, columns, null, null, null, null, "id").use { rows ->
            var count = 0L
            while (rows.moveToNext()) {
                // Read every field now so a damaged source can still be archived with an honest warning.
                rowValues(rows)
                count++
            }
            count
        }
    }

    fun restore(sourceFile: File, target: SQLiteDatabase, expectedCount: Long) {
        require(expectedCount > 0) { "Deferred energy transfer requires a positive source count" }
        openSource(sourceFile).use { source ->
            check(DatabaseUtils.queryNumEntries(source, TABLE) == expectedCount) {
                "Archived energy receipt count changed"
            }
            target.beginTransaction()
            try {
                var copied = 0L
                source.query(TABLE, columns, null, null, null, null, "id").use { rows ->
                    while (rows.moveToNext()) {
                        val values = rowValues(rows)
                        val inserted = target.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                        if (inserted == -1L) {
                            target.query(TABLE, columns, "id = ?", arrayOf(values.getAsLong("id").toString()),
                                null, null, null).use { existing ->
                                check(existing.moveToFirst()) { "Energy receipt identity conflicts during archive recovery" }
                                check(rowValues(existing) == values) { "Energy receipt differs during archive recovery" }
                            }
                        }
                        copied++
                    }
                }
                check(copied == expectedCount && DatabaseUtils.queryNumEntries(target, TABLE) == expectedCount) {
                    "Deferred energy transfer is incomplete"
                }
                target.setTransactionSuccessful()
            } finally {
                target.endTransaction()
            }
        }
    }

    private fun openSource(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    private fun rowValues(row: Cursor) = ContentValues().apply {
        columns.forEachIndexed { index, column ->
            // DatabaseUtils.cursorRowToContentValues stringifies numbers, losing double precision.
            when (row.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> putNull(column)
                Cursor.FIELD_TYPE_INTEGER -> put(column, row.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> put(column, row.getDouble(index))
                Cursor.FIELD_TYPE_STRING -> put(column, row.getString(index))
                else -> error("Unsupported energy receipt value: $column")
            }
        }
    }
}
