package com.bydcollector.collector.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import com.bydcollector.collector.data.callback.CallbackQueuePhase
import com.bydcollector.collector.data.callback.CallbackQueueState

class MainPollStatusFormatterTest {
    @Test
    fun queueEvidenceIsLocalizedAndNeverMasksStopOrFailure() {
        val catching = CallbackQueueState(CallbackQueuePhase.CATCHING_UP, "pending_head_age")
        for ((language, runningText, catchingText) in listOf(
            Triple(UiLanguage.UK, "Працює", "Обробка черги"), Triple(UiLanguage.EN, "Running", "Catching up"))) {
            val text = strings(language)
            assertEquals(MainPollDisplayStatus(catchingText, StatusKind.WARNING),
                MainPollStatusFormatter.format(true, "ok", text, catching))
            assertEquals(MainPollDisplayStatus(runningText, StatusKind.OK),
                MainPollStatusFormatter.queueStatus(CallbackQueueState(CallbackQueuePhase.HEALTHY, null), text))
            assertEquals(StatusKind.WAITING, MainPollStatusFormatter.format(false, "ok", text, catching).kind)
            assertEquals(StatusKind.ERROR, MainPollStatusFormatter.format(true, "error: storage", text, catching).kind)
            assertEquals(StatusKind.WAITING, MainPollStatusFormatter.queueStatus(
                CallbackQueueState(CallbackQueuePhase.WAITING, "no_progress"), text)?.kind)
        }
    }

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
