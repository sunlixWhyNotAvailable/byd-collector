package com.bydcollector.collector.ui

import com.bydcollector.collector.data.local.CollectorEvent
import com.bydcollector.collector.maintenance.ArchiveStorageJobStatus
import com.bydcollector.collector.maintenance.ArchiveStorageSnapshot
import com.bydcollector.collector.maintenance.DbMaintenanceRuntimeStatus
import com.bydcollector.collector.ui.compose.AppTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardUiStateStoreTest {
    @Test
    fun seedPopulatesChromeAndEveryEmptyTab() {
        val initial = dashboardState("initial")
        val store = DashboardUiStateStore { 100L }

        assertNull(store.chromeState.value)
        store.seed(initial)

        assertEquals(initial, store.chromeState.value?.state)
        assertEquals(AppTab.entries.size, AppTab.entries.count { store.currentTab(it) == initial })
        assertEquals(100L, store.chromeState.value?.loadedAtElapsedMs)
        assertEquals(
            store.chromeState.value?.generation,
            store.tabState(AppTab.MAIN).value?.generation
        )
    }

    @Test
    fun seedNeverOverwritesExistingSnapshots() {
        val first = dashboardState("first")
        val second = dashboardState("second")
        val refreshed = dashboardState("refreshed")
        val store = DashboardUiStateStore { 100L }
        store.seed(first)
        val generation = store.beginTabRefresh(AppTab.MAIN)
        assertTrue(store.publishTab(AppTab.MAIN, generation, refreshed))

        store.seed(second)

        assertEquals(first, store.currentChrome())
        assertEquals(refreshed, store.currentTab(AppTab.MAIN))
        assertEquals(first, store.currentTab(AppTab.EXTRA))
    }

    @Test
    fun rapidTabGenerationsRejectOlderCompletion() {
        val store = DashboardUiStateStore { 10L }
        store.seed(dashboardState("initial"))
        val first = store.beginTabRefresh(AppTab.MAIN)
        val second = store.beginTabRefresh(AppTab.MAIN)

        assertTrue(second > first)
        assertFalse(store.publishTab(AppTab.MAIN, first, dashboardState("stale")))
        assertTrue(store.tabState(AppTab.MAIN).value?.inFlight == true)
        assertEquals(second, store.tabState(AppTab.MAIN).value?.generation)
        assertTrue(store.publishTab(AppTab.MAIN, second, dashboardState("newer")))
        assertFalse(store.tabState(AppTab.MAIN).value?.inFlight == true)
    }

    @Test
    fun failureRetainsPreviousStateAndRejectsLateCompletion() {
        var now = 500L
        val previous = dashboardState("previous")
        val store = DashboardUiStateStore { now }
        store.seed(previous)
        val generation = store.beginTabRefresh(AppTab.STORAGE)

        now = 900L
        assertTrue(store.failTab(AppTab.STORAGE, generation, "offline"))
        val failed = store.tabState(AppTab.STORAGE).value ?: error("missing cached state")
        assertEquals(previous, failed.state)
        assertEquals(500L, failed.loadedAtElapsedMs)
        assertEquals("offline", failed.lastError)
        assertFalse(failed.inFlight)
        assertFalse(store.publishTab(AppTab.STORAGE, generation, dashboardState("late")))
        assertEquals(previous, store.currentTab(AppTab.STORAGE))
    }

    @Test
    fun chromeAndTabFlowsAreIndependent() {
        val chrome = dashboardState("chrome")
        val main = dashboardState("main")
        val store = DashboardUiStateStore { 1L }
        store.seed(chrome)

        val generation = store.beginTabRefresh(AppTab.MAIN)
        assertTrue(store.publishTab(AppTab.MAIN, generation, main))

        assertEquals(chrome, store.chromeState.value?.state)
        assertEquals(main, store.tabState(AppTab.MAIN).value?.state)
        assertEquals(chrome, store.tabState(AppTab.EXTRA).value?.state)
        assertNotSame(store.chromeState, store.tabState(AppTab.MAIN))
    }

    @Test
    fun markingTabStaleDoesNotBlankIt() {
        val previous = dashboardState("previous")
        val store = DashboardUiStateStore { 1L }
        store.seed(previous)

        store.markTabStale(AppTab.EXTRA)

        val marked = store.tabState(AppTab.EXTRA).value ?: error("missing cached state")
        assertEquals(previous, marked.state)
        assertEquals(0L, marked.loadedAtElapsedMs)
        assertEquals(previous, store.currentTab(AppTab.EXTRA))
        assertNull(store.chromeState.value?.lastError)
    }

    @Test
    fun producerOwnedValuesSurviveAStaleTabRefresh() {
        val store = DashboardUiStateStore { 1L }
        store.seed(dashboardState("initial"))
        val countGeneration = store.beginCountBootstrap() ?: error("count bootstrap did not start")
        store.publishRowCountBaseline(
            countGeneration,
            DashboardRowCounts(10L, 11L, 12L, 13L, 14L, 15L)
        )
        store.incrementMainRowCounts(pollRows = 1L, normalizedHistoryRows = 2L)
        store.incrementDebugReadingCount(3L)
        store.publishVehicleKpis(
            uk = VehicleKpis(socPercent = "88%", odometerKm = "1 234 км"),
            en = VehicleKpis(socPercent = "88%", odometerKm = "1 234 km")
        )
        store.publishIntegrationRuntime(
            dashboardState("integration").copy(
                mqttEnabled = false,
                mqttStatus = "runtime-ok",
                influxEnabled = false,
                influxStatus = "runtime-error"
            )
        )
        store.publishRuntimeFlags(
            DashboardRuntimeFlags(
                serviceRunning = true,
                mainPollingRunning = true,
                debugPollingRunning = true,
                pollingEnabled = true,
                debugPollingEnabled = true,
                mqttEnabled = true,
                influxEnabled = true,
                permissionsGranted = true,
                adbAuthorized = true,
                dbMaintenanceStatus = DbMaintenanceRuntimeStatus(running = true),
                archiveStorageJobStatus = ArchiveStorageJobStatus(running = true)
            )
        )
        store.publishDatabaseFootprints(100L, 200L)

        val generation = store.beginTabRefresh(AppTab.ALL_PARAMETERS)
        assertTrue(store.publishTab(AppTab.ALL_PARAMETERS, generation, dashboardState("stale")))
        val state = store.currentTab(AppTab.ALL_PARAMETERS) ?: error("missing state")

        assertEquals(11L, state.pollCount)
        assertEquals(16L, state.normalizedHistoryCount)
        assertEquals(18L, state.debugReadingCount)
        assertEquals("88%", state.vehicleKpis.socPercent)
        assertEquals("1 234 км", state.vehicleKpis.odometerKm)
        assertEquals("runtime-ok", state.mqttStatus)
        assertEquals("runtime-error", state.influxStatus)
        assertTrue(state.mqttEnabled)
        assertTrue(state.influxEnabled)
        assertEquals(DebugRuntimeStatus.RUNNING, state.debugRuntimeStatus)
        assertEquals(100L, state.databaseSizeBytes)
        assertEquals(200L, state.debugDatabaseSizeBytes)
        assertTrue(state.dbMaintenanceStatus.running)
        assertTrue(state.archiveStorageJobStatus.running)

        store.selectVehicleKpiLanguage(VehicleKpiLanguage.EN)
        assertEquals("1 234 km", store.currentTab(AppTab.ALL_PARAMETERS)?.vehicleKpis?.odometerKm)
    }

    @Test
    fun initialHydrationCanStartOnlyOncePerProcessStore() {
        val store = DashboardUiStateStore { 1L }

        assertTrue(store.beginInitialHydration())
        assertFalse(store.beginInitialHydration())
    }

    @Test
    fun storageCutoverInvalidatesCountsAndAllowsOneRecount() {
        val store = DashboardUiStateStore { 1L }
        store.seed(dashboardState("initial"))
        val first = store.beginCountBootstrap() ?: error("count bootstrap did not start")
        store.publishRowCountBaseline(first, DashboardRowCounts(1L, 2L, 3L, 4L, 5L, 6L))
        assertNull(store.beginCountBootstrap())

        store.invalidateRowCounts()
        store.incrementMainRowCounts(pollRows = 1L, valueRows = 1L)
        store.incrementDebugReadingCount(1L)

        assertTrue(store.beginCountBootstrap() != null)
        assertNull(store.beginCountBootstrap())
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
