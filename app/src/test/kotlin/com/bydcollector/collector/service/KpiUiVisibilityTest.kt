package com.bydcollector.collector.service

import org.junit.Assert.*
import org.junit.Test

class KpiUiVisibilityTest {
    @Test fun `old activity disposal cannot hide a replacement and repeated geometry does not wake workers`() {
        val old = Any()
        val replacement = Any()
        val observed = mutableListOf<Boolean>()
        val listener: () -> Unit = { observed += KpiUiVisibility.visible }
        KpiUiVisibility.addListener(listener)
        try {
            KpiUiVisibility.update(old, true)
            repeat(140) { KpiUiVisibility.update(old, true) }
            KpiUiVisibility.update(replacement, true)
            KpiUiVisibility.update(old, false)
            assertTrue(KpiUiVisibility.visible)
            assertEquals(listOf(true), observed)
            KpiUiVisibility.update(replacement, false)
            assertFalse(KpiUiVisibility.visible)
            assertEquals(listOf(true, false), observed)
        } finally {
            KpiUiVisibility.removeListener(listener)
            KpiUiVisibility.update(old, false)
            KpiUiVisibility.update(replacement, false)
        }
    }

    @Test fun `service listener detaches without erasing surviving UI demand`() {
        val owner = Any()
        var calls = 0
        val listener: () -> Unit = { calls++ }
        KpiUiVisibility.addListener(listener)
        try {
            KpiUiVisibility.update(owner, true)
            KpiUiVisibility.removeListener(listener)
            assertTrue(KpiUiVisibility.visible)
            KpiUiVisibility.update(owner, false)
            assertEquals(1, calls)
        } finally {
            KpiUiVisibility.removeListener(listener)
            KpiUiVisibility.update(owner, false)
        }
    }
}
