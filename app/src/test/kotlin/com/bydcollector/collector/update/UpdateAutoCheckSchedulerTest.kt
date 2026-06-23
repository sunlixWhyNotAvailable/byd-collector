package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateAutoCheckSchedulerTest {
    private var nowMs = 1_000L
    private val scheduler = UpdateAutoCheckScheduler(delayMs = 30_000L, nowMs = { nowMs })

    @Test
    fun runtimeStartSchedulesTimerBeforeForegroundMatters() {
        assertEquals(UpdateAutoCheckAction.Schedule(30_000L), scheduler.onRuntimeStarted(enabled = true))
    }

    @Test
    fun repeatedRuntimeStartSchedulesOnlyRemainingDelay() {
        scheduler.onRuntimeStarted(enabled = true)
        nowMs = 11_000L

        assertEquals(UpdateAutoCheckAction.Schedule(20_000L), scheduler.onRuntimeStarted(enabled = true))
    }

    @Test
    fun timerExpiryInBackgroundDefersCheckUntilForegroundResume() {
        scheduler.onRuntimeStarted(enabled = true)

        assertEquals(UpdateAutoCheckAction.None, scheduler.onTimerElapsed(enabled = true, foreground = false))
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onForeground(enabled = true))
    }

    @Test
    fun timerExpiryInForegroundRunsImmediately() {
        scheduler.onRuntimeStarted(enabled = true)

        assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = true))
    }

    @Test
    fun foregroundBeforeThirtySecondsDoesNotRunCheck() {
        scheduler.onRuntimeStarted(enabled = true)
        nowMs = 29_000L

        assertEquals(UpdateAutoCheckAction.None, scheduler.onForeground(enabled = true))
    }

    @Test
    fun foregroundAfterElapsedRuntimeRunsEvenIfDelayedMessageWasDeferred() {
        scheduler.onRuntimeStarted(enabled = true)
        nowMs = 31_001L

        assertEquals(UpdateAutoCheckAction.Run, scheduler.onForeground(enabled = true))
    }

    @Test
    fun disablingAutoCheckClearsPendingWork() {
        scheduler.onRuntimeStarted(enabled = true)
        scheduler.onTimerElapsed(enabled = true, foreground = false)

        assertEquals(UpdateAutoCheckAction.None, scheduler.onAutoCheckEnabledChanged(enabled = false))
        assertEquals(UpdateAutoCheckAction.None, scheduler.onForeground(enabled = true))
    }
}
