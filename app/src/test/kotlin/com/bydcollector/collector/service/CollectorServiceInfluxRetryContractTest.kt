package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorServiceInfluxRetryContractTest {
    @Test
    fun independentInfluxRetryUsesTheExistingServiceLifecycle() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val normalizedWrite = service.substringAfter("private fun exportInfluxAfterNormalizedWrite")
            .substringBefore("private fun requestInfluxCycle")
        val influxPath = service.substringAfter("private fun requestInfluxCycle")
            .substringBefore("private fun stopDebug")
        val maintenance = service.substringAfter("private fun stopRuntimeForMaintenance")
            .substringBefore("private fun restoreRuntimeAfterMaintenance")
        val debugMaintenance = maintenance.substringAfter("if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE)")
            .substringBefore("cancelInfluxRetry()")
        val stopInflux = service.substringAfter("private fun stopInfluxExport")
            .substringBefore("private fun reconcileTelegramRuntime")
        val noRuntime = service.substringAfter("private fun stopIfNoActiveRuntime")
            .substringBefore("private fun flushPendingMqttAsync")

        assertTrue(normalizedWrite.contains("requestInfluxCycle()"))
        assertTrue(service.contains("private val influxRetryTask"))
        assertTrue(influxPath.contains("influxRequestQueued.compareAndSet(false, true)"))
        assertTrue(influxPath.contains("influxCoordinator.runOneCycle(force = false)"))
        assertTrue(service.contains("influxCoordinator.resumeExport()"))
        assertTrue(maintenance.contains("resetInfluxExecutorForMaintenance()"))
        assertTrue(
            maintenance.indexOf("if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE) return") <
                maintenance.indexOf("resetInfluxExecutorForMaintenance()")
        )
        assertTrue(stopInflux.contains("cancelInfluxRetry()"))
        assertTrue(stopInflux.contains("queueInfluxStop(stopServiceWhenIdle = true)"))
        assertFalse(stopInflux.contains("resetInfluxExecutorForMaintenance()"))
        assertFalse(noRuntime.contains("settings.isInfluxEnabled()"))
        assertFalse(service.contains("AlarmManager"))
        assertFalse(service.contains("JobScheduler"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
