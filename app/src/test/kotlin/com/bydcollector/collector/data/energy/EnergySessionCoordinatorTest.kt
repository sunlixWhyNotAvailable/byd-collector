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
    fun corruptOrFutureStateFailsClosed() {
        val malformed = FakeStorage(EnergyRuntimeRow("not-json", null, "now"))
        assertFailsWith<RuntimeException> { EnergySessionCoordinator(malformed).currentSnapshot() }

        val future = FakeStorage(
            EnergyRuntimeRow(
                EnergyStateCodec.encodeState(EnergyRuntimeState()).replace("\"schema_version\":1", "\"schema_version\":2"),
                null,
                "now"
            )
        )
        assertFailsWith<IllegalArgumentException> { EnergySessionCoordinator(future).currentSnapshot() }
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
    }
}
