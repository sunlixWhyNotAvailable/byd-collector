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
        assertTrue(load.contains("val store = storeProvider()"))
        assertFalse(source.contains("private val store: TelemetryStore,"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
