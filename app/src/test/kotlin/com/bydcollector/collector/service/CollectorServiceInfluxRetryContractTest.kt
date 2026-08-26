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
        val liveness = service.substringAfter("private fun currentRuntimeLiveness")
            .substringBefore("private fun hasRuntimeOwner")

        assertTrue(normalizedWrite.contains("requestInfluxCycle()"))
        assertTrue(service.contains("private val influxRetryTask"))
        assertTrue(influxPath.contains("influxRequestQueued.compareAndSet(false, true)"))
        assertTrue(influxPath.contains("synchronized(influxQueueLock)"))
        assertTrue(influxPath.contains("submittedGeneration == influxWorkGeneration.get()"))
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
        assertTrue(noRuntime.contains("val liveness = currentRuntimeLiveness()"))
        assertTrue(liveness.contains("influxQueued = influxRequestQueued.get()"))
        assertTrue(liveness.contains("influxInFlight = influxWorkInFlight.get() > 0"))
        assertTrue(liveness.contains("influxRetryScheduled = influxRetryScheduled"))
        assertFalse(noRuntime.contains("settings.isInfluxEnabled()"))
        assertTrue(service.contains("onSettled = ::settleInfluxWork"))
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
