package com.bydcollector.collector.ui.compose

import com.bydcollector.collector.ui.RuntimeActionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelStatusFormatterTest {
    @Test
    fun healthyInfluxBatchScheduleIsWaitingForItsDeadline() {
        val status = "scheduled; pending: 10362; retry at 2026-08-13 17:23:15"

        assertEquals(
            "очікування",
            ChannelStatusFormatter.compactText(status, strings(UiLanguage.UK), RuntimeActionStatus.RUNNING)
        )
        assertEquals(
            StatusKind.WAITING,
            ChannelStatusFormatter.kind(status, enabled = true, RuntimeActionStatus.RUNNING)
        )
    }

    @Test
    fun realMqttRetryIsShownAsErrorEvenWhenChannelIsEnabled() {
        val status = "enabled; pending: 3; retry #2 at 2026-06-24 10:15"

        assertEquals("помилка", ChannelStatusFormatter.compactText(status, strings(UiLanguage.UK)))
        assertEquals(StatusKind.ERROR, ChannelStatusFormatter.kind(status, enabled = true))
    }

    @Test
    fun persistedFailureWinsOverStaleRunningRuntime() {
        val status = "running; pending: 1; error: Tailscale connection failed"

        assertEquals(
            "помилка",
            ChannelStatusFormatter.compactText(status, strings(UiLanguage.UK), RuntimeActionStatus.RUNNING)
        )
        assertEquals(
            StatusKind.ERROR,
            ChannelStatusFormatter.kind(status, enabled = true, RuntimeActionStatus.RUNNING)
        )
    }

    @Test
    fun stoppedOwnerCannotDisplayPersistedExportAsActive() {
        val status = "exporting; pending: 42"

        assertEquals(
            "очікування",
            ChannelStatusFormatter.compactText(status, strings(UiLanguage.UK), RuntimeActionStatus.STOPPED)
        )
        assertEquals(
            StatusKind.WAITING,
            ChannelStatusFormatter.kind(status, enabled = true, RuntimeActionStatus.STOPPED)
        )
    }
}
