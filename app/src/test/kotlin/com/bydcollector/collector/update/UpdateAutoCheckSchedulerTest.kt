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
    fun timerExpiryInBackgroundRunsWithoutActivityGate() {
        scheduler.onRuntimeStarted(enabled = true)
        nowMs = 31_000L

        assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = false))
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
        assertEquals(UpdateAutoCheckAction.Run, cold.onTimerElapsed(enabled = true, foreground = false))
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
        assertEquals(UpdateAutoCheckAction.Schedule(1L), scheduler.onForeground(enabled = true))
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
        assertEquals(
            UpdateAutoCheckAction.Schedule(60 * 60 * 1000L),
            scheduler.onAutoCheckEnabledChanged(enabled = true)
        )
    }

    @Test
    fun acceptedCheckDoesNotRearmFromBackgroundLifecycle() {
        scheduler.onRuntimeStarted(enabled = true)
        nowMs = 31_000L
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = true))
        scheduler.onCheckStarted()

        nowMs = 40_000L
        assertEquals(UpdateAutoCheckAction.None, scheduler.onBackground(enabled = true))
        nowMs = 45_000L
        assertEquals(UpdateAutoCheckAction.None, scheduler.onForeground(enabled = true))
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
        assertEquals(UpdateAutoCheckAction.Schedule(30 * 60 * 1000L), scheduler.onForeground(enabled = true))

        nowMs += 30 * 60 * 1000L
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onForeground(enabled = true))
    }

    @Test
    fun suppressionTimerResumesAutomaticCheckInBackgroundAtTtl() {
        scheduler.onRuntimeStarted(enabled = true)
        scheduler.onCheckStarted()
        scheduler.onDismissed()

        assertEquals(
            UpdateAutoCheckAction.Schedule(60 * 60 * 1000L),
            scheduler.onBackground(enabled = true)
        )
        nowMs += 60 * 60 * 1000L
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = false))
    }

    @Test
    fun diagnosticStateContainsOnlySchedulerTimingFields() {
        scheduler.onRuntimeStarted(enabled = true)
        val state = scheduler.diagnosticState()
        assertTrue(state.contains("initialized=true"))
        assertTrue(state.contains("deadline_elapsed_ms="))
        assertTrue(state.contains("suppressed_until_elapsed_ms=null"))
        assertTrue(state.contains("retry_attempt=0"))
    }

    @Test
    fun errorsRetryFromActualCompletionWithBoundedBackoff() {
        scheduler.onRuntimeStarted(enabled = true)
        nowMs = 31_000L
        scheduler.onCheckStarted()

        val expectedDelays = listOf(30_000L, 60_000L, 120_000L, 300_000L, 300_000L)
        expectedDelays.forEachIndexed { index, delay ->
            val completedAt = 100_000L + index * 1_000_000L
            nowMs = completedAt + 7_000L
            assertEquals(
                UpdateAutoCheckAction.Schedule(delay - 7_000L),
                scheduler.onCheckCompleted(UpdateCheckResult.Error("offline"), completedAt, enabled = true)
            )
            assertEquals(UpdateAutoCheckAction.Schedule(delay - 7_000L), scheduler.onBackground(enabled = true))
            assertEquals(UpdateAutoCheckAction.Schedule(delay - 7_000L), scheduler.onRuntimeStarted(enabled = true))
            nowMs = completedAt + delay
            assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = false))
            scheduler.onCheckStarted()
        }
    }

    @Test
    fun eitherSuccessfulResultStopsRetriesAcrossLifecycleCallbacks() {
        listOf<UpdateCheckResult>(UpdateCheckResult.UpToDate, UpdateCheckResult.Available(
            UpdateInfo("2.8.2", "https://example.test/update.apk", "notes")
        )).forEach { result ->
            val current = UpdateAutoCheckScheduler(delayMs = 30_000L, nowMs = { nowMs })
            current.onRuntimeStarted(enabled = true)
            current.onCheckStarted()
            assertEquals(UpdateAutoCheckAction.None, current.onCheckCompleted(result, nowMs, enabled = true))
            assertEquals(UpdateAutoCheckAction.None, current.onForeground(enabled = true))
            assertEquals(UpdateAutoCheckAction.None, current.onBackground(enabled = true))
            assertEquals(UpdateAutoCheckAction.None, current.onRuntimeStarted(enabled = true))
        }
    }

    @Test
    fun disabledCompletionCannotArmRetryAndReenableStartsFreshDelay() {
        scheduler.onRuntimeStarted(enabled = true)
        scheduler.onCheckStarted()
        assertEquals(UpdateAutoCheckAction.None, scheduler.onAutoCheckEnabledChanged(enabled = false))
        nowMs = 50_000L
        assertEquals(
            UpdateAutoCheckAction.None,
            scheduler.onCheckCompleted(UpdateCheckResult.Error("offline"), nowMs, enabled = false)
        )
        assertEquals(UpdateAutoCheckAction.Schedule(30_000L), scheduler.onAutoCheckEnabledChanged(enabled = true))
    }

    @Test
    fun successfulManualCheckDuringSuppressionKeepsPostTtlEligibility() {
        scheduler.onRuntimeStarted(enabled = true)
        scheduler.onCheckStarted()
        scheduler.onDismissed()
        nowMs += 10_000L
        scheduler.onCheckStarted()

        assertEquals(
            UpdateAutoCheckAction.Schedule(60 * 60 * 1000L - 10_000L),
            scheduler.onCheckCompleted(UpdateCheckResult.UpToDate, nowMs, enabled = true)
        )
        nowMs += 60 * 60 * 1000L - 10_000L
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = false))
    }
}
