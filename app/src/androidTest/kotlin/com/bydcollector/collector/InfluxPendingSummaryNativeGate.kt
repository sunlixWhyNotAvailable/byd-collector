package com.bydcollector.collector

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.bydcollector.collector.data.local.BoundedInfluxPendingSummaryReader
import com.bydcollector.collector.data.normalized.normalizedIsoTime

/** Native SQLite oracle coverage for bounded compact and legacy Influx history summaries. */
internal object InfluxPendingSummaryNativeGate {
    private data class HistoryRow(val id: Long, val field: String, val observed: Long, val fieldId: Long? = null)

    fun cases(): List<Pair<String, () -> Unit>> = listOf(
        "influx_summary_compact_and_legacy_fenced_oracle" to ::compactAndLegacyFencedOracle,
        "influx_summary_large_multipage_gapped_history" to ::largeMultipageGappedHistory,
        "influx_summary_incremental_single_row_refreshes" to ::incrementalSingleRowRefreshes,
        "influx_summary_cancel_rollback_replacement_and_exact_drain" to ::cancelRollbackReplacementAndExactDrain
    )

    private fun compactAndLegacyFencedOracle() {
        listOf(false, true).forEach { compact ->
            val db = fixtureDatabase(compact)
            try {
                insert(db, compact, listOf(
                    HistoryRow(1, "a", 1_000),
                    HistoryRow(3, "b", 3_000),
                    HistoryRow(7, "a", 7_000),
                    HistoryRow(8, "a", 8_000),
                    HistoryRow(10, "b", 10_000),
                    HistoryRow(12, "b", 12_000),
                    HistoryRow(15, "a", 15_000),
                    HistoryRow(20, "b", 20_000),
                    HistoryRow(26, "b", 26_000),
                    HistoryRow(30, "a", 30_000, fieldId = if (compact) 3 else null)
                ))
                setCursor(db, "a", 5)
                setCursor(db, "b", 11)
                val reader = BoundedInfluxPendingSummaryReader(pageSize = 3, maxRowsPerRefresh = 3)

                check(!reader.summary(db, compact, setOf("a", "b")).complete) {
                    "cold history was exposed as an exact queue count (compact=$compact)"
                }
                reader.refresh(db, compact, setOf("a")) // category selection can change during indexing
                insert(db, compact, listOf(HistoryRow(40, "a", 40_000))) // above the fixed baseline fence
                var summary = reader.summary(db, compact, setOf("a", "b"))
                check(!summary.complete && summary.rows == -1L) { "insert above the fence was treated as indexed" }
                repeat(32) {
                    summary = reader.refresh(db, compact, setOf("a", "b"))
                    if (summary.complete) return@repeat
                }
                check(summary.complete) { "bounded history/cursor scan did not converge (compact=$compact)" }
                assertMatchesOracle(db, compact, setOf("a", "b"), summary)
                assertMatchesOracle(db, compact, setOf("a"), reader.summary(db, compact, setOf("a")))
                assertMatchesOracle(db, compact, setOf("b"), reader.summary(db, compact, setOf("b")))

                setCursor(db, "b", 20) // move from one straddling block to another
                check(!reader.summary(db, compact, setOf("a", "b")).complete) {
                    "a stale cursor-tail summary survived cursor advancement"
                }
                repeat(8) {
                    summary = reader.refresh(db, compact, setOf("a", "b"))
                    if (summary.complete) return@repeat
                }
                assertMatchesOracle(db, compact, setOf("a", "b"), summary)

                setCursor(db, "a", 100)
                setCursor(db, "b", 100)
                summary = reader.summary(db, compact, setOf("a", "b"))
                check(summary.complete && summary.rows == 0L && summary.oldestObservedAt == null) {
                    "fully acknowledged history did not become an exact zero"
                }
            } finally {
                db.close()
            }
        }
    }

