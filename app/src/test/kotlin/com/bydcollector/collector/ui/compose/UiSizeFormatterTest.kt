package com.bydcollector.collector.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class UiSizeFormatterTest {
    @Test
    fun ukSizesUseUkrainianUnits() {
        val strings = strings(UiLanguage.UK)

        assertEquals("0 МБ", UiSizeFormatter.bytes(0, strings))
        assertEquals("1.5 МБ", UiSizeFormatter.bytes((1.5 * 1024 * 1024).toLong(), strings))
        assertEquals("1.3 ГБ", UiSizeFormatter.bytes((1.3 * 1024 * 1024 * 1024).toLong(), strings))
        assertEquals("6 ГБ", UiSizeFormatter.gigabytes(6, strings))
    }

    @Test
    fun enSizesUseEnglishUnits() {
        val strings = strings(UiLanguage.EN)

        assertEquals("0 MB", UiSizeFormatter.bytes(0, strings))
        assertEquals("1.5 MB", UiSizeFormatter.bytes((1.5 * 1024 * 1024).toLong(), strings))
        assertEquals("1.3 GB", UiSizeFormatter.bytes((1.3 * 1024 * 1024 * 1024).toLong(), strings))
        assertEquals("6 GB", UiSizeFormatter.gigabytes(6, strings))
    }
}
