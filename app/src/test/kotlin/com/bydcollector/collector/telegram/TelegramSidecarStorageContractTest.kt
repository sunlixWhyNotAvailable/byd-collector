package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelegramLegacySnapshot
import com.bydcollector.collector.data.local.TelegramLegacyMigrationDecision
import com.bydcollector.collector.data.local.TelegramMigrationResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramSidecarStorageContractTest {
    @Test
    fun legacySnapshotRequiresPreservationForAnyDataOrUncertainRead() {
        val empty = TelegramLegacySnapshot()
        val invalidData = TelegramLegacySnapshot(runtimeStatePresent = true, runtimeStateValid = false)
        val unknown = TelegramLegacySnapshot(readError = "read failed")

        assertFalse(empty.requiresPreservation)
        assertTrue(empty.provenEmpty)
        assertTrue(invalidData.requiresPreservation)
        assertTrue(invalidData.hasProvenLegacyData)
        assertTrue(unknown.requiresPreservation)
        assertTrue(unknown.readUnknown)
        assertFalse(unknown.hasProvenLegacyData)
        assertFalse(unknown.provenEmpty)
        assertTrue(TelegramLegacySnapshot(truncated = true).requiresPreservation)
    }

    @Test
    fun migrationDecisionSeparatesUnknownProvenMissingAndCompletedSidecar() {
        val unknown = TelegramLegacySnapshot(readError = "read failed")
        val empty = TelegramLegacySnapshot()
        val withData = TelegramLegacySnapshot(runtimeStateJson = "{}", runtimeStateUpdatedAtMs = 1L)

        assertTrue(
            unknown.migrationDecision(importAlreadyComplete = false, migrationPreviouslyRequired = false) ==
                TelegramLegacyMigrationDecision.RETRY_UNKNOWN_READ
        )
        assertTrue(
            empty.migrationDecision(importAlreadyComplete = false, migrationPreviouslyRequired = true) ==
                TelegramLegacyMigrationDecision.FAIL_PROVEN_MISSING
        )
        assertTrue(
            withData.migrationDecision(importAlreadyComplete = false, migrationPreviouslyRequired = false) ==
                TelegramLegacyMigrationDecision.IMPORT_PROVEN_DATA
        )
        assertTrue(
            unknown.migrationDecision(importAlreadyComplete = true, migrationPreviouslyRequired = true) ==
                TelegramLegacyMigrationDecision.VERIFY_COMPLETED_IMPORT
        )
        val partialRead = TelegramLegacySnapshot(runtimeStatePresent = true, readError = "state payload failed")
        assertTrue(
            partialRead.migrationDecision(importAlreadyComplete = false, migrationPreviouslyRequired = false) ==
                TelegramLegacyMigrationDecision.IMPORT_PROVEN_DATA
        )
    }

    @Test
    fun completedMarkerCanVerifyRuntimeWhileDenyingStaleLegacyCleanup() {
        val staleLegacy = TelegramLegacySnapshot(runtimeStateJson = "{}", runtimeStateUpdatedAtMs = 1L)
        assertFalse(staleLegacy.completedImportCleanupVerified(exactSidecarSnapshot = false))
        assertTrue(TelegramLegacySnapshot().completedImportCleanupVerified(exactSidecarSnapshot = false))
        assertFalse(
            TelegramLegacySnapshot(readError = "read failed")
                .completedImportCleanupVerified(exactSidecarSnapshot = true)
        )

        val completed = TelegramMigrationResult(
            status = TelegramMigrationResult.Status.ALREADY_COMPLETE,
            sidecarVerified = true,
            cleanupVerified = false
        )

        assertTrue(completed.verified)
        assertFalse(completed.cleanupVerified)
    }

}
