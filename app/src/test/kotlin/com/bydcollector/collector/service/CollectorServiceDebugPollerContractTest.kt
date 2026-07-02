package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CollectorServiceDebugPollerContractTest {
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
        assertTrue(start.contains("maintenanceBlocksRuntimeStart()"))
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
