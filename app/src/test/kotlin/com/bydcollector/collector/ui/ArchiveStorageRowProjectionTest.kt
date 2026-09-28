package com.bydcollector.collector.ui

import com.bydcollector.collector.maintenance.ArchiveEntryStatus
import com.bydcollector.collector.maintenance.ArchiveStorageEntry
import com.bydcollector.collector.maintenance.ArchiveStorageItemPhase
import com.bydcollector.collector.maintenance.ArchiveStorageItemState
import com.bydcollector.collector.maintenance.ArchiveStorageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveStorageRowProjectionTest {
    @Test
    fun groupsRawTmpAndZipAsOneLogicalRowAndKeepsPhysicalActionIds() {
        val archiveId = "bydcollector_telemetry_20260927_121314"
        val raw = entry(archiveId, ArchiveEntryStatus.RAW_DIRECTORY, bytes = 100L, deletable = true)
        val tmp = entry("$archiveId.zip.tmp", ArchiveEntryStatus.TMP, bytes = 30L, deletable = false)
        val zip = entry("$archiveId.zip", ArchiveEntryStatus.COMPRESSED_ZIP, bytes = 40L, deletable = true)

        val row = projectArchiveStorageRows(snapshot(raw, tmp, zip), emptyList(), scanPending = false).single()

        assertEquals(archiveId, row.key)
        assertEquals(archiveId, row.displayName)
        assertEquals(listOf(archiveId, "$archiveId.zip.tmp", "$archiveId.zip"), row.entries.map { it.id })
        assertEquals(170L, row.sizeBytes)
        assertEquals(listOf(archiveId, "$archiveId.zip"), row.deleteEntryIds)
        assertNull(row.shareEntryId)
    }

    @Test
    fun sortsFromBasenameTimestampInsteadOfPhysicalModificationTime() {
        val olderId = "bydcollector_telemetry_20260927_120000"
        val newerId = "bydcollector_telemetry_20260928_120000"
        val rows = projectArchiveStorageRows(
            snapshot(
                entry("$olderId.zip", ArchiveEntryStatus.COMPRESSED_ZIP, createdAtMs = Long.MAX_VALUE),
                entry(newerId, ArchiveEntryStatus.RAW_DIRECTORY, createdAtMs = 1L)
            ),
            emptyList(),
            scanPending = false
        )

        assertEquals(listOf(newerId, olderId), rows.map { it.key })
        assertTrue(rows.first().sortTimestampMs > rows.last().sortTimestampMs)
    }

    @Test
    fun onlyCompletedSingleZipRowsCanBeShared() {
        val legacyId = "bydcollector_secondary_20260927_121314"
        val activeId = "bydcollector_secondary_20260927_131314"
        val failedId = "bydcollector_secondary_20260927_141314"
        val mixedId = "bydcollector_secondary_20260927_151314"
        val states = listOf(
            state(activeId, ArchiveStorageItemPhase.CREATING_ZIP),
            state(failedId, ArchiveStorageItemPhase.FAILED, error = "verification failed"),
            state(mixedId, ArchiveStorageItemPhase.READY)
        )
        val rows = projectArchiveStorageRows(
            snapshot(
                entry("$legacyId.zip", ArchiveEntryStatus.COMPRESSED_ZIP),
                entry("$activeId.zip", ArchiveEntryStatus.COMPRESSED_ZIP),
                entry("$failedId.zip", ArchiveEntryStatus.COMPRESSED_ZIP),
                entry(mixedId, ArchiveEntryStatus.RAW_DIRECTORY),
                entry("$mixedId.zip", ArchiveEntryStatus.COMPRESSED_ZIP)
            ),
            states,
            scanPending = false
        ).associateBy { it.key }

        assertEquals("$legacyId.zip", rows.getValue(legacyId).shareEntryId)
        assertNull(rows.getValue(activeId).shareEntryId)
        assertNull(rows.getValue(failedId).shareEntryId)
        assertNull(rows.getValue(mixedId).shareEntryId)
    }

    @Test
    fun onlyInProgressStateOnlyRowsAppearDuringAnUnfinishedScan() {
        val queuedId = "bydcollector_telemetry_20260927_121314"
        val failedId = "bydcollector_telemetry_20260927_131314"
        val readyId = "bydcollector_telemetry_20260927_141314"
        val deletedId = "bydcollector_telemetry_20260927_151314"
        val states = listOf(
            state(queuedId, ArchiveStorageItemPhase.QUEUED),
            state(failedId, ArchiveStorageItemPhase.FAILED, error = "archive verification failed"),
            state(readyId, ArchiveStorageItemPhase.READY),
            state(deletedId, ArchiveStorageItemPhase.DELETED)
        )

        val pendingRows = projectArchiveStorageRows(snapshot(), states, scanPending = true)
        assertEquals(setOf(queuedId), pendingRows.map { it.key }.toSet())
        pendingRows.forEach { row ->
            assertTrue(row.entries.isEmpty())
            assertEquals(0L, row.sizeBytes)
            assertTrue(row.deleteEntryIds.isEmpty())
            assertNull(row.shareEntryId)
        }
        assertEquals(emptyList(), projectArchiveStorageRows(snapshot(), states, scanPending = false))
        assertFalse(pendingRows.any { it.key == failedId || it.key == deletedId || it.key == readyId })
    }

    private fun snapshot(vararg entries: ArchiveStorageEntry) = ArchiveStorageSnapshot(
        archiveRootPath = "archives",
        mainDatabaseSizeBytes = 1L,
        debugDatabaseSizeBytes = 2L,
        archiveBytes = entries.sumOf(ArchiveStorageEntry::sizeBytes),
        archiveLimitBytes = 1000L,
        entries = entries.toList()
    )

    private fun entry(
        id: String,
        status: ArchiveEntryStatus,
        bytes: Long = 1L,
        createdAtMs: Long = 0L,
        deletable: Boolean = true
    ) = ArchiveStorageEntry(
        id = id,
        displayName = id.removeSuffix(".zip.tmp").removeSuffix(".zip"),
        path = "archives/$id",
        createdAtMs = createdAtMs,
        sizeBytes = bytes,
        status = status,
        deletable = deletable
    )

    private fun state(
        archiveId: String,
        phase: ArchiveStorageItemPhase,
        error: String? = null
    ) = ArchiveStorageItemState(
        archiveId = archiveId,
        operationId = "operation-$archiveId",
        phase = phase,
        stepIndex = 2,
        stepCount = 4,
        startedAtMs = 1L,
        updatedAtMs = 2L,
        error = error
    )
}
