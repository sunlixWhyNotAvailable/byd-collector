package com.bydcollector.collector.data.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TelemetryHealthDetailContractTest {
    @Test
    fun dashboardCanReadSummaryWithoutFullCountsOrRecentEvents() {
        val source = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()

        assertTrue(source.contains("detail: HealthSnapshotDetail = HealthSnapshotDetail.FULL"))
        assertTrue(source.contains("detail != HealthSnapshotDetail.SUMMARY"))
        assertTrue(source.contains("detail == HealthSnapshotDetail.FULL"))
        assertTrue(source.contains("pollCount = if (includeFullDetails)"))
        assertTrue(source.contains("mqttPendingCount = if (includeIntegrations)"))
        assertTrue(source.contains("recentEvents = if (includeFullDetails) safeRecentEvents() else emptyList()"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
