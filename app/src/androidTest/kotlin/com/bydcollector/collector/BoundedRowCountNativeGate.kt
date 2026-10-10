package com.bydcollector.collector

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.bydcollector.collector.data.local.BoundedIdRowCountReader
import com.bydcollector.collector.data.local.BoundedIdRowCountTable

/** Native SQLite oracle checks for bounded append-only dashboard counts. */
internal object BoundedRowCountNativeGate {
    fun cases(): List<Pair<String, () -> Unit>> = listOf(
        "row_count_baseline_fence_and_incremental_gaps" to ::baselineFenceAndIncrementalGaps,
        "row_count_cancel_resume_and_new_generation" to ::cancelResumeAndNewGeneration,
        "row_count_rollback_does_not_advance_count" to ::rollbackDoesNotAdvanceCount
    )

    private fun baselineFenceAndIncrementalGaps() {
        val db = fixtureDatabase()
        try {
            insert(db, 2L, 8L, 15L)
            val reader = reader(pageSize = 2, budget = 2)
            check(readCount(reader, db) == -1L) { "partial baseline was exposed as exact" }

            // This commit is above the baseline's captured high-water mark.
            insert(db, 40L)
            check(completeCount(reader, db) == 3L) { "baseline included or lost a concurrent insert" }
            check(readCount(reader, db) == 4L) { "incremental range did not count the row above the fence" }
            check(readCount(reader, db) == 4L) { "repeated refresh double-counted an already reconciled range" }
            check(actualCount(db) == 4L) { "SQLite COUNT(*) oracle mismatch" }
            insert(db, 50L, 60L, 70L)
            check(readCount(reader, db) == -1L) { "incomplete incremental scan renewed an old count" }
            check(completeCount(reader, db) == actualCount(db)) { "incremental completion disagreed with SQLite" }
        } finally {
            db.close()
        }
    }

    private fun cancelResumeAndNewGeneration() {
        val db = fixtureDatabase()
        try {
            insert(db, 1L, 3L, 9L)
            val reader = reader(pageSize = 1, budget = 1)
            val canceled = CancellationSignal().apply { cancel() }
            val cancellationObserved = try {
                reader.refresh(db, canceled)
                false
            } catch (_: OperationCanceledException) {
                true
            }
            check(cancellationObserved) { "pre-canceled count refresh was not canceled" }
            check(completeCount(reader, db) == 3L) { "resume after cancellation skipped or repeated rows" }

            // A new owner/storage generation intentionally starts with no trusted cache.
            val replacement = reader(pageSize = 1, budget = 1)
            check(readCount(replacement, db) == -1L) { "replacement reader reused another generation's exact count" }
            check(completeCount(replacement, db) == actualCount(db)) { "replacement baseline disagreed with SQLite" }
        } finally {
            db.close()
        }
    }

    private fun rollbackDoesNotAdvanceCount() {
        val db = fixtureDatabase()
        try {
            insert(db, 1L)
            val reader = reader(pageSize = 4, budget = 4)
            check(readCount(reader, db) == 1L) { "single-row baseline did not complete" }

            db.beginTransaction()
            try {
                insert(db, 20L)
            } finally {
                db.endTransaction() // deliberately roll the insert back
            }
            check(readCount(reader, db) == 1L) { "rolled-back row was counted" }
            insert(db, 30L)
            check(readCount(reader, db) == 2L) { "post-rollback committed row was not reconciled" }
            check(readCount(reader, db) == actualCount(db)) { "rollback recovery disagreed with SQLite COUNT(*)" }
        } finally {
            db.close()
        }
    }

    private fun fixtureDatabase(): SQLiteDatabase = SQLiteDatabase.create(null).apply {
        execSQL("CREATE TABLE row_fixture (id INTEGER PRIMARY KEY AUTOINCREMENT)")
    }

    private fun reader(pageSize: Int, budget: Int) = BoundedIdRowCountReader(
        listOf(BoundedIdRowCountTable("rows", "row_fixture", "id")),
        pageSize = pageSize,
        maxRowsPerRead = budget
    )

    private fun insert(db: SQLiteDatabase, vararg ids: Long) {
        ids.forEach { id -> db.execSQL("INSERT INTO row_fixture(id) VALUES(?)", arrayOf(id)) }
    }

    private fun readCount(reader: BoundedIdRowCountReader, db: SQLiteDatabase): Long =
        reader.refresh(db).getValue("rows")

    private fun completeCount(reader: BoundedIdRowCountReader, db: SQLiteDatabase): Long {
        repeat(16) {
            val count = readCount(reader, db)
            if (count >= 0L) return count
        }
        error("bounded row-count scan did not complete")
    }

    private fun actualCount(db: SQLiteDatabase): Long = db.rawQuery(
        "SELECT COUNT(*) FROM row_fixture", emptyArray()
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
}
