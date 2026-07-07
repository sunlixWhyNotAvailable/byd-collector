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
        assertTrue(stop.contains("detachDebugPoller()?.shutdownAndAwait(\"database_maintenance\", 2_000L) == false"))
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
    fun maintenanceStatusHasSynchronousPersistenceAndInterruptedRecovery() {
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(settings.contains("fun setDbMaintenanceStatus(status: DbMaintenanceRuntimeStatus, synchronous: Boolean = false)"))
        assertTrue(settings.contains("if (synchronous) editor.commit() else editor.apply()"))
        assertTrue(settings.contains("fun recoverInterruptedDbMaintenanceIfNeeded(source: String): Boolean"))
        assertTrue(settings.contains("previous.running && previous.operation == status.operation && previous.startedAtMs > 0L"))
        assertTrue(settings.contains("DB_MAINTENANCE_RECOVERY_GRACE_MS = 15_000L"))
        assertTrue(settings.contains("now - status.updatedAtMs < DB_MAINTENANCE_RECOVERY_GRACE_MS"))
        assertTrue(settings.contains("running = false"))
        assertTrue(settings.contains("completed = false"))
        assertTrue(settings.contains("error = \"Interrupted before completion\""))
    }

    @Test
    fun maintenanceStatusStoresUpdateTimestamps() {
        val model = sourceFile("com/bydcollector/collector/maintenance/DbMaintenanceModels.kt").readText()
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(model.contains("val startedAtMs: Long = 0L"))
        assertTrue(model.contains("val updatedAtMs: Long = 0L"))
        assertTrue(settings.contains("KEY_DB_MAINTENANCE_STARTED_AT_MS"))
        assertTrue(settings.contains("KEY_DB_MAINTENANCE_UPDATED_AT_MS"))
    }

    @Test
    fun staleMaintenanceIsRecoveredFromActivityAndServiceButNotForMaintenanceIntent() {
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()

        assertTrue(activity.contains("if (!CollectorService.isMaintenanceRunningInProcess())"))
        assertTrue(activity.contains("settings.recoverInterruptedDbMaintenanceIfNeeded(\"activity_start\")"))
        assertTrue(service.contains("private val maintenanceRunningInProcess = AtomicBoolean(false)"))
        assertTrue(service.contains("fun isMaintenanceRunningInProcess(): Boolean = maintenanceRunningInProcess.get()"))
        assertTrue(service.contains("maintenanceRunningInProcess.set(true)"))
        assertTrue(service.contains("maintenanceRunningInProcess.set(false)"))
        assertTrue(service.contains("private fun recoverInterruptedMaintenanceIfNeeded(action: String)"))
        assertTrue(service.contains("if (action == ACTION_COMPACT_DATABASE || action == ACTION_ARCHIVE_DATABASE) return"))
        assertTrue(service.contains("settings.recoverInterruptedDbMaintenanceIfNeeded(\"service_start:${'$'}action\")"))
        assertTrue(service.contains("settings.dbMaintenanceStatus().running"))
    }

    @Test
    fun maintenanceClosesMqttWithoutRetainedOfflinePublish() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val stop = source.substringAfter("private fun stopRuntimeForMaintenance").substringBefore("private fun restoreRuntimeAfterMaintenance")

        assertTrue(stop.contains("mqttCoordinator.disconnectForMaintenance()"))
        assertFalse(stop.contains("disconnectOfflineAsync()"))
    }

    @Test
    fun mqttAndInfluxUseSharedChannelExecutorHelper() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()

        assertTrue(source.contains("private fun <T> executeChannel("))
        assertTrue(source.contains("private data class ChannelActionStatus("))
        assertTrue(source.contains("Thread.MIN_PRIORITY"))
        assertFalse(source.contains("val generation = mqttWorkGeneration.get()"))
        assertFalse(source.contains("val generation = influxWorkGeneration.get()"))
        assertFalse(source.contains("\"MQTT async action rejected\",\n                \"${'$'}{error::class.java.simpleName}"))
        assertFalse(source.contains("\"Influx async action rejected\",\n                \"${'$'}{error::class.java.simpleName}"))
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
