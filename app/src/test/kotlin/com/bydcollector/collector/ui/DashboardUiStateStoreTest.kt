package com.bydcollector.collector.ui

import com.bydcollector.collector.dashboardSnapshotDue
import com.bydcollector.collector.data.local.CollectorEvent
import com.bydcollector.collector.data.callback.CallbackQueuePhase
import com.bydcollector.collector.data.callback.CallbackQueueState
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
    fun firstClientSnapshotIncludesLiveQueuesWithoutDatabaseHealth() {
        val store = DashboardUiStateStore { 1L }
        val initial = dashboardState("initial")
        val flags = DashboardRuntimeFlags(
            serviceRunning = true, mainPollingRunning = true, debugPollingRunning = true,
            pollingEnabled = false, debugPollingEnabled = false, mqttEnabled = false, influxEnabled = false,
            permissionsGranted = true, adbAuthorized = true, dbMaintenanceStatus = DbMaintenanceRuntimeStatus(),
            archiveStorageJobStatus = ArchiveStorageJobStatus(),
            mainCallbackQueue = CallbackQueueState(CallbackQueuePhase.HEALTHY, null),
            secondaryCallbackQueue = CallbackQueueState(CallbackQueuePhase.CATCHING_UP, "pending_head_age"))
        store.publishRuntimeFlags(flags)

        val first = store.snapshotForClient(initial, VehicleKpiLanguage.UK)
        assertTrue(first.mainPollingRunning)
        assertTrue(first.debugPollingRunning)
        assertFalse(first.pollingEnabled)
        assertFalse(first.debugPollingEnabled)
        assertNull(first.lastPollStatus)
        assertEquals(flags.mainCallbackQueue, first.mainCallbackQueue)
        assertEquals(flags.secondaryCallbackQueue, first.secondaryCallbackQueue)

        val stopped = flags.copy(mainPollingRunning = false, mainRuntimeStatus = RuntimeActionStatus.STOPPED,
            mainCallbackQueue = null, debugRuntimeStatus = DebugRuntimeStatus.ERROR,
            secondaryCallbackQueue = CallbackQueueState(CallbackQueuePhase.ERROR, "reader_failed"))
        store.publishRuntimeFlags(stopped)
        val reopened = store.snapshotForClient(initial, VehicleKpiLanguage.EN)
        assertFalse(reopened.mainPollingRunning)
        assertEquals(RuntimeActionStatus.STOPPED, reopened.mainRuntimeStatus)
        assertNull(reopened.mainCallbackQueue)
        assertEquals(DebugRuntimeStatus.ERROR, reopened.debugRuntimeStatus)
        assertEquals(stopped.secondaryCallbackQueue, reopened.secondaryCallbackQueue)
    }

    @Test
    fun frequentCachedUpdatesCannotStarveStorageCompletionOrChromeRefresh() {
        var now = 100L
        val store = DashboardUiStateStore { now }
        val pending = dashboardState("storage").copy(archiveStorageScanPending = true)
        store.seed(pending)
        // Notifications arrive faster than the 1 s pending-storage refresh deadline.
        repeat(9) {
            now += 100L
            store.publishTab(AppTab.STORAGE, store.beginTabRefresh(AppTab.STORAGE), pending, dataRefreshed = false)
            store.publishChrome(store.beginChromeRefresh(), pending, dataRefreshed = false)
        }
        assertEquals(100L, store.tabState(AppTab.STORAGE).value!!.loadedAtElapsedMs)
        assertEquals(100L, store.chromeState.value!!.loadedAtElapsedMs)
        now = 1_100L
        assertTrue(dashboardSnapshotDue(store.tabState(AppTab.STORAGE).value!!.loadedAtElapsedMs, 1_000L, now))
        store.publishTab(AppTab.STORAGE, store.beginTabRefresh(AppTab.STORAGE), pending.copy(archiveStorageScanPending = false))
        assertFalse(store.currentTab(AppTab.STORAGE)!!.archiveStorageScanPending)
        assertTrue(store.currentTab(AppTab.STORAGE)!!.archiveStorageSnapshot.entries.isEmpty())
        assertEquals(now, store.tabState(AppTab.STORAGE).value!!.loadedAtElapsedMs)

        store.markTabStale(AppTab.STORAGE)
        store.publishTab(AppTab.STORAGE, store.beginTabRefresh(AppTab.STORAGE), pending, dataRefreshed = false)
        assertEquals(0L, store.tabState(AppTab.STORAGE).value!!.loadedAtElapsedMs)
    }

    @Test
    fun liveQueuesStayIndependentAndSurviveAnOlderDatabaseSnapshot() {
        val store = DashboardUiStateStore { 1L }
        store.seed(dashboardState("initial"))
        val main = CallbackQueueState(CallbackQueuePhase.HEALTHY, null)
        val secondary = CallbackQueueState(CallbackQueuePhase.CATCHING_UP, "pending_head_age")
        store.publishRuntimeFlags(DashboardRuntimeFlags(
            serviceRunning = true, mainPollingRunning = true, debugPollingRunning = true,
            pollingEnabled = true, debugPollingEnabled = true, mqttEnabled = false, influxEnabled = false,
            permissionsGranted = true, adbAuthorized = true, dbMaintenanceStatus = DbMaintenanceRuntimeStatus(),
            archiveStorageJobStatus = ArchiveStorageJobStatus(), mainCallbackQueue = main, secondaryCallbackQueue = secondary))
        val generation = store.beginChromeRefresh()
        store.publishChrome(generation, dashboardState("stale database"))
        assertEquals(main, store.currentChrome()?.mainCallbackQueue)
        assertEquals(secondary, store.currentChrome()?.secondaryCallbackQueue)
        assertEquals(main, store.currentTab(AppTab.MAIN)?.mainCallbackQueue)
        assertEquals(secondary, store.currentTab(AppTab.ALL_PARAMETERS)?.secondaryCallbackQueue)
    }

    @Test
    fun backgroundAccessCheckSurvivesReopeningAndStaleServiceFlags() {
        val store = DashboardUiStateStore { 1L }
        // The notification listener may finish a check before the first Activity is opened.
        store.publishAccessStatus(permissionsGranted = true, adbAuthorized = true)
        store.seed(dashboardState("old snapshot"))
        store.publishRuntimeFlags(
            DashboardRuntimeFlags(
                serviceRunning = false, mainPollingRunning = false, debugPollingRunning = false,
                pollingEnabled = false, debugPollingEnabled = false,
                mqttEnabled = false, influxEnabled = false,
                permissionsGranted = false, adbAuthorized = false,
                dbMaintenanceStatus = DbMaintenanceRuntimeStatus(),
                archiveStorageJobStatus = ArchiveStorageJobStatus()
            )
        )
        val refresh = store.beginChromeRefresh()
        assertTrue(store.publishChrome(refresh, dashboardState("stale provider")))
        assertTrue(store.currentChrome()!!.permissionsGranted)
        assertTrue(store.currentChrome()!!.adbAuthorized)
        assertFalse(store.currentChrome()!!.serviceRunning)
        assertFalse(store.currentChrome()!!.pollingEnabled)
        assertFalse(store.currentChrome()!!.debugPollingEnabled)

        // A genuine later failure replaces the completed pair; a refresh cannot restore old success.
        store.publishAccessStatus(permissionsGranted = false, adbAuthorized = false)
        val next = store.beginTabRefresh(AppTab.EXTRA)
        store.publishTab(AppTab.EXTRA, next,
            dashboardState("old success").copy(permissionsGranted = true, adbAuthorized = true))
        assertFalse(store.currentChrome()!!.permissionsGranted)
        assertFalse(store.currentChrome()!!.adbAuthorized)
        assertFalse(store.currentTab(AppTab.EXTRA)!!.permissionsGranted)
        assertFalse(store.currentTab(AppTab.EXTRA)!!.adbAuthorized)
    }

    @Test
    fun seedPopulatesChromeAndEveryEmptyTab() {
        val initial = dashboardState("initial")
        val store = DashboardUiStateStore { 100L }

        assertNull(store.chromeState.value)
        store.seed(initial)

        assertEquals(initial, store.chromeState.value?.state?.withoutFreshness())
        assertEquals(AppTab.entries.size, AppTab.entries.count { store.currentTab(it)?.withoutFreshness() == initial })
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

        assertEquals(first, store.currentChrome()?.withoutFreshness())
        assertEquals(refreshed, store.currentTab(AppTab.MAIN)?.withoutFreshness())
        assertEquals(first, store.currentTab(AppTab.EXTRA)?.withoutFreshness())
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
        assertEquals(previous, failed.state.withoutFreshness())
        assertEquals(500L, failed.loadedAtElapsedMs)
        assertEquals("offline", failed.lastError)
        assertFalse(failed.inFlight)
        assertFalse(store.publishTab(AppTab.STORAGE, generation, dashboardState("late")))
        assertEquals(previous, store.currentTab(AppTab.STORAGE)?.withoutFreshness())
    }

    @Test
    fun chromeAndTabFlowsAreIndependent() {
        val chrome = dashboardState("chrome")
        val main = dashboardState("main")
        val store = DashboardUiStateStore { 1L }
        store.seed(chrome)

        val generation = store.beginTabRefresh(AppTab.MAIN)
        assertTrue(store.publishTab(AppTab.MAIN, generation, main))

        assertEquals(chrome, store.chromeState.value?.state?.withoutFreshness())
        assertEquals(main, store.tabState(AppTab.MAIN).value?.state?.withoutFreshness())
        assertEquals(chrome, store.tabState(AppTab.EXTRA).value?.state?.withoutFreshness())
        assertNotSame(store.chromeState, store.tabState(AppTab.MAIN))
    }

    @Test
    fun markingTabStaleDoesNotBlankIt() {
        val previous = dashboardState("previous")
        val store = DashboardUiStateStore { 1L }
        store.seed(previous)

        store.markTabStale(AppTab.EXTRA)

        val marked = store.tabState(AppTab.EXTRA).value ?: error("missing cached state")
        assertEquals(previous, marked.state.withoutFreshness())
        assertEquals(0L, marked.loadedAtElapsedMs)
        assertEquals(previous, store.currentTab(AppTab.EXTRA)?.withoutFreshness())
        assertNull(store.chromeState.value?.lastError)
    }

    @Test
    fun archiveInvalidationSurvivesAnOlderInFlightLoadUntilFreshStorageLoad() {
        var now = 100L
        val store = DashboardUiStateStore { now }
        store.seed(dashboardState("raw archive"))
        val staleLoad = store.beginTabRefresh(AppTab.STORAGE)

        store.markTabStale(AppTab.STORAGE)
        assertFalse(store.publishTab(AppTab.STORAGE, staleLoad, dashboardState("old scan")))
        assertFalse(store.failTab(AppTab.STORAGE, staleLoad, "old failure"))
        assertEquals(0L, store.tabState(AppTab.STORAGE).value?.loadedAtElapsedMs)
        assertEquals("raw archive", store.currentTab(AppTab.STORAGE)?.databasePath)

        now = 200L
        val freshLoad = store.beginTabRefresh(AppTab.STORAGE)
        assertTrue(store.publishTab(AppTab.STORAGE, freshLoad, dashboardState("ready zip")))
        assertEquals(200L, store.tabState(AppTab.STORAGE).value?.loadedAtElapsedMs)
        assertEquals("ready zip", store.currentTab(AppTab.STORAGE)?.databasePath)
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
        store.publishDatabaseFootprints(100L, 200L, 300L)

        val generation = store.beginTabRefresh(AppTab.ALL_PARAMETERS)
        assertTrue(store.publishTab(AppTab.ALL_PARAMETERS, generation, dashboardState("stale")))
        val state = store.currentTab(AppTab.ALL_PARAMETERS) ?: error("missing state")

        assertEquals(10L, state.pollCount)
        assertEquals(14L, state.normalizedHistoryCount)
        assertEquals(15L, state.debugReadingCount)
        assertEquals("88%", state.vehicleKpis.socPercent)
        assertEquals("1 234 км", state.vehicleKpis.odometerKm)
        assertEquals("runtime-ok", state.mqttStatus)
        assertEquals("runtime-error", state.influxStatus)
        assertTrue(state.mqttEnabled)
        assertTrue(state.influxEnabled)
        assertEquals(DebugRuntimeStatus.RUNNING, state.debugRuntimeStatus)
        assertEquals(100L, state.databaseSizeBytes)
        assertEquals(200L, state.debugDatabaseSizeBytes)
        assertEquals(300L, state.archiveStorageSnapshot.tripsDatabaseSizeBytes)
        assertEquals(600L, state.archiveStorageSnapshot.activeDatabaseSizeBytes)
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
    fun latePollRestoreCannotReplaceLiveProducerEvenWithoutUiSeed() {
        val store = DashboardUiStateStore { 1L }
        val restored = DashboardMainPollState(1L, "old", null, null, "old status", 10L, 2)
        val live = restored.copy(activeSessionId = 2L, lastSuccessAt = "new", lastPollStatus = "new status")
        store.publishMainPollState(live)
        store.restoreMainPollState(restored)
        assertEquals(live, store.mainPollSnapshot())
        store.seed(dashboardState("UI opened"))
        assertEquals("new", store.currentChrome()?.lastSuccessAt)
        assertEquals(2L, store.currentChrome()?.activeSessionId)
    }

    @Test
    fun storageCutoverInvalidatesCountsAndAllowsOneRecount() {
        val store = DashboardUiStateStore { 1L }
        store.seed(dashboardState("initial"))
        val first = store.beginCountBootstrap() ?: error("count bootstrap did not start")
        store.publishRowCountBaseline(first, DashboardRowCounts(1L, 2L, 3L, 4L, 5L, 6L))
        assertNull(store.beginCountBootstrap())

        store.invalidateRowCounts()

        assertEquals(UNKNOWN_DASHBOARD_COUNT, store.rowCountSnapshot().pollCount)
        assertEquals(UNKNOWN_DASHBOARD_COUNT, store.rowCountSnapshot().debugReadingCount)
        assertTrue(store.beginCountBootstrap() != null)
        assertNull(store.beginCountBootstrap())
    }

    @Test
    fun staleSnapshotKeepsOnlyTheNewerQueueGroupsAndAcceptsOtherFields() {
        val callbackQueue = CallbackQueueState(CallbackQueuePhase.HEALTHY, null)
        val secondaryQueue = CallbackQueueState(CallbackQueuePhase.CATCHING_UP, "pending_head_age")
        val current = dashboardState("cached").copy(
            pollCount = 44L,
            valueRowCount = 45L,
            ecRowCount = 46L,
            normalizedCurrentCount = 47L,
            normalizedHistoryCount = 48L,
            debugReadingCount = 49L,
            mqttStatus = "cached integration status",
            influxExportedRowsTotal = 67L,
            mainCallbackQueue = callbackQueue,
            secondaryCallbackQueue = secondaryQueue,
            mqttPendingCount = 21L,
            influxPendingRows = 34L,
            influxOldestPendingAt = "cached-oldest",
            freshness = DashboardSnapshotFreshness(
                ownerStartedAtElapsedMs = 100L,
                ownerIncarnationId = "owner-a",
                ownerStorageGeneration = 2L,
                ownerRevision = 10L,
                ownerMeasuredAtElapsedMs = 120L,
                callbackQueueRevision = 5L,
                callbackQueueMeasuredAtElapsedMs = 115L,
                rowCountRevision = 2L,
                rowCountMeasuredAtElapsedMs = 111L,
                rowCountStorageGeneration = 2L,
                queueMetricsRevision = 8L,
                queueMetricsMeasuredAtElapsedMs = 118L,
                integrationRevision = 7L,
                integrationMeasuredAtElapsedMs = 116L,
                integrationStorageGeneration = 2L
            )
        )
        val incoming = dashboardState("delayed IPC").copy(
            lastSuccessAt = "new tab measurement",
            mqttEnabled = true,
            influxEnabled = true,
            mqttStatus = "new tab integration status",
            mainCallbackQueue = CallbackQueueState(CallbackQueuePhase.ERROR, "stale"),
            secondaryCallbackQueue = null,
            mqttPendingCount = 1L,
            influxPendingRows = 2L,
            influxOldestPendingAt = "stale-oldest",
            freshness = current.freshness.copy(
                ownerRevision = 9L,
                ownerMeasuredAtElapsedMs = 119L,
                callbackQueueRevision = 4L,
                callbackQueueMeasuredAtElapsedMs = 114L,
                rowCountRevision = 1L,
                rowCountMeasuredAtElapsedMs = 110L,
                rowCountStorageGeneration = 1L,
                queueMetricsRevision = 7L,
                queueMetricsMeasuredAtElapsedMs = 117L,
                integrationRevision = 6L,
                integrationMeasuredAtElapsedMs = 113L,
                integrationStorageGeneration = 2L
            )
        )

        val merged = mergeDashboardFreshQueueOverlay(current, incoming)

        assertEquals(callbackQueue, merged.mainCallbackQueue)
        assertEquals(secondaryQueue, merged.secondaryCallbackQueue)
        assertEquals(21L, merged.mqttPendingCount)
        assertEquals(34L, merged.influxPendingRows)
        assertEquals("cached-oldest", merged.influxOldestPendingAt)
        assertEquals(44L, merged.pollCount)
        assertEquals(45L, merged.valueRowCount)
        assertEquals(46L, merged.ecRowCount)
        assertEquals(47L, merged.normalizedCurrentCount)
        assertEquals(48L, merged.normalizedHistoryCount)
        assertEquals(49L, merged.debugReadingCount)
        assertEquals("cached integration status", merged.mqttStatus)
        assertTrue(merged.mqttEnabled)
        assertTrue(merged.influxEnabled)
        assertEquals(67L, merged.influxExportedRowsTotal)
        assertEquals("new tab measurement", merged.lastSuccessAt)
        assertEquals(5L, merged.freshness.callbackQueueRevision)
        assertEquals(2L, merged.freshness.rowCountRevision)
        assertEquals(8L, merged.freshness.queueMetricsRevision)
        assertEquals(7L, merged.freshness.integrationRevision)
        assertEquals(10L, merged.freshness.ownerRevision)
    }

    @Test
    fun newerOwnerIncarnationWinsEvenWhenItsGroupRevisionIsLower() {
        val oldOwner = dashboardState("old owner").copy(
            mqttPendingCount = 90L,
            mainCallbackQueue = CallbackQueueState(CallbackQueuePhase.ERROR, "old"),
            freshness = DashboardSnapshotFreshness(
                ownerStartedAtElapsedMs = 100L,
                ownerIncarnationId = "owner-old",
                ownerStorageGeneration = 9L,
                callbackQueueRevision = 100L,
                queueMetricsRevision = 100L
            )
        )
        val newOwner = dashboardState("new owner").copy(
            mqttPendingCount = 3L,
            mainCallbackQueue = CallbackQueueState(CallbackQueuePhase.HEALTHY, null),
            freshness = DashboardSnapshotFreshness(
                ownerStartedAtElapsedMs = 200L,
                ownerIncarnationId = "owner-new",
                ownerStorageGeneration = 0L,
                callbackQueueRevision = 1L,
                queueMetricsRevision = 1L
            )
        )
        val active = mergeDashboardFreshQueueOverlay(oldOwner, newOwner)
        val delayedOld = oldOwner.copy(lastSuccessAt = "unrelated old tab fields")

        val merged = mergeDashboardFreshQueueOverlay(active, delayedOld)

        assertEquals(newOwner.mainCallbackQueue, active.mainCallbackQueue)
        assertEquals(3L, active.mqttPendingCount)
        assertEquals(newOwner.mainCallbackQueue, merged.mainCallbackQueue)
        assertEquals(3L, merged.mqttPendingCount)
        assertEquals("unrelated old tab fields", merged.lastSuccessAt)
        assertEquals("owner-new", merged.freshness.ownerIncarnationId)
    }

    @Test
    fun equalMeasurementStampsKeepPublishedGroupPayloads() {
        val freshness = DashboardSnapshotFreshness(
            ownerStartedAtElapsedMs = 200L,
            ownerIncarnationId = "owner-current",
            rowCountRevision = 2L,
            rowCountMeasuredAtElapsedMs = 20L,
            queueMetricsRevision = 3L,
            queueMetricsMeasuredAtElapsedMs = 30L,
            integrationRevision = 4L,
            integrationMeasuredAtElapsedMs = 40L
        )
        val current = dashboardState("cached").copy(
            pollCount = 10L,
            mqttPendingCount = 11L,
            influxPendingRows = 12L,
            mqttStatus = "published integration status",
            freshness = freshness
        )
        val conflictingDuplicate = dashboardState("duplicate").copy(
            lastSuccessAt = "new tab field",
            pollCount = 20L,
            mqttPendingCount = 21L,
            influxPendingRows = 22L,
            mqttStatus = "conflicting duplicate status",
            freshness = freshness
        )

        val merged = mergeDashboardFreshQueueOverlay(current, conflictingDuplicate)

        assertEquals(10L, merged.pollCount)
        assertEquals(11L, merged.mqttPendingCount)
        assertEquals(12L, merged.influxPendingRows)
        assertEquals("published integration status", merged.mqttStatus)
        assertEquals("new tab field", merged.lastSuccessAt)
    }

    @Test
    fun cachedReadsDoNotRenewIndependentQueueOrIntegrationMeasurements() {
        var now = 10L
        val store = DashboardUiStateStore { now }
        val initial = dashboardState("initial")
        val countGeneration = store.beginCountBootstrap() ?: error("count bootstrap did not start")
        now = 50L
        assertTrue(store.publishRowCountBaseline(countGeneration, DashboardRowCounts(1L, 2L, 3L, 4L, 5L, 6L)))
        val storageGeneration = store.storageGenerationSnapshot()
        assertTrue(store.publishQueueMetrics(7L, 8L, "oldest", 55L, storageGeneration))
        assertTrue(store.publishIntegrationRuntime(dashboardState("integration"), 60L, storageGeneration))

        now = 500L
        val snapshot = store.snapshotForClient(initial, VehicleKpiLanguage.EN)

        assertEquals(50L, snapshot.freshness.rowCountMeasuredAtElapsedMs)
        assertEquals(60L, snapshot.freshness.integrationMeasuredAtElapsedMs)
        assertEquals(55L, snapshot.freshness.queueMetricsMeasuredAtElapsedMs)
        assertEquals(7L, snapshot.mqttPendingCount)
        assertEquals(8L, snapshot.influxPendingRows)
        assertFalse(store.publishQueueMetrics(99L, 99L, null, 70L, storageGeneration - 1L))
        assertFalse(store.publishIntegrationRuntime(dashboardState("stale"), 59L, storageGeneration))

        val queueRevision = store.queueMetricsRevisionSnapshot()
        store.invalidateQueueMetrics()
        assertFalse(store.publishQueueMetrics(99L, 99L, null, 70L, storageGeneration, queueRevision))
        val queueInvalidated = store.snapshotForClient(initial, VehicleKpiLanguage.EN)
        assertEquals(UNKNOWN_DASHBOARD_COUNT, queueInvalidated.mqttPendingCount)
        assertEquals(UNKNOWN_DASHBOARD_COUNT, queueInvalidated.influxPendingRows)
        assertEquals(50L, queueInvalidated.freshness.rowCountMeasuredAtElapsedMs)
        assertEquals(60L, queueInvalidated.freshness.integrationMeasuredAtElapsedMs)

        store.invalidateRowCounts()
        val invalidated = store.snapshotForClient(initial, VehicleKpiLanguage.EN)
        assertEquals(UNKNOWN_DASHBOARD_COUNT, invalidated.pollCount)
        assertEquals(UNKNOWN_DASHBOARD_COUNT, invalidated.mqttPendingCount)
        assertEquals(UNKNOWN_DASHBOARD_COUNT, invalidated.influxPendingRows)
        assertTrue(invalidated.freshness.ownerStorageGeneration > storageGeneration)
        assertEquals(60L, invalidated.freshness.integrationMeasuredAtElapsedMs)
        assertEquals(storageGeneration, invalidated.freshness.integrationStorageGeneration)
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
            pollCount = UNKNOWN_DASHBOARD_COUNT,
            valueRowCount = UNKNOWN_DASHBOARD_COUNT,
            ecRowCount = UNKNOWN_DASHBOARD_COUNT,
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
            debugReadingCount = UNKNOWN_DASHBOARD_COUNT,
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
            mqttDeviceId = "",
            mqttDeviceIdentifier = "",
            mqttDeviceName = "",
            mqttDeviceModel = "",
            mqttEnabledCategories = emptySet(),
            mqttStatus = "disabled",
            mqttLastError = null,
            mqttLastPublishedAt = null,
            mqttPendingCount = UNKNOWN_DASHBOARD_COUNT,
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
            influxVehicleId = "",
            influxEnabledCategories = emptySet(),
            influxStatus = "stopped",
            influxPendingRows = UNKNOWN_DASHBOARD_COUNT,
            influxOldestPendingAt = null,
            influxNextRetryAt = null,
            influxLastSuccessAt = null,
            influxLastErrorAt = null,
            influxLastError = null,
            influxExportedRowsTotal = 0L,
            normalizedCurrentCount = UNKNOWN_DASHBOARD_COUNT,
            normalizedHistoryCount = UNKNOWN_DASHBOARD_COUNT,
            permissionsGranted = false,
            adbAuthorized = false,
            vehicleKpis = VehicleKpis(),
            recentEvents = listOf(CollectorEvent(1L, marker, "test", marker, null))
        )
    }

    private fun DashboardState.withoutFreshness(): DashboardState =
        copy(freshness = DashboardSnapshotFreshness())
}
