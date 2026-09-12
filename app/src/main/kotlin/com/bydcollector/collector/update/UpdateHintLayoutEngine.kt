package com.bydcollector.collector.update

import kotlin.math.ceil

internal data class UpdateHintBounds(val left: Int, val top: Int, val width: Int, val height: Int)

internal data class UpdateHintLayoutItem(
    val key: String,
    val preferredSizePercent: Int,
    val preferredWidthPx: Int,
    val preferredHeightPx: Int
)

internal data class UpdateHintLayoutPlacement(
    val xPx: Int,
    val yPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val effectiveSizePercent: Float
)

internal object UpdateHintLayoutEngine {
    private const val MAX_ITEMS = 1_024

    fun layout(
        items: List<UpdateHintLayoutItem>,
        bounds: UpdateHintBounds,
        outerMarginPx: Int,
        gapPx: Int
    ): Map<String, UpdateHintLayoutPlacement> {
        if (items.isEmpty()) return emptyMap()
        if (!validInput(items, bounds, outerMarginPx, gapPx)) return emptyMap()
        for (rows in items.size downTo 1) {
            build(items, bounds, outerMarginPx, gapPx, rows, Float.POSITIVE_INFINITY)?.let { return it }
        }
        var low = 0f
        var high = items.maxOf { it.preferredSizePercent }.toFloat()
        repeat(40) {
            val ceiling = (low + high) / 2f
            if ((items.size downTo 1).any {
                    build(items, bounds, outerMarginPx, gapPx, it, ceiling) != null
                }) low = ceiling else high = ceiling
        }
        for (rows in items.size downTo 1) {
            build(items, bounds, outerMarginPx, gapPx, rows, low)?.let { return it }
        }
        return emptyMap()
    }

    private fun validInput(
        items: List<UpdateHintLayoutItem>,
        bounds: UpdateHintBounds,
        margin: Int,
        gap: Int
    ): Boolean = items.size <= MAX_ITEMS && bounds.width > 0 && bounds.height > 0 &&
        bounds.left.toLong() + bounds.width <= Int.MAX_VALUE &&
        bounds.top.toLong() + bounds.height <= Int.MAX_VALUE &&
        margin >= 0 && gap >= 0 && items.map { it.key }.toSet().size == items.size &&
        items.all {
            it.key.isNotBlank() && it.key.length <= UpdateHintProtocol.MAX_ID_LENGTH &&
                it.preferredSizePercent in 1..UpdateHintProtocol.MAX_SIZE_PERCENT &&
                it.preferredWidthPx in 1..UpdateHintProtocol.MAX_DIMENSION_PX &&
                it.preferredHeightPx in 1..UpdateHintProtocol.MAX_DIMENSION_PX
        }

    private fun build(
        items: List<UpdateHintLayoutItem>,
        bounds: UpdateHintBounds,
        margin: Int,
        gap: Int,
        rows: Int,
        ceiling: Float
    ): Map<String, UpdateHintLayoutPlacement>? {
        val columns = ceil(items.size / rows.toDouble()).toInt()
        val widths = IntArray(items.size)
        val heights = IntArray(items.size)
        val percents = FloatArray(items.size)
        items.forEachIndexed { index, item ->
            val percent = minOf(item.preferredSizePercent.toFloat(), ceiling)
            val ratio = percent / item.preferredSizePercent
            percents[index] = percent
            widths[index] = ceil(item.preferredWidthPx * ratio).toInt().coerceAtLeast(1)
            heights[index] = ceil(item.preferredHeightPx * ratio).toInt().coerceAtLeast(1)
        }
        val rowHeights = IntArray(rows)
        val columnWidths = IntArray(columns)
        items.indices.forEach { index ->
            val column = index / rows
            val row = index % rows
            rowHeights[row] = maxOf(rowHeights[row], heights[index])
            columnWidths[column] = maxOf(columnWidths[column], widths[index])
        }
        val usedWidth = margin.toLong() * 2 + columnWidths.sumOf { it.toLong() } +
            gap.toLong() * (columns - 1)
        val usedHeight = margin.toLong() * 2 + rowHeights.sumOf { it.toLong() } +
            gap.toLong() * (rows - 1)
        if (usedWidth > bounds.width || usedHeight > bounds.height) return null

        val columnX = LongArray(columns)
        val rowY = LongArray(rows)
        for (column in 1 until columns) {
            columnX[column] = columnX[column - 1] + columnWidths[column - 1] + gap
        }
        for (row in 1 until rows) rowY[row] = rowY[row - 1] + rowHeights[row - 1] + gap
        val result = linkedMapOf<String, UpdateHintLayoutPlacement>()
        for (index in items.indices) {
            val column = index / rows
            val row = index % rows
            val x = bounds.left.toLong() + margin + columnX[column]
            val y = bounds.top.toLong() + margin + rowY[row]
            if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
            result[items[index].key] = UpdateHintLayoutPlacement(
                x.toInt(), y.toInt(), widths[index], heights[index], percents[index]
            )
        }
        return result
    }
}
