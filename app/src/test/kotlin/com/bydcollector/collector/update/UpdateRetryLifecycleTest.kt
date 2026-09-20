package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateRetryLifecycleTest {
    @Test
    fun admittedWakeClearsSuppressionAndBackoffThenStartsThirtySecondCycle() {
        var now = 1_000L
        val scheduler = scheduler { now }
        val policy = UpdateWakePolicy()
        policy.onEntry(now, interactive = true)
        scheduler.onRuntimeStarted(enabled = true)
        scheduler.onCheckStarted()
        scheduler.onCheckCompleted(UpdateCheckResult.Error("offline"), now, enabled = true)
        scheduler.onDismissed()

        policy.onSleep()
        now += 1L
        assertTrue(policy.onWake("android.intent.action.SCREEN_ON", now))
        scheduler.reset()

        assertEquals(UpdateAutoCheckAction.Schedule(30_000L), scheduler.onRuntimeStarted(enabled = true))
    }

    @Test
    fun sleepFencesAutomaticResultWithoutCancellingPhysicalRequest() {
        var now = 1_000L
        val tasks = ArrayDeque<() -> Unit>()
        val session = session(tasks, { UpdateCheckResult.Error("offline") }) { now }
        assertTrue(session.request(manual = false))
        val oldGeneration = session.snapshot().generation

        UpdateWakePolicy().apply { onEntry(now, interactive = true); onSleep() }
        session.invalidateAutomatic()
        assertTrue(session.snapshot().inFlight)
        tasks.removeFirst().invoke()

        assertEquals(UpdateUiState.Hidden, session.snapshot().uiState)
        assertTrue(session.snapshot().completion!!.generation == oldGeneration)
        assertTrue(session.snapshot().completion!!.generation < session.snapshot().generation)
    }

    @Test
    fun manualAndJoinedManualFlightsSurviveWakeAndSuppressNewAutomaticCycle() {
        listOf(false, true).forEach { joined ->
            var now = 1_000L
            val tasks = ArrayDeque<() -> Unit>()
            val session = session(tasks, { UpdateCheckResult.UpToDate }) { now }
            val policy = UpdateWakePolicy()
            val scheduler = scheduler { now }
            policy.onEntry(now, interactive = true)
            scheduler.onRuntimeStarted(enabled = true)

            assertTrue(session.request(manual = !joined))
            if (joined) assertFalse(session.request(manual = true))
            scheduler.onCheckStarted()
            assertTrue(session.hasCurrentManualRequest())
            policy.onSleep()
            now++
            assertTrue(policy.onWake("android.intent.action.SCREEN_ON", now))
            session.invalidateAutomatic()
            scheduler.reset()
            val action = if (session.hasCurrentManualRequest()) {
                UpdateAutoCheckAction.None
            } else {
                scheduler.onRuntimeStarted(enabled = true)
            }

            assertEquals(UpdateAutoCheckAction.None, action)
            assertEquals(1, tasks.size)
            assertIs<UpdateUiState.Checking>(session.snapshot().uiState)
            tasks.removeFirst().invoke()
            assertIs<UpdateUiState.UpToDate>(session.snapshot().uiState)
            val completion = session.snapshot().completion!!
            assertEquals(
                UpdateAutoCheckAction.None,
                scheduler.onCheckCompleted(completion.result, completion.completedAtElapsedMs, enabled = true)
            )
            assertEquals(UpdateAutoCheckAction.None, scheduler.onBackground(enabled = true))
        }
    }

    @Test
    fun oldBusyCompletionReleasesGateWithoutConsumingNewWakeDeadline() {
        var now = 1_000L
        var result: UpdateCheckResult = UpdateCheckResult.Error("old")
        val tasks = ArrayDeque<() -> Unit>()
        val session = session(tasks, { result }) { now }
        val scheduler = scheduler { now }
        val policy = UpdateWakePolicy()
        policy.onEntry(now, interactive = true)
        assertTrue(session.request(manual = false))

        policy.onSleep()
        now++
        assertTrue(policy.onWake("android.intent.action.SCREEN_ON", now))
        session.invalidateAutomatic()
        scheduler.reset()
        assertEquals(UpdateAutoCheckAction.Schedule(30_000L), scheduler.onRuntimeStarted(enabled = true))
        now += 30_000L
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = false))
        assertFalse(session.request(manual = false))

        tasks.removeFirst().invoke()
        assertTrue(session.snapshot().completion!!.generation < session.snapshot().generation)
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = false))
        result = UpdateCheckResult.UpToDate
        assertTrue(session.request(manual = false))
        scheduler.onCheckStarted()
        assertEquals(1, tasks.size)
    }

    @Test
    fun autoOffOnCannotResurrectCompletionFromOldFlight() {
        var now = 1_000L
        val tasks = ArrayDeque<() -> Unit>()
        val session = session(tasks, { UpdateCheckResult.Error("old") }) { now }
        val scheduler = scheduler { now }
        scheduler.onRuntimeStarted(enabled = true)
        assertTrue(session.request(manual = false))
        scheduler.onCheckStarted()

        assertEquals(UpdateAutoCheckAction.None, scheduler.onAutoCheckEnabledChanged(enabled = false))
        session.invalidateAutomatic()
        tasks.removeFirst().invoke()
        val completion = session.snapshot().completion!!
        assertTrue(completion.generation < session.snapshot().generation)

        now += 5_000L
        assertEquals(UpdateAutoCheckAction.Schedule(30_000L), scheduler.onAutoCheckEnabledChanged(enabled = true))
        assertEquals(UpdateAutoCheckAction.Schedule(30_000L), scheduler.onForeground(enabled = true))
    }

    @Test
    fun successfulHiddenAutomaticCheckDoesNotRearmFromUiLifecycle() {
        var now = 1_000L
        val tasks = ArrayDeque<() -> Unit>()
        val session = session(tasks, { UpdateCheckResult.UpToDate }) { now }
        val scheduler = scheduler { now }
        scheduler.onRuntimeStarted(enabled = true)
        now += 30_000L
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(enabled = true, foreground = false))
        assertTrue(session.request(manual = false))
        scheduler.onCheckStarted()
        tasks.removeFirst().invoke()
        val completion = session.snapshot().completion!!
        assertEquals(UpdateUiState.Hidden, session.snapshot().uiState)

        assertEquals(
            UpdateAutoCheckAction.None,
            scheduler.onCheckCompleted(completion.result, completion.completedAtElapsedMs, enabled = true)
        )
        assertEquals(UpdateAutoCheckAction.None, scheduler.onBackground(enabled = true))
        assertEquals(UpdateAutoCheckAction.None, scheduler.onForeground(enabled = true))
    }

    private fun scheduler(now: () -> Long) = UpdateAutoCheckScheduler(
        delayMs = 30_000L,
        nowMs = now
    )

    private fun session(
        tasks: ArrayDeque<() -> Unit>,
        checker: () -> UpdateCheckResult,
        now: () -> Long
    ) = UpdateCheckSession(
        dispatch = { tasks += it },
        checker = checker,
        elapsedRealtimeMs = now
    )
}
