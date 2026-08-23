package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CollectorServiceDebugPollerContractTest {
    @Test
    fun debugPollerUsesTheCompletePackagedCatalogSize() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val start = source.substringAfter("private fun startDebugIfNeeded").substringBefore("private fun handleStartFailure")

        assertTrue(start.contains("val batchSize = DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT"))
        assertTrue(!start.contains("debugBatchSize"))
        assertTrue(!start.contains("debugAutostartBatchSize"))
    }

    @Test
    fun debugStartRetriesStorageInsideTheStartExecutorAndPublishesRuntimeStates() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val start = source.substringAfter("private fun startDebugIfNeeded").substringBefore("private fun handleStartFailure")

        assertTrue(start.contains("debugStartExecutor.execute"))
        assertTrue(start.contains("BydCollectorApplication.ensureDebugStorageReady(applicationContext)"))
        assertTrue(start.contains("DebugRuntimeStatus.STARTING"))
        assertTrue(start.contains("DebugRuntimeStatus.ERROR"))
        assertTrue(start.contains("DebugRuntimeStatus.RUNNING"))
        assertTrue(source.contains("setDebugRuntime(DebugRuntimeStatus.STOPPED)"))
        assertTrue(start.contains("settings.debugStorageCutoverError()"))
        assertTrue(!start.contains("if (!debugStorageReady) {\n            updateNotification"))
    }

    @Test
    fun debugPollerIsProtectedByLockAndRecheckedBeforeAsyncStart() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val start = source.substringAfter("private fun startDebugIfNeeded").substringBefore("private fun handleStartFailure")
        val stop = source.substringAfter("private fun stopDebug").substringBefore("private data class RuntimeSnapshot")
        val maintenance = source.substringAfter("private fun stopRuntimeForMaintenance").substringBefore("private fun restoreRuntimeAfterMaintenance")

        assertTrue(source.contains("private val debugPollerLock = Any()"))
        assertTrue(start.contains("synchronized(debugPollerLock)"))
        assertTrue(start.contains("!settings.isDebugPollingEnabled()"))
        assertTrue(start.contains("settings.isDebugManuallyStopped()"))
        assertTrue(start.contains("maintenanceBlocksRuntimeStart(debugRuntime = true)"))
        assertTrue(stop.contains("detachDebugPoller()"))
        assertTrue(maintenance.contains("detachDebugPoller()"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
