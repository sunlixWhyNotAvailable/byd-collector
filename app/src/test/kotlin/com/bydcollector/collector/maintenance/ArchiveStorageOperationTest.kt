package com.bydcollector.collector.maintenance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveStorageOperationTest {
    @Test
    fun repeatedAuditsCoalesceAndBusyDeletionDoesNotDisturbOwner() {
        val coordinator = ArchiveStorageOperationCoordinator()
        val owner = coordinator.admit("main-owner", deferIfBusy = false)
        assertEquals(ArchiveStorageAdmissionKind.ACCEPTED, owner.kind)
        assertEquals("main-owner", owner.operationId)

        val firstAudit = coordinator.admit("secondary-audit", deferIfBusy = true)
        val repeatedAudit = coordinator.admit("reconcile-audit", deferIfBusy = true)
        val delete = coordinator.admit("delete-request", deferIfBusy = false)

        assertEquals(ArchiveStorageAdmissionKind.DEFERRED, firstAudit.kind)
        assertEquals("secondary-audit", firstAudit.operationId)
        assertEquals(firstAudit.operationId, repeatedAudit.operationId)
        assertEquals(ArchiveStorageAdmissionKind.REJECTED, delete.kind)
        assertNull(delete.operationId)
        assertNull(coordinator.finish("stale-owner"))
        assertTrue(coordinator.isActive())

        assertEquals("secondary-audit", coordinator.finish("main-owner"))
        assertEquals("secondary-audit", coordinator.pendingAuditOperationId())
        val acceptedAudit = coordinator.admit("later-audit", deferIfBusy = true)
        assertEquals(ArchiveStorageAdmissionKind.ACCEPTED, acceptedAudit.kind)
        assertEquals("secondary-audit", acceptedAudit.operationId)
        coordinator.acceptDeferredAudit("secondary-audit")
        assertNull(coordinator.pendingAuditOperationId())
        assertNull(coordinator.finish("secondary-audit"))
    }

    @Test
    fun rejectedDeferredExecutorWorkRemainsRetryable() {
        val coordinator = ArchiveStorageOperationCoordinator()
        coordinator.admit("active-owner", deferIfBusy = false)
        val deferred = coordinator.admit("audit-owner", deferIfBusy = true).operationId
        assertEquals("audit-owner", deferred)
        assertEquals("audit-owner", coordinator.finish("active-owner"))

        val attempted = coordinator.admit("retry", deferIfBusy = true)
        assertEquals(ArchiveStorageAdmissionKind.ACCEPTED, attempted.kind)
        assertEquals("audit-owner", attempted.operationId)
        // Accepting the deferred owner consumes its pending marker before executor submission.
        coordinator.acceptDeferredAudit("audit-owner")
        assertNull(coordinator.pendingAuditOperationId())
        // Rejected submission settles that owner and restores the audit as retryable work.
        assertEquals("audit-owner", coordinator.reject("audit-owner", deferRejectedAudit = true))

        assertFalse(coordinator.isActive())
        assertEquals("audit-owner", coordinator.pendingAuditOperationId())
        val retry = coordinator.admit("new-request", deferIfBusy = true)
        assertEquals(ArchiveStorageAdmissionKind.ACCEPTED, retry.kind)
        assertEquals("audit-owner", retry.operationId)
        coordinator.acceptDeferredAudit("audit-owner")
        assertNull(coordinator.pendingAuditOperationId())
    }

    @Test
    fun laterSuccessDoesNotEraseAnEarlierItemFailure() {
        val outcome = ArchiveStorageOperationOutcome()
        outcome.record(ArchiveStorageJobStatus(itemId = "archive-a", error = "ZIP verification failed"))
        outcome.record(ArchiveStorageJobStatus(itemId = "archive-b", error = null))

        assertEquals(mapOf("archive-a" to "ZIP verification failed"), outcome.itemFailures())
        assertTrue(assertNotNull(outcome.terminalError()).contains("archive-a=ZIP verification failed"))
    }
}
