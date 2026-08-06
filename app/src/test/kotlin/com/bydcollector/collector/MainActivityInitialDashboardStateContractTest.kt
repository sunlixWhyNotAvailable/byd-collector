package com.bydcollector.collector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainActivityInitialDashboardStateContractTest {
    @Test
    fun lightweightPersistedStateLoadsBeforeFirstComposeFrame() {
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val provider = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()

        val initialLoad = activity.indexOf("val initialDashboardState = stateProvider.loadInitial()")
        val setContent = activity.indexOf("setContent {")
        assertTrue(initialLoad >= 0)
        assertTrue(setContent > initialLoad)
        assertTrue(activity.contains("dashboardUiStateStore.seed(initialDashboardState)"))
        assertTrue(provider.contains("fun loadInitial(): DashboardState = load(DashboardLoadProfile.INITIAL)"))
        assertTrue(provider.contains("profile.readsTelemetryStore && !mainMaintenanceRunning"))
        assertTrue(provider.contains("settings.mqttConfig(includeCredentials = false)"))
        assertTrue(provider.contains("settings.influxConfig(includeCredentials = false)"))
        assertFalse(provider.contains("includeCredentials = true"))

        val credentialHydration = activity.indexOf("loadCredentialsAfterFirstFrame()")
        val runtimeStore = activity.indexOf("val runtimeStore = currentStore()")
        assertTrue(credentialHydration > setContent)
        assertTrue(runtimeStore > setContent)
        assertTrue(activity.contains("val chromeSnapshot by dashboardUiStateStore.chromeState.collectAsStateWithLifecycle()"))
        assertTrue(activity.contains("val tabSnapshot by dashboardUiStateStore.tabState(activeTab).collectAsStateWithLifecycle()"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
