package com.bydcollector.collector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.bydcollector.collector.ui.DashboardLoadProfile
import com.bydcollector.collector.ui.compose.AppTab

class MainActivityKpiRefreshContractTest {
    @Test
    fun onlyTheActiveAllParametersProfileReadsVehicleKpis() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        assertEquals(DashboardLoadProfile.ALL_PARAMETERS, dashboardProfile(AppTab.ALL_PARAMETERS))
        assertEquals(DashboardLoadProfile.MAIN, dashboardProfile(AppTab.MAIN))
        assertNull(dashboardProfile(AppTab.TELEGRAM))
        assertFalse(source.contains("foreground || tab == AppTab.ALL_PARAMETERS"))
        assertTrue(source.contains("stateProvider.load("))
        assertTrue(source.contains("profile = profile"))
    }

    @Test
    fun dashboardCadenceIsScopedAndStaleWhileRevalidate() {
        val provider = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()

        assertEquals(2_000L, dashboardTabRefreshIntervalMs(AppTab.MAIN, storageRefreshPending = false))
        assertEquals(1_000L, dashboardTabRefreshIntervalMs(AppTab.ALL_PARAMETERS, storageRefreshPending = false))
        assertEquals(5_000L, dashboardTabRefreshIntervalMs(AppTab.HA, storageRefreshPending = false))
        assertEquals(30_000L, dashboardTabRefreshIntervalMs(AppTab.STORAGE, storageRefreshPending = false))
        assertEquals(1_000L, dashboardTabRefreshIntervalMs(AppTab.STORAGE, storageRefreshPending = true))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.TELEGRAM, storageRefreshPending = false))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.EXTRA, storageRefreshPending = false))
        assertTrue(dashboardSnapshotDue(null, 2_000L, 5_000L))
        assertFalse(dashboardSnapshotDue(4_000L, 2_000L, 5_000L))
        assertTrue(dashboardSnapshotDue(3_000L, 2_000L, 5_000L))
        assertTrue(provider.contains("vehicle KPI reads are limited to the ALL_PARAMETERS profile"))
        assertTrue(provider.contains("debugStatusCache.get(nowMs = nowMs)"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
