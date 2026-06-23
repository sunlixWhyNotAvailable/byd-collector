package com.bydcollector.collector.ui

import android.content.Context
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.debug.DirectDebugStatus
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.remote.DirectBridgeManager
import com.bydcollector.collector.diagnostics.DiagnosticLogRecorder
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.system.RequiredAccessChecker

//assembles one immutable dashboard snapshot from settings, service flags, sqlite health, and diagnostics
class DashboardStateProvider(
    private val context: Context,
    private val store: TelemetryStore,
    private val settings: CollectorSettings
) {
    fun load(
        includeTelemetryDetails: Boolean = true,
        includeDebugStatus: Boolean = false,
        includeVehicleKpis: Boolean = false
    ): DashboardState {
        val serviceRunning = CollectorService.isRunning()
        val mainPollingRunning = CollectorService.isMainPollingRunning()
        //uses main polling state for health so keep-alive-only service runs do not look like active collection
        val health = store.healthSnapshot(running = mainPollingRunning)
        val debugStatus = if (includeDebugStatus) {
            //opens the debug db only on heavy tabs because status scans can be expensive on the tablet
            DirectDebugStore(context).use { debugStore -> debugStore.status() }
        } else {
            lightweightDebugStatus()
        }
        val keepAliveConfig = settings.keepAliveConfig()
        val mqttConfig = settings.mqttConfig()
        val influxConfig = settings.influxConfig()
        val influxState = store.influxExportState()
        val vehicleKpis = if (includeVehicleKpis) {
            //kpi mapping is kept off lightweight tabs to avoid wide normalized reads during every refresh
            VehicleKpiMapper.from(store.normalizedCurrentState())
        } else {
            VehicleKpis()
        }
        return DashboardState(
            running = mainPollingRunning,
            serviceRunning = serviceRunning,
            mainPollingRunning = mainPollingRunning,
            autoStartEnabled = settings.isAutoStartEnabled(),
            pollingEnabled = settings.isPollingEnabled(),
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
            latestSoc = health.latestSoc,
            latestSpeed = health.latestSpeed,
            latestCharging = health.latestCharging,
            logRecording = DiagnosticLogRecorder.isRecording(),
            logDirectoryPath = DiagnosticLogRecorder.logDirectoryPath(context),
            logPullCommand = DiagnosticLogRecorder.logPullCommand(context),
            dbPullCommand = DiagnosticLogRecorder.dbPullCommand(),
            debugPollingEnabled = settings.isDebugPollingEnabled(),
            debugPollingRunning = CollectorService.isDebugRunning(),
            debugAutoStartEnabled = settings.isDebugAutoStartEnabled(),
            debugBatchSize = settings.debugBatchSize(),
            debugParameterCount = debugStatus.candidateCount,
            debugDatabasePath = debugStatus.databasePath,
            debugDatabaseSizeBytes = debugStatus.databaseSizeBytes,
            debugReadingCount = debugStatus.readingCount,
            debugLastReadingAt = DisplayTimeFormatter.formatNullable(debugStatus.lastReadingAt),
            debugLastErrorAt = DisplayTimeFormatter.formatNullable(debugStatus.lastErrorAt),
            debugLastError = debugStatus.lastError,
            debugErrorCount = debugStatus.errorCount,
            debugLastSessionId = debugStatus.lastSessionId,
            debugDbPullCommand = DirectDebugStore.pullCommand(),
            keepWifiEnabled = keepAliveConfig.keepWifi,
            keepMobileDataEnabled = keepAliveConfig.keepMobileData,
            keepBluetoothEnabled = keepAliveConfig.keepBluetooth,
            recoverCollectorServiceEnabled = keepAliveConfig.recoverCollectorService,
            keepAliveEnabled = keepAliveConfig.anyEnabled,
            keepAliveStatus = if (keepAliveConfig.anyEnabled) "enabled" else "disabled",
            keepAliveLogPullCommand = DiagnosticLogRecorder.keepAliveLogPullCommand(),
            mqttEnabled = mqttConfig.enabled,
            mqttAutoStartEnabled = settings.isMqttAutoStartEnabled(),
            mqttHost = mqttConfig.host,
            mqttPort = mqttConfig.port,
            mqttUsername = mqttConfig.username.orEmpty(),
            mqttPasswordSet = mqttConfig.password != null,
            mqttClientId = mqttConfig.clientId,
            mqttTopicPrefix = mqttConfig.topicPrefix,
            mqttDiscoveryPrefix = mqttConfig.discoveryPrefix,
            mqttEnabledCategories = mqttConfig.enabledCategories,
            mqttStatus = formatMqttStatus(
                enabled = mqttConfig.enabled,
                lastError = health.mqttLastError,
                lastPublishedAt = health.mqttLastPublishedAt,
                pendingCount = health.mqttPendingCount,
                retryFailureCount = health.mqttRetryFailureCount,
                nextRetryAt = health.mqttNextRetryAt
            ),
            mqttLastError = health.mqttLastError,
            mqttLastPublishedAt = DisplayTimeFormatter.formatNullable(health.mqttLastPublishedAt),
            mqttPendingCount = health.mqttPendingCount,
            mqttRetryFailureCount = health.mqttRetryFailureCount,
            mqttNextRetryAt = DisplayTimeFormatter.formatNullable(health.mqttNextRetryAt),
            mqttRetryLastFailureAt = DisplayTimeFormatter.formatNullable(health.mqttRetryLastFailureAt),
            mqttRetryLastSuccessAt = DisplayTimeFormatter.formatNullable(health.mqttRetryLastSuccessAt),
            influxEnabled = influxConfig.enabled,
            influxAutoStartEnabled = settings.isInfluxAutoStartEnabled(),
            haSharedCategoriesEnabled = settings.isHaSharedCategoriesEnabled(),
            influxHost = influxConfig.host,
            influxPort = influxConfig.port,
            influxDatabase = influxConfig.database,
            influxUsername = influxConfig.username.orEmpty(),
            influxPasswordSet = influxConfig.password != null,
            influxMeasurement = influxConfig.measurement,
            influxEnabledCategories = influxConfig.enabledCategories,
            influxStatus = formatInfluxStatus(influxState),
            influxMode = influxState.mode,
            influxPendingRows = influxState.pendingRows,
            influxOldestPendingAt = DisplayTimeFormatter.formatNullable(influxState.oldestPendingAt),
            influxNextRetryAt = DisplayTimeFormatter.formatNullable(influxState.nextRetryAt),
            influxLastSuccessAt = DisplayTimeFormatter.formatNullable(influxState.lastSuccessAt),
            influxLastErrorAt = DisplayTimeFormatter.formatNullable(influxState.lastErrorAt),
            influxLastError = influxState.lastError,
            influxExportedRowsTotal = influxState.exportedRowsTotal,
            normalizedCurrentCount = health.normalizedCurrentCount,
            normalizedHistoryCount = health.normalizedHistoryCount,
            requiredAccessRows = RequiredAccessChecker.displayCheck(context),
            directBridgeStatus = DirectBridgeManager.status(context),
            vehicleKpis = vehicleKpis,
            recentEvents = health.recentEvents.map { event ->
                event.copy(timestamp = DisplayTimeFormatter.formatNullable(event.timestamp) ?: event.timestamp)
            }
        )
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

    private fun formatInfluxStatus(state: com.bydcollector.collector.influx.InfluxExportStateSnapshot): String {
        val base = "${state.status}; pending: ${state.pendingRows}"
        return when {
            !state.lastError.isNullOrBlank() -> "$base; error: ${state.lastError.truncate(96)}"
            !state.nextRetryAt.isNullOrBlank() -> "$base; retry at ${DisplayTimeFormatter.formatNullable(state.nextRetryAt) ?: state.nextRetryAt}"
            !state.lastSuccessAt.isNullOrBlank() -> "$base; last success: ${DisplayTimeFormatter.formatNullable(state.lastSuccessAt) ?: state.lastSuccessAt}"
            else -> base
        }
    }

    private fun lightweightDebugStatus(): DirectDebugStatus {
        val dbFile = context.getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME)
        //keeps debug card geometry stable without reading round-robin history outside debug/log tabs
        return DirectDebugStatus(
            databasePath = dbFile.absolutePath,
            databaseSizeBytes = dbFile.takeIf { it.exists() }?.length() ?: 0L,
            lastSessionId = null,
            lastSessionStartedAt = null,
            lastSessionEndedAt = null,
            lastBatchSize = null,
            candidateCount = 0,
            readingCount = 0,
            lastReadingAt = null,
            lastErrorAt = null,
            lastError = null,
            errorCount = 0
        )
    }

    private fun String.truncate(maxLength: Int): String {
        return if (length <= maxLength) this else take(maxLength) + "..."
    }
}
