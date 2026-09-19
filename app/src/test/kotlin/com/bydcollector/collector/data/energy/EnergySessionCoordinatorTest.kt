package com.bydcollector.collector.data.energy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnergySessionCoordinatorTest {
    @Test
    fun startsPartialSessionFromOpenTripAndPersistsPendingAtomically() {
        val storage = FakeStorage()
        val coordinator = EnergySessionCoordinator(storage) {
            EnergySessionSeed("existing-trip", "2026-09-14T10:00:00Z")
        }

        val result = coordinator.process(receipt("source-1", "boot-a", 1_000L))

        val snapshot = assertNotNull(result.snapshot)
        assertEquals("existing-trip", snapshot.powerSessionId)
        assertEquals("2026-09-14T10:00:00Z", snapshot.startedAt)
        assertTrue(snapshot.active)
        assertTrue(snapshot.energyPartial)
        assertNull(snapshot.dischargedKwh)
        assertEquals(EnergySessionTransition.STARTED, result.transition)
        assertEquals(snapshot, assertNotNull(coordinator.pendingProjection()).snapshot)
        assertEquals(1, storage.commits)
    }

    @Test
    fun pendingProjectionBlocksNewerSourceAndCasClearPreservesWrongId() {
        val coordinator = EnergySessionCoordinator(FakeStorage())
        val first = coordinator.process(receipt("source-1", "boot-a", 1_000L))

        assertFailsWith<EnergyProjectionPendingException> {
            coordinator.process(receipt("source-2", "boot-a", 2_000L))
        }
        assertFalse(coordinator.confirmProjected("not-the-snapshot"))
        assertNotNull(coordinator.pendingProjection())
        assertTrue(coordinator.confirmProjected(assertNotNull(first.snapshot).snapshotId))
        assertNull(coordinator.pendingProjection())
        assertTrue(coordinator.confirmProjected(first.snapshot!!.snapshotId))
        assertFalse(coordinator.confirmProjected("superseded-snapshot"))
    }

    @Test
    fun exactReplayNeverReintegratesAndRestagesAfterAckLoss() {
        val coordinator = EnergySessionCoordinator(FakeStorage())
        val input = receipt("source-1", "boot-a", 1_000L)
        val first = coordinator.process(input)
        val whilePending = coordinator.process(input)
        assertTrue(whilePending.duplicate)
        assertEquals(first.snapshot, whilePending.snapshot)

        assertTrue(coordinator.confirmProjected(assertNotNull(first.snapshot).snapshotId))
        val afterClear = coordinator.process(input)

        assertTrue(afterClear.duplicate)
        assertEquals(first.snapshot, afterClear.snapshot)
        assertEquals(first.pendingProjection, afterClear.pendingProjection)
    }

    @Test
    fun sameBootOlderDifferentIdentityIsAckableWithoutProjectionOrHighWaterChange() {
        val coordinator = EnergySessionCoordinator(FakeStorage())
        val latest = coordinator.process(receipt("source-2", "boot-a", 2_000L))
        coordinator.confirmProjected(assertNotNull(latest.snapshot).snapshotId)

        val stale = coordinator.process(receipt("source-1", "boot-a", 1_000L))

        assertTrue(stale.stale)
        assertFalse(stale.duplicate)
        assertNull(stale.pendingProjection)
        assertEquals(latest.snapshot, stale.snapshot)
        assertNull(coordinator.pendingProjection())
        assertEquals("source-2", coordinator.currentSnapshot()?.sourceIdentity)
    }

    @Test
    fun activeSessionSurvivesBootChangeAndMeasuredValuesAppearOnlyAfterCoverage() {
        val coordinator = EnergySessionCoordinator(FakeStorage())
        val first = coordinator.process(receipt("a-1", "boot-a", 1_000L))
        coordinator.confirmProjected(assertNotNull(first.snapshot).snapshotId)
        val covered = coordinator.process(receipt("a-2", "boot-a", 2_000L))
        val coveredSnapshot = assertNotNull(covered.snapshot)
        assertNotNull(coveredSnapshot.dischargedKwh)
        val sessionId = coveredSnapshot.powerSessionId
        coordinator.confirmProjected(coveredSnapshot.snapshotId)

        val rebooted = coordinator.process(receipt("b-1", "boot-b", 100L))

        val rebootedSnapshot = assertNotNull(rebooted.snapshot)
        assertEquals(sessionId, rebootedSnapshot.powerSessionId)
        assertTrue(rebootedSnapshot.active)
        assertTrue(rebootedSnapshot.energyPartial)
        assertEquals(EnergyIntegrationReason.BOOT_CHANGED.name.lowercase(), rebootedSnapshot.reason)
    }

    @Test
    fun currentSnapshotCanBeRestagedForFreshMain() {
        val coordinator = EnergySessionCoordinator(FakeStorage())
        val first = coordinator.process(receipt("source-1", "boot-a", 1_000L))
        coordinator.confirmProjected(assertNotNull(first.snapshot).snapshotId)

        val staged = assertNotNull(coordinator.stageCurrentProjection())

        assertEquals(first.snapshot, staged.snapshot)
        assertEquals(staged, coordinator.pendingProjection())
    }

    @Test
    fun inactiveReceiptsAdvanceSourceCursorWithoutReprojectingFinishedSnapshot() {
        val coordinator = EnergySessionCoordinator(FakeStorage())
        val started = coordinator.process(receipt("on-1", "boot-a", 1_000L))
        coordinator.confirmProjected(assertNotNull(started.snapshot).snapshotId)
        val stopped = coordinator.process(receipt("off-1", "boot-a", 2_000L, powerOn = false))
        assertEquals(1_000L, assertNotNull(stopped.snapshot).energyCoveredMs)
        assertTrue(stopped.snapshot!!.energyPartial)
        coordinator.confirmProjected(assertNotNull(stopped.snapshot).snapshotId)

        val repeatedOff = coordinator.process(receipt("off-2", "boot-a", 3_000L, powerOn = false))

        assertNull(repeatedOff.pendingProjection)
        assertEquals(stopped.snapshot, repeatedOff.snapshot)
        assertEquals("off-1", repeatedOff.snapshot?.sourceIdentity)
        assertNull(coordinator.pendingProjection())

        val ackReplay = coordinator.process(receipt("off-2", "boot-a", 3_000L, powerOn = false))
        assertTrue(ackReplay.duplicate)
        assertNull(ackReplay.pendingProjection)
        assertEquals("off-1", ackReplay.snapshot?.sourceIdentity)
    }

    @Test
    fun inactiveUnknownPreservesConfirmedOffSoNextOnStartsAtKnownBoundary() {
        val storage = FakeStorage()
        val coordinator = EnergySessionCoordinator(storage)
        val started = coordinator.process(receipt("on-1", "boot-a", 1_000L))
        coordinator.confirmProjected(assertNotNull(started.snapshot).snapshotId)
        val stopped = coordinator.process(receipt("off-1", "boot-a", 2_000L, powerOn = false))
        coordinator.confirmProjected(assertNotNull(stopped.snapshot).snapshotId)

        val unknown = coordinator.process(receipt("unknown", "boot-a", 3_000L, powerOn = null))
        assertNull(unknown.pendingProjection)
        val restarted = coordinator.process(receipt("on-2", "boot-a", 4_000L))

        assertEquals(EnergySessionTransition.STARTED, restarted.transition)
        assertFalse(assertNotNull(restarted.snapshot).energyPartial)
        assertEquals("energy:on-2", restarted.snapshot!!.powerSessionId)
    }

    @Test
    fun unknownInsideActiveSessionBreaksAnchorAndOffCannotInventClosingCoverage() {
        val coordinator = EnergySessionCoordinator(FakeStorage())
        val started = coordinator.process(receipt("on-1", "boot-a", 1_000L))
        coordinator.confirmProjected(assertNotNull(started.snapshot).snapshotId)
        val unknown = coordinator.process(receipt("unknown", "boot-a", 1_500L, powerOn = null))
        coordinator.confirmProjected(assertNotNull(unknown.snapshot).snapshotId)

        val stopped = coordinator.process(receipt("off", "boot-a", 2_000L, powerOn = false))

        assertEquals(0L, assertNotNull(stopped.snapshot).energyCoveredMs)
        assertEquals(500L, stopped.snapshot!!.energyUncoveredMs)
        assertTrue(stopped.snapshot!!.energyPartial)
        assertEquals(EnergyIntegrationReason.INVALID_SAMPLE.name.lowercase(), stopped.snapshot!!.reason)
    }

    @Test
    fun validCheckpointWithMalformedPendingIsQuarantinedAndProjectionIsRegenerated() {
        val storage = FakeStorage()
        val original = EnergySessionCoordinator(storage).process(receipt("source-1", "boot-a", 1_000L))
        val poisoned = assertNotNull(storage.row).copy(pendingProjectionJson = "not-json")
        storage.row = poisoned

        val recovered = assertNotNull(EnergySessionCoordinator(storage).pendingProjection())

        assertEquals(original.snapshot, recovered.snapshot)
        assertEquals(poisoned, storage.quarantined.single().first)
        assertEquals("invalid_or_mismatched_pending_projection", storage.quarantined.single().second)
    }

    @Test
    fun validCheckpointWithMismatchedPendingRegeneratesCheckpointProjection() {
        val storage = FakeStorage()
        val original = EnergySessionCoordinator(storage).process(receipt("source-1", "boot-a", 1_000L))
        val wrong = assertNotNull(original.pendingProjection).copy(
            snapshot = assertNotNull(original.snapshot).copy(
                snapshotId = "energy:wrong-source",
                sourceIdentity = "wrong-source"
            )
        )
        storage.row = assertNotNull(storage.row).copy(
            pendingProjectionJson = EnergyStateCodec.encodeProjection(wrong)
        )

        val recovered = assertNotNull(EnergySessionCoordinator(storage).pendingProjection())

        assertEquals(original.snapshot, recovered.snapshot)
        assertEquals(1, storage.quarantined.size)
    }

    @Test
    fun invalidCheckpointReconstructsTotalsAndCursorFromValidatedPendingWithoutAnchor() {
        val storage = FakeStorage()
        val coordinator = EnergySessionCoordinator(storage)
        val first = coordinator.process(receipt("source-1", "boot-a", 1_000L))
        coordinator.confirmProjected(assertNotNull(first.snapshot).snapshotId)
        val measured = coordinator.process(receipt("source-2", "boot-a", 2_000L))
        storage.row = assertNotNull(storage.row).copy(stateJson = "broken-checkpoint")

        val recovered = assertNotNull(EnergySessionCoordinator(storage).pendingProjection())
        val state = EnergyStateCodec.decodeState(assertNotNull(storage.row).stateJson)

        assertEquals(measured.snapshot?.sourceIdentity, recovered.snapshot.sourceIdentity)
        assertEquals(measured.snapshot?.dischargedKwh, recovered.snapshot.dischargedKwh)
        assertTrue(recovered.snapshot.energyPartial)
        assertEquals(EnergyIntegrationQuality.PARTIAL, recovered.snapshot.integrationQuality)
        assertEquals(EnergyRuntimeState.REASON_RECOVERED_PENDING, recovered.snapshot.reason)
        assertEquals(measured.snapshot?.sourceIdentity, state.lastSourceIdentity)
        assertEquals(measured.snapshot?.dischargedKwh, state.totals.dischargedKwh)
        assertEquals(measured.snapshot?.regeneratedKwh, state.totals.regeneratedKwh)
        assertTrue(state.totals.partial)
        assertNull(state.anchor)
        assertEquals(EnergyRuntimeState.REASON_RECOVERED_PENDING, state.reason)
        assertEquals(recovered, EnergyStateCodec.decodeProjection(assertNotNull(storage.row).pendingProjectionJson!!))
        assertEquals("invalid_checkpoint_recovered_from_pending", storage.quarantined.single().second)
    }

    @Test
    fun fullyInvalidRuntimeStaysUnavailableUntilConfirmedOffThenOn() {
        val poisoned = EnergyRuntimeRow("bad-state", "bad-pending", "now")
        val storage = FakeStorage(poisoned)
        val coordinator = EnergySessionCoordinator(storage)

        assertNull(coordinator.currentSnapshot())
        assertEquals(poisoned, storage.quarantined.single().first)
        assertEquals(EnergyRecoveryState.WAITING_FOR_OFF, decodedState(storage).recoveryState)

        val unknownSessionOn = coordinator.process(receipt("on-unknown", "boot-a", 1_000L, powerOn = true))
        assertNull(unknownSessionOn.snapshot)
        assertEquals(EnergyRecoveryState.WAITING_FOR_OFF, decodedState(storage).recoveryState)
        assertTrue(coordinator.process(receipt("on-unknown", "boot-a", 1_000L, powerOn = true)).duplicate)

        val confirmedOff = coordinator.process(receipt("off", "boot-a", 2_000L, powerOn = false))
        assertNull(confirmedOff.snapshot)
        assertEquals(EnergyRecoveryState.WAITING_FOR_ON, decodedState(storage).recoveryState)

        val staleOn = coordinator.process(receipt("stale-on", "boot-a", 1_500L, powerOn = true))
        assertTrue(staleOn.stale)
        assertEquals(EnergyRecoveryState.WAITING_FOR_ON, decodedState(storage).recoveryState)

        val restarted = coordinator.process(receipt("on-new", "boot-a", 3_000L, powerOn = true))
        assertEquals(EnergySessionTransition.STARTED, restarted.transition)
        assertNotNull(restarted.snapshot)
        assertFalse(assertNotNull(restarted.snapshot).energyPartial)
        assertEquals(EnergyRecoveryState.NONE, decodedState(storage).recoveryState)
    }

    @Test
    fun quarantineMustSucceedBeforePoisonedRowCanBeReplaced() {
        val poisoned = EnergyRuntimeRow("bad-state", null, "now")
        val storage = FakeStorage(poisoned).apply { quarantineFailure = Exception("checked injection") }

        assertFailsWith<Exception> { EnergySessionCoordinator(storage).currentSnapshot() }
        assertEquals(poisoned, storage.row)
        assertEquals(0, storage.commits)
    }

    @Test
    fun quarantinePreservesInterruptionAndErrors() {
        val interruptedStorage = FakeStorage(EnergyRuntimeRow("bad", null, "now")).apply {
            quarantineFailure = InterruptedException("cancel")
        }
        assertFailsWith<InterruptedException> { EnergySessionCoordinator(interruptedStorage).currentSnapshot() }

        val errorStorage = FakeStorage(EnergyRuntimeRow("bad", null, "now")).apply {
            quarantineFailure = AssertionError("fatal")
        }
        assertFailsWith<AssertionError> { EnergySessionCoordinator(errorStorage).currentSnapshot() }
    }

    @Test
    fun storageFailureCannotPublishHalfCheckpoint() {
        val storage = FakeStorage().apply { failCommit = true }
        val coordinator = EnergySessionCoordinator(storage)

        assertFailsWith<IllegalStateException> {
            coordinator.process(receipt("source-1", "boot-a", 1_000L))
        }
        assertNull(storage.row)
    }

    private fun receipt(identity: String, boot: String, elapsed: Long, powerOn: Boolean? = true) = EnergyReceipt(
        sourceIdentity = identity,
        observedAt = "2026-09-14T10:00:${elapsed / 1_000L}Z",
        input = EnergyInput(
            bootId = boot,
            elapsedMs = elapsed,
            voltage = 400.0,
            current = 90.0,
            powerOn = powerOn,
            gunDisconnected = true,
            externalCharging = false
        )
    )

    private class FakeStorage(initial: EnergyRuntimeRow? = null) : EnergyRuntimeStorage {
        var row: EnergyRuntimeRow? = initial
        var commits = 0
        var failCommit = false
        var quarantineFailure: Throwable? = null
        val quarantined = mutableListOf<Pair<EnergyRuntimeRow, String>>()

        override fun readEnergyRuntimeRow(): EnergyRuntimeRow? = row

        override fun commitEnergyRuntimeRow(stateJson: String, pendingProjectionJson: String?, updatedAt: String) {
            if (failCommit) throw IllegalStateException("injected")
            row = EnergyRuntimeRow(stateJson, pendingProjectionJson, updatedAt)
            commits++
        }

        override fun clearEnergyPending(expectedPendingJson: String, updatedAt: String): Boolean {
            val current = row ?: return false
            if (current.pendingProjectionJson != expectedPendingJson) return false
            row = current.copy(pendingProjectionJson = null, updatedAt = updatedAt)
            return true
        }

        override fun quarantineEnergyRuntimeRow(row: EnergyRuntimeRow, reason: String, updatedAt: String) {
            quarantineFailure?.let { throw it }
            quarantined += row to reason
        }
    }

    private fun decodedState(storage: FakeStorage): EnergyRuntimeState =
        EnergyStateCodec.decodeState(assertNotNull(storage.row).stateJson)
}
