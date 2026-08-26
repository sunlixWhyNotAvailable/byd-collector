package com.bydcollector.collector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainActivityMaintenanceContractTest {
    @Test
    fun backendActionsUseCurrentApplicationStoreAfterArchive() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        assertTrue(source.contains("private fun currentStore(): TelemetryStore = BydCollectorApplication.store(applicationContext)"))
        assertTrue(source.contains("private fun refreshStoreBackedState()"))
        assertTrue(source.contains("HaMqttActions.testConnection(actionStore, settings)"))
        assertTrue(source.contains("InfluxActions.testConnection(actionStore, settings)"))
        assertTrue(source.contains("InfluxActions.reExportNewCategories(actionStore, settings)"))
        assertTrue(source.contains("CollectorAutoStart.scheduleWatchdog(applicationContext, settings, currentStore())"))
        assertTrue(source.contains("CollectorAutoStart.scheduleRestartAfterUiClosed(applicationContext, settings, currentStore())"))
        assertTrue(source.contains("AdbAuthorizationManager.request("))
        assertTrue(source.contains("mode = mode"))
        assertTrue(source.contains("store = currentStore()"))
        assertFalse(source.contains("HaMqttActions.testConnection(store, settings)"))
        assertFalse(source.contains("InfluxActions.testConnection(store, settings)"))
        assertFalse(source.contains("InfluxActions.reExportNewCategories(store, settings)"))
    }

    @Test
    fun eventWritesUseCurrentStore() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        assertFalse(source.contains("store.recordEvent("))
        assertFalse(source.contains("currentStore().recordEvent("))
        assertTrue(source.contains("dispatchOperationalEvent(dashboardExecutor)"))
        assertInOrder(source, "dispatchOperationalEvent(dashboardExecutor)", "withTelemetryStoreRead { eventStore ->", "eventStore.recordEvent(")
    }

    @Test
    fun mainAutoStartToggleRejectsDuringDatabaseMaintenanceBeforeStoreAccess() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val toggle = source
            .substringAfter("override fun onToggleMainAutoStart")
            .substringBefore("override fun onGrantAdb")

        assertTrue(toggle.contains("CollectorSettings.isDbMaintenanceRunning(applicationContext)"))
        assertTrue(toggle.contains("CollectorService.isMaintenanceRunningInProcess()"))
        assertTrue(toggle.contains("strings(uiLanguage).dbMaintenanceAlreadyRunning"))
        assertTrue(toggle.contains("Toast.makeText"))
        assertInOrder(toggle, "CollectorSettings.isDbMaintenanceRunning(applicationContext)", "refreshStoreBackedState()")
    }

    @Test
    fun dashboardHydrationNeverMutatesAutoStartPreferences() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val refresh = source.substringAfter("private fun refresh(force: Boolean = true)")
            .substringBefore("private fun recordDashboardRefreshFailure")
        val mainToggle = source.substringAfter("override fun onToggleMainAutoStart")
            .substringBefore("override fun onGrantAdb")
        val debugToggle = source.substringAfter("override fun onToggleDebugAutoStart")
            .substringBefore("override fun onToggleSharedCategories")

        assertFalse(refresh.contains("setAutoStartEnabled("))
        assertFalse(refresh.contains("setDebugAutoStartEnabled("))
        assertTrue(mainToggle.contains("source=ui control=main_auto_start tab=${'$'}activeTab"))
        assertTrue(debugToggle.contains("source=ui control=debug_auto_start tab=${'$'}activeTab"))
    }

    @Test
    fun queuedDashboardReadersAndPreflightHoldTheApplicationReadLease() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val counts = source.substringAfter("private fun scheduleDashboardCountBootstrap")
            .substringBefore("private fun hydrateDashboardTabsOnce")
        val preflight = source.substringAfter("private fun openMainArchiveDialog")
            .substringBefore("private fun loadCredentialsAfterFirstFrame")

        assertInOrder(counts, "dashboardCountExecutor.execute", "withTelemetryStoreRead { countStore ->", "countStore.dashboardRowCounts()")
        assertInOrder(counts, "withTelemetryStoreRead", "DirectDebugStore(applicationContext)")
        assertInOrder(preflight, "val task = Runnable", "withTelemetryStoreRead { preflightStore ->", "StorageFormatCutoverCoordinator.readMainPreflight")
        assertTrue(preflight.contains("StorageFormatCutoverCoordinator.readMainPreflight(preflightStore.databaseFile())"))
    }

    @Test
    fun oneShotIntegrationActionsHoldTheTelemetryStoreReadLease() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val mqtt = source.substringAfter("private fun runMqttChannelAction")
            .substringBefore("private fun runInfluxChannelAction")
        val influx = source.substringAfter("private fun runInfluxChannelAction")
            .substringBefore("private fun currentMaintenanceUiState")

        assertTrue(mqtt.contains("action: (TelemetryStore) -> MqttActionResult"))
        assertTrue(mqtt.contains("runCatching { withTelemetryStoreRead(action) }"))
        assertTrue(influx.contains("action: (TelemetryStore) -> InfluxActionResult"))
        assertTrue(influx.contains("runCatching { withTelemetryStoreRead(action) }"))
    }

    @Test
    fun databaseMaintenanceUiIsActivityWiredAndReadOnly() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()

        assertTrue(source.contains("private var pendingMaintenanceOperation by mutableStateOf<DbMaintenanceOperation?>(null)"))
        assertTrue(source.contains("private var maintenanceLaunchOperation by mutableStateOf<DbMaintenanceOperation?>(null)"))
        assertTrue(source.contains("databaseMaintenanceUiState = currentMaintenanceUiState(renderedChrome)"))
        assertTrue(actions.contains("fun onOpenArchiveDatabase()"))
        assertTrue(actions.contains("fun onConfirmDatabaseMaintenance()"))
        assertTrue(actions.contains("fun onCancelDatabaseMaintenance()"))
        assertTrue(actions.contains("fun onDismissDatabaseMaintenance()"))
        assertTrue(source.contains("pendingMaintenanceOperation = DbMaintenanceOperation.ARCHIVE"))
        assertTrue(source.contains("private fun openMainArchiveDialog()"))
        assertTrue(source.contains("StorageFormatCutoverCoordinator.readMainPreflight(preflightStore.databaseFile())"))
        assertTrue(source.contains("pendingMainArchivePreflight = preflight"))
        assertTrue(source.contains("pendingMainArchivePreflight = MainArchivePreflight("))
        assertTrue(source.contains("warning = \"${'$'}{strings(uiLanguage).archivePreflightFailed}"))
        assertTrue(source.contains("strings(uiLanguage).archivePreflightFailed"))
        assertFalse(source.contains("\"Не вдалося перевірити стан бази\""))
        assertTrue(strings.contains("archivePreflightFailed = \"Не вдалося перевірити стан бази\""))
        assertTrue(strings.contains("archivePreflightFailed = \"Could not check database status\""))
        assertInOrder(source, "StorageFormatCutoverCoordinator.readMainPreflight", "pendingMaintenanceOperation = DbMaintenanceOperation.ARCHIVE")
        assertInOrder(source, "val runningStatus = DbMaintenanceRuntimeStatus(", "settings.setDbMaintenanceStatus(runningStatus, synchronous = true)")
        assertInOrder(source, "maintenanceLaunchOperation = operation", "dashboardExecutor.execute")
        assertInOrder(source, "settings.setDbMaintenanceStatus(runningStatus, synchronous = true)", "CollectorServiceController.archiveDatabase(applicationContext)")
        assertTrue(source.contains("CollectorServiceController.archiveDatabase(applicationContext)"))
        assertTrue(source.contains("if (!committed)"))
        assertTrue(source.contains("runOnUiThread {\n                            if (!destroyed) refresh()"))
        assertTrue(actions.contains("fun onSetArchiveStorageLimitGb(value: Int)"))
        assertTrue(actions.contains("fun onDeleteArchives(ids: List<String>)"))
        assertTrue(actions.contains("fun onShareArchives(ids: List<String>)"))
        assertTrue(source.contains("AppTab.STORAGE -> DashboardLoadProfile.STORAGE"))
        assertTrue(source.contains("stateProvider.invalidateArchiveStorageSnapshot()"))
        assertTrue(source.contains("stateProvider.close()"))
        assertTrue(source.contains("settings.setArchiveStorageLimitGb(value)"))
        assertTrue(source.contains("CollectorServiceController.deleteArchives(this@MainActivity, ids)"))
        assertTrue(source.contains("settings.isCutoverArchiveStoragePending() && !CollectorService.isArchiveStorageActive()"))
        assertTrue(source.contains("CollectorServiceController.reconcileArchiveStorage(this)"))
        assertFalse(source.contains("onArchiveDeletePromptVisibilityChanged"))
        assertFalse(actions.contains("fun onOpenCompactDatabase()"))
        assertFalse(source.contains("DbMaintenanceOperation.COMPACT"))
        assertFalse(source.contains("compactDatabase"))
        assertTrue(source.contains("override fun onCancelDatabaseMaintenance()"))
        assertTrue(source.contains("CollectorServiceController.cancelDatabaseMaintenance(this@MainActivity)"))
        assertTrue(source.contains("cancelAvailable = runtime.cancelAvailable"))
        assertTrue(source.contains("if (dashboardUiStateStore.currentChrome()?.dbMaintenanceStatus?.running == true || maintenanceLaunchOperation != null) return"))
        assertTrue(source.contains("settings.clearDbMaintenanceStatus()"))
        assertTrue(source.contains("private fun currentMaintenanceUiState(state: DashboardState? = dashboardUiStateStore.currentChrome())"))
        val onDestroy = source.substringAfter("override fun onDestroy()").substringBefore("@Suppress")
        assertInOrder(onDestroy, "!CollectorSettings.isDbMaintenanceRunning(applicationContext)", "CollectorAutoStart.scheduleRestartAfterUiClosed(applicationContext, settings, currentStore())")
    }

    @Test
    fun archiveSharingUsesFreshZipResolutionAndReadOnlyContentUris() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val chooser = source.substringAfter("private fun openArchiveShareChooser")
            .substringBefore("private fun failArchiveShare")
        val failure = source.substringAfter("private fun failArchiveShare")
            .substringBefore("private fun refreshStoreBackedState")
        val paths = File("src/main/res/xml/update_file_paths.xml").takeIf { it.isFile }
            ?: File("app/src/main/res/xml/update_file_paths.xml")

        assertTrue(source.contains("ArchiveStorageManager("))
        assertTrue(source.contains(".resolveShareZipFiles(requestedIds)"))
        assertTrue(source.contains("FileProvider.getUriForFile("))
        assertTrue(source.contains("Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE"))
        assertTrue(source.contains("type = \"application/zip\""))
        assertTrue(source.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(source.contains("putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))"))
        assertTrue(source.contains("clipData = ClipData.newUri("))
        assertTrue(source.contains("clip.addItem(ClipData.Item(it))"))
        assertTrue(source.contains("val lease = CollectorService.archiveShareLeaseRegistry.acquire(requestedIds)"))
        assertTrue(source.contains("lease?.let(CollectorService.archiveShareLeaseRegistry::release)"))
        assertTrue(source.contains("archiveShareInFlight.compareAndSet(false, true)"))
        assertTrue(source.contains("CollectorService.isArchiveStorageActive()"))
        assertInOrder(chooser, "startActivity(Intent.createChooser", "archiveShareHandoffGeneration =")
        assertFalse(failure.contains("archiveShareHandoffGeneration"))
        assertFalse(source.contains("zipDirectory"))
        assertTrue(paths.readText().contains("<files-path name=\"database_archives\" path=\"db_archive/\" />"))
    }

    @Test
    fun successfulDashboardRefreshReconcilesUiOnlyCutoverArchives() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val refreshResult = source.substringAfterLast("refreshInFlight = false")
            .substringBefore("private fun recordDashboardRefreshFailure")

        assertTrue(refreshResult.contains("reconcileCutoverArchiveStorageIfNeeded()"))
        assertInOrder(
            refreshResult,
            "dashboardUiStateStore.publishTab",
            "reconcileCutoverArchiveStorageIfNeeded()"
        )
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun assertInOrder(source: String, vararg tokens: String) {
        var previousIndex = -1
        var previousToken: String? = null
        tokens.forEach { token ->
            val index = source.indexOf(token, previousIndex + 1)
            assertTrue(index >= 0, "Missing token: $token")
            if (previousToken != null) {
                assertTrue(index > previousIndex, "Expected `$token` after `$previousToken`")
            }
            previousIndex = index
            previousToken = token
        }
    }
}
