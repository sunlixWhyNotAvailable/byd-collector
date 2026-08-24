package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorServiceMqttRetryContractTest {
    @Test
    fun persistedMqttRetryUsesOneServiceOwnedDeadline() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val retryTask = service.substringAfter("private val mqttRetryTask")
            .substringBefore("private val influxRetryTask")
        val retryPath = service.substringAfter("private fun postMqttRetrySchedule")
            .substringBefore("private fun flushPendingMqttAsync")
        val mqttExecution = service.substringAfter("private fun executeMqtt")
            .substringBefore("private fun resetMqttExecutorForOffline")
        val stopMqtt = service.substringAfter("private fun stopMqttExport")
            .substringBefore("private fun startInfluxExport")
        val shutdown = service.substringAfter("private fun stopRuntimeForUserShutdown")
            .substringBefore("private fun finishUserShutdown")
        val destroy = service.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onTaskRemoved")
        val maintenanceStop = service.substringAfter("private fun stopRuntimeForMaintenance")
            .substringBefore("private fun prepareRuntimeStopForMaintenance")
        val maintenancePrepare = service.substringAfter("private fun prepareRuntimeStopForMaintenance")
            .substringBefore("private fun restoreRuntimeAfterMaintenance")
        val debugMaintenance = maintenancePrepare
            .substringAfter("if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE)")
            .substringBefore("return DetachedMaintenanceRuntime")

        assertTrue(service.contains("private val mqttRetryTask"))
        assertTrue(retryTask.contains("flushPendingMqttAsync(force = false)"))
        assertTrue(retryPath.contains("mqttCoordinator.retryDelayMs()"))
        assertTrue(retryPath.contains("mainHandler.postDelayed(mqttRetryTask, delayMs)"))
        assertTrue(retryPath.contains("mqttRetryScheduled && mqttRetryAtElapsedMs"))
        assertInOrder(mqttExecution, "onSuccess = { result, submittedGeneration ->", "postMqttRetrySchedule(submittedGeneration)")
        assertTrue(stopMqtt.contains("cancelMqttRetry()"))
        assertTrue(shutdown.contains("cancelMqttRetry()"))
        assertTrue(destroy.contains("cancelMqttRetry()"))
        assertTrue(maintenancePrepare.contains("cancelMqttRetry()"))
        assertTrue(maintenanceStop.contains("resetMqttExecutorForMaintenance()"))
        assertFalse(debugMaintenance.contains("cancelMqttRetry()"))
        assertFalse(service.contains("AlarmManager"))
        assertFalse(service.contains("JobScheduler"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun assertInOrder(source: String, vararg tokens: String) {
        var previousIndex = -1
        tokens.forEach { token ->
            val index = source.indexOf(token, previousIndex + 1)
            assertTrue(index >= 0, "Missing token: $token")
            previousIndex = index
        }
    }
}
