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
        assertTrue(source.contains("AdbAuthorizationManager.requestAuthorization(applicationContext, currentStore())"))
        assertTrue(source.contains("AdbAuthorizationManager.selfCheck("))
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
        assertTrue(source.contains("databaseMaintenanceUiState = currentMaintenanceUiState()"))
        assertTrue(actions.contains("fun onOpenCompactDatabase()"))
        assertTrue(actions.contains("fun onOpenArchiveDatabase()"))
        assertTrue(actions.contains("fun onConfirmDatabaseMaintenance()"))
        assertTrue(actions.contains("fun onDismissDatabaseMaintenance()"))
        assertTrue(source.contains("pendingMaintenanceOperation = DbMaintenanceOperation.COMPACT"))
        assertTrue(source.contains("pendingMaintenanceOperation = DbMaintenanceOperation.ARCHIVE"))
        assertTrue(source.contains("CollectorServiceController.compactDatabase(this@MainActivity)"))
        assertTrue(source.contains("CollectorServiceController.archiveDatabase(this@MainActivity)"))
        assertTrue(source.contains("if (dashboardState?.dbMaintenanceStatus?.running == true) return"))
        assertTrue(source.contains("settings.clearDbMaintenanceStatus()"))
        assertTrue(source.contains("private fun currentMaintenanceUiState(): DbMaintenanceUiState?"))
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
}
