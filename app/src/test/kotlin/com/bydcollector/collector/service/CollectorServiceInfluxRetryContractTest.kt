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
        val generation = service.substringAfter("private fun advanceInfluxGeneration")
            .substringBefore("private fun <T> executeOrderedTelegram")

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
        assertTrue(liveness.contains("influxOwned = influxConnection.owned"))
        assertTrue(stopInflux.contains("influxConnection.beginStop()"))
        assertTrue(stopInflux.contains("isStop = true"))
        assertTrue(stopInflux.contains("if (!settings.isInfluxEnabled()) influxConnection.release()"))
        assertTrue(stopInflux.contains("if (!accepted) influxConnection.stopSubmissionFailed()"))
        assertFalse(noRuntime.contains("settings.isInfluxEnabled()"))
        assertTrue(service.contains("onSettled = ::settleInfluxWork"))
        assertFalse(service.contains("AlarmManager"))
        assertFalse(service.contains("JobScheduler"))
        assertTrue(influxPath.contains("\"singleflight_occupied\","))
        assertTrue(service.contains("influxRuntimeDiagnostics.gate(reason, details + (\"runtime_id\" to influxDiagnosticRuntimeId))"))
        assertTrue(service.contains("influx_retry_scheduled"))
        assertTrue(service.contains("influx_retry_fired"))
        assertTrue(service.contains("influx_retry_cancelled"))
        assertTrue(service.contains("influxRuntimeDiagnostics.attachJournal(applicationContext)"))
        assertTrue(service.contains("InfluxRuntimeDiagnosticsProcess.instance"))
        val stateDetails = service.substringAfter("private fun influxDiagnosticStateDetails")
            .substringBefore("private fun postInfluxRetrySchedule")
        assertFalse(stateDetails.contains("settings.influxConfig()"))
        assertTrue(stateDetails.contains("if (::influxCoordinator.isInitialized) influxCoordinator else null"))
        assertTrue(stateDetails.contains("coordinator?.activeRoute?.name ?: \"none\""))
        assertTrue(stateDetails.contains("coordinator?.frozenEndpoints ?: \"none\""))
        assertTrue(influxPath.contains("influxDiagnosticStateDetails(influxWorkGeneration.get(), queued = true)"))
        assertTrue(influxPath.contains("workGeneration = if (isCurrentGeneration && !influxRequestQueued.get())"))
        assertTrue(generation.contains("influxWorkGeneration.incrementAndGet()"))
        assertTrue(generation.contains("influxCoordinator.cancelInFlight()"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
