package com.bydcollector.collector.ui

import com.bydcollector.collector.data.local.HealthSnapshotDetail

internal object DashboardStateProfileMerger {
    fun merge(
        previous: DashboardState?,
        next: DashboardState,
        healthDetailLoaded: HealthSnapshotDetail?,
        debugStatusLoaded: Boolean,
        vehicleKpisLoaded: Boolean,
        integrationSettingsLoaded: Boolean,
        runtimeSettingsLoaded: Boolean,
        archiveDetailsLoaded: Boolean
    ): DashboardState {
        if (previous == null) return next

        var merged = next
        when (healthDetailLoaded) {
            null -> merged = merged.copyHealthFrom(previous)
            HealthSnapshotDetail.SUMMARY -> merged = merged.copy(
                mqttLastError = previous.mqttLastError,
                mqttLastPublishedAt = previous.mqttLastPublishedAt,
                mqttPendingCount = previous.mqttPendingCount,
                mqttRetryFailureCount = previous.mqttRetryFailureCount,
                mqttNextRetryAt = previous.mqttNextRetryAt,
                mqttRetryLastFailureAt = previous.mqttRetryLastFailureAt,
                mqttRetryLastSuccessAt = previous.mqttRetryLastSuccessAt,
                pollCount = previous.pollCount,
                valueRowCount = previous.valueRowCount,
                ecRowCount = previous.ecRowCount,
                lastEcImport = previous.lastEcImport,
                lastEcImportStatus = previous.lastEcImportStatus,
                elapsedMs = previous.elapsedMs,
                requestCount = previous.requestCount,
                latestSoc = previous.latestSoc,
                latestSpeed = previous.latestSpeed,
                latestCharging = previous.latestCharging,
                normalizedCurrentCount = previous.normalizedCurrentCount,
                normalizedHistoryCount = previous.normalizedHistoryCount,
                recentEvents = previous.recentEvents
            )
            HealthSnapshotDetail.INTEGRATIONS -> merged = merged.copy(
                pollCount = previous.pollCount,
                valueRowCount = previous.valueRowCount,
                ecRowCount = previous.ecRowCount,
                lastEcImport = previous.lastEcImport,
                lastEcImportStatus = previous.lastEcImportStatus,
                elapsedMs = previous.elapsedMs,
                requestCount = previous.requestCount,
                latestSoc = previous.latestSoc,
                latestSpeed = previous.latestSpeed,
                latestCharging = previous.latestCharging,
                normalizedCurrentCount = previous.normalizedCurrentCount,
                normalizedHistoryCount = previous.normalizedHistoryCount,
                recentEvents = previous.recentEvents
            )
            HealthSnapshotDetail.FULL -> Unit
        }

        if (!debugStatusLoaded) {
            merged = merged.copy(
                debugParameterCount = previous.debugParameterCount,
                debugDatabasePath = previous.debugDatabasePath,
                debugDatabaseSizeBytes = previous.debugDatabaseSizeBytes,
                debugReadingCount = previous.debugReadingCount,
                debugLastReadingAt = previous.debugLastReadingAt,
                debugLastErrorAt = previous.debugLastErrorAt,
                debugLastError = previous.debugLastError,
                debugErrorCount = previous.debugErrorCount,
                debugLastSessionId = previous.debugLastSessionId
            )
        }
        if (!vehicleKpisLoaded || merged.vehicleKpis == previous.vehicleKpis) {
            merged = merged.copy(vehicleKpis = previous.vehicleKpis)
        }
        if (!integrationSettingsLoaded) {
            merged = merged.copy(
                mqttEnabled = previous.mqttEnabled,
                mqttAutoStartEnabled = previous.mqttAutoStartEnabled,
                mqttHost = previous.mqttHost,
                mqttPort = previous.mqttPort,
                mqttPasswordSet = previous.mqttPasswordSet,
                mqttClientId = previous.mqttClientId,
                mqttTopicPrefix = previous.mqttTopicPrefix,
                mqttDiscoveryPrefix = previous.mqttDiscoveryPrefix,
                mqttEnabledCategories = previous.mqttEnabledCategories,
                mqttStatus = previous.mqttStatus,
                influxEnabled = previous.influxEnabled,
                influxAutoStartEnabled = previous.influxAutoStartEnabled,
                haSharedCategoriesEnabled = previous.haSharedCategoriesEnabled,
                influxHost = previous.influxHost,
                influxPort = previous.influxPort,
                influxDatabase = previous.influxDatabase,
                influxPasswordSet = previous.influxPasswordSet,
                influxMeasurement = previous.influxMeasurement,
                influxEnabledCategories = previous.influxEnabledCategories,
                influxStatus = previous.influxStatus,
                influxPendingRows = previous.influxPendingRows,
                influxOldestPendingAt = previous.influxOldestPendingAt,
                influxNextRetryAt = previous.influxNextRetryAt,
                influxLastSuccessAt = previous.influxLastSuccessAt,
                influxLastErrorAt = previous.influxLastErrorAt,
                influxLastError = previous.influxLastError,
                influxExportedRowsTotal = previous.influxExportedRowsTotal
            )
        }
        if (!runtimeSettingsLoaded) {
            merged = merged.copy(
                autoStartEnabled = previous.autoStartEnabled,
                pollingEnabled = previous.pollingEnabled,
                debugPollingEnabled = previous.debugPollingEnabled,
                debugAutoStartEnabled = previous.debugAutoStartEnabled,
                keepWifiEnabled = previous.keepWifiEnabled,
                keepMobileDataEnabled = previous.keepMobileDataEnabled,
                keepBluetoothEnabled = previous.keepBluetoothEnabled,
                recoverCollectorServiceEnabled = previous.recoverCollectorServiceEnabled,
                tailscaleActivationEnabled = previous.tailscaleActivationEnabled,
                keepAliveEnabled = previous.keepAliveEnabled,
                keepAliveStatus = previous.keepAliveStatus
            )
        }
        if (!archiveDetailsLoaded) {
            merged = merged.copy(
                archiveStorageSnapshot = previous.archiveStorageSnapshot,
                archiveStorageScanPending = previous.archiveStorageScanPending
            )
        }
        return merged
    }

