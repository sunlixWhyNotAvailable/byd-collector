package com.bydcollector.collector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainActivityKpiRefreshContractTest {
    @Test
    fun foregroundRefreshIncludesVehicleKpisOnAnyTab() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        assertTrue(source.contains("val includeVehicleKpis = foreground || tab == AppTab.ALL_PARAMETERS"))
        assertFalse(source.contains("val includeVehicleKpis = tab == AppTab.ALL_PARAMETERS"))
        assertTrue(source.contains("handler.postDelayed(refreshTask, DASHBOARD_REFRESH_INTERVAL_MS)"))
    }

    @Test
    fun dashboardProviderDocumentsKpiReadAsForegroundLightweightRead() {
        val provider = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()

        assertTrue(provider.contains("vehicle kpi reads are lightweight normalized-current reads"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
