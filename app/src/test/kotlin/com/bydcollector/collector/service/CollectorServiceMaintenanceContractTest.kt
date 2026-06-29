package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorServiceMaintenanceContractTest {
    @Test
    fun maintenanceStopFailsWhenRuntimeWorkersDoNotStopAndDoesNotPersistPauseSettings() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val stop = source.substringAfter("private fun stopRuntimeForMaintenance").substringBefore("private fun restoreRuntimeAfterMaintenance")

        assertTrue(source.contains("private val maintenanceActive = AtomicBoolean(false)"))
        assertTrue(stop.contains("if (!poller.stopAndJoin(2_000L)) error("))
        assertTrue(stop.contains("debugPoller?.shutdownAndAwait(\"database_maintenance\", 2_000L) == false"))
        assertTrue(stop.contains("error(\"Debug poller did not stop for database maintenance\")"))
        assertFalse(stop.contains("settings.setPollingEnabled(false)"))
        assertFalse(stop.contains("settings.setDebugPollingEnabled(false)"))
        assertFalse(stop.contains("settings.setMqttEnabled(false)"))
        assertFalse(stop.contains("settings.setInfluxEnabled(false)"))
    }

    @Test
    fun maintenanceUsesLocalSnapshotAndGuardedActionPaths() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val start = source.substringAfter("private fun startDatabaseMaintenance").substringBefore("private fun oneShotMqttCoordinator")

        assertFalse(source.contains("maintenanceRuntimeSnapshot"))
        assertTrue(start.contains("if (!maintenanceActive.compareAndSet(false, true))"))
        assertTrue(start.contains("val snapshot = runtimeSnapshot()"))
        assertTrue(start.contains("restoreRuntimeAfterMaintenance(snapshot)"))
        assertTrue(start.contains("maintenanceActive.set(false)"))
        assertTrue(source.contains("if (maintenanceActive.get() && action != ACTION_COMPACT_DATABASE && action != ACTION_ARCHIVE_DATABASE)"))
        assertTrue(source.contains("private fun maintenanceBlocksRuntimeStart(): Boolean"))
    }

    @Test
    fun maintenanceClosesMqttWithoutRetainedOfflinePublish() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val stop = source.substringAfter("private fun stopRuntimeForMaintenance").substringBefore("private fun restoreRuntimeAfterMaintenance")

        assertTrue(stop.contains("mqttCoordinator.disconnectForMaintenance()"))
        assertFalse(stop.contains("disconnectOfflineAsync()"))
    }

    @Test
    fun archiveFailureWithMissingOriginalDoesNotReopenFreshDatabase() {
        val source = sourceFile("com/bydcollector/collector/maintenance/DbMaintenanceCoordinator.kt").readText()
        val run = source.substringAfter("fun run(").substringBefore("private fun compact")
        val archive = source.substringAfter("private fun archive").substringBefore("private fun reopenAndRebind")
        val missingOriginalBranch = archive.substringAfter("if (!databaseFile.exists())").substringBefore("reopenAndRebind()")

        assertTrue(source.contains("private class TerminalArchiveFailure"))
        assertTrue(run.contains("var skipRestore = false"))
        assertTrue(run.contains("catch (error: TerminalArchiveFailure)"))
        assertTrue(run.contains("skipRestore = true"))
        assertTrue(run.contains("if (!restored && !skipRestore)"))
        assertTrue(missingOriginalBranch.contains("throw TerminalArchiveFailure(\"Database archive failed and original database was not restored:"))
        assertFalse(missingOriginalBranch.contains("application.reopenTelemetryStoreForMaintenance()"))
        assertFalse(missingOriginalBranch.contains("reopenAndRebind()"))
        assertTrue(archive.contains("if (!reopened && databaseFile.exists())"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