    private fun DashboardState.copyHealthFrom(previous: DashboardState): DashboardState {
        return copy(
            activeSessionId = previous.activeSessionId,
            lastSuccessAt = previous.lastSuccessAt,
            lastError = previous.lastError,
            lastErrorAt = previous.lastErrorAt,
            lastPollStatus = previous.lastPollStatus,
            pollCount = previous.pollCount,
            valueRowCount = previous.valueRowCount,
            ecRowCount = previous.ecRowCount,
            lastEcImport = previous.lastEcImport,
            lastEcImportStatus = previous.lastEcImportStatus,
            elapsedMs = previous.elapsedMs,
            requestCount = previous.requestCount,
            databasePath = previous.databasePath,
            databaseSizeBytes = previous.databaseSizeBytes,
            latestSoc = previous.latestSoc,
            latestSpeed = previous.latestSpeed,
            latestCharging = previous.latestCharging,
            mqttLastError = previous.mqttLastError,
            mqttLastPublishedAt = previous.mqttLastPublishedAt,
            mqttPendingCount = previous.mqttPendingCount,
            mqttRetryFailureCount = previous.mqttRetryFailureCount,
            mqttNextRetryAt = previous.mqttNextRetryAt,
            mqttRetryLastFailureAt = previous.mqttRetryLastFailureAt,
            mqttRetryLastSuccessAt = previous.mqttRetryLastSuccessAt,
            normalizedCurrentCount = previous.normalizedCurrentCount,
            normalizedHistoryCount = previous.normalizedHistoryCount,
            recentEvents = previous.recentEvents
        )
    }
}
