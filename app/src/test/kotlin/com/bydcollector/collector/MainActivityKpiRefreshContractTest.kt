package com.bydcollector.collector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.bydcollector.collector.ui.DashboardLoadProfile
import com.bydcollector.collector.ui.compose.AppTab

class MainActivityKpiRefreshContractTest {
    @Test
    fun tabsSelectTheRequiredDashboardProfile() {
        assertEquals(DashboardLoadProfile.ALL_PARAMETERS, dashboardProfile(AppTab.ALL_PARAMETERS))
        assertEquals(DashboardLoadProfile.MAIN, dashboardProfile(AppTab.MAIN))
        assertNull(dashboardProfile(AppTab.TELEGRAM))
    }

    @Test
    fun dashboardCadenceIsScopedAndStaleWhileRevalidate() {
        assertNull(DASHBOARD_CHROME_REFRESH_INTERVAL_MS)
        assertNull(dashboardTabRefreshIntervalMs(AppTab.MAIN, storageRefreshPending = false))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.ALL_PARAMETERS, storageRefreshPending = false))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.HA, storageRefreshPending = false))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.STORAGE, storageRefreshPending = false))
        assertEquals(1_000L, dashboardTabRefreshIntervalMs(AppTab.STORAGE, storageRefreshPending = true))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.TELEGRAM, storageRefreshPending = false))
        assertNull(dashboardTabRefreshIntervalMs(AppTab.EXTRA, storageRefreshPending = false))
        assertTrue(dashboardSnapshotDue(null, 2_000L, 5_000L))
        assertFalse(dashboardSnapshotDue(4_000L, 2_000L, 5_000L))
        assertTrue(dashboardSnapshotDue(3_000L, 2_000L, 5_000L))
    }

}
