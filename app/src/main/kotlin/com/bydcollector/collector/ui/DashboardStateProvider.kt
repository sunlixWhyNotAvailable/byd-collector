package com.bydcollector.collector.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.data.debug.DirectDebugDatabaseResolver
import com.bydcollector.collector.data.debug.DirectDebugParameterAsset
import com.bydcollector.collector.data.debug.DirectDebugStatus
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.local.CollectorEvent
import com.bydcollector.collector.data.local.HealthSnapshot
import com.bydcollector.collector.data.local.HealthSnapshotDetail
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.diagnostics.DiagnosticLogRecorder
import com.bydcollector.collector.influx.InfluxExportStateSnapshot
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.util.TimedCache
import com.bydcollector.collector.util.sqliteFootprintBytes

//assembles one immutable dashboard snapshot from settings, service flags, sqlite health, and diagnostics
class DashboardStateProvider(
    private val context: Context,
    private val storeProvider: () -> TelemetryStore,
    private val settings: CollectorSettings
) {
    constructor(
        context: Context,
        store: TelemetryStore,
        settings: CollectorSettings
    ) : this(context, { store }, settings)

    private val healthCaches = HealthSnapshotDetail.values().associateWith { detail ->
        TimedCache<HealthSnapshot>(
            ttlMs = when (detail) {
                HealthSnapshotDetail.SUMMARY -> 1_000L
                HealthSnapshotDetail.INTEGRATIONS -> 2_000L
                HealthSnapshotDetail.FULL -> 5_000L
            }
        )
    }
    private val debugStatusCache = TimedCache<DirectDebugStatus>(ttlMs = 5_000L)
    private val archiveStorageCache =
        (context.applicationContext as BydCollectorApplication).archiveStorageSnapshotCache
    private val healthCacheRunning = mutableMapOf<HealthSnapshotDetail, Boolean>()
    private var recentEventsSource: List<CollectorEvent>? = null
    private var formattedRecentEvents: List<CollectorEvent> = emptyList()
    private var archiveStorageJobActive = false

    fun loadInitial(): DashboardState = load(DashboardLoadProfile.INITIAL)

    fun load(
        profile: DashboardLoadProfile,
        previous: DashboardState? = null,
        vehicleKpiLanguage: VehicleKpiLanguage = VehicleKpiLanguage.UK
    ): DashboardState {
        val maintenanceStatus = settings.dbMaintenanceStatus()
        val mainMaintenanceRunning = maintenanceStatus.running &&
            maintenanceStatus.operation == DbMaintenanceOperation.ARCHIVE
        val debugMaintenanceRunning = maintenanceStatus.running &&
            maintenanceStatus.operation == DbMaintenanceOperation.DEBUG_ARCHIVE
        val application = context.applicationContext as BydCollectorApplication
        return when {
            profile.readsTelemetryStore && !mainMaintenanceRunning -> {
                application.withTelemetryStoreRead {
                    loadSnapshot(
                        profile,
                        previous,
                        vehicleKpiLanguage,
                        mainMaintenanceRunning,
                        debugMaintenanceRunning,
                        storeProvider()
                    )
                }
            }
            profile.readsDebugStatus && !debugMaintenanceRunning -> application.withDatabaseRead {
                loadSnapshot(
                    profile,
                    previous,
                    vehicleKpiLanguage,
                    mainMaintenanceRunning,
                    debugMaintenanceRunning,
                    null
                )
            }
            else -> loadSnapshot(
                profile,
                previous,
                vehicleKpiLanguage,
                mainMaintenanceRunning,
                debugMaintenanceRunning,
                null
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun loadSnapshot(
        profile: DashboardLoadProfile,
        previous: DashboardState?,
        vehicleKpiLanguage: VehicleKpiLanguage,
        mainMaintenanceRunning: Boolean,
        debugMaintenanceRunning: Boolean,
        store: TelemetryStore?
    ): DashboardState {
        val serviceRunning = CollectorService.isRunning()
        val mainPollingRunning = CollectorService.isMainPollingRunning()
        val maintenanceStatus = settings.dbMaintenanceStatus()
        val nowMs = SystemClock.elapsedRealtime()
        val healthDetailLoaded = profile.healthDetail?.takeIf { store != null }
        val health = if (healthDetailLoaded != null) {
            loadHealthSnapshot(store!!, mainPollingRunning, healthDetailLoaded, nowMs)
        } else {
            maintenanceHealthSnapshot(mainPollingRunning)
        }
        val debugStatusRequested = profile.readsDebugStatus && !debugMaintenanceRunning
        val debugStatusLoaded = debugStatusRequested &&
            BydCollectorApplication.isDebugStorageReady(context)
        val debugStatus = if (debugStatusLoaded) {
            debugStatusCache.get(nowMs = nowMs) {
                DirectDebugStore(context).use { debugStore ->
                    debugStore.status(previous?.debugReadingCount ?: UNKNOWN_DASHBOARD_COUNT)
                }
            }
        } else {
            lightweightDebugStatus()
        }
        val debugParameterCount = DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT
        val runtimeSettingsLoaded = profile.readsRuntimeSettings
        val keepAliveConfig = if (runtimeSettingsLoaded) settings.keepAliveConfig() else null
        val integrationSettingsLoaded = profile.readsIntegrationSettings
        //credential drafts stay Activity-owned; this path intentionally never reads Keystore values
        val mqttConfig = if (integrationSettingsLoaded) settings.mqttConfig(includeCredentials = false) else null
        val influxConfig = if (integrationSettingsLoaded) settings.influxConfig(includeCredentials = false) else null
        val archiveStorageLimitGb = settings.archiveStorageLimitGb()
        val archiveStorageJobStatus = settings.archiveStorageJobStatus()
        val archiveJobActiveNow = archiveStorageJobStatus.running || CollectorService.isArchiveStorageActive()
        if (archiveJobActiveNow != archiveStorageJobActive) {
            archiveStorageCache.invalidate()
            archiveStorageJobActive = archiveJobActiveNow
        }
        val archiveDetailsLoaded = profile.readsArchiveDetails && !archiveJobActiveNow
        val archiveStorageResult = archiveStorageCache.snapshot(
            limitBytes = archiveStorageLimitGb * 1024L * 1024L * 1024L,
            includeDetails = archiveDetailsLoaded
        )
        if (archiveDetailsLoaded) {
            archiveStorageResult.error?.let { error ->
                Log.w(TAG, "Archive storage scan failed; retaining the last good snapshot", error)
            }
        }
        val influxStateLoaded = integrationSettingsLoaded && store != null
        val influxState = when {
            mainMaintenanceRunning -> maintenanceInfluxState()
            profile == DashboardLoadProfile.INITIAL -> initialInfluxState(influxConfig?.enabled == true)
            influxStateLoaded -> store!!.influxExportState()
            else -> maintenanceInfluxState()
        }
        val useInfluxState = mainMaintenanceRunning || influxStateLoaded ||
            profile == DashboardLoadProfile.INITIAL || previous == null
        //KPI values are fed directly by the successful normalized-poll producer; dashboard refresh never rereads them.
        val vehicleKpisLoaded = false
        val vehicleKpis = previous?.vehicleKpis ?: VehicleKpis()
        val accessSnapshot = AdbAuthorizationManager.currentSnapshot()
        val integrationHealthLoaded = healthDetailLoaded == HealthSnapshotDetail.INTEGRATIONS ||
            healthDetailLoaded == HealthSnapshotDetail.FULL
        val mqttStatus = if (integrationHealthLoaded || previous == null) {
            formatMqttStatus(
                enabled = mqttConfig?.enabled == true,
                lastError = health.mqttLastError,
                lastPublishedAt = health.mqttLastPublishedAt,
                pendingCount = health.mqttPendingCount,
                retryFailureCount = health.mqttRetryFailureCount,
                nextRetryAt = health.mqttNextRetryAt
            )
        } else {
            previous?.mqttStatus ?: formatMqttStatus(
                enabled = mqttConfig?.enabled == true,
                lastError = health.mqttLastError,
                lastPublishedAt = health.mqttLastPublishedAt,
                pendingCount = health.mqttPendingCount,
                retryFailureCount = health.mqttRetryFailureCount,
                nextRetryAt = health.mqttNextRetryAt
            )
        }
        val influxStatus = if (useInfluxState) {
            formatInfluxStatus(influxState)
        } else {
            previous?.influxStatus ?: formatInfluxStatus(influxState)
        }
        val next = DashboardState(
            running = mainPollingRunning,
            serviceRunning = serviceRunning,
            mainPollingRunning = mainPollingRunning,
            mainRuntimeStatus = CollectorService.mainRuntimeStatus(),
            autoStartEnabled = if (runtimeSettingsLoaded) settings.isAutoStartEnabled() else false,
            pollingEnabled = if (runtimeSettingsLoaded) settings.isPollingEnabled() else false,
            activeSessionId = health.activeSessionId,
            lastSuccessAt = DisplayTimeFormatter.formatNullable(health.lastSuccessAt),
            lastError = health.lastError,
            lastErrorAt = DisplayTimeFormatter.formatNullable(health.lastErrorAt),
            lastPollStatus = formatTrailingTimestamp(health.lastPollStatus),
            pollCount = health.pollCount,
            valueRowCount = health.valueRowCount,
            ecRowCount = health.ecRowCount,
            lastEcImport = DisplayTimeFormatter.formatNullable(health.lastEcImport),
            lastEcImportStatus = formatLeadingTimestamp(health.lastEcImportStatus),
            elapsedMs = health.elapsedMs,
            requestCount = health.requestCount,
            databasePath = health.databasePath,
            databaseSizeBytes = health.databaseSizeBytes,
            dbMaintenanceStatus = maintenanceStatus,
            archiveStorageLimitGb = archiveStorageLimitGb,
            archiveStorageSnapshot = archiveStorageResult.snapshot,
            archiveStorageScanPending = archiveStorageResult.pending,
            archiveStorageJobStatus = archiveStorageJobStatus,
            latestSoc = health.latestSoc,
            latestSpeed = health.latestSpeed,
            latestCharging = health.latestCharging,
            logRecording = DiagnosticLogRecorder.isRecording(),
            debugPollingEnabled = if (runtimeSettingsLoaded) settings.isDebugPollingEnabled() else false,
            debugPollingRunning = CollectorService.isDebugRunning(),
            debugRuntimeStatus = CollectorService.debugRuntimeStatus(),
            debugRuntimeError = null,
            debugAutoStartEnabled = if (runtimeSettingsLoaded) settings.isDebugAutoStartEnabled() else false,
            debugParameterCount = debugParameterCount,
            debugDatabasePath = debugStatus.databasePath,
            debugDatabaseSizeBytes = debugStatus.databaseSizeBytes,
            debugReadingCount = debugStatus.readingCount,
            debugLastReadingAt = DisplayTimeFormatter.formatNullable(debugStatus.lastReadingAt),
            debugLastErrorAt = DisplayTimeFormatter.formatNullable(debugStatus.lastErrorAt),
            debugLastError = debugStatus.lastError,
            debugErrorCount = debugStatus.errorCount,
            debugLastSessionId = debugStatus.lastSessionId,
            keepWifiEnabled = keepAliveConfig?.keepWifi == true,
            keepMobileDataEnabled = keepAliveConfig?.keepMobileData == true,
            keepBluetoothEnabled = keepAliveConfig?.keepBluetooth == true,
            recoverCollectorServiceEnabled = keepAliveConfig?.recoverCollectorService == true,
            tailscaleActivationEnabled = if (runtimeSettingsLoaded) settings.isTailscaleActivationEnabled() else false,
            keepAliveEnabled = keepAliveConfig?.anyEnabled == true,
            keepAliveStatus = if (keepAliveConfig?.anyEnabled == true) "enabled" else "disabled",
            mqttEnabled = mqttConfig?.enabled == true,
            mqttRuntimeStatus = CollectorService.mqttRuntimeStatus(),
            mqttAutoStartEnabled = if (integrationSettingsLoaded) settings.isMqttAutoStartEnabled() else false,
            mqttHost = mqttConfig?.host.orEmpty(),
            mqttPort = mqttConfig?.port ?: 0,
            mqttUsername = "",
            mqttPasswordSet = previous?.mqttPasswordSet ?: false,
            mqttClientId = mqttConfig?.clientId.orEmpty(),
            mqttTopicPrefix = mqttConfig?.topicPrefix.orEmpty(),
            mqttDiscoveryPrefix = mqttConfig?.discoveryPrefix.orEmpty(),
            mqttEnabledCategories = mqttConfig?.enabledCategories ?: emptySet(),
            mqttStatus = mqttStatus,
            mqttLastError = health.mqttLastError,
            mqttLastPublishedAt = DisplayTimeFormatter.formatNullable(health.mqttLastPublishedAt),
            mqttPendingCount = health.mqttPendingCount,
            mqttRetryFailureCount = health.mqttRetryFailureCount,
            mqttNextRetryAt = DisplayTimeFormatter.formatNullable(health.mqttNextRetryAt),
            mqttRetryLastFailureAt = DisplayTimeFormatter.formatNullable(health.mqttRetryLastFailureAt),
            mqttRetryLastSuccessAt = DisplayTimeFormatter.formatNullable(health.mqttRetryLastSuccessAt),
            influxEnabled = influxConfig?.enabled == true,
            influxRuntimeStatus = CollectorService.influxRuntimeStatus(),
            influxAutoStartEnabled = if (integrationSettingsLoaded) settings.isInfluxAutoStartEnabled() else false,
            haSharedCategoriesEnabled = if (integrationSettingsLoaded) settings.isHaSharedCategoriesEnabled() else false,
            influxHost = influxConfig?.host.orEmpty(),
            influxPort = influxConfig?.port ?: 0,
            influxDatabase = influxConfig?.database.orEmpty(),
            influxUsername = "",
            influxPasswordSet = previous?.influxPasswordSet ?: false,
            influxMeasurement = influxConfig?.measurement.orEmpty(),
            influxEnabledCategories = influxConfig?.enabledCategories ?: emptySet(),
            influxStatus = influxStatus,
            influxPendingRows = if (useInfluxState) influxState.pendingRows else previous?.influxPendingRows ?: 0L,
            influxOldestPendingAt = if (useInfluxState) {
                DisplayTimeFormatter.formatNullable(influxState.oldestPendingAt)
            } else {
                previous?.influxOldestPendingAt
            },
            influxNextRetryAt = if (useInfluxState) {
                DisplayTimeFormatter.formatNullable(influxState.nextRetryAt)
            } else {
                previous?.influxNextRetryAt
            },
            influxLastSuccessAt = if (useInfluxState) {
                DisplayTimeFormatter.formatNullable(influxState.lastSuccessAt)
            } else {
                previous?.influxLastSuccessAt
            },
            influxLastErrorAt = if (useInfluxState) {
                DisplayTimeFormatter.formatNullable(influxState.lastErrorAt)
            } else {
                previous?.influxLastErrorAt
            },
            influxLastError = if (useInfluxState) influxState.lastError else previous?.influxLastError,
            influxExportedRowsTotal = if (useInfluxState) influxState.exportedRowsTotal else previous?.influxExportedRowsTotal ?: 0L,
            normalizedCurrentCount = health.normalizedCurrentCount,
            normalizedHistoryCount = health.normalizedHistoryCount,
            permissionsGranted = accessSnapshot.permissionsGranted,
            adbAuthorized = accessSnapshot.adbAuthorized,
            vehicleKpis = vehicleKpis,
            recentEvents = formatRecentEvents(health.recentEvents),
            mainStorageCutoverDeferredReason = settings.mainStorageCutoverDeferredReason(),
            mainStorageCutoverError = settings.mainStorageCutoverError(),
            debugStorageCutoverError = settings.debugStorageCutoverError()
        )
        return DashboardStateProfileMerger.merge(
            previous = previous,
            next = next,
            healthDetailLoaded = healthDetailLoaded,
            debugStatusLoaded = debugStatusLoaded,
            vehicleKpisLoaded = vehicleKpisLoaded,
            integrationSettingsLoaded = integrationSettingsLoaded,
            runtimeSettingsLoaded = runtimeSettingsLoaded,
            archiveDetailsLoaded = archiveDetailsLoaded
        ).copy(debugParameterCount = DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT)
    }

    fun invalidateArchiveStorageSnapshot() {
        archiveStorageCache.invalidate()
    }

    fun completeArchiveStorageDeletion() {
        archiveStorageCache.completeRetiredAfterNextScan()
        archiveStorageCache.snapshot(
            limitBytes = settings.archiveStorageLimitGb() * 1024L * 1024L * 1024L,
            includeDetails = true
        )
    }

    fun retireArchiveStorageEntries(ids: Collection<String>) {
        archiveStorageCache.retire(ids)
    }

    fun restoreRetiredArchiveStorageEntries(ids: Collection<String>? = null) {
        archiveStorageCache.restoreRetired(ids)
    }

    fun invalidateIntegrationRuntime() {
        healthCaches.getValue(HealthSnapshotDetail.INTEGRATIONS).clear()
        healthCaches.getValue(HealthSnapshotDetail.FULL).clear()
    }

    private fun loadHealthSnapshot(
        store: TelemetryStore,
        running: Boolean,
        detail: HealthSnapshotDetail,
        nowMs: Long
    ): HealthSnapshot {
        val cache = healthCaches.getValue(detail)
        val runningChanged = healthCacheRunning[detail] != running
        return cache.get(nowMs = nowMs, force = runningChanged) {
            healthCacheRunning[detail] = running
            //Keep TelemetryStore's default FULL for non-dashboard callers; profiles opt in explicitly here.
            store.healthSnapshot(running = running, detail = detail, includeCounts = false)
        }
    }

    private fun formatRecentEvents(events: List<CollectorEvent>): List<CollectorEvent> {
        if (events === recentEventsSource) return formattedRecentEvents
        return events.map { event ->
            event.copy(timestamp = DisplayTimeFormatter.formatNullable(event.timestamp) ?: event.timestamp)
        }.also {
            recentEventsSource = events
            formattedRecentEvents = it
        }
    }

    private fun maintenanceHealthSnapshot(running: Boolean): HealthSnapshot {
        val dbFile = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
        return HealthSnapshot(
            running = running,
            activeSessionId = null,
            lastSuccessAt = null,
            lastError = null,
            lastErrorAt = null,
            lastPollStatus = null,
            pollCount = UNKNOWN_DASHBOARD_COUNT,
            valueRowCount = UNKNOWN_DASHBOARD_COUNT,
            ecRowCount = UNKNOWN_DASHBOARD_COUNT,
            normalizedCurrentCount = UNKNOWN_DASHBOARD_COUNT,
            normalizedHistoryCount = UNKNOWN_DASHBOARD_COUNT,
            mqttLastError = null,
            mqttLastPublishedAt = null,
            mqttPendingCount = 0L,
            mqttRetryFailureCount = 0,
            mqttNextRetryAt = null,
            mqttRetryLastFailureAt = null,
            mqttRetryLastSuccessAt = null,
            lastEcImport = null,
            lastEcImportStatus = null,
            elapsedMs = null,
            requestCount = null,
            databasePath = dbFile.absolutePath,
            databaseSizeBytes = sqliteFootprintBytes(dbFile),
            latestSoc = null,
            latestSpeed = null,
            latestCharging = null,
            recentEvents = emptyList()
        )
    }

    private fun maintenanceInfluxState(): InfluxExportStateSnapshot {
        return InfluxExportStateSnapshot(
            status = "maintenance",
            mode = null,
            pendingRows = 0L,
            oldestPendingAt = null,
            nextRetryAt = null,
            lastSuccessAt = null,
            lastErrorAt = null,
            lastError = null,
            exportedRowsTotal = 0L
        )
    }

    private fun initialInfluxState(enabled: Boolean): InfluxExportStateSnapshot {
        return maintenanceInfluxState().copy(status = if (enabled) "enabled" else "stopped")
    }

    private fun formatTrailingTimestamp(status: String?): String? {
        val value = status ?: return null
        val marker = " at "
        val index = value.lastIndexOf(marker)
        if (index < 0) return value
        val timestamp = value.substring(index + marker.length)
        val localTime = DisplayTimeFormatter.formatNullable(timestamp) ?: timestamp
        return value.substring(0, index + marker.length) + localTime
    }

    private fun formatLeadingTimestamp(status: String?): String? {
        val value = status ?: return null
        val separator = value.indexOf(' ')
        if (separator < 0) return DisplayTimeFormatter.formatNullable(value) ?: value
        val timestamp = value.substring(0, separator)
        val localTime = DisplayTimeFormatter.formatNullable(timestamp) ?: timestamp
        return localTime + value.substring(separator)
    }

    private fun formatMqttStatus(
        enabled: Boolean,
        lastError: String?,
        lastPublishedAt: String?,
        pendingCount: Long,
        retryFailureCount: Int,
        nextRetryAt: String?
    ): String {
        val base = if (enabled) "enabled" else "disabled"
        return when {
            retryFailureCount > 0 && !nextRetryAt.isNullOrBlank() -> {
                val retryAt = DisplayTimeFormatter.formatNullable(nextRetryAt) ?: nextRetryAt
                "$base; pending: $pendingCount; retry #$retryFailureCount at $retryAt"
            }
            !lastError.isNullOrBlank() -> "$base; pending: $pendingCount; error: ${lastError.truncate(96)}"
            !lastPublishedAt.isNullOrBlank() -> {
                val publishedAt = DisplayTimeFormatter.formatNullable(lastPublishedAt) ?: lastPublishedAt
                "$base; pending: $pendingCount; last publish: $publishedAt"
            }
            else -> "$base; pending: $pendingCount"
        }
    }

    private fun formatInfluxStatus(state: InfluxExportStateSnapshot): String {
        val base = "${state.status}; pending: ${state.pendingRows}"
        return when {
            !state.lastError.isNullOrBlank() -> "$base; error: ${state.lastError.truncate(96)}"
            state.status == "backoff" && !state.nextRetryAt.isNullOrBlank() -> "$base; retry at ${DisplayTimeFormatter.formatNullable(state.nextRetryAt) ?: state.nextRetryAt}"
            !state.lastSuccessAt.isNullOrBlank() -> "$base; last success: ${DisplayTimeFormatter.formatNullable(state.lastSuccessAt) ?: state.lastSuccessAt}"
            else -> base
        }
    }

    private fun lightweightDebugStatus(): DirectDebugStatus {
        val resolution = runCatching { DirectDebugDatabaseResolver.databaseFile(context) }
        val dbFile = resolution.getOrNull()
        //keeps debug card geometry stable without reading round-robin history outside All data
        return DirectDebugStatus(
            databasePath = dbFile?.absolutePath.orEmpty(),
            databaseSizeBytes = dbFile?.let(::sqliteFootprintBytes) ?: 0L,
            lastSessionId = null,
            lastSessionStartedAt = null,
            lastSessionEndedAt = null,
            lastBatchSize = null,
            candidateCount = 0,
            readingCount = UNKNOWN_DASHBOARD_COUNT,
            lastReadingAt = null,
            lastErrorAt = null,
            lastError = resolution.exceptionOrNull()?.message,
            errorCount = if (resolution.isFailure) 1L else 0L
        )
    }

    private fun String.truncate(maxLength: Int): String {
        return if (length <= maxLength) this else take(maxLength) + "..."
    }

    companion object {
        private const val TAG = "DashboardStateProvider"
    }
}
