package com.bydcollector.collector.data.local

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.bydcollector.collector.data.normalized.normalizedIsoTime
import com.bydcollector.collector.influx.InfluxPendingSummary
import java.util.concurrent.atomic.AtomicBoolean

/** Sparse, append-only history summaries; delivery continues to use durable cursor row selection. */
internal class BoundedInfluxPendingSummaryReader(
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxRowsPerRefresh: Int = DEFAULT_MAX_ROWS_PER_REFRESH
) {
    private interface SummaryPart {
        val count: Long
        val minObservedAtMs: Long?
        val minObservedAtText: String?
    }

    private data class FieldBlock(
        override val count: Long,
        val firstId: Long,
        val lastId: Long,
        override val minObservedAtMs: Long?,
        override val minObservedAtText: String?
    ) : SummaryPart

    private data class HistoryBlock(
        val firstId: Long,
        val lastId: Long,
        val sourceRows: Int,
        val fields: Map<String, FieldBlock>,
        val previous: HistoryBlock?
    )

    private data class CursorTail(
        val cursorId: Long,
        val firstId: Long,
        val lastId: Long,
        override val count: Long,
        override val minObservedAtMs: Long?,
        override val minObservedAtText: String?
    ) : SummaryPart

    private data class Snapshot(
        val throughId: Long,
        val fenceId: Long,
        val tail: HistoryBlock?,
        val cursorTails: Map<String, CursorTail>
    )

    private data class AccountingInputs(val maxHistoryId: Long, val cursors: Map<String, Long>)
    private data class SourceRow(val id: Long, val fieldKey: String, val observedAtMs: Long?, val observedAtText: String?)
    private data class MutableFieldBlock(
        var count: Long = 0L,
        var firstId: Long = Long.MAX_VALUE,
        var lastId: Long = 0L,
        var minObservedAtMs: Long? = null,
        var minObservedAtText: String? = null
    )

    private val refreshInProgress = AtomicBoolean(false)
    private val snapshotLock = Any()
    private var generation = 0L
    @Volatile private var snapshot: Snapshot? = null

    init {
        require(pageSize in 1..DEFAULT_PAGE_SIZE)
        require(maxRowsPerRefresh >= pageSize)
    }

    /** Advances bounded pages and returns an exact snapshot through this pass's captured history fence. */
    fun refresh(
        db: SQLiteDatabase,
        compactV2: Boolean,
        fieldKeys: Set<String>,
        cancellationSignal: CancellationSignal? = null
    ): InfluxPendingSummary {
        if (fieldKeys.isEmpty()) return exactEmpty()
        if (!refreshInProgress.compareAndSet(false, true)) return summary(db, compactV2, fieldKeys, cancellationSignal)
        try {
            cancellationSignal?.throwIfCanceled()
            val firstInputs = readAccountingInputs(db, fieldKeys, cancellationSignal)
            var current = currentSnapshot()
            if (current != null && firstInputs.maxHistoryId < current.throughId) {
                resetIfSame(current)
                current = null
            }

            var expectedGeneration = currentGeneration()
            var remainingRows = maxRowsPerRefresh
            while (remainingRows > 0) {
                cancellationSignal?.throwIfCanceled()
                current = current ?: Snapshot(
                    throughId = 0L,
                    fenceId = firstInputs.maxHistoryId,
                    tail = null,
                    cursorTails = emptyMap()
                )
                if (current.throughId >= current.fenceId) {
                    if (firstInputs.maxHistoryId <= current.throughId) {
                        publish(expectedGeneration, current)
                        break
                    }
                    current = current.copy(fenceId = firstInputs.maxHistoryId)
                }

                val tailRows = current.tail?.sourceRows ?: 0
                val availableBlockRows = if (tailRows in 1 until pageSize) pageSize - tailRows else pageSize
                val limit = minOf(availableBlockRows, remainingRows)
                val rows = readPage(
                    db = db,
                    compactV2 = compactV2,
                    afterId = current.throughId,
                    fenceId = current.fenceId,
                    limit = limit,
                    cancellationSignal = cancellationSignal
                )
                val updated = if (rows.isEmpty()) {
                    current.copy(throughId = current.fenceId)
                } else {
                    val page = summarizePage(rows, compactV2)
                    val block = appendPage(current.tail, page)
                    val through = if (rows.size < limit || rows.last().id >= current.fenceId) {
                        current.fenceId
                    } else {
                        rows.last().id
                    }
                    current.copy(throughId = through, tail = block)
                }
                if (!publish(expectedGeneration, updated)) return unknown()
                current = updated
                remainingRows -= rows.size
                if (rows.isEmpty() || rows.size < limit) break
            }

            current = currentSnapshot() ?: return unknown()
            if (current.throughId < firstInputs.maxHistoryId) return unknown()
            val latestInputs = readAccountingInputs(db, fieldKeys, cancellationSignal)
            if (latestInputs.maxHistoryId < current.throughId || fieldKeys.any { it !in latestInputs.cursors }) {
                resetIfSame(current)
                return unknown()
            }
            current = refreshCursorTails(
                db = db,
                compactV2 = compactV2,
                current = current,
                cursors = latestInputs.cursors,
                fieldKeys = fieldKeys,
                cancellationSignal = cancellationSignal,
                rowBudget = remainingRows,
                expectedGeneration = expectedGeneration
            ) ?: return unknown()
            return summarizeSnapshot(current, compactV2, fieldKeys, latestInputs.cursors)
        } finally {
            refreshInProgress.set(false)
        }
    }

    /** Reads the cache, durable cursors, and a fresh history high-water mark; never scans history rows. */
    fun summary(
        db: SQLiteDatabase,
        compactV2: Boolean,
        fieldKeys: Set<String>,
        cancellationSignal: CancellationSignal? = null
    ): InfluxPendingSummary {
        if (fieldKeys.isEmpty()) return exactEmpty()
        val inputs = readAccountingInputs(db, fieldKeys, cancellationSignal)
        if (fieldKeys.any { it !in inputs.cursors }) return unknown()
        if (inputs.maxHistoryId == 0L) {
            currentSnapshot()?.takeIf { it.throughId > 0L }?.let(::resetIfSame)
            return exactEmpty()
        }
        val current = currentSnapshot() ?: return unknown()
        if (inputs.maxHistoryId < current.throughId) {
            resetIfSame(current)
            return unknown()
        }
        if (inputs.maxHistoryId > current.throughId) return unknown()

        return summarizeSnapshot(current, compactV2, fieldKeys, inputs.cursors)
    }

    private fun summarizeSnapshot(
        current: Snapshot,
        compactV2: Boolean,
        fieldKeys: Set<String>,
        cursors: Map<String, Long>
    ): InfluxPendingSummary {
        if (fieldKeys.any { it !in cursors }) return unknown()
        var rows = 0L
        var minObservedAtMs: Long? = null
        var minObservedAtText: String? = null
        fieldKeys.forEach { fieldKey ->
            val cursorId = cursors.getValue(fieldKey)
            var block = current.tail
            while (block != null) {
                val field = block.fields[fieldKey]
                if (field != null && cursorId < field.lastId) {
                    val contribution: SummaryPart = when {
                        cursorId < field.firstId -> field
                        else -> current.cursorTails[fieldKey]?.takeIf {
                            it.cursorId == cursorId && it.firstId == field.firstId && it.lastId == field.lastId
                        } ?: return unknown()
                    }
                    rows += contribution.count
                    if (compactV2) {
                        contribution.minObservedAtMs?.let { value ->
                            if (minObservedAtMs == null || value < minObservedAtMs!!) minObservedAtMs = value
                        }
                    } else {
                        contribution.minObservedAtText?.let { value ->
                            if (minObservedAtText == null || value < minObservedAtText!!) minObservedAtText = value
                        }
                    }
                }
                block = block.previous
            }
        }
        return InfluxPendingSummary(
            rows = rows,
            oldestObservedAt = if (compactV2) minObservedAtMs?.let(::normalizedIsoTime) else minObservedAtText,
            complete = true
        )
    }

    fun clear() {
        synchronized(snapshotLock) {
            generation++
            snapshot = null
        }
    }

    private fun refreshCursorTails(
        db: SQLiteDatabase,
        compactV2: Boolean,
        current: Snapshot,
        cursors: Map<String, Long>,
        fieldKeys: Set<String>,
        cancellationSignal: CancellationSignal?,
        rowBudget: Int,
        expectedGeneration: Long
    ): Snapshot? {
        if (rowBudget <= 0) return current
        val pendingByBlock = LinkedHashMap<Pair<Long, Long>, MutableMap<String, Long>>()
        var block = current.tail
        while (block != null) {
            block.fields.forEach { (fieldKey, field) ->
                if (fieldKey !in fieldKeys) return@forEach
                val cursorId = cursors[fieldKey] ?: return@forEach
                if (cursorId < field.firstId || cursorId >= field.lastId) return@forEach
                val cached = current.cursorTails[fieldKey]
                val fieldRange = block.fields.getValue(fieldKey)
                if (cached?.cursorId == cursorId && cached.firstId == fieldRange.firstId && cached.lastId == fieldRange.lastId) {
                    return@forEach
                }
                pendingByBlock.getOrPut(block.firstId to block.lastId) { LinkedHashMap() }[fieldKey] = cursorId
            }
            block = block.previous
        }

        var remaining = rowBudget
        var tails = current.cursorTails
        current.tail?.let { newest ->
            // Prefer recent ranges so current export cursors do not wait behind old category history.
            val orderedBlocks = ArrayList<HistoryBlock>()
            var node: HistoryBlock? = newest
            while (node != null) {
                orderedBlocks += node
                node = node.previous
            }
            for (candidate in orderedBlocks) {
                val key = candidate.firstId to candidate.lastId
                val cursorsForBlock = pendingByBlock[key] ?: continue
                if (candidate.sourceRows > remaining) continue
                cancellationSignal?.throwIfCanceled()
                val rows = readBlock(
                    db = db,
                    compactV2 = compactV2,
                    block = candidate,
                    cancellationSignal = cancellationSignal
                ) ?: run {
                    resetIfSame(current)
                    return null
                }
                val counts = cursorsForBlock.mapValues { MutableFieldBlock() }.toMutableMap()
                rows.forEach { row ->
                    val cursorId = cursorsForBlock[row.fieldKey] ?: return@forEach
                    if (row.id <= cursorId) return@forEach
                    val value = counts.getValue(row.fieldKey)
                    value.count++
                    value.firstId = minOf(value.firstId, row.id)
                    value.lastId = maxOf(value.lastId, row.id)
                    updateMinimum(value, row, compactV2)
                }
                val additions = counts.mapValues { (fieldKey, value) ->
                    val cursorId = cursorsForBlock.getValue(fieldKey)
                    val fieldRange = candidate.fields.getValue(fieldKey)
                    fieldKey to CursorTail(
                        cursorId = cursorId,
                        firstId = fieldRange.firstId,
                        lastId = fieldRange.lastId,
                        count = value.count,
                        minObservedAtMs = value.minObservedAtMs,
                        minObservedAtText = value.minObservedAtText
                    )
                }.values.toMap()
                tails = tails + additions
                val updated = current.copy(cursorTails = tails)
                if (!publish(expectedGeneration, updated)) return null
                remaining -= candidate.sourceRows
            }
        }
        return current.copy(cursorTails = tails)
    }

    private fun readAccountingInputs(
        db: SQLiteDatabase,
        fieldKeys: Set<String>,
        cancellationSignal: CancellationSignal?
    ): AccountingInputs {
        val placeholders = fieldKeys.joinToString(",") { "?" }
        val sql = """
            SELECT cursor.field_key, cursor.last_exported_history_id, high.max_id
            FROM (SELECT COALESCE(MAX(id), 0) AS max_id FROM vehicle_state_history) high
            LEFT JOIN influx_export_cursor cursor ON cursor.field_key IN ($placeholders)
        """.trimIndent()
        val cursor = query(db, sql, fieldKeys.toList().toTypedArray(), cancellationSignal)
        cursor.use {
            val positions = LinkedHashMap<String, Long>()
            var maxId = 0L
            while (it.moveToNext()) {
                if (!it.isNull(0)) positions[it.getString(0)] = it.getLong(1)
                maxId = it.getLong(2)
            }
            return AccountingInputs(maxId, positions)
        }
    }

    private fun readPage(
        db: SQLiteDatabase,
        compactV2: Boolean,
        afterId: Long,
        fenceId: Long,
        limit: Int,
        cancellationSignal: CancellationSignal?
    ): List<SourceRow> {
        val source = if (compactV2) {
            """
            SELECT history.id, field.field_key, history.observed_at_ms, NULL
            FROM vehicle_state_history history
            JOIN normalized_history_field_catalog field ON field.id = history.field_id
            WHERE history.id > ? AND history.id <= ?
            ORDER BY history.id LIMIT ?
            """.trimIndent()
        } else {
            """
            SELECT history.id, history.field_key, NULL, history.observed_at
            FROM vehicle_state_history history
            WHERE history.id > ? AND history.id <= ?
            ORDER BY history.id LIMIT ?
            """.trimIndent()
        }
        val cursor = query(
            db,
            source,
            arrayOf(afterId.toString(), fenceId.toString(), limit.toString()),
            cancellationSignal
        )
        cursor.use {
            val rows = ArrayList<SourceRow>(limit)
            while (it.moveToNext()) {
                rows += SourceRow(
                    id = it.getLong(0),
                    fieldKey = it.getString(1),
                    observedAtMs = if (compactV2) it.getLong(2) else null,
                    observedAtText = if (compactV2 || it.isNull(3)) null else it.getString(3)
                )
            }
            return rows
        }
    }

    private fun readBlock(
        db: SQLiteDatabase,
        compactV2: Boolean,
        block: HistoryBlock,
        cancellationSignal: CancellationSignal?
    ): List<SourceRow>? {
        val source = if (compactV2) {
            """
            SELECT history.id, field.field_key, history.observed_at_ms, NULL
            FROM vehicle_state_history history
            JOIN normalized_history_field_catalog field ON field.id = history.field_id
            WHERE history.id >= ? AND history.id <= ?
            ORDER BY history.id LIMIT ?
            """.trimIndent()
        } else {
            """
            SELECT history.id, history.field_key, NULL, history.observed_at
            FROM vehicle_state_history history
            WHERE history.id >= ? AND history.id <= ?
            ORDER BY history.id LIMIT ?
            """.trimIndent()
        }
        val cursor = query(
            db,
            source,
            arrayOf(block.firstId.toString(), block.lastId.toString(), (block.sourceRows + 1).toString()),
            cancellationSignal
        )
        cursor.use {
            val rows = ArrayList<SourceRow>(block.sourceRows)
            while (it.moveToNext()) {
                if (rows.size == block.sourceRows) return null
                rows += SourceRow(
                    id = it.getLong(0),
                    fieldKey = it.getString(1),
                    observedAtMs = if (compactV2) it.getLong(2) else null,
                    observedAtText = if (compactV2 || it.isNull(3)) null else it.getString(3)
                )
            }
            if (rows.size != block.sourceRows || rows.firstOrNull()?.id != block.firstId || rows.lastOrNull()?.id != block.lastId) {
                return null
            }
            return rows
        }
    }

    private fun summarizePage(rows: List<SourceRow>, compactV2: Boolean): HistoryBlock {
        val byField = LinkedHashMap<String, MutableFieldBlock>()
        rows.forEach { row ->
            val field = byField.getOrPut(row.fieldKey) { MutableFieldBlock() }
            field.count++
            field.firstId = minOf(field.firstId, row.id)
            field.lastId = maxOf(field.lastId, row.id)
            updateMinimum(field, row, compactV2)
        }
        return HistoryBlock(
            firstId = rows.first().id,
            lastId = rows.last().id,
            sourceRows = rows.size,
            fields = byField.mapValues { (_, value) ->
                FieldBlock(value.count, value.firstId, value.lastId, value.minObservedAtMs, value.minObservedAtText)
            },
            previous = null
        )
    }

    private fun appendPage(previous: HistoryBlock?, page: HistoryBlock): HistoryBlock {
        if (previous == null || previous.sourceRows + page.sourceRows > pageSize) {
            return page.copy(previous = previous)
        }
        val fields = LinkedHashMap(previous.fields)
        page.fields.forEach { (fieldKey, addition) ->
            val existing = fields[fieldKey]
            fields[fieldKey] = if (existing == null) {
                addition
            } else {
                FieldBlock(
                    count = existing.count + addition.count,
                    firstId = minOf(existing.firstId, addition.firstId),
                    lastId = maxOf(existing.lastId, addition.lastId),
                    minObservedAtMs = listOfNotNull(existing.minObservedAtMs, addition.minObservedAtMs).minOrNull(),
                    minObservedAtText = listOfNotNull(existing.minObservedAtText, addition.minObservedAtText).minOrNull()
                )
            }
        }
        return HistoryBlock(
            firstId = previous.firstId,
            lastId = page.lastId,
            sourceRows = previous.sourceRows + page.sourceRows,
            fields = fields,
            previous = previous.previous
        )
    }

    private fun updateMinimum(target: MutableFieldBlock, row: SourceRow, compactV2: Boolean) {
        if (compactV2) {
            val value = row.observedAtMs ?: return
            if (target.minObservedAtMs == null || value < target.minObservedAtMs!!) target.minObservedAtMs = value
        } else {
            val value = row.observedAtText ?: return
            if (target.minObservedAtText == null || value < target.minObservedAtText!!) target.minObservedAtText = value
        }
    }

    private fun query(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
        cancellationSignal: CancellationSignal?
    ) = if (cancellationSignal == null) db.rawQuery(sql, args) else db.rawQuery(sql, args, cancellationSignal)

    private fun currentSnapshot(): Snapshot? = snapshot

    private fun currentGeneration(): Long = synchronized(snapshotLock) { generation }

    private fun publish(expectedGeneration: Long, value: Snapshot): Boolean = synchronized(snapshotLock) {
        if (generation != expectedGeneration) return@synchronized false
        snapshot = value
        true
    }

    private fun resetIfSame(value: Snapshot) {
        synchronized(snapshotLock) {
            if (snapshot === value) {
                generation++
                snapshot = null
            }
        }
    }

    private fun exactEmpty() = InfluxPendingSummary(rows = 0L, oldestObservedAt = null, complete = true)
    private fun unknown() = InfluxPendingSummary(rows = UNKNOWN_ROWS, oldestObservedAt = null, complete = false)

    companion object {
        const val UNKNOWN_ROWS = -1L
        const val DEFAULT_PAGE_SIZE = 4_096
        const val DEFAULT_MAX_ROWS_PER_REFRESH = 32_768
    }
}
