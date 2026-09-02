package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        nowMs = 31_000L

        assertEquals(UpdateAutoCheckAction.None, scheduler.onTimerElapsed(enabled = true, foreground = false))
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onForeground(enabled = true))
    }

    @Test
    fun timerExpiryInForegroundRunsImmediately() {
        scheduler.onRuntimeStarted(enabled = true)
        nowMs = 31_000L

        assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = true))
    }

    @Test
    fun foregroundBeforeThirtySecondsDoesNotRunCheck() {
        scheduler.onRuntimeStarted(enabled = true)
        nowMs = 29_000L

        assertEquals(UpdateAutoCheckAction.Schedule(2_000L), scheduler.onForeground(enabled = true))
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

    @Test
    fun backgroundColdStartArmsThirtySecondDeadline() {
        val cold = UpdateAutoCheckScheduler(delayMs = 30_000L, nowMs = { nowMs })

        assertEquals(UpdateAutoCheckAction.Schedule(30_000L), cold.onBackground(enabled = true))
        nowMs = 31_000L
        assertEquals(UpdateAutoCheckAction.None, cold.onTimerElapsed(enabled = true, foreground = false))
        assertEquals(UpdateAutoCheckAction.Run, cold.onForeground(enabled = true))
    }

    @Test
    fun checkIsConsumedOnlyAfterAcceptedRequest() {
        scheduler.onRuntimeStarted(enabled = true)
        nowMs = 31_000L
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = true))
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onForeground(enabled = true))

        scheduler.onCheckStarted()
        assertEquals(UpdateAutoCheckAction.None, scheduler.onForeground(enabled = true))
    }

    @Test
    fun closeSuppressionUsesMonotonicTtlAndResetStartsNewRuntime() {
        scheduler.onRuntimeStarted(enabled = true)
        scheduler.onCheckStarted()
        scheduler.onDismissed()

        nowMs += 60 * 60 * 1000L - 1L
        assertEquals(UpdateAutoCheckAction.None, scheduler.onForeground(enabled = true))
        nowMs += 2L
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onForeground(enabled = true))

        scheduler.reset()
        assertEquals(UpdateAutoCheckAction.Schedule(30_000L), scheduler.onRuntimeStarted(enabled = true))
    }

    @Test
    fun disablingAndReenablingDoesNotClearCloseSuppression() {
        scheduler.onRuntimeStarted(enabled = true)
        scheduler.onCheckStarted()
        scheduler.onDismissed()
        scheduler.onAutoCheckEnabledChanged(enabled = false)
        assertEquals(UpdateAutoCheckAction.None, scheduler.onAutoCheckEnabledChanged(enabled = true))
    }

    @Test
    fun acceptedCheckArmsFreshCycleOnBackground() {
        scheduler.onRuntimeStarted(enabled = true)
        nowMs = 31_000L
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = true))
        scheduler.onCheckStarted()

        nowMs = 40_000L
        assertEquals(UpdateAutoCheckAction.Schedule(30_000L), scheduler.onBackground(enabled = true))
        nowMs = 45_000L
        assertEquals(UpdateAutoCheckAction.Schedule(25_000L), scheduler.onForeground(enabled = true))
    }

    @Test
    fun earlyTimerCallbackIsRescheduledAgainstFixedDeadline() {
        scheduler.onRuntimeStarted(enabled = true)
        nowMs = 5_000L
        assertEquals(UpdateAutoCheckAction.Schedule(26_000L), scheduler.onTimerElapsed(enabled = true, foreground = true))
    }

    @Test
    fun manualCheckDuringSuppressionPreservesPostTtlEligibility() {
        scheduler.onRuntimeStarted(enabled = true)
        scheduler.onCheckStarted()
        scheduler.onDismissed()

        nowMs += 30 * 60 * 1000L
        scheduler.onCheckStarted()
        assertEquals(UpdateAutoCheckAction.None, scheduler.onForeground(enabled = true))

        nowMs += 30 * 60 * 1000L
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onForeground(enabled = true))
    }

    @Test
    fun diagnosticStateContainsOnlySchedulerTimingFields() {
        scheduler.onRuntimeStarted(enabled = true)
        val state = scheduler.diagnosticState()
        assertTrue(state.contains("initialized=true"))
        assertTrue(state.contains("deadline_elapsed_ms="))
        assertTrue(state.contains("suppressed_until_elapsed_ms=null"))
    }
}
