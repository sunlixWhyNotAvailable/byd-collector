package com.bydcollector.collector.ui

import com.bydcollector.collector.data.local.CollectorEvent
import com.bydcollector.collector.data.callback.CallbackQueueState
import com.bydcollector.collector.maintenance.ArchiveEntryStatus
import com.bydcollector.collector.maintenance.ArchiveStorageEntry
import com.bydcollector.collector.maintenance.ArchiveStorageItemPhase
import com.bydcollector.collector.maintenance.ArchiveStorageItemState
import com.bydcollector.collector.maintenance.ArchiveStorageJobStatus
import com.bydcollector.collector.maintenance.ArchiveStorageSnapshot
import com.bydcollector.collector.maintenance.DbMaintenanceRuntimeStatus
import java.time.LocalDateTime
import java.time.ZoneOffset

enum class DebugRuntimeStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

enum class RuntimeActionStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

data class DashboardState(
    val running: Boolean,
    val serviceRunning: Boolean,
    val mainPollingRunning: Boolean,
    val autoStartEnabled: Boolean,
    val pollingEnabled: Boolean,
    val activeSessionId: Long?,
    val lastSuccessAt: String?,
    val lastError: String?,
    val lastErrorAt: String?,
    val lastPollStatus: String?,
    val pollCount: Long,
    val valueRowCount: Long,
    val ecRowCount: Long,
    val lastEcImport: String?,
    val lastEcImportStatus: String?,
    val elapsedMs: Long?,
    val requestCount: Int?,
    val databasePath: String,
    val databaseSizeBytes: Long,
    val dbMaintenanceStatus: DbMaintenanceRuntimeStatus,
    val archiveStorageLimitGb: Int,
    val archiveStorageSnapshot: ArchiveStorageSnapshot,
    val archiveStorageScanPending: Boolean,
    val archiveStorageJobStatus: ArchiveStorageJobStatus,
    val archiveStorageItemStates: List<ArchiveStorageItemState> = emptyList(),
    val archiveStorageScanError: String? = null,
    val latestSoc: String?,
    val latestSpeed: String?,
    val latestCharging: String?,
    val logRecording: Boolean,
    val debugPollingEnabled: Boolean,
    val debugPollingRunning: Boolean,
    val debugRuntimeStatus: DebugRuntimeStatus = if (debugPollingRunning) DebugRuntimeStatus.RUNNING else DebugRuntimeStatus.STOPPED,
    val debugRuntimeError: String? = null,
    val debugAutoStartEnabled: Boolean,
    val debugParameterCount: Int,
    val debugDatabasePath: String,
    val debugDatabaseSizeBytes: Long,
    val debugReadingCount: Long,
    val debugLastReadingAt: String?,
    val debugLastErrorAt: String?,
    val debugLastError: String?,
    val debugErrorCount: Long,
    val debugLastSessionId: Long?,
    val keepWifiEnabled: Boolean,
    val keepMobileDataEnabled: Boolean,
    val keepBluetoothEnabled: Boolean,
    val recoverCollectorServiceEnabled: Boolean,
    val tailscaleActivationEnabled: Boolean,
    val keepAliveEnabled: Boolean,
    val keepAliveStatus: String,
    val mqttEnabled: Boolean,
    val mqttAutoStartEnabled: Boolean,
    val mqttHost: String,
    val mqttPort: Int,
    val mqttUsername: String,
    val mqttPasswordSet: Boolean,
    val mqttClientId: String,
    val mqttTopicPrefix: String,
    val mqttDiscoveryPrefix: String,
    val mqttEnabledCategories: Set<String>,
    val mqttStatus: String,
    val mqttLastError: String?,
    val mqttLastPublishedAt: String?,
    val mqttPendingCount: Long,
    val mqttRetryFailureCount: Int,
    val mqttNextRetryAt: String?,
    val mqttRetryLastFailureAt: String?,
    val mqttRetryLastSuccessAt: String?,
    val influxEnabled: Boolean,
    val influxAutoStartEnabled: Boolean,
    val haSharedCategoriesEnabled: Boolean,
    val influxHost: String,
    val influxPort: Int,
    val influxDatabase: String,
    val influxUsername: String,
    val influxPasswordSet: Boolean,
    val influxMeasurement: String,
    val influxEnabledCategories: Set<String>,
    val influxStatus: String,
    val influxPendingRows: Long,
    val influxOldestPendingAt: String?,
    val influxNextRetryAt: String?,
    val influxLastSuccessAt: String?,
    val influxLastErrorAt: String?,
    val influxLastError: String?,
    val influxExportedRowsTotal: Long,
    val normalizedCurrentCount: Long,
    val normalizedHistoryCount: Long,
    val permissionsGranted: Boolean,
    val adbAuthorized: Boolean,
    val vehicleKpis: VehicleKpis,
    val recentEvents: List<CollectorEvent>,
    val mainStorageCutoverDeferredReason: String? = null,
    val mainStorageCutoverError: String? = null,
    val debugStorageCutoverError: String? = null,
    val mainRuntimeStatus: RuntimeActionStatus =
        if (mainPollingRunning) RuntimeActionStatus.RUNNING else RuntimeActionStatus.STOPPED,
    val mqttRuntimeStatus: RuntimeActionStatus =
        if (mqttEnabled) RuntimeActionStatus.RUNNING else RuntimeActionStatus.STOPPED,
    val influxRuntimeStatus: RuntimeActionStatus =
        if (influxEnabled) RuntimeActionStatus.RUNNING else RuntimeActionStatus.STOPPED,
    val mainCallbackQueue: CallbackQueueState? = null,
    val secondaryCallbackQueue: CallbackQueueState? = null
) : java.io.Serializable

