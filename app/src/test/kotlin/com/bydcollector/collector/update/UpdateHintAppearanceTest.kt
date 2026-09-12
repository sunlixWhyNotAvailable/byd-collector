package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateHintAppearanceTest {
    @Test
    fun defaultsAndNormalizationPreserveTheApprovedRanges() {
        val defaults = UpdateHintAppearance()
        assertEquals(UpdateHintAppearance(0, 18, 1, 0xFF54D898.toInt(), 100), defaults)
        assertEquals(1f, defaults.alpha)
        assertEquals(1f, defaults.scale)
        assertEquals(UpdateHintAppearance(100, 0, 16, 0xFF123456.toInt(), 50),
            UpdateHintAppearance(101, -1, 30, 0x00123456, 0).normalized())
        assertEquals(UpdateHintAppearance(0, 40, 0, 0xFFFFFFFF.toInt(), 150),
            UpdateHintAppearance(-1, 50, -1, 0x00FFFFFF, 200).normalized())
        assertEquals(0f, UpdateHintAppearance(transparencyPercent = 100).alpha)
    }
}
