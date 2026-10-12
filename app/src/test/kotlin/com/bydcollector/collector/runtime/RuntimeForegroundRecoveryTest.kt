package com.bydcollector.collector.runtime

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeForegroundRecoveryTest {
    @Test fun unavailableOwnerUsesBoundedForegroundRetriesAndThenFails() {
        val harness = Harness()
        harness.gate.setEligible(true)
        assertEquals(1, harness.probes.size)
        harness.probes.removeAt(0)(RuntimePreflightResult.OWNER_UNAVAILABLE)
        assertEquals(RuntimeRecoveryStatus.RETRYING, harness.statuses.last())
        assertEquals(1_000L, harness.scheduled.single().second)

        harness.runNextRetry()
        harness.probes.removeAt(0)(RuntimePreflightResult.OWNER_UNAVAILABLE)
        assertEquals(3_000L, harness.scheduled.single().second)

        harness.runNextRetry()
        harness.probes.removeAt(0)(RuntimePreflightResult.OWNER_UNAVAILABLE)
        assertEquals(RuntimeRecoveryStatus.FAILED, harness.statuses.last())
        assertTrue(harness.scheduled.isEmpty())
        assertEquals(3, harness.probeCount)
    }

    @Test fun backgroundCancelsRetryAndIgnoresLateProbeBeforeNextForegroundCheck() {
        val harness = Harness()
        harness.gate.setEligible(true)
        val staleProbe = harness.probes.removeAt(0)
        staleProbe(RuntimePreflightResult.OWNER_UNAVAILABLE)
        assertEquals(1, harness.scheduled.size)

        harness.gate.setEligible(false)
        assertTrue(harness.scheduled.isEmpty())
        val lastStatusBeforeLateResult = harness.statuses.last()
        staleProbe(RuntimePreflightResult.COMPATIBLE)
        assertEquals(lastStatusBeforeLateResult, harness.statuses.last())

        harness.gate.setEligible(true)
        assertEquals(1, harness.probes.size)
        harness.probes.removeAt(0)(RuntimePreflightResult.COMPATIBLE)
        assertEquals(RuntimeRecoveryStatus.READY, harness.statuses.last())
    }

    @Test fun focusLossCancelsRetryWithoutResettingEpisodeBudgetButPauseResetsIt() {
        val harness = Harness()
        harness.gate.setEligible(true)
        harness.probes.removeAt(0)(RuntimePreflightResult.OWNER_UNAVAILABLE)
        assertEquals(1_000L, harness.scheduled.single().second)

        harness.gate.setEligible(false)
        assertTrue(harness.scheduled.isEmpty())
        harness.gate.setEligible(true)
        harness.probes.removeAt(0)(RuntimePreflightResult.OWNER_UNAVAILABLE)
        assertEquals(3_000L, harness.scheduled.single().second)

        harness.gate.endForeground()
        assertTrue(harness.scheduled.isEmpty())
        harness.gate.setEligible(true)
        harness.probes.removeAt(0)(RuntimePreflightResult.OWNER_UNAVAILABLE)
        assertEquals(1_000L, harness.scheduled.single().second)
    }

    @Test fun mismatchBootstrapsOncePerRecoveryEpisodeAndManualRetryStartsANewEpisode() {
        val harness = Harness()
        harness.gate.setEligible(true)
        harness.probes.removeAt(0)(RuntimePreflightResult.CLIENT_MISMATCH)
        assertEquals(1, harness.ownerLaunches)
        assertEquals(RuntimeRecoveryStatus.BOOTSTRAPPING, harness.statuses.last())

        harness.gate.setEligible(false)
        harness.gate.setEligible(true)
        assertEquals(1, harness.ownerLaunches)
        assertTrue(harness.probes.isEmpty())
        assertEquals(RuntimeRecoveryStatus.FAILED, harness.statuses.last())

        harness.gate.retry()
        assertEquals(1, harness.probes.size)
        harness.probes.removeAt(0)(RuntimePreflightResult.CLIENT_MISMATCH)
        assertEquals(2, harness.ownerLaunches)
        assertEquals(RuntimeRecoveryStatus.BOOTSTRAPPING, harness.statuses.last())
        assertTrue(harness.scheduled.isEmpty())
    }

    @Test fun modalFocusLossRetainsReadyOwnerButPauseRequiresANewPreflight() {
        val harness = Harness()
        harness.gate.setEligible(true)
        harness.probes.removeAt(0)(RuntimePreflightResult.COMPATIBLE)
        assertEquals(RuntimeRecoveryStatus.READY, harness.statuses.last())

        harness.gate.setEligible(false)
        assertEquals(RuntimeRecoveryStatus.READY, harness.statuses.last())
        harness.gate.setEligible(true)
        assertTrue(harness.probes.isEmpty())
        assertEquals(RuntimeRecoveryStatus.READY, harness.statuses.last())

        harness.gate.endForeground()
        assertEquals(RuntimeRecoveryStatus.WAITING, harness.statuses.last())
        harness.gate.setEligible(true)
        assertEquals(1, harness.probes.size)
        harness.probes.removeAt(0)(RuntimePreflightResult.COMPATIBLE)
        assertEquals(RuntimeRecoveryStatus.READY, harness.statuses.last())
    }

    @Test fun focusedOwnedDialogRecoversAfterPauseButExternalFocusDoesNot() {
        val harness = Harness()
        harness.gate.endForeground()

        // The Activity window can lose focus to its own native Dialog; that Dialog may recover
        // only while the Activity is resumed and the Dialog window itself is actually focused.
        val cameraOwnsFocus = runtimeRecoveryEligible(
            activityResumed = true,
            activityWindowFocused = false,
            ownedDialogWindowFocused = false
        )
        val cameraActivityIsPaused = runtimeRecoveryEligible(
            activityResumed = false,
            activityWindowFocused = false,
            ownedDialogWindowFocused = true
        )
        assertFalse(cameraOwnsFocus)
        assertFalse(cameraActivityIsPaused)
        harness.gate.setEligible(cameraOwnsFocus || cameraActivityIsPaused)
        assertTrue(harness.probes.isEmpty())

        val returnedToFocusedOwnedDialog = runtimeRecoveryEligible(
            activityResumed = true,
            activityWindowFocused = false,
            ownedDialogWindowFocused = true
        )
        assertTrue(returnedToFocusedOwnedDialog)
        harness.gate.setEligible(returnedToFocusedOwnedDialog)
        assertEquals(1, harness.probes.size)
        harness.probes.removeAt(0)(RuntimePreflightResult.COMPATIBLE)
        assertEquals(RuntimeRecoveryStatus.READY, harness.statuses.last())
    }

    @Test fun knownPreflightErrorsAreClassifiedAndSecurityAlwaysFailsClosed() {
        assertEquals(RuntimePreflightResult.CLIENT_MISMATCH,
            classifyRuntimePreflightFailure(IllegalStateException("wrapper", IllegalArgumentException("Runtime protocol mismatch"))))
        assertEquals(RuntimePreflightResult.OWNER_UNAVAILABLE,
            classifyRuntimePreflightFailure(IllegalStateException("Collector runtime is not installed")))
        assertEquals(RuntimePreflightResult.REJECTED,
            classifyRuntimePreflightFailure(IllegalStateException("Runtime protocol mismatch", SecurityException("signature rejected"))))
        assertEquals(RuntimePreflightResult.REJECTED,
            classifyRuntimePreflightFailure(IllegalStateException("unexpected owner failure")))
    }

    @Test fun coldLoadFailureDuringFocusLossInvalidatesReadyForReturnPreflight() {
        val harness = Harness()
        harness.gate.setEligible(true)
        harness.probes.removeAt(0)(RuntimePreflightResult.COMPATIBLE)
        harness.gate.setEligible(false)
        assertEquals(RuntimeRecoveryStatus.READY, harness.statuses.last())

        harness.gate.failCurrentRecovery()
        assertEquals(RuntimeRecoveryStatus.WAITING, harness.statuses.last())
        harness.gate.setEligible(true)
        assertEquals(1, harness.probes.size)
        harness.probes.removeAt(0)(RuntimePreflightResult.COMPATIBLE)
        assertEquals(RuntimeRecoveryStatus.READY, harness.statuses.last())
    }

    private class Harness {
        val probes = mutableListOf<(RuntimePreflightResult) -> Unit>()
        val statuses = mutableListOf<RuntimeRecoveryStatus>()
        val scheduled = mutableListOf<Pair<Runnable, Long>>()
        var ownerLaunches = 0
        var probeCount = 0
        val gate = RuntimeForegroundRecovery(
            schedule = { task, delay -> scheduled += task to delay },
            cancel = { task -> scheduled.removeAll { it.first === task } },
            probe = { callback -> probeCount++; probes += callback },
            onStatusChanged = statuses::add,
            onClientMismatch = { ownerLaunches++ }
        )

        fun runNextRetry() {
            val task = scheduled.removeAt(0).first
            task.run()
        }
    }
}
