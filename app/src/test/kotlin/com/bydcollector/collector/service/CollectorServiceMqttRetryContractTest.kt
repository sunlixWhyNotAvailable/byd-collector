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

    @Test
    fun offlineTransitionDrainsTheWorkerAndReusesTheOwnedClient() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val maintenanceService = sourceFile("com/bydcollector/collector/service/DatabaseMaintenanceService.kt").readText()
        val offline = service.substringAfter("private fun disconnectOfflineAsync")
            .substringBefore("private fun completeMqttOffline")
        val complete = service.substringAfter("private fun completeMqttOffline")
            .substringBefore("private fun executeMqtt")
        val reset = service.substringAfter("private fun resetMqttExecutorForOffline")
            .substringBefore("private fun resetMqttExecutorForMaintenance")
        val maintenance = service.substringAfter("private fun resetMqttExecutorForMaintenance")
            .substringBefore("private fun shutdownMqttExecutor")
        val destroy = service.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onTaskRemoved")
        val startMain = service.substringAfter("private fun startMainIfNeeded")
            .substringBefore("private fun startDebugIfNeeded")

        assertTrue(offline.contains("resetMqttExecutorForOffline"))
        assertFalse(startMain.contains("mqttOfflineQueued.set(false)"))
        assertInOrder(reset, "shutdownNow()", "replacement.execute", "mqttExecutor = replacement")
        assertInOrder(complete, "awaitMqttWorkerTermination(previous", "mqttCoordinator.disconnectOffline()")
        assertFalse(
            complete.substringAfter("if (!awaitMqttWorkerTermination")
                .substringBefore("runCatching { mqttCoordinator.disconnectOffline() }")
                .contains("return")
        )
        assertFalse(service.contains("oneShotMqttCoordinator"))
        assertTrue(service.split("mqttCoordinator = createMqttCoordinator(processMqttClientFacade)").size == 3)
        assertTrue(service.contains("private val processMqttClientFacade = PahoMqttClientFacade()"))
        assertInOrder(maintenance, "previous.awaitTermination", "mqttCoordinator.disconnectForMaintenance()")
        assertInOrder(destroy, "shutdownMqttExecutor()", "awaitMqttWorkerTermination(")
        assertTrue(maintenanceService.contains("COLLECTOR_STOP_TIMEOUT_MS = 20_000L"))
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
