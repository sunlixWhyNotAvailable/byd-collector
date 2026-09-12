package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateHintLayoutEngineTest {
    @Test
    fun `first fitting rows are filled column major across three columns`() {
        val layout = UpdateHintLayoutEngine.layout(
            (1..5).map { item("$it", 100, 100) },
            UpdateHintBounds(0, 0, 400, 260), 10, 5
        )
        assertEquals(listOf(10 to 10, 10 to 115, 115 to 10, 115 to 115, 220 to 10),
            (1..5).map { layout.getValue("$it").let { value -> value.xPx to value.yPx } })
    }

    @Test
    fun `row and column maxima align mixed card geometry`() {
        val layout = UpdateHintLayoutEngine.layout(
            listOf(item("a", 100, 60), item("b", 80, 100),
                item("c", 120, 70), item("d", 90, 80)),
            UpdateHintBounds(4, 6, 280, 230), 10, 8
        )
        assertPlacement(layout.getValue("a"), 14, 16, 100, 60, 100f)
        assertPlacement(layout.getValue("b"), 14, 94, 80, 100, 100f)
        assertPlacement(layout.getValue("c"), 122, 16, 120, 70, 100f)
        assertPlacement(layout.getValue("d"), 122, 94, 90, 80, 100f)
    }

    @Test
    fun `common float ceiling shrinks larger preferences first and below fifty`() {
        val mixed = UpdateHintLayoutEngine.layout(
            listOf(UpdateHintLayoutItem("small", 50, 50, 50),
                UpdateHintLayoutItem("large", 100, 100, 100)),
            UpdateHintBounds(0, 0, 140, 130), 10, 5
        )
        assertEquals(50f, mixed.getValue("small").effectiveSizePercent, 0.01f)
        assertTrue(mixed.getValue("large").effectiveSizePercent in 64.9f..65.01f)

        val belowFifty = UpdateHintLayoutEngine.layout(
            listOf(item("large", 200, 200)), UpdateHintBounds(0, 0, 100, 100), 10, 5
        ).getValue("large")
        assertEquals(80, belowFifty.widthPx)
        assertEquals(80, belowFifty.heightPx)
        assertTrue(belowFifty.effectiveSizePercent in 39.9f..40.01f)
    }

    @Test
    fun `ceil dimensions fill the exact boundary without clipping`() {
        val placement = UpdateHintLayoutEngine.layout(
            listOf(item("odd", 101, 101)), UpdateHintBounds(0, 0, 70, 70), 10, 0
        ).getValue("odd")
        assertEquals(50, placement.widthPx)
        assertEquals(50, placement.heightPx)
        assertEquals(60, placement.xPx + placement.widthPx)
        assertEquals(60, placement.yPx + placement.heightPx)
    }

    @Test
    fun `fresh layout restores original geometry after removal or resize`() {
        val items = listOf(item("a", 200, 200), item("b", 200, 200))
        val constrained = UpdateHintLayoutEngine.layout(items,
            UpdateHintBounds(0, 0, 160, 250), 10, 10)
        assertTrue(constrained.getValue("a").effectiveSizePercent < 100f)
        assertPlacement(UpdateHintLayoutEngine.layout(items.take(1),
            UpdateHintBounds(0, 0, 240, 240), 10, 10).getValue("a"),
            10, 10, 200, 200, 100f)
        assertEquals(100f, UpdateHintLayoutEngine.layout(items,
            UpdateHintBounds(0, 0, 500, 500), 10, 10).getValue("a").effectiveSizePercent, 0f)
    }

    @Test
    fun `invalid or overflow prone geometry is rejected`() {
        assertTrue(UpdateHintLayoutEngine.layout(
            listOf(item("a", Int.MAX_VALUE, 1)), UpdateHintBounds(0, 0, 100, 100), 0, 0
        ).isEmpty())
        assertTrue(UpdateHintLayoutEngine.layout(
            listOf(item("same", 1, 1), item("same", 1, 1)),
            UpdateHintBounds(0, 0, 100, 100), 0, 0
        ).isEmpty())
        assertTrue(UpdateHintLayoutEngine.layout(
            listOf(item("a", 1, 1)), UpdateHintBounds(Int.MAX_VALUE, 0, 100, 100), 1, 0
        ).isEmpty())
    }

    private fun item(key: String, width: Int, height: Int) =
        UpdateHintLayoutItem(key, 100, width, height)

    private fun assertPlacement(
        actual: UpdateHintLayoutPlacement,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        percent: Float
    ) {
        assertEquals(x, actual.xPx)
        assertEquals(y, actual.yPx)
        assertEquals(width, actual.widthPx)
        assertEquals(height, actual.heightPx)
        assertEquals(percent, actual.effectiveSizePercent, 0f)
    }
}
