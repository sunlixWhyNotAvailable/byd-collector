package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UpdateCheckSessionTest {
    private val info = UpdateInfo("2.8.0", "https://example.test/update.apk", "notes")

    @Test
    fun availableResultIdIsStableForSnapshotAndFreshForNextEligibleCheck() {
        val tasks = ArrayDeque<() -> Unit>()
        val session = UpdateCheckSession(dispatch = { tasks += it }, checker = { UpdateCheckResult.Available(info) })

        assertTrue(session.request(manual = false))
        tasks.removeFirst().invoke()

        val first = session.snapshot()
        assertEquals(UpdateUiState.Available(info), first.uiState)
        assertNotNull(first.availableResultId)
        assertEquals(first.availableResultId, session.snapshot().availableResultId)
        assertTrue(session.request(manual = false))
        assertEquals(null, session.snapshot().availableResultId)
        tasks.removeFirst().invoke()
        val second = session.snapshot()
        assertEquals(UpdateUiState.Available(info), second.uiState)
        assertNotNull(second.availableResultId)
        assertTrue(second.availableResultId != first.availableResultId)
        var notified = 0
        session.addListener { notified++ }
        assertEquals(UpdateUiState.Available(info), session.snapshot().uiState)
        assertEquals(0, notified)
    }

    @Test
    fun manualStatesAreVisibleAndAutoNonAvailableStatesHidden() {
        val tasks = ArrayDeque<() -> Unit>()
        var result: UpdateCheckResult = UpdateCheckResult.UpToDate
        val session = UpdateCheckSession(dispatch = { tasks += it }, checker = { result })

        assertTrue(session.request(manual = true))
        assertIs<UpdateUiState.Checking>(session.snapshot().uiState)
        assertEquals(null, session.snapshot().availableResultId)
        tasks.removeFirst().invoke()
        assertIs<UpdateUiState.UpToDate>(session.snapshot().uiState)
        assertEquals(null, session.snapshot().availableResultId)

        result = UpdateCheckResult.Error("network")
        assertTrue(session.request(manual = false))
        assertEquals(UpdateUiState.Hidden, session.snapshot().uiState)
        tasks.removeFirst().invoke()
        assertEquals(UpdateUiState.Hidden, session.snapshot().uiState)
        assertEquals(null, session.snapshot().availableResultId)
    }

    @Test
    fun dismissDuringCheckKeepsBusyUntilSettledAndDoesNotReopenOffer() {
        val tasks = ArrayDeque<() -> Unit>()
        val session = UpdateCheckSession(dispatch = { tasks += it }, checker = { UpdateCheckResult.Available(info) })

        assertTrue(session.request(manual = true))
        assertFalse(session.dismiss())
        assertTrue(session.snapshot().inFlight)
        tasks.removeFirst().invoke()
        assertFalse(session.snapshot().inFlight)
        assertEquals(UpdateUiState.Hidden, session.snapshot().uiState)
    }

    @Test
    fun manualRequestJoinsAutomaticFlightWithoutSecondDispatch() {
        val tasks = ArrayDeque<() -> Unit>()
        val session = UpdateCheckSession(dispatch = { tasks += it }, checker = { UpdateCheckResult.UpToDate })

        assertTrue(session.request(manual = false))
        assertFalse(session.request(manual = true))
        assertEquals(1, tasks.size)
        assertIs<UpdateUiState.Checking>(session.snapshot().uiState)

        tasks.removeFirst().invoke()
        assertIs<UpdateUiState.UpToDate>(session.snapshot().uiState)
    }

    @Test
    fun resetInvalidatesOldResultButDoesNotOverlapPhysicalRequest() {
        val tasks = ArrayDeque<() -> Unit>()
        val session = UpdateCheckSession(dispatch = { tasks += it }, checker = { UpdateCheckResult.Available(info) })

        assertTrue(session.request(manual = false))
        session.reset()
        assertFalse(session.request(manual = true))
        assertEquals(UpdateUiState.Hidden, session.snapshot().uiState)
        tasks.removeFirst().invoke()
        assertFalse(session.snapshot().inFlight)
        assertEquals(UpdateUiState.Hidden, session.snapshot().uiState)

        assertTrue(session.request(manual = true))
        tasks.removeFirst().invoke()
        assertEquals(UpdateUiState.Available(info), session.snapshot().uiState)
    }

    @Test
    fun dispatchAndCheckerFailuresSettleAsManualError() {
        val dispatchFailure = UpdateCheckSession(
            dispatch = { error("executor stopped") },
            checker = { UpdateCheckResult.UpToDate }
        )
        assertTrue(dispatchFailure.request(manual = true))
        assertFalse(dispatchFailure.snapshot().inFlight)
        assertIs<UpdateUiState.Error>(dispatchFailure.snapshot().uiState)

        val checkerFailure = UpdateCheckSession(
            dispatch = { it() },
            checker = { error("broken") }
        )
        assertTrue(checkerFailure.request(manual = true))
        assertIs<UpdateUiState.Error>(checkerFailure.snapshot().uiState)
    }

    @Test
    fun removedListenerIsNotRetained() {
        val session = UpdateCheckSession(dispatch = { it() }, checker = { UpdateCheckResult.UpToDate })
        var calls = 0
        val listener: () -> Unit = { calls++ }
        session.addListener(listener)
        session.removeListener(listener)
        session.request(manual = true)
        assertEquals(0, calls)
    }

    @Test
    fun failedColdCheckRetriesOnReturnAndCachesResultCompletedWithoutUi() {
        var elapsedMs = 0L
        val scheduler = UpdateAutoCheckScheduler(30_000L, { elapsedMs })
        val tasks = ArrayDeque<() -> Unit>()
        var result: UpdateCheckResult = UpdateCheckResult.Error("offline")
        val session = UpdateCheckSession(dispatch = { tasks += it }, checker = { result })
        scheduler.onRuntimeStarted(enabled = true) // Background start: no Handler or HTTP.
        elapsedMs = 60_000L
        assertEquals(0, tasks.size)
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onForeground(enabled = true))
        assertTrue(session.request(manual = false))
        scheduler.onCheckStarted()
        tasks.removeFirst().invoke()
        assertEquals(UpdateUiState.Hidden, session.snapshot().uiState)

        scheduler.onBackground(enabled = true)
        elapsedMs += 5_000L
        assertEquals(UpdateAutoCheckAction.Schedule(25_000L), scheduler.onForeground(enabled = true))
        elapsedMs += 25_000L
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onForeground(enabled = true))
        assertTrue(session.request(manual = false))
        scheduler.onCheckStarted()
        result = UpdateCheckResult.Available(info)
        tasks.removeFirst().invoke() // UI may have disappeared; the process retains the offer.
        assertEquals(UpdateUiState.Available(info), session.snapshot().uiState)
        assertTrue(session.dismiss())
        assertEquals(null, session.snapshot().availableResultId)
        scheduler.onDismissed()
        elapsedMs += 3_599_999L
        assertEquals(UpdateAutoCheckAction.Schedule(1L), scheduler.onForeground(enabled = true))
        elapsedMs++
        assertEquals(UpdateAutoCheckAction.Run, scheduler.onForeground(enabled = true))
    }
}
