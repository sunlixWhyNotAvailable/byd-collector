package com.bydcollector.collector.data.local

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal

/** A row-count source whose primary key is append-only and monotonically allocated. */
internal data class BoundedIdRowCountTable(
    val key: String,
    val table: String,
    val idColumn: String
)

/**
 * Counts append-only tables in bounded slices. A fixed high-water mark fences each
 * baseline/incremental pass, so rows committed while a pass is in progress are read
 * by the next pass rather than racing the baseline count.
 */
internal class BoundedIdRowCountReader(
    tables: List<BoundedIdRowCountTable>,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxRowsPerRead: Int = DEFAULT_MAX_ROWS_PER_READ
) {
    private data class TableState(
        val spec: BoundedIdRowCountTable,
        var cursor: Long = 0L,
        var fence: Long? = null,
        var pendingRows: Long = 0L,
        var exactCount: Long? = null
    )

    private data class PageResult(val rowCount: Int, val lastId: Long)

    private val states = tables.map { TableState(it) }
    private var nextTableIndex = 0

    init {
        require(states.isNotEmpty()) { "at least one table is required" }
        require(pageSize > 0)
        require(maxRowsPerRead >= pageSize)
        states.forEach { state ->
            require(state.spec.key.isNotBlank())
            requireIdentifier(state.spec.table)
            requireIdentifier(state.spec.idColumn)
        }
        require(states.map { it.spec.key }.distinct().size == states.size) { "duplicate count key" }
    }

    /** Returns -1 while a table's current baseline/incremental pass is incomplete. */
    @Synchronized
    fun refresh(db: SQLiteDatabase, cancellationSignal: CancellationSignal? = null): Map<String, Long> {
        val waitingForFence = states.filter { it.fence == null }
        if (waitingForFence.isNotEmpty()) {
            val highWater = readHighWaterMarks(db, waitingForFence, cancellationSignal)
            waitingForFence.forEach { state ->
                val mark = highWater.getValue(state.spec.key)
                if (state.exactCount == null || mark > state.cursor) {
                    state.fence = mark
                    state.pendingRows = 0L
                }
            }
        }

        var remainingRows = maxRowsPerRead
        var emptyPasses = 0
        var index = if (states.isEmpty()) 0 else nextTableIndex % states.size
        while (remainingRows > 0 && emptyPasses < states.size) {
            val state = states[index]
            index = (index + 1) % states.size
            val fence = state.fence
            if (fence == null) {
                emptyPasses++
                continue
            }

            val limit = minOf(pageSize, remainingRows)
            val page = readPage(db, state, fence, limit, cancellationSignal)
            if (page.rowCount == 0) {
                completePass(state, fence)
                emptyPasses++
                continue
            }

            state.cursor = page.lastId
            state.pendingRows += page.rowCount.toLong()
            remainingRows -= page.rowCount
            emptyPasses = 0
            if (page.rowCount < limit) completePass(state, fence)
        }
        nextTableIndex = index
        return states.associate { state ->
            state.spec.key to if (state.fence == null) state.exactCount ?: UNKNOWN_ROW_COUNT else UNKNOWN_ROW_COUNT
        }
    }

    private fun readHighWaterMarks(
        db: SQLiteDatabase,
        pending: List<TableState>,
        cancellationSignal: CancellationSignal?
    ): Map<String, Long> {
        val expressions = pending.joinToString(", ") { state ->
            "COALESCE(MAX(${quoteIdentifier(state.spec.idColumn)}), 0)"
                .let { "(SELECT $it FROM ${quoteIdentifier(state.spec.table)})" }
        }
        val query = if (cancellationSignal == null) {
            db.rawQuery("SELECT $expressions", emptyArray())
        } else {
            db.rawQuery("SELECT $expressions", emptyArray(), cancellationSignal)
        }
        query.use { cursor ->
            check(cursor.moveToFirst()) { "row-count high-water query returned no row" }
            return pending.mapIndexed { index, state -> state.spec.key to cursor.getLong(index) }.toMap()
        }
    }

    private fun readPage(
        db: SQLiteDatabase,
        state: TableState,
        fence: Long,
        limit: Int,
        cancellationSignal: CancellationSignal?
    ): PageResult {
        val spec = state.spec
        val sql = "SELECT ${quoteIdentifier(spec.idColumn)} FROM ${quoteIdentifier(spec.table)} " +
            "WHERE ${quoteIdentifier(spec.idColumn)} > ? AND ${quoteIdentifier(spec.idColumn)} <= ? " +
            "ORDER BY ${quoteIdentifier(spec.idColumn)} LIMIT ?"
        val args = arrayOf(state.cursor.toString(), fence.toString(), limit.toString())
        val query = if (cancellationSignal == null) {
            db.rawQuery(sql, args)
        } else {
            db.rawQuery(sql, args, cancellationSignal)
        }
        query.use { cursor ->
            var count = 0
            var lastId = state.cursor
            while (cursor.moveToNext()) {
                lastId = cursor.getLong(0)
                count++
            }
            return PageResult(count, lastId)
        }
    }

    private fun completePass(state: TableState, fence: Long) {
        state.exactCount = (state.exactCount ?: 0L) + state.pendingRows
        state.cursor = fence
        state.pendingRows = 0L
        state.fence = null
    }

    private fun quoteIdentifier(identifier: String): String = "\"$identifier\""

    private fun requireIdentifier(identifier: String) {
        require(identifier.matches(IDENTIFIER_PATTERN)) { "unsafe SQLite identifier: $identifier" }
    }

    companion object {
        const val UNKNOWN_ROW_COUNT = -1L
        private const val DEFAULT_PAGE_SIZE = 4_096
        private const val DEFAULT_MAX_ROWS_PER_READ = 32_768
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
