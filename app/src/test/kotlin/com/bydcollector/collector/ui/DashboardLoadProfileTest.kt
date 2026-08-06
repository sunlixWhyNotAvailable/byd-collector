package com.bydcollector.collector.ui

import com.bydcollector.collector.data.local.CollectorEvent
import com.bydcollector.collector.data.local.HealthSnapshotDetail
import com.bydcollector.collector.maintenance.ArchiveStorageJobStatus
import com.bydcollector.collector.maintenance.ArchiveStorageSnapshot
import com.bydcollector.collector.maintenance.DbMaintenanceRuntimeStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardLoadProfileTest {
    @Test
    fun profilesGateEachExpensiveSource() {
        assertFalse(DashboardLoadProfile.INITIAL.readsTelemetryStore)
        assertFalse(DashboardLoadProfile.INITIAL.readsDebugStatus)
        assertFalse(DashboardLoadProfile.INITIAL.readsVehicleKpis)
        assertEquals(HealthSnapshotDetail.SUMMARY, DashboardLoadProfile.CHROME.healthDetail)
        assertEquals(HealthSnapshotDetail.SUMMARY, DashboardLoadProfile.ALL_PARAMETERS.healthDetail)
        assertEquals(HealthSnapshotDetail.INTEGRATIONS, DashboardLoadProfile.MAIN.healthDetail)
        assertEquals(HealthSnapshotDetail.INTEGRATIONS, DashboardLoadProfile.HA.healthDetail)
        assertEquals(HealthSnapshotDetail.FULL, DashboardLoadProfile.LOGS.healthDetail)
        assertTrue(DashboardLoadProfile.ALL_PARAMETERS.readsDebugStatus)
        assertTrue(DashboardLoadProfile.LOGS.readsDebugStatus)
        assertFalse(DashboardLoadProfile.MAIN.readsDebugStatus)
        assertTrue(DashboardLoadProfile.ALL_PARAMETERS.readsVehicleKpis)
        assertFalse(DashboardLoadProfile.MAIN.readsVehicleKpis)
        assertFalse(DashboardLoadProfile.LOGS.readsVehicleKpis)
        assertTrue(DashboardLoadProfile.STORAGE.readsArchiveDetails)
        assertFalse(DashboardLoadProfile.MAIN.readsArchiveDetails)
    }

    @Test
    fun providerNeverRequestsCredentialsForDashboardState() {
        val source = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()

        assertTrue(source.contains("settings.mqttConfig(includeCredentials = false)"))
        assertTrue(source.contains("settings.influxConfig(includeCredentials = false)"))
        assertFalse(source.contains("includeCredentials = true"))
        assertFalse(source.contains("settings.mqttPassword()"))
        assertFalse(source.contains("settings.influxPassword()"))
        assertTrue(source.contains("mqttUsername = \"\""))
        assertTrue(source.contains("influxUsername = \"\""))
    }

    @Test
    fun profileMergePreservesSlicesThatWereNotLoaded() {
        val previous = dashboardState("previous").copy(
            pollCount = 42L,
            mqttPendingCount = 7L,
            debugReadingCount = 8L,
            vehicleKpis = VehicleKpis(socPercent = "88%"),
            archiveStorageSnapshot = ArchiveStorageSnapshot(
                archiveRootPath = "previous-archive",
                mainDatabaseSizeBytes = 1L,
                debugDatabaseSizeBytes = 2L,
                archiveBytes = 3L,
                archiveLimitBytes = 4L,
                entries = emptyList()
            )
        )
        val next = dashboardState("next").copy(
            lastSuccessAt = "loaded-success",
            pollCount = 0L,
            mqttPendingCount = 0L,
            debugReadingCount = 0L,
            vehicleKpis = VehicleKpis(),
            archiveStorageSnapshot = ArchiveStorageSnapshot(
                archiveRootPath = "next-archive",
                mainDatabaseSizeBytes = 0L,
                debugDatabaseSizeBytes = 0L,
                archiveBytes = 0L,
                archiveLimitBytes = 0L,
                entries = emptyList()
            )
        )

        val merged = DashboardStateProfileMerger.merge(
            previous = previous,
            next = next,
            healthDetailLoaded = HealthSnapshotDetail.SUMMARY,
            debugStatusLoaded = false,
            vehicleKpisLoaded = false,
            integrationSettingsLoaded = false,
            runtimeSettingsLoaded = false,
            archiveDetailsLoaded = false
        )

        assertEquals("loaded-success", merged.lastSuccessAt)
        assertEquals(42L, merged.pollCount)
        assertEquals(7L, merged.mqttPendingCount)
        assertEquals(8L, merged.debugReadingCount)
        assertEquals("88%", merged.vehicleKpis.socPercent)
        assertEquals("previous-archive", merged.archiveStorageSnapshot.archiveRootPath)
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun dashboardState(marker: String): DashboardState {
        return DashboardState(
            running = false,
            serviceRunning = false,
            mainPollingRunning = false,
            autoStartEnabled = false,
            pollingEnabled = false,
            activeSessionId = null,
            lastSuccessAt = marker,
            lastError = null,
            lastErrorAt = null,
            lastPollStatus = null,
            pollCount = 0L,
            valueRowCount = 0L,
            ecRowCount = 0L,
            lastEcImport = null,
            lastEcImportStatus = null,
            elapsedMs = null,
            requestCount = null,
            databasePath = marker,
            databaseSizeBytes = 0L,
            dbMaintenanceStatus = DbMaintenanceRuntimeStatus(),
            archiveStorageLimitGb = 1,
            archiveStorageSnapshot = ArchiveStorageSnapshot(
                archiveRootPath = marker,
                mainDatabaseSizeBytes = 0L,
                debugDatabaseSizeBytes = 0L,
                archiveBytes = 0L,
                archiveLimitBytes = 0L,
                entries = emptyList()
            ),
            archiveStorageScanPending = false,
            archiveStorageJobStatus = ArchiveStorageJobStatus(),
            latestSoc = null,
            latestSpeed = null,
            latestCharging = null,
            logRecording = false,
            debugPollingEnabled = false,
            debugPollingRunning = false,
            debugAutoStartEnabled = false,
            debugParameterCount = 0,
            debugDatabasePath = marker,
            debugDatabaseSizeBytes = 0L,
            debugReadingCount = 0L,
            debugLastReadingAt = null,
            debugLastErrorAt = null,
            debugLastError = null,
            debugErrorCount = 0L,
            debugLastSessionId = null,
            keepWifiEnabled = false,
            keepMobileDataEnabled = false,
            keepBluetoothEnabled = false,
            recoverCollectorServiceEnabled = false,
            tailscaleActivationEnabled = false,
            keepAliveEnabled = false,
            keepAliveStatus = "disabled",
            mqttEnabled = false,
            mqttAutoStartEnabled = false,
            mqttHost = "",
            mqttPort = 0,
            mqttUsername = "",
            mqttPasswordSet = false,
            mqttClientId = "",
            mqttTopicPrefix = "",
            mqttDiscoveryPrefix = "",
            mqttEnabledCategories = emptySet(),
            mqttStatus = "disabled",
            mqttLastError = null,
            mqttLastPublishedAt = null,
            mqttPendingCount = 0L,
            mqttRetryFailureCount = 0,
            mqttNextRetryAt = null,
            mqttRetryLastFailureAt = null,
            mqttRetryLastSuccessAt = null,
            influxEnabled = false,
            influxAutoStartEnabled = false,
            haSharedCategoriesEnabled = false,
            influxHost = "",
            influxPort = 0,
            influxDatabase = "",
            influxUsername = "",
            influxPasswordSet = false,
            influxMeasurement = "",
            influxEnabledCategories = emptySet(),
            influxStatus = "stopped",
            influxMode = null,
            influxPendingRows = 0L,
            influxOldestPendingAt = null,
            influxNextRetryAt = null,
            influxLastSuccessAt = null,
            influxLastErrorAt = null,
            influxLastError = null,
            influxExportedRowsTotal = 0L,
            normalizedCurrentCount = 0L,
            normalizedHistoryCount = 0L,
            permissionsGranted = false,
            adbAuthorized = false,
            vehicleKpis = VehicleKpis(),
            recentEvents = listOf(CollectorEvent(1L, marker, "test", marker, null))
        )
    }
}
