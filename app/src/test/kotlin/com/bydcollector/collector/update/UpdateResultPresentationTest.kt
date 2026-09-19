package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateResultPresentationTest {
    private val info = UpdateInfo("2.8.0", "https://example.test/update.apk", "notes")
    private val gate = UpdateResultPresentation()

    @Test
    fun deniedPermissionAndFailedWindowKeepTheSameResultPendingUntilFirstDraw() {
        val snapshot = availableSnapshot(1L)
        repeat(3) { assertTrue(gate.canPresentHint(snapshot, false, true)) }
        assertTrue(gate.markPresented(snapshot, 1L))
        assertFalse(gate.markPresented(snapshot, 1L))
        assertFalse(gate.canPresentHint(snapshot, false, true))
    }

    @Test
    fun visibleUiWithoutAnActualOfferDoesNotConsumeButDrawDoes() {
        val snapshot = availableSnapshot(1L)
        assertFalse(gate.canPresentHint(snapshot, true, true))
        assertTrue(gate.canPresentHint(snapshot, false, true))
        assertTrue(gate.markPresented(snapshot, 1L))
        assertFalse(gate.canPresentHint(snapshot, false, true))
    }

    @Test
    fun enablingTheHintCanPresentAPreviouslyUndisplayedResult() {
        val snapshot = availableSnapshot(1L)
        assertFalse(gate.canPresentHint(snapshot, false, false))
        assertTrue(gate.canPresentHint(snapshot, false, true))
    }

    @Test
    fun staleDrawCannotConsumeANewerResultAndNextCheckHasItsOwnOpportunity() {
        val first = availableSnapshot(1L)
        val next = availableSnapshot(2L)
        assertFalse(gate.markPresented(next, 1L))
        assertTrue(gate.canPresentHint(next, false, true))
        assertTrue(gate.markPresented(next, 2L))
        assertFalse(gate.markPresented(first, 1L))
        assertFalse(gate.canPresentHint(first, false, true))
        assertTrue(gate.canPresentHint(availableSnapshot(3L), false, true))
    }

    @Test
    fun dismissedOrNonAvailableResultsCannotBeMarkedPresented() {
        val hidden = availableSnapshot(1L).copy(uiState = UpdateUiState.Hidden, availableResultId = null)
        assertFalse(gate.canPresentHint(hidden, false, true))
        assertFalse(gate.markPresented(hidden, 1L))
        assertTrue(gate.canPresentHint(availableSnapshot(1L), false, true))
    }

    @Test
    fun resetStartsANewProcessPresentationSession() {
        val snapshot = availableSnapshot(1L)
        assertTrue(gate.markPresented(snapshot, 1L))
        gate.reset()
        assertTrue(gate.canPresentHint(snapshot, false, true))
    }

    private fun availableSnapshot(resultId: Long) = UpdateCheckSession.Snapshot(
        uiState = UpdateUiState.Available(info), inFlight = false, revision = resultId, availableResultId = resultId
    )
}
