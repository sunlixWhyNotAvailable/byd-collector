package com.bydcollector.collector.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelStatusFormatterTest {
    @Test
    fun retryStatusIsShownAsErrorEvenWhenChannelIsEnabled() {
        val status = "enabled; pending: 3; retry #2 at 2026-06-24 10:15"

        assertEquals("помилка", ChannelStatusFormatter.compactText(status, strings(UiLanguage.UK)))
        assertEquals(StatusKind.ERROR, ChannelStatusFormatter.kind(status, enabled = true))
    }
}
