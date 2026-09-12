package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateResultPresentationTest {
    private val info = UpdateInfo("2.8.0", "https://example.test/update.apk", "notes")
    private val gate = UpdateResultPresentation()

    @Test
    fun backgroundEnabledAvailableResultIsAcceptedOnlyOnce() {
        val snapshot = availableSnapshot(resultId = 1L)

        assertTrue(gate.accept(snapshot, ownUiVisible = false, hintEnabled = true))
        assertFalse(gate.accept(snapshot, ownUiVisible = false, hintEnabled = true))
    }

    @Test
    fun visibleUiConsumesResultWithoutReplayingAfterRecreationOrBackgrounding() {
        val snapshot = availableSnapshot(resultId = 1L)

        assertFalse(gate.accept(snapshot, ownUiVisible = true, hintEnabled = true))
        assertFalse(gate.accept(snapshot.copy(), ownUiVisible = true, hintEnabled = true))
        assertFalse(gate.accept(snapshot, ownUiVisible = false, hintEnabled = true))
    }

    @Test
    fun disabledHintConsumesResultWithoutReplayingWhenEnabled() {
        val snapshot = availableSnapshot(resultId = 1L)

        assertFalse(gate.accept(snapshot, ownUiVisible = false, hintEnabled = false))
        assertFalse(gate.accept(snapshot, ownUiVisible = false, hintEnabled = true))
    }

    @Test
    fun nextCompletedCheckOfSameVersionGetsItsOwnOpportunity() {
        assertTrue(gate.accept(availableSnapshot(resultId = 1L), ownUiVisible = false, hintEnabled = true))
        assertTrue(gate.accept(availableSnapshot(resultId = 2L), ownUiVisible = false, hintEnabled = true))
        assertFalse(gate.accept(availableSnapshot(resultId = 1L), ownUiVisible = false, hintEnabled = true))
    }

    @Test
    fun nonAvailableSnapshotDoesNotConsumeAResultId() {
        val hidden = UpdateCheckSession.Snapshot(
            uiState = UpdateUiState.Hidden,
            inFlight = false,
            revision = 1L,
            availableResultId = null
        )

        assertFalse(gate.accept(hidden, ownUiVisible = false, hintEnabled = true))
        assertTrue(gate.accept(availableSnapshot(resultId = 1L), ownUiVisible = false, hintEnabled = true))
    }

    @Test
    fun resetStartsANewProcessPresentationSession() {
        val snapshot = availableSnapshot(resultId = 1L)
        assertTrue(gate.accept(snapshot, ownUiVisible = false, hintEnabled = true))

        gate.reset()

        assertTrue(gate.accept(snapshot, ownUiVisible = false, hintEnabled = true))
    }

    private fun availableSnapshot(resultId: Long) = UpdateCheckSession.Snapshot(
        uiState = UpdateUiState.Available(info),
        inFlight = false,
        revision = resultId,
        availableResultId = resultId
    )
}
