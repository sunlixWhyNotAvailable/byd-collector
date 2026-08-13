package com.bydcollector.collector.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelStatusFormatterTest {
    @Test
    fun healthyInfluxBatchScheduleIsShownAsExporting() {
        val status = "running; pending: 10362; retry at 2026-08-13 17:23:15"

        assertEquals("експортує", ChannelStatusFormatter.compactText(status, strings(UiLanguage.UK)))
        assertEquals(StatusKind.OK, ChannelStatusFormatter.kind(status, enabled = true))
    }

    @Test
    fun realMqttRetryIsShownAsErrorEvenWhenChannelIsEnabled() {
        val status = "enabled; pending: 3; retry #2 at 2026-06-24 10:15"

        assertEquals("помилка", ChannelStatusFormatter.compactText(status, strings(UiLanguage.UK)))
        assertEquals(StatusKind.ERROR, ChannelStatusFormatter.kind(status, enabled = true))
    }
}
