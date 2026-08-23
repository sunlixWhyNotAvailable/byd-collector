package com.bydcollector.collector.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardStateProviderMaintenanceContractTest {
    @Test
    fun providerLoadsCurrentStoreFromProviderEachRefresh() {
        val source = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()
        val load = source.substringAfter("profile: DashboardLoadProfile").substringBefore("private fun loadHealthSnapshot")

        assertTrue(source.contains("private val storeProvider: () -> TelemetryStore"))
        assertTrue(load.contains("profile.readsTelemetryStore && !mainMaintenanceRunning"))
        assertTrue(load.contains("storeProvider()"))
        assertFalse(source.contains("private val store: TelemetryStore,"))
    }

    @Test
    fun providerUsesExplicitHealthDetailsAndAvoidsHeavyReadsDuringMaintenance() {
        val source = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()

        assertTrue(source.contains("HealthSnapshotDetail.INTEGRATIONS"))
        assertTrue(source.contains("HealthSnapshotDetail.FULL"))
        assertTrue(source.contains("detail = detail, includeCounts = false"))
        assertTrue(source.contains("profile.readsDebugStatus && !debugMaintenanceRunning"))
        assertTrue(source.contains("KPI values are fed directly by the successful normalized-poll producer"))
        assertTrue(source.contains("DirectDebugStore(context).use"))
        assertTrue(source.contains("debugStatusCache.get(nowMs = nowMs)"))
        assertTrue(source.contains("private fun maintenanceHealthSnapshot("))
        assertTrue(source.contains("private fun maintenanceInfluxState()"))
    }

    @Test
    fun providerScansArchiveDetailsOnlyForStorageAndKeepsMaintenanceOrder() {
        val source = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()
        val load = source.substringAfter("profile: DashboardLoadProfile").substringBefore("private fun loadHealthSnapshot")

        assertTrue(source.contains("ArchiveStorageSnapshotCache("))
        assertTrue(source.contains("fun invalidateArchiveStorageSnapshot()"))
        assertTrue(source.contains("fun close()"))
        assertTrue(load.contains("archiveStorageJobStatus.running || CollectorService.isArchiveStorageActive()"))
        assertTrue(load.contains("archiveStorageCache.invalidate()"))
        assertTrue(load.contains("val archiveDetailsLoaded = profile.readsArchiveDetails && !archiveJobActiveNow"))
        assertTrue(load.contains("includeDetails = archiveDetailsLoaded"))
        assertTrue(load.contains("settings.archiveStorageLimitGb()"))
        assertTrue(load.contains("settings.archiveStorageJobStatus()"))
        assertTrue(load.contains("archiveStorageResult.snapshot"))
        assertInOrder(load, "val maintenanceStatus = settings.dbMaintenanceStatus()", "val store = if (profile.readsTelemetryStore")
        assertInOrder(load, "val store = if (profile.readsTelemetryStore", "val archiveStorageResult = archiveStorageCache.snapshot(")
    }

    @Test
    fun providerAlwaysShowsThePackagedDebugCatalogCount() {
        val source = sourceFile("com/bydcollector/collector/ui/DashboardStateProvider.kt").readText()
        assertTrue(source.contains("DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT"))
        assertFalse(source.contains("val debugParameterCount = if (debugStatusLoaded) debugStatus.candidateCount else 0"))
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
