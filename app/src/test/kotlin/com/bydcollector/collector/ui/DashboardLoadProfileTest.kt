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
    fun debugStorageGateRunsBeforeAnyDashboardDebugDatabaseOpen() {
        val source = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()
        val gate = source.indexOf("BydCollectorApplication.ensureDebugStorageReady(context)")
        val open = source.indexOf("DirectDebugStore(context).use")

        assertTrue(gate >= 0)
        assertTrue(open > gate)
        assertTrue(source.contains("val debugStatusLoaded = debugStatusRequested &&"))
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

    @Test
    fun nullHealthMergePreservesHealthAcrossStorageAndExtraMaintenanceProfiles() {
        val previous = dashboardState("previous").copy(
            activeSessionId = 42L,
            pollCount = 43L,
            mqttPendingCount = 44L,
            normalizedHistoryCount = 45L,
            autoStartEnabled = false,
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
            activeSessionId = null,
            pollCount = 0L,
            mqttPendingCount = 0L,
            normalizedHistoryCount = 0L,
            autoStartEnabled = true,
            dbMaintenanceStatus = DbMaintenanceRuntimeStatus(running = true, messageUk = "maintenance-next"),
            archiveStorageSnapshot = ArchiveStorageSnapshot(
                archiveRootPath = "next-archive",
                mainDatabaseSizeBytes = 10L,
                debugDatabaseSizeBytes = 20L,
                archiveBytes = 30L,
                archiveLimitBytes = 40L,
                entries = emptyList()
            ),
            archiveStorageJobStatus = ArchiveStorageJobStatus(running = true, messageUk = "archive-next")
        )

        val storage = DashboardStateProfileMerger.merge(
            previous = previous,
            next = next,
            healthDetailLoaded = null,
            debugStatusLoaded = false,
            vehicleKpisLoaded = false,
            integrationSettingsLoaded = false,
            runtimeSettingsLoaded = false,
            archiveDetailsLoaded = true
        )
        val extra = DashboardStateProfileMerger.merge(
            previous = previous,
            next = next,
            healthDetailLoaded = null,
            debugStatusLoaded = false,
            vehicleKpisLoaded = false,
            integrationSettingsLoaded = false,
            runtimeSettingsLoaded = true,
            archiveDetailsLoaded = false
        )

        listOf(storage, extra).forEach { merged ->
            assertEquals(42L, merged.activeSessionId)
            assertEquals("previous", merged.lastSuccessAt)
            assertEquals(43L, merged.pollCount)
            assertEquals(44L, merged.mqttPendingCount)
            assertEquals(45L, merged.normalizedHistoryCount)
            assertEquals("previous", merged.databasePath)
            assertTrue(merged.dbMaintenanceStatus.running)
            assertTrue(merged.archiveStorageJobStatus.running)
        }
        assertEquals("next-archive", storage.archiveStorageSnapshot.archiveRootPath)
        assertFalse(storage.autoStartEnabled)
        assertEquals("previous-archive", extra.archiveStorageSnapshot.archiveRootPath)
        assertTrue(extra.autoStartEnabled)
    }

    @Test
    fun integrationHealthMergeKeepsNonIntegrationHealthFromPreviousSnapshot() {
        val previous = dashboardState("previous").copy(
            pollCount = 42L,
            normalizedCurrentCount = 43L,
            mqttPendingCount = 1L,
            mqttLastError = "previous-mqtt"
        )
        val next = dashboardState("next").copy(
            pollCount = 0L,
            normalizedCurrentCount = 0L,
            mqttPendingCount = 9L,
            mqttLastError = "next-mqtt"
        )

        val merged = DashboardStateProfileMerger.merge(
            previous = previous,
            next = next,
            healthDetailLoaded = HealthSnapshotDetail.INTEGRATIONS,
            debugStatusLoaded = false,
            vehicleKpisLoaded = false,
            integrationSettingsLoaded = true,
            runtimeSettingsLoaded = false,
            archiveDetailsLoaded = false
        )

        assertEquals(42L, merged.pollCount)
        assertEquals(43L, merged.normalizedCurrentCount)
        assertEquals("next", merged.databasePath)
        assertEquals("previous", merged.recentEvents.single().timestamp)
        assertEquals(9L, merged.mqttPendingCount)
        assertEquals("next-mqtt", merged.mqttLastError)
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
