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

        val cacheCheck = activity.indexOf("if (dashboardUiStateStore.currentChrome() == null)")
        val initialLoad = activity.indexOf("dashboardUiStateStore.seed(stateProvider.loadInitial())")
        val setContent = activity.indexOf("setContent {")
        assertTrue(cacheCheck >= 0)
        assertTrue(initialLoad > cacheCheck)
        assertTrue(setContent > initialLoad)
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

    @Test
    fun dashboardCountBootstrapNeverRetriesDebugStorageReadiness() {
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val bootstrap = activity.substringAfter("private fun scheduleDashboardCountBootstrap")
            .substringBefore("private fun hydrateDashboardTabsOnce")

        assertTrue(bootstrap.contains("BydCollectorApplication.isDebugStorageReady(applicationContext)"))
        assertFalse(bootstrap.contains("BydCollectorApplication.ensureDebugStorageReady(applicationContext)"))
    }

    @Test
    fun restoredTripsTabReloadsItsActivityLocalDataOnResume() {
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val resume = activity.substringAfter("override fun onResume()")
            .substringBefore("override fun onWindowFocusChanged")

        assertTrue(resume.contains("if (activeTab == AppTab.TRIPS) loadTripsUi()"))
        val readiness = activity.substringAfter("val tabContentReady =")
            .substringBefore("BydCollectorApp(")
        assertFalse(readiness.contains("inFlight"))
        assertTrue(readiness.contains("archiveStorageScanPending"))
        assertTrue(readiness.contains("archiveStorageSnapshot.entries.isNotEmpty()"))
        val load = activity.substringAfter("private fun loadTripsUi(").substringBefore("private fun openCurrentTrip()")
        assertTrue(load.contains("routeTripId ?: tripsUiState.routeLoadingId"))
        assertTrue(load.contains("requestedRouteId?.let { id -> mapOf(id to trips.queryRoutePoints(id)) }"))
        assertTrue(load.contains("TripsUiMapper.retainRoutes(loaded.years, tripsUiState.years, loaded.refreshedRouteId)"))
        // Reversed worker completion cannot publish over a newer request/session/language.
        val publish = load.indexOf("TripsUiMapper.retainRoutes")
        for (guard in listOf("requestGeneration != tripsRequestGeneration", "uiLanguage != requestedLanguage",
            "!navigationSession.isGenerationCurrent(sessionGeneration)")) {
            assertTrue(load.indexOf(guard) in 0 until publish, guard)
        }
        assertFalse(load.substringBefore("runCatching { dashboardExecutor.execute").contains("years ="))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