    private fun cancelRollbackReplacementAndExactDrain() {
        val db = fixtureDatabase(compact = false)
        try {
            insert(db, compact = false, listOf(
                HistoryRow(2, "a", 2_000), HistoryRow(5, "a", 5_000), HistoryRow(9, "b", 9_000)
            ))
            setCursor(db, "a", 0)
            setCursor(db, "b", 0)
            val canceledReader = BoundedInfluxPendingSummaryReader(pageSize = 2, maxRowsPerRefresh = 2)
            val canceled = CancellationSignal().apply { cancel() }
            val canceledAsExpected = try {
                canceledReader.refresh(
                    db = db,
                    compactV2 = false,
                    fieldKeys = setOf("a", "b"),
                    cancellationSignal = canceled
                )
                false
            } catch (_: OperationCanceledException) {
                true
            }
            check(canceledAsExpected) { "pre-canceled refresh did not stop before advancing the cache" }
            check(canceledReader.summary(db, false, setOf("a", "b")).rows == -1L) {
                "cancellation exposed a partial count"
            }

            val reader = BoundedInfluxPendingSummaryReader(pageSize = 2, maxRowsPerRefresh = 2)
            var summary = reader.refresh(db, false, setOf("a", "b"))
            check(!summary.complete) { "partial first page was exposed as exact" }
            db.beginTransaction()
            try {
                insert(db, compact = false, listOf(HistoryRow(15, "b", 15_000)))
            } finally {
                db.endTransaction() // deliberate rollback
            }
            repeat(16) {
                summary = reader.refresh(db, false, setOf("a", "b"))
                if (summary.complete) return@repeat
            }
            assertMatchesOracle(db, false, setOf("a", "b"), summary)
            check(summary.rows == 3L) { "rolled-back row affected the exact baseline" }

            insert(db, compact = false, listOf(HistoryRow(20, "a", 20_000)))
            check(reader.summary(db, false, setOf("a", "b")).rows == -1L) {
                "concurrent committed insert did not invalidate the completed fence"
            }
            repeat(16) {
                summary = reader.refresh(db, false, setOf("a", "b"))
                if (summary.complete) return@repeat
            }
            assertMatchesOracle(db, false, setOf("a", "b"), summary)

            val replacementReader = BoundedInfluxPendingSummaryReader(pageSize = 2, maxRowsPerRefresh = 2)
            check(!replacementReader.summary(db, false, setOf("a", "b")).complete) {
                "a replacement store reader reused the old instance's cache"
            }
            repeat(16) {
                summary = replacementReader.refresh(db, false, setOf("a", "b"))
                if (summary.complete) return@repeat
            }
            assertMatchesOracle(db, false, setOf("a", "b"), summary)
        } finally {
            db.close()
        }
    }

    private fun largeMultipageGappedHistory() {
        listOf(false, true).forEach { compact ->
            val db = fixtureDatabase(compact)
            try {
                val rows = (0 until 9_001).map { index ->
                    val field = if (index % 2 == 0) "a" else "b"
                    HistoryRow(
                        id = index * 2L + 1L,
                        field = field,
                        observed = index * 1_000L,
                        fieldId = if (compact && field == "a" && index % 4 == 0) 3 else null
                    )
                }
                insert(db, compact, rows)
                setCursor(db, "a", 4_000)
                setCursor(db, "b", 10_000)
                val reader = BoundedInfluxPendingSummaryReader(pageSize = 4_096, maxRowsPerRefresh = 8_192)
                var summary = reader.refresh(db, compact, setOf("a", "b"))
                check(!summary.complete) { "large baseline was exposed before its fence completed" }
                repeat(8) {
                    summary = reader.refresh(db, compact, setOf("a", "b"))
                    if (summary.complete) return@repeat
                }
                assertMatchesOracle(db, compact, setOf("a", "b"), summary)
            } finally {
                db.close()
            }
        }
    }

    private fun incrementalSingleRowRefreshes() {
        listOf(false, true).forEach { compact ->
            val db = fixtureDatabase(compact)
            try {
                insert(db, compact, (1L..10L).map { id ->
                    HistoryRow(id, if (id % 2L == 0L) "b" else "a", id * 1_000L)
                })
                setCursor(db, "a", 4)
                setCursor(db, "b", 3)
                val reader = BoundedInfluxPendingSummaryReader(pageSize = 32, maxRowsPerRefresh = 32)
                var summary = reader.refresh(db, compact, setOf("a", "b"))
                check(summary.complete) { "small initial baseline did not complete (compact=$compact)" }

                for (index in 0 until 128) {
                    val id = 11L + index
                    val field = if (id % 2L == 0L) "b" else "a"
                    insert(db, compact, listOf(
                        HistoryRow(
                            id,
                            field,
                            id * 1_000L,
                            fieldId = if (compact && field == "a" && id % 4L == 3L) 3 else null
                        )
                    ))
                    summary = reader.refresh(db, compact, setOf("a", "b"))
                    for (retry in 0 until 2) {
                        if (summary.complete) break
                        summary = reader.refresh(db, compact, setOf("a", "b"))
                    }
                    check(summary.complete) { "single-row refresh did not retain an exact summary (compact=$compact, id=$id)" }
                }
                assertMatchesOracle(db, compact, setOf("a", "b"), summary)
            } finally {
                db.close()
            }
        }
    }

