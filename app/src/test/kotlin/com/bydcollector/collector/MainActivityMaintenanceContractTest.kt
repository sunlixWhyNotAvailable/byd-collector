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
        assertTrue(source.contains("HaMqttActions.testConnection(currentStore(), settings)"))
        assertTrue(source.contains("InfluxActions.testConnection(currentStore(), settings)"))
        assertTrue(source.contains("InfluxActions.reExportNewCategories(currentStore(), settings)"))
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
        assertTrue(source.contains("currentStore().recordEvent("))
    }

    @Test
    fun databaseMaintenanceUiIsActivityWiredAndReadOnly() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()

        assertTrue(source.contains("private var pendingMaintenanceOperation by mutableStateOf<DbMaintenanceOperation?>(null)"))
        assertTrue(source.contains("private var maintenanceLaunchOperation by mutableStateOf<DbMaintenanceOperation?>(null)"))
        assertTrue(source.contains("databaseMaintenanceUiState = currentMaintenanceUiState()"))
        assertTrue(actions.contains("fun onOpenArchiveDatabase()"))
        assertTrue(actions.contains("fun onConfirmDatabaseMaintenance()"))
        assertTrue(actions.contains("fun onCancelDatabaseMaintenance()"))
        assertTrue(actions.contains("fun onDismissDatabaseMaintenance()"))
        assertTrue(source.contains("pendingMaintenanceOperation = DbMaintenanceOperation.ARCHIVE"))
        assertInOrder(source, "settings.setDbMaintenanceStatus(", "DbMaintenanceRuntimeStatus(")
        assertInOrder(source, "maintenanceLaunchOperation = operation", "CollectorServiceController.archiveDatabase(this@MainActivity)")
        assertTrue(source.contains("CollectorServiceController.archiveDatabase(this@MainActivity)"))
        assertTrue(actions.contains("fun onSetArchiveStorageLimitGb(value: Int)"))
        assertTrue(actions.contains("fun onDeleteArchives(ids: List<String>)"))
        assertTrue(actions.contains("fun onReconcileArchiveStorage()"))
        assertTrue(source.contains("includeArchiveStorageDetails = activeTab == AppTab.STORAGE"))
        assertTrue(source.contains("stateProvider.invalidateArchiveStorageSnapshot()"))
        assertTrue(source.contains("stateProvider.close()"))
        assertTrue(source.contains("settings.setArchiveStorageLimitGb(value)"))
        assertTrue(source.contains("CollectorServiceController.deleteArchives(this@MainActivity, ids)"))
        assertTrue(source.contains("archiveStorageRunning = settings.archiveStorageJobStatus().running"))
        assertTrue(source.contains("archiveDeletePromptVisible = visible"))
        assertTrue(source.contains("databaseMaintenanceVisible = pendingMaintenanceOperation != null"))
        assertFalse(actions.contains("fun onOpenCompactDatabase()"))
        assertFalse(source.contains("DbMaintenanceOperation.COMPACT"))
        assertFalse(source.contains("compactDatabase"))
        assertTrue(source.contains("override fun onCancelDatabaseMaintenance()"))
        assertTrue(source.contains("CollectorServiceController.cancelDatabaseMaintenance(this@MainActivity)"))
        assertTrue(source.contains("cancelAvailable = runtime.cancelAvailable"))
        assertTrue(source.contains("if (dashboardState?.dbMaintenanceStatus?.running == true || maintenanceLaunchOperation != null) return"))
        assertTrue(source.contains("settings.clearDbMaintenanceStatus()"))
        assertTrue(source.contains("private fun currentMaintenanceUiState(): DbMaintenanceUiState?"))
        val onDestroy = source.substringAfter("override fun onDestroy()").substringBefore("@Suppress")
        assertInOrder(onDestroy, "!CollectorSettings.isDbMaintenanceRunning(applicationContext)", "CollectorAutoStart.scheduleRestartAfterUiClosed(applicationContext, settings, currentStore())")
        assertFalse(source.contains("sendCmd"))
        assertFalse(source.contains("setXD"))
        assertFalse(source.contains("setTrigger"))
        assertFalse(source.contains("wakeUpMcu"))
        assertFalse(source.contains("setAction"))
        assertFalse(source.contains("setActionsBatch"))
        assertFalse(source.contains("TX_WRITE"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing first token: $first")
        assertTrue(secondIndex >= 0, "Missing second token: $second")
        assertTrue(firstIndex < secondIndex, "Expected `$first` before `$second`")
    }
}
