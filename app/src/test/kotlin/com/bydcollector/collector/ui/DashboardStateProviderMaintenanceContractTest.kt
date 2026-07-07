package com.bydcollector.collector.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardStateProviderMaintenanceContractTest {
    @Test
    fun providerLoadsCurrentStoreFromProviderEachRefresh() {
        val source = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()
        val load = source.substringAfter("fun load(").substringBefore("private fun formatTrailingTimestamp")

        assertTrue(source.contains("private val storeProvider: () -> TelemetryStore"))
        assertTrue(load.contains("val store = if (maintenanceStatus.running) null else storeProvider()"))
        assertFalse(source.contains("private val store: TelemetryStore,"))
    }

    @Test
    fun providerAvoidsHeavyStoreReadsWhileDatabaseMaintenanceIsRunning() {
        val source = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()
        val load = source.substringAfter("fun load(").substringBefore("private fun formatTrailingTimestamp")

        assertInOrder(load, "val maintenanceStatus = settings.dbMaintenanceStatus()", "val store = if (maintenanceStatus.running) null else storeProvider()")
        assertTrue(source.contains("private fun maintenanceHealthSnapshot("))
        assertTrue(source.contains("private fun maintenanceInfluxState()"))
        assertTrue(load.contains("val influxState = store?.influxExportState() ?: maintenanceInfluxState()"))
        assertTrue(load.contains("if (includeVehicleKpis && store != null)"))
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