    private fun fixtureDatabase(compact: Boolean): SQLiteDatabase = SQLiteDatabase.create(null).apply {
        execSQL(
            "CREATE TABLE influx_export_cursor (" +
                "field_key TEXT PRIMARY KEY, last_exported_history_id INTEGER NOT NULL DEFAULT 0)"
        )
        if (compact) {
            execSQL("CREATE TABLE normalized_history_field_catalog (id INTEGER PRIMARY KEY, field_key TEXT NOT NULL, category TEXT NOT NULL)")
            execSQL("CREATE TABLE vehicle_state_history (id INTEGER PRIMARY KEY AUTOINCREMENT, field_id INTEGER NOT NULL, observed_at_ms INTEGER NOT NULL)")
            execSQL("CREATE INDEX idx_vehicle_state_history_field_id ON vehicle_state_history(field_id, id)")
            execSQL("INSERT INTO normalized_history_field_catalog(id,field_key,category) VALUES(1,'a','drive'),(2,'b','charge'),(3,'a','drive_v2')")
        } else {
            execSQL("CREATE TABLE vehicle_state_history (id INTEGER PRIMARY KEY AUTOINCREMENT, field_key TEXT NOT NULL, observed_at TEXT NOT NULL, changed_at TEXT NOT NULL)")
            execSQL("CREATE INDEX idx_vehicle_state_history_field_key_changed ON vehicle_state_history(field_key, changed_at)")
        }
        execSQL("INSERT INTO influx_export_cursor(field_key,last_exported_history_id) VALUES('a',0),('b',0)")
    }

    private fun insert(db: SQLiteDatabase, compact: Boolean, rows: List<HistoryRow>) {
        val writeRows = {
            rows.forEach { row ->
                if (compact) {
                    val fieldId = row.fieldId ?: if (row.field == "a") 1 else 2
                    db.execSQL(
                        "INSERT INTO vehicle_state_history(id,field_id,observed_at_ms) VALUES(?,?,?)",
                        arrayOf(row.id, fieldId, row.observed)
                    )
                } else {
                    val observed = normalizedIsoTime(row.observed)
                    db.execSQL(
                        "INSERT INTO vehicle_state_history(id,field_key,observed_at,changed_at) VALUES(?,?,?,?)",
                        arrayOf(row.id, row.field, observed, observed)
                    )
                }
            }
        }
        if (rows.size <= 100) {
            writeRows()
            return
        }
        db.beginTransaction()
        try {
            writeRows()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun setCursor(db: SQLiteDatabase, fieldKey: String, id: Long) {
        db.execSQL("UPDATE influx_export_cursor SET last_exported_history_id=? WHERE field_key=?", arrayOf(id, fieldKey))
    }

    private fun assertMatchesOracle(
        db: SQLiteDatabase,
        compact: Boolean,
        fieldKeys: Set<String>,
        actual: com.bydcollector.collector.influx.InfluxPendingSummary
    ) {
        check(actual.complete) { "summary remained unknown for $fieldKeys" }
        val placeholders = fieldKeys.joinToString(",") { "?" }
        val sql = if (compact) {
            """
            SELECT COUNT(*), MIN(history.observed_at_ms)
            FROM vehicle_state_history history
            JOIN normalized_history_field_catalog field ON field.id=history.field_id
            JOIN influx_export_cursor cursor ON cursor.field_key=field.field_key
            WHERE field.field_key IN ($placeholders) AND history.id>cursor.last_exported_history_id
            """.trimIndent()
        } else {
            """
            SELECT COUNT(*), MIN(history.observed_at)
            FROM vehicle_state_history history
            JOIN influx_export_cursor cursor ON cursor.field_key=history.field_key
            WHERE history.field_key IN ($placeholders) AND history.id>cursor.last_exported_history_id
            """.trimIndent()
        }
        db.rawQuery(sql, fieldKeys.toTypedArray()).use { cursor ->
            check(cursor.moveToFirst())
            check(actual.rows == cursor.getLong(0)) { "count mismatch: $actual versus ${cursor.getLong(0)}" }
            val oldest = if (cursor.isNull(1)) null else if (compact) normalizedIsoTime(cursor.getLong(1)) else cursor.getString(1)
            check(actual.oldestObservedAt == oldest) { "oldest mismatch: ${actual.oldestObservedAt} versus $oldest" }
        }
    }
}
