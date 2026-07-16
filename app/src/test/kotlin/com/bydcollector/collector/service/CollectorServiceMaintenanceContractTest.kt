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
        assertTrue(start.contains("restoreRuntimeAfterMaintenance(operation, snapshot)"))
        assertTrue(start.contains("maintenanceActive.set(false)"))
        assertTrue(source.contains("action != ACTION_CANCEL_DATABASE_MAINTENANCE"))
        assertTrue(source.contains("private fun maintenanceBlocksRuntimeStart(debugRuntime: Boolean = false): Boolean"))
        assertTrue(source.contains("activeMaintenanceOperation == DbMaintenanceOperation.ARCHIVE"))
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
    fun maintenanceStatusStoresCancelAvailabilityAndProvidesPrefsOnlyRunningGuard() {
        val model = sourceFile("com/bydcollector/collector/maintenance/DbMaintenanceModels.kt").readText()
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(model.contains("val cancelAvailable: Boolean = false"))
        assertTrue(settings.contains("KEY_DB_MAINTENANCE_CANCEL_AVAILABLE"))
        assertTrue(settings.contains("cancelAvailable = prefs.getBoolean(KEY_DB_MAINTENANCE_CANCEL_AVAILABLE, false)"))
        assertTrue(settings.contains("putBoolean(KEY_DB_MAINTENANCE_CANCEL_AVAILABLE, status.cancelAvailable)"))
        assertTrue(settings.contains("fun isDbMaintenanceRunning(context: Context): Boolean"))
        assertTrue(settings.contains("context.applicationContext"))
        assertTrue(settings.contains("getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)"))
        assertTrue(settings.contains("operation != null && prefs.getBoolean(KEY_DB_MAINTENANCE_RUNNING, false)"))
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
        assertTrue(service.contains("if (action == ACTION_ARCHIVE_DATABASE || action == ACTION_ARCHIVE_DEBUG_DATABASE) return"))
        assertFalse(service.contains("ACTION_COMPACT_DATABASE"))
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
    fun databaseMaintenanceCancelsScheduledAutostartAndAutostartSkipsDbWhileRunning() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val autoStart = sourceFile("com/bydcollector/collector/system/CollectorAutoStart.kt").readText()
        val keepAliveReceiver = sourceFile("com/bydcollector/collector/system/KeepAliveRecoveryReceiver.kt").readText()
        val startMaintenance = service.substringAfter("private fun startDatabaseMaintenance").substringBefore("private fun cancelDatabaseMaintenance")
        val handleBroadcast = autoStart.substringAfter("fun handleBroadcast").substringBefore("fun recoverFromForeground")
        val recoverFromForeground = autoStart.substringAfter("fun recoverFromForeground").substringBefore("fun scheduleRestartAfterTaskRemoved")
        val taskRemoved = autoStart.substringAfter("fun scheduleRestartAfterTaskRemoved").substringBefore("fun scheduleRestartAfterUiClosed")
        val uiClosed = autoStart.substringAfter("fun scheduleRestartAfterUiClosed").substringBefore("fun scheduleWatchdog")
        val watchdog = autoStart.substringAfter("fun scheduleWatchdog").substringBefore("fun cancelScheduled")

        assertTrue(startMaintenance.contains("CollectorAutoStart.cancelScheduled(applicationContext)"))
        assertInOrder(handleBroadcast, "CollectorSettings.isDbMaintenanceRunning(appContext)", "BydCollectorApplication.store(appContext)")
        assertTrue(handleBroadcast.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext)) return"))
        assertTrue(recoverFromForeground.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext) || !shouldRunService(settings)) return"))
        assertTrue(taskRemoved.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext)) return"))
        assertTrue(uiClosed.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext)) return"))
        assertTrue(watchdog.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext)) return"))
        assertInOrder(keepAliveReceiver, "CollectorSettings.isDbMaintenanceRunning(appContext)", "BydCollectorApplication.store(appContext)")
        assertTrue(keepAliveReceiver.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext)) return"))
    }

    @Test
    fun maintenanceSupportsCooperativeCancelOnlyBeforeCriticalStages() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val coordinator = sourceFile("com/bydcollector/collector/maintenance/DbMaintenanceCoordinator.kt").readText()
        val controller = sourceFile("com/bydcollector/collector/service/CollectorServiceController.kt").readText()

        assertTrue(service.contains("ACTION_CANCEL_DATABASE_MAINTENANCE"))
        assertTrue(service.contains("maintenanceCoordinator.requestCancel()"))
        assertTrue(controller.contains("fun cancelDatabaseMaintenance(context: Context)"))
        assertTrue(coordinator.contains("fun requestCancel(): Boolean"))
        assertTrue(coordinator.contains("private val cancelRequested = AtomicBoolean(false)"))
        assertTrue(coordinator.contains("private val cancelAllowed = AtomicBoolean(false)"))
        assertTrue(coordinator.contains("private val cancelLock = Any()"))
        assertTrue(coordinator.contains("synchronized(cancelLock)"))
        assertTrue(coordinator.contains("private fun closeCancelWindowAndCheck(operation: DbMaintenanceOperation)"))
        assertTrue(coordinator.contains("private class DbMaintenanceCancelled"))
        assertTrue(coordinator.contains("publishCancelled(operation)"))
        assertInOrder(coordinator, "publish(operation, 1, cancelAvailable = true)", "stopRuntime(operation)")
        assertInOrder(coordinator, "closeCancelWindowAndCheck(operation)", "val result = archive(operation)")
        assertFalse(coordinator.contains("shutdownNow()"))
    }

    @Test
    fun archiveStorageRunsOnSeparateWorkerAndCompactActionIsRemoved() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val controller = sourceFile("com/bydcollector/collector/service/CollectorServiceController.kt").readText()
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(service.contains("private val archiveStorageExecutor = namedSingleThreadExecutor(\"byd-archive-storage\")"))
        assertTrue(service.contains("enqueueArchiveStorageMaintenance(result.archivePath)"))
        assertTrue(service.contains("ACTION_RECONCILE_ARCHIVE_STORAGE"))
        assertTrue(service.contains("ACTION_DELETE_ARCHIVES"))
        assertTrue(service.contains("ArchiveStorageManager("))
        assertTrue(controller.contains("fun reconcileArchiveStorage(context: Context)"))
        assertTrue(controller.contains("fun deleteArchives(context: Context, ids: List<String>)"))
        assertTrue(settings.contains("fun archiveStorageLimitGb(): Int"))
        assertTrue(settings.contains("fun archiveStorageJobStatus(): ArchiveStorageJobStatus"))
        assertFalse(service.contains("ACTION_COMPACT_DATABASE"))
        assertFalse(controller.contains("fun compactDatabase"))
    }

    @Test
    fun debugArchiveStopsAndRestoresOnlyRoundRobinRuntime() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val coordinator = sourceFile("com/bydcollector/collector/maintenance/DbMaintenanceCoordinator.kt").readText()
        val run = coordinator.substringAfter("fun run(").substringBefore("private fun archive")
        val archiveDebug = coordinator.substringAfter("private fun archiveDebug").substringBefore("private fun reopenDebugAndRebind")
        val stop = service.substringAfter("private fun stopRuntimeForMaintenance").substringBefore("private fun restoreRuntimeAfterMaintenance")
        val debugBranch = stop.substringAfter("if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE)").substringBefore("if (!poller.stopAndJoin")

        assertTrue(service.contains("ACTION_ARCHIVE_DEBUG_DATABASE"))
        assertTrue(debugBranch.contains("shutdownAndAwait(\"debug_database_maintenance\", 2_000L)"))
        assertFalse(debugBranch.contains("poller.stopAndJoin"))
        assertFalse(debugBranch.contains("mqttCoordinator"))
        assertFalse(debugBranch.contains("resetInfluxExecutorForMaintenance"))
        assertTrue(coordinator.contains("private fun archiveDebug(operation: DbMaintenanceOperation)"))
        assertTrue(coordinator.contains("debugStore.checkpointForArchive()"))
        assertTrue(coordinator.contains("check(newStore.verifyWritableDatabase())"))
        assertTrue(service.contains("snapshot.debugRunning &&"))
        assertTrue(service.contains("maintenanceBlocksRuntimeStart(debugRuntime = true)"))
        assertTrue(service.contains("activeMaintenanceOperation != DbMaintenanceOperation.DEBUG_ARCHIVE || debugRuntime"))
        assertInOrder(service, "maintenanceActive.set(false)", "if (restoreAfterMaintenance)")
        assertInOrder(
            "$run\n$archiveDebug",
            "stopRuntime(operation)",
            "debugStore.checkpointForArchive()",
            "closeDebugStore()",
            "val archive = DatabaseArchiveManager.archive(",
            "val newStore = reopenDebugAndRebind()",
            "check(newStore.verifyWritableDatabase())"
        )
    }

    @Test
    fun mainArchiveStopsCheckpointsClosesArchivesReopensAndQuickChecksInOrder() {
        val coordinator = sourceFile("com/bydcollector/collector/maintenance/DbMaintenanceCoordinator.kt").readText()
        val run = coordinator.substringAfter("fun run(").substringBefore("private fun archive")
        val archiveMain = coordinator.substringAfter("private fun archiveMain").substringBefore("private fun reopenAndRebind")

        assertInOrder(
            "$run\n$archiveMain",
            "stopRuntime(operation)",
            "store.checkpointForArchive()",
            "application.closeTelemetryStoreForMaintenance()",
            "val archive = DatabaseArchiveManager.archive(",
            "val newStore = application.reopenTelemetryStoreForMaintenance()",
            "check(newStore.verifyWritableDatabase())"
        )
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
        val run = source.substringAfter("fun run(").substringBefore("private fun archive")
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

    private fun assertInOrder(source: String, vararg tokens: String) {
        var previousIndex = -1
        var previousToken: String? = null
        for (token in tokens) {
            val index = source.indexOf(token, previousIndex + 1)
            assertTrue(index >= 0, "Missing token: $token")
            if (previousToken != null) {
                assertTrue(index > previousIndex, "Expected `$token` after `$previousToken`")
            }
            previousIndex = index
            previousToken = token
        }
    }
}
