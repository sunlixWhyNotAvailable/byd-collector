package com.bydcollector.collector.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class MainPollStatusFormatterTest {
    @Test
    fun stoppedMainPollingShowsStoppedInsteadOfSuccess() {
        val status = MainPollStatusFormatter.format(
            running = false,
            lastPollStatus = null,
            strings = strings(UiLanguage.UK)
        )

        assertEquals("зупинено", status.text)
        assertEquals(StatusKind.WAITING, status.kind)
    }

    @Test
    fun activePollingErrorStatusIsError() {
        val status = MainPollStatusFormatter.format(
            running = true,
            lastPollStatus = "Polling error: Direct helper unavailable at 2026-06-24T10:00:00+03:00",
            strings = strings(UiLanguage.UK)
        )

        assertEquals("помилка", status.text)
        assertEquals(StatusKind.ERROR, status.kind)
    }
}
