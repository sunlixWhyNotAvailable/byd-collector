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
    fun vehicleKpisAreProducerFedInsteadOfReadByTabRefresh() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val provider = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()

        assertEquals(DashboardLoadProfile.ALL_PARAMETERS, dashboardProfile(AppTab.ALL_PARAMETERS))
        assertEquals(DashboardLoadProfile.MAIN, dashboardProfile(AppTab.MAIN))
        assertNull(dashboardProfile(AppTab.TELEGRAM))
        assertTrue(source.contains("stateProvider.load("))
        assertTrue(source.contains("profile = profile"))
        assertTrue(provider.contains("KPI values are fed directly by the successful normalized-poll producer"))
        assertFalse(provider.contains("normalizedCurrentState("))
    }

    @Test
    fun dashboardCadenceIsScopedAndStaleWhileRevalidate() {
        val provider = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()

        assertNull(DASHBOARD_CHROME_REFRESH_INTERVAL_MS)
        assertNull(dashboardTabRefreshIntervalMs(AppTab.MAIN, storageRefreshPending = false))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.ALL_PARAMETERS, storageRefreshPending = false))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.HA, storageRefreshPending = false))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.STORAGE, storageRefreshPending = false))
        assertEquals(1_000L, dashboardTabRefreshIntervalMs(AppTab.STORAGE, storageRefreshPending = true))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.TELEGRAM, storageRefreshPending = false))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.EXTRA, storageRefreshPending = false))
        assertEquals(5_000L, dashboardTabRefreshIntervalMs(AppTab.LOGS, storageRefreshPending = false))
        assertTrue(dashboardSnapshotDue(null, 2_000L, 5_000L))
        assertFalse(dashboardSnapshotDue(4_000L, 2_000L, 5_000L))
        assertTrue(dashboardSnapshotDue(3_000L, 2_000L, 5_000L))
        assertTrue(provider.contains("debugStatusCache.get(nowMs = nowMs)"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
