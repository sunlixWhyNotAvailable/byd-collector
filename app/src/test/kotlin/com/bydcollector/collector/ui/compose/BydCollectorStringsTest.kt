package com.bydcollector.collector.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class BydCollectorStringsTest {
    @Test
    fun influxCountersUsePointsInsteadOfMainDatabaseRows() {
        assertEquals("точок", strings(UiLanguage.UK).points)
        assertEquals("points", strings(UiLanguage.EN).points)
    }
}
