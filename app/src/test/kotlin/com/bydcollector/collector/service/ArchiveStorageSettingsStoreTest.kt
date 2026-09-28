package com.bydcollector.collector.service

import android.content.SharedPreferences
import com.bydcollector.collector.maintenance.ArchiveStorageItemPhase
import com.bydcollector.collector.maintenance.ArchiveStorageItemState
import com.bydcollector.collector.maintenance.ArchiveStorageItemsReconciliation
import com.bydcollector.collector.maintenance.ArchiveStorageJobMode
import com.bydcollector.collector.maintenance.ArchiveStorageJobStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveStorageSettingsStoreTest {
    @Test
    fun deferredAndStaleOwnersCannotOverwriteOrClearActiveProgress() {
        val prefs = MemorySharedPreferences()
        val owner = "main-owner"
        val activeId = "bydcollector_telemetry_20260928_100000"
        val queuedId = "bydcollector_secondary_20260928_100000"
        val activeStatus = ArchiveStorageJobStatus(
            mode = ArchiveStorageJobMode.COMPRESS,
            running = true,
            stepIndex = 2,
            stepCount = 4,
            messageUk = "Створення ZIP",
            messageEn = "Creating ZIP",
            itemId = activeId,
            phase = ArchiveStorageItemPhase.CREATING_ZIP,
            updatedAtMs = 1L
        )

        assertTrue(ArchiveStorageSettingsStore.beginOperation(prefs, owner, activeStatus))
        assertTrue(
            ArchiveStorageSettingsStore.publishProgress(
                prefs,
                owner,
                activeStatus,
                item(activeId, owner, ArchiveStorageItemPhase.CREATING_ZIP, stepIndex = 2, stepCount = 4)
            )
        )
        val deferredId = ArchiveStorageSettingsStore.deferAudit(prefs, "secondary-audit", listOf(activeId, queuedId))
        assertEquals("secondary-audit", deferredId)
        assertEquals(deferredId, ArchiveStorageSettingsStore.deferAudit(prefs, "repeated-audit", listOf(queuedId)))

        val staleStatus = ArchiveStorageJobStatus(
            mode = ArchiveStorageJobMode.DELETE,
            running = true,
            messageEn = "stale failure",
            error = "stale failure"
        )
        assertFalse(ArchiveStorageSettingsStore.beginOperation(prefs, "stale-owner", staleStatus))
        assertFalse(
            ArchiveStorageSettingsStore.publishProgress(
                prefs,
                "stale-owner",
                staleStatus,
                item(activeId, "stale-owner", ArchiveStorageItemPhase.FAILED, error = "stale failure")
            )
        )
        assertFalse(ArchiveStorageSettingsStore.finishOperation(prefs, "stale-owner", staleStatus))
        assertFalse(ArchiveStorageSettingsStore.clearJobStatus(prefs, "stale-owner"))

        val persistedStatus = ArchiveStorageSettingsStore.readJobStatus(prefs)
        assertTrue(persistedStatus.running)
        assertEquals(owner, persistedStatus.operationId)
        assertEquals(ArchiveStorageItemPhase.CREATING_ZIP, persistedStatus.phase)
        val items = ArchiveStorageSettingsStore.readItems(prefs).associateBy { it.archiveId }
        assertEquals(ArchiveStorageItemPhase.CREATING_ZIP, items.getValue(activeId).phase)
        assertEquals(owner, items.getValue(activeId).operationId)
        assertEquals(ArchiveStorageItemPhase.QUEUED, items.getValue(queuedId).phase)
        assertEquals(deferredId, ArchiveStorageSettingsStore.pendingAuditOperationId(prefs))
    }

    @Test
    fun itemPhasesErrorsAndPendingAuditSurviveRestartRecovery() {
        val prefs = MemorySharedPreferences()
        val owner = "compress-owner"
        val failedId = "bydcollector_telemetry_20260928_100000"
        val activeId = "bydcollector_secondary_20260928_100000"
        val queuedId = "bydcollector_debug_round_robin_20260928_100000"
        val status = ArchiveStorageJobStatus(
            mode = ArchiveStorageJobMode.COMPRESS,
            running = true,
            stepIndex = 1,
            stepCount = 4,
            messageEn = "Verifying database",
            updatedAtMs = 1L
        )

        assertTrue(ArchiveStorageSettingsStore.beginOperation(prefs, owner, status))
        assertTrue(ArchiveStorageSettingsStore.queueItems(prefs, owner, listOf(failedId, activeId, queuedId)))
        assertTrue(
            ArchiveStorageSettingsStore.publishProgress(
                prefs,
                owner,
                status.copy(itemId = failedId, phase = ArchiveStorageItemPhase.VERIFYING_ZIP, stepIndex = 3),
                item(failedId, owner, ArchiveStorageItemPhase.FAILED, stepIndex = 3, stepCount = 4, error = "ZIP checksum mismatch")
            )
        )
        assertTrue(
            ArchiveStorageSettingsStore.publishProgress(
                prefs,
                owner,
                status.copy(itemId = activeId, phase = ArchiveStorageItemPhase.CREATING_ZIP, stepIndex = 2),
                item(activeId, owner, ArchiveStorageItemPhase.CREATING_ZIP, stepIndex = 2, stepCount = 4)
            )
        )
        assertEquals("pending-audit", ArchiveStorageSettingsStore.deferAudit(prefs, "pending-audit", listOf(queuedId)))

        val reopenedPrefs = prefs.reopened()
        val beforeRestart = ArchiveStorageSettingsStore.readItems(reopenedPrefs).associateBy { it.archiveId }
        val failed = beforeRestart.getValue(failedId)
        assertEquals(ArchiveStorageItemPhase.FAILED, failed.phase)
        assertEquals("ZIP checksum mismatch", failed.error)
        assertNotNull(failed.completedAtMs)
        assertTrue(failed.startedAtMs > 0L)
        val queued = beforeRestart.getValue(queuedId)
        assertEquals(ArchiveStorageItemPhase.QUEUED, queued.phase)
        assertTrue(queued.startedAtMs > 0L)
        assertTrue(queued.updatedAtMs > 0L)

        assertEquals("pending-audit", ArchiveStorageSettingsStore.recoverAfterProcessRestart(reopenedPrefs, "recovery-owner"))
        val recoveredStatus = ArchiveStorageSettingsStore.readJobStatus(reopenedPrefs)
        assertFalse(recoveredStatus.running)
        assertEquals(owner, recoveredStatus.operationId)
        assertEquals("archive_operation_interrupted:process_restart", recoveredStatus.error)
        assertEquals("pending-audit", ArchiveStorageSettingsStore.pendingAuditOperationId(reopenedPrefs))

        val afterRestart = ArchiveStorageSettingsStore.readItems(reopenedPrefs).associateBy { it.archiveId }
        assertEquals(failed, afterRestart.getValue(failedId))
        assertEquals(ArchiveStorageItemPhase.CREATING_ZIP, afterRestart.getValue(activeId).phase)
        assertEquals(queued, afterRestart.getValue(queuedId))
    }

    @Test
    fun pendingAuditSurvivesRejectedSchedulingUntilRetryBegins() {
        val prefs = MemorySharedPreferences()
        val owner = "active-owner"
        val pending = "pending-audit"
        val status = ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION, running = true, updatedAtMs = 1L)

        assertTrue(ArchiveStorageSettingsStore.beginOperation(prefs, owner, status))
        assertEquals(pending, ArchiveStorageSettingsStore.deferAudit(prefs, pending, emptyList()))
        assertTrue(ArchiveStorageSettingsStore.finishOperation(prefs, owner, status.copy(running = false)))

        val reopenedPrefs = prefs.reopened()
        assertEquals(pending, ArchiveStorageSettingsStore.pendingAuditOperationId(reopenedPrefs))
        assertTrue(ArchiveStorageSettingsStore.beginOperation(reopenedPrefs, pending, status))
        assertNull(ArchiveStorageSettingsStore.pendingAuditOperationId(reopenedPrefs))

        // The accepted owner is settled and re-queued when executor submission is rejected.
        assertTrue(
            ArchiveStorageSettingsStore.finishOperation(
                reopenedPrefs,
                pending,
                status.copy(error = "RejectedExecutionException: test")
            )
        )
        assertEquals(pending, ArchiveStorageSettingsStore.deferAudit(reopenedPrefs, pending, emptyList()))

        val retryPrefs = reopenedPrefs.reopened()
        assertEquals(pending, ArchiveStorageSettingsStore.pendingAuditOperationId(retryPrefs))
        assertTrue(ArchiveStorageSettingsStore.beginOperation(retryPrefs, pending, status))
        assertNull(ArchiveStorageSettingsStore.pendingAuditOperationId(retryPrefs))
    }

    @Test
    fun successfulReconciliationPreservesNewDeferredItemAndPrunesUnchangedMissingItem() {
        val prefs = MemorySharedPreferences()
        val owner = "scan-owner"
        val keptId = "bydcollector_telemetry_20260928_100000"
        val missingId = "bydcollector_secondary_20260928_100000"
        val deferredId = "bydcollector_debug_round_robin_20260928_100000"
        val status = ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION, running = true, updatedAtMs = 1L)

        assertTrue(ArchiveStorageSettingsStore.beginOperation(prefs, owner, status))
        assertTrue(ArchiveStorageSettingsStore.queueItems(prefs, owner, listOf(keptId, missingId)))
        listOf(keptId, missingId).forEach { archiveId ->
            assertTrue(
                ArchiveStorageSettingsStore.publishProgress(
                    prefs,
                    owner,
                    status.copy(itemId = archiveId, phase = ArchiveStorageItemPhase.READY),
                    item(archiveId, owner, ArchiveStorageItemPhase.READY)
                )
            )
        }
        val sourceSnapshot = ArchiveStorageSettingsStore.readItems(prefs)
        assertEquals(setOf(keptId, missingId), sourceSnapshot.map { it.archiveId }.toSet())

        val deferredOwner = ArchiveStorageSettingsStore.deferAudit(prefs, "deferred-owner", listOf(deferredId))
        val queuedDuringScan = ArchiveStorageSettingsStore.readItems(prefs).single { it.archiveId == deferredId }
        assertEquals(ArchiveStorageItemPhase.QUEUED, queuedDuringScan.phase)
        assertFalse(sourceSnapshot.any { it.archiveId == deferredId })

        val scanResult = ArchiveStorageItemsReconciliation(
            itemStates = listOf(sourceSnapshot.single { it.archiveId == keptId }),
            knownArchiveIds = setOf(keptId, deferredId),
            scanSucceeded = true,
            sourceItemStates = sourceSnapshot
        )
        assertTrue(ArchiveStorageSettingsStore.reconcileItems(prefs, owner, scanResult))

        val reconciled = ArchiveStorageSettingsStore.readItems(prefs).associateBy { it.archiveId }
        assertEquals(setOf(keptId, deferredId), reconciled.keys)
        assertEquals(ArchiveStorageItemPhase.READY, reconciled.getValue(keptId).phase)
        assertEquals(queuedDuringScan, reconciled.getValue(deferredId))
        assertEquals(deferredOwner, reconciled.getValue(deferredId).operationId)
    }

    @Test
    fun failedScanKeepsItemsAndSuccessfulScanPrunesOnlyMissingRecognizedArchives() {
        val prefs = MemorySharedPreferences()
        val owner = "audit-owner"
        val keptId = "bydcollector_telemetry_20260928_100000"
        val missingId = "bydcollector_secondary_20260928_100000"
        val invalidPath = "bydcollector_telemetry_20260928_100000/nested"
        val invalidExtension = "bydcollector_telemetry_20260928_100000.zip.tmp"
        val status = ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION, running = true, updatedAtMs = 1L)

        assertTrue(ArchiveStorageSettingsStore.beginOperation(prefs, owner, status))
        assertTrue(
            ArchiveStorageSettingsStore.queueItems(
                prefs,
                owner,
                listOf(keptId, missingId, "unknown_archive", invalidPath, invalidExtension)
            )
        )
        assertEquals(setOf(keptId, missingId), ArchiveStorageSettingsStore.readItems(prefs).map { it.archiveId }.toSet())

        val failedScan = ArchiveStorageItemsReconciliation(
            itemStates = emptyList(),
            knownArchiveIds = emptySet(),
            scanSucceeded = false
        )
        assertFalse(ArchiveStorageSettingsStore.reconcileItems(prefs, owner, failedScan))
        assertEquals(setOf(keptId, missingId), ArchiveStorageSettingsStore.readItems(prefs).map { it.archiveId }.toSet())

        val successfulScan = ArchiveStorageItemsReconciliation(
            itemStates = listOf(
                item(keptId, owner, ArchiveStorageItemPhase.READY),
                item(invalidPath, owner, ArchiveStorageItemPhase.READY)
            ),
            knownArchiveIds = setOf(keptId, missingId, invalidPath),
            scanSucceeded = true
        )
        assertTrue(ArchiveStorageSettingsStore.reconcileItems(prefs, owner, successfulScan))
        assertEquals(listOf(keptId), ArchiveStorageSettingsStore.readItems(prefs).map { it.archiveId })
    }

    private fun item(
        archiveId: String,
        operationId: String,
        phase: ArchiveStorageItemPhase,
        stepIndex: Int = 0,
        stepCount: Int = 0,
        error: String? = null
    ) = ArchiveStorageItemState(
        archiveId = archiveId,
        operationId = operationId,
        phase = phase,
        stepIndex = stepIndex,
        stepCount = stepCount,
        startedAtMs = 1L,
        updatedAtMs = 1L,
        error = error
    )

    private class MemorySharedPreferences(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
        private val values = initial.toMutableMap()

        fun reopened() = MemorySharedPreferences(values.toMap())

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = update(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = update(key, values?.toSet())
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = update(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = update(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = update(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = update(key, value)
            override fun remove(key: String?): SharedPreferences.Editor = update(key, null)
            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

            override fun commit(): Boolean {
                flush()
                return true
            }

            override fun apply() = flush()

            private fun update(key: String?, value: Any?): SharedPreferences.Editor = apply {
                key?.let { updates[it] = value }
            }

            private fun flush() {
                if (clearRequested) values.clear()
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
        }
    }
}