/** One logical archive backed by zero or more physical raw, temporary, and ZIP entries. */
internal data class ArchiveStorageRow(
    val key: String,
    val displayName: String,
    val entries: List<ArchiveStorageEntry>,
    val sortTimestampMs: Long,
    val itemState: ArchiveStorageItemState?
) {
    val sizeBytes: Long
        get() = entries.sumOf(ArchiveStorageEntry::sizeBytes)

    val deleteEntryIds: List<String>
        get() = entries.filter { it.deletable && it.status != ArchiveEntryStatus.TMP }.map { it.id }

    /** Existing, state-free ZIPs remain shareable; all other representations stay protected. */
    val shareEntryId: String?
        get() = entries.singleOrNull()
            ?.takeIf { it.status == ArchiveEntryStatus.COMPRESSED_ZIP }
            ?.takeIf { itemState == null || itemState.phase == ArchiveStorageItemPhase.READY }
            ?.id
}

/** Group physical representations without changing their IDs, which remain action/cache identities. */
internal fun projectArchiveStorageRows(
    snapshot: ArchiveStorageSnapshot,
    itemStates: List<ArchiveStorageItemState>,
    scanPending: Boolean
): List<ArchiveStorageRow> {
    val statesById = itemStates
        .groupBy(ArchiveStorageItemState::archiveId)
        .mapValues { (_, states) -> states.maxByOrNull(ArchiveStorageItemState::updatedAtMs)!! }
    val entriesById = snapshot.entries.mapNotNull { entry ->
        entry.logicalArchiveId()?.let { it to entry }
    }.groupBy({ it.first }, { it.second })
    val rows = entriesById.map { (archiveId, entries) ->
        val orderedEntries = entries.sortedBy {
            when (it.status) {
                ArchiveEntryStatus.RAW_DIRECTORY -> 0
                ArchiveEntryStatus.TMP -> 1
                ArchiveEntryStatus.COMPRESSED_ZIP -> 2
            }
        }
        ArchiveStorageRow(
            key = archiveId,
            displayName = orderedEntries.first().displayName,
            entries = orderedEntries,
            sortTimestampMs = archiveTimestampMs(archiveId),
            itemState = statesById[archiveId]
        )
    }.toMutableList()

    if (scanPending) {
        statesById.values.asSequence()
            .filter { it.archiveId !in entriesById }
            .filter { it.phase.inProgress }
            .forEach { state ->
                rows += ArchiveStorageRow(
                    key = state.archiveId,
                    displayName = state.archiveId,
                    entries = emptyList(),
                    sortTimestampMs = archiveTimestampMs(state.archiveId),
                    itemState = state
                )
            }
    }

    return rows.sortedWith(compareByDescending<ArchiveStorageRow> { it.sortTimestampMs }.thenBy { it.key })
}

private fun ArchiveStorageEntry.logicalArchiveId(): String? = when (status) {
    ArchiveEntryStatus.RAW_DIRECTORY -> id.takeIf { !it.endsWith(".zip") && !it.endsWith(".zip.tmp") }
    ArchiveEntryStatus.COMPRESSED_ZIP -> id.takeIf { it.endsWith(".zip") }?.removeSuffix(".zip")
    ArchiveEntryStatus.TMP -> id.takeIf { it.endsWith(".zip.tmp") }?.removeSuffix(".zip.tmp")
}

private fun archiveTimestampMs(archiveId: String): Long {
    val suffix = ARCHIVE_TIMESTAMP_SUFFIX.find(archiveId)?.groupValues?.getOrNull(1) ?: return 0L
    return runCatching {
        LocalDateTime.of(
            suffix.substring(0, 4).toInt(),
            suffix.substring(4, 6).toInt(),
            suffix.substring(6, 8).toInt(),
            suffix.substring(9, 11).toInt(),
            suffix.substring(11, 13).toInt(),
            suffix.substring(13, 15).toInt()
        ).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrDefault(0L)
}

private val ARCHIVE_TIMESTAMP_SUFFIX = Regex("_(\\d{8}_\\d{6})$")

data class VehicleKpis(
    val socPercent: String = "-",
    val remainingEnergyKwh: String = "-",
    val odometerKm: String = "-",
    val cabinTempC: String = "-",
    val perfume1RemainingPercent: String = "-",
    val perfume2RemainingPercent: String = "-",
    val perfume3RemainingPercent: String = "-",
    val sohPercent: String = "-",
    val batteryPowerCharging: Boolean = false,
    val batteryPowerKw: String = "-",
    val remainingRangeKm: String = "-",
    val batteryTempC: String = "-",
    val cellVoltageDeltaMv: String = "-"
) : java.io.Serializable

data class DashboardRowCounts(
    val pollCount: Long,
    val valueRowCount: Long,
    val ecRowCount: Long,
    val normalizedCurrentCount: Long,
    val normalizedHistoryCount: Long,
    val debugReadingCount: Long
) : java.io.Serializable

const val UNKNOWN_DASHBOARD_COUNT = -1L
