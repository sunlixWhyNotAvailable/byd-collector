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
        assertTrue(stop.contains("val detached = runOnRuntimeOwnerBlocking"))
        assertTrue(stop.contains("detached.mainPoller?.stopAndJoin(2_000L) == false"))
        assertTrue(stop.contains("detached.debugPoller?.shutdownAndAwait(debugStopReason, 2_000L) == false"))
        assertTrue(stop.contains("error(\"Debug poller did not stop for database maintenance\")"))
        assertTrue(stop.contains("resetTelegramExecutorForMaintenance()"))
        assertTrue(stop.contains("resetInfluxExecutorForMaintenance()"))
        assertTrue(source.contains("previous.awaitTermination(MQTT_MAINTENANCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)"))
        assertTrue(source.contains("check(stopped) { \"MQTT worker did not stop before database maintenance\" }"))
        assertTrue(source.contains("previous.awaitTermination(INFLUX_MAINTENANCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)"))
        assertTrue(source.contains("if (!stopped) maintenanceRuntimeRestoreAllowed.set(false)"))
        assertTrue(source.contains("previous.awaitTermination(TELEGRAM_MAINTENANCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)"))
        assertTrue(source.contains("check(stopped) { \"Telegram worker did not stop before database maintenance\" }"))
        assertInOrder(
            source.substringAfter("private fun resetTelegramExecutorForMaintenance")
                .substringBefore("private fun shutdownTelegramExecutorForUserShutdown"),
            "previous.awaitTermination(TELEGRAM_MAINTENANCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)",
            "check(stopped)",
            "telegramExecutor = namedSingleThreadExecutor(\"byd-telegram\")"
        )
        assertFalse(stop.contains("settings.setPollingEnabled(false)"))
        assertFalse(stop.contains("settings.setDebugPollingEnabled(false)"))
        assertFalse(stop.contains("settings.setMqttEnabled(false)"))
        assertFalse(stop.contains("settings.setInfluxEnabled(false)"))
        assertFalse(stop.contains("settings.setTelegramEnabled(false)"))
    }

    @Test
    fun maintenanceUsesLocalSnapshotAndGuardedActionPaths() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val start = source.substringAfter("private fun startDatabaseMaintenance").substringBefore("private fun oneShotMqttCoordinator")

        assertFalse(source.contains("maintenanceRuntimeSnapshot"))
        assertTrue(start.contains("if (!maintenanceActive.compareAndSet(false, true))"))
        assertTrue(start.contains("val snapshot = runtimeSnapshot()"))
        assertTrue(start.contains("dispatchDatabaseMaintenanceCompletion("))
        assertTrue(start.contains("finishDatabaseMaintenanceOnRuntimeOwner("))
        assertTrue(start.contains("restoreRuntimeAfterMaintenance(operation, snapshot)"))
        assertTrue(start.contains("maintenanceActive.set(false)"))
        assertTrue(source.contains("action != ACTION_CANCEL_DATABASE_MAINTENANCE"))
        assertTrue(source.contains("private fun maintenanceBlocksRuntimeStart(debugRuntime: Boolean = false): Boolean"))
        assertTrue(source.contains("activeMaintenanceOperation == DbMaintenanceOperation.ARCHIVE"))
    }

    @Test
    fun influxStopSerializesOnTheCurrentWorkerAndMaintenancePublishesReplacementOnlyAfterTermination() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val stop = source.substringAfter("private fun stopInfluxExport").substringBefore("private fun reconcileTelegramRuntime")
        val reset = source.substringAfter("private fun resetInfluxExecutorForMaintenance")
            .substringBefore("private fun startDatabaseMaintenance")
        val maintenance = source.substringAfter("private fun startDatabaseMaintenance")
            .substringBefore("private fun resetTelegramExecutorForMaintenance")
        val prepare = source.substringAfter("private fun prepareRuntimeStopForMaintenance")
            .substringBefore("private fun restoreRuntimeAfterMaintenance")
        val finish = source.substringAfter("private fun finishDatabaseMaintenanceOnRuntimeOwner")
            .substringBefore("private fun resetTelegramExecutorForMaintenance")

        assertTrue(stop.contains("queueInfluxStop(stopServiceWhenIdle = true)"))
        assertTrue(stop.contains("errorCategory = \"influx_stop_error\""))
        assertTrue(stop.contains("afterComplete = {"))
        assertFalse(stop.contains("resetInfluxExecutorForMaintenance()"))
        assertFalse(stop.contains("finally"))
        assertTrue(source.contains("canExecute = { !maintenanceBlocksRuntimeStart() }"))
        assertTrue(stop.contains("mainHandler.post {"))
        assertTrue(stop.contains("stopIfNoActiveRuntime()"))
        assertTrue(stop.contains("influxCoordinator.stopExport()"))
        assertInOrder(
            reset,
            "advanceInfluxGeneration()",
            "setInfluxRuntime(RuntimeActionStatus.STOPPING)",
            "previous.awaitTermination(INFLUX_MAINTENANCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)",
            "check(stopped)",
            "influxExecutor = namedSingleThreadExecutor(\"byd-influx\")",
            "setInfluxRuntime(RuntimeActionStatus.STOPPED)"
        )
        assertTrue(prepare.contains("cancelInfluxRetry()"))
        assertFalse(prepare.contains("setInfluxRuntime(RuntimeActionStatus.STOPPING)"))
        assertInOrder(finish, "maintenanceActive.set(false)", "restoreRuntimeAfterMaintenance(operation, snapshot)")
        assertInOrder(
            source.substringAfter("private fun stopRuntimeForMaintenance").substringBefore("private fun prepareRuntimeStopForMaintenance"),
            "resetTelegramExecutorForMaintenance()",
            "resetKeepAliveSupervisorForMaintenance()",
            "previous.shutdownAndAwait(2_000L)",
            "keepAliveSupervisor = KeepAliveSupervisor(applicationContext, store)"
        )
        val keepAlive = sourceFile("com/bydcollector/collector/keepalive/KeepAliveSupervisor.kt").readText()
        assertTrue(keepAlive.contains("fun shutdownAndAwait(timeoutMs: Long): Boolean"))
        assertTrue(keepAlive.contains("executor.awaitTermination(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)"))
        assertTrue(maintenance.contains("maintenanceRuntimeRestoreAllowed.set(true)"))
        assertTrue(maintenance.contains("if (maintenanceRuntimeRestoreAllowed.get()) restoreAfterMaintenance = true"))
        assertTrue(maintenance.contains("restoreAfterMaintenance &&"))
        assertTrue(maintenance.contains("maintenanceRuntimeRestoreAllowed.get()"))
    }

    @Test
    fun maintenanceLifecycleStateAndWakeLockAreConfinedToTheMainHandler() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val stop = source.substringAfter("private fun stopRuntimeForMaintenance")
            .substringBefore("private fun restoreRuntimeAfterMaintenance")
        val prepare = source.substringAfter("private fun prepareRuntimeStopForMaintenance")
            .substringBefore("private fun restoreRuntimeAfterMaintenance")
        val restore = source.substringAfter("private fun restoreRuntimeAfterMaintenance")
            .substringBefore("private fun rebuildStoreBackedRuntime")
        val finish = source.substringAfter("private fun finishDatabaseMaintenanceOnRuntimeOwner")
            .substringBefore("private fun resetTelegramExecutorForMaintenance")
        val completion = source.substringAfter("private fun dispatchDatabaseMaintenanceCompletion")
            .substringBefore("private fun finishDatabaseMaintenanceOnRuntimeOwner")
        val mqttReset = source.substringAfter("private fun resetMqttExecutorForMaintenance")
            .substringBefore("private fun shutdownMqttExecutor")
        val influxReset = source.substringAfter("private fun resetInfluxExecutorForMaintenance")
            .substringBefore("private fun startDatabaseMaintenance")
        val telegramReset = source.substringAfter("private fun resetTelegramExecutorForMaintenance")
            .substringBefore("private fun shutdownTelegramExecutorForUserShutdown")

        assertTrue(source.contains("private fun isRuntimeOwner(): Boolean = Looper.myLooper() == mainHandler.looper"))
        assertTrue(source.contains("private fun <T> runOnRuntimeOwnerBlocking(action: () -> T): T"))
        assertInOrder(stop, "runOnRuntimeOwnerBlocking", "stopAndJoin(2_000L)", "shutdownAndAwait(debugStopReason, 2_000L)")
        assertTrue(prepare.contains("requireRuntimeOwner()"))
        assertTrue(prepare.contains("sessionId = null"))
        assertTrue(prepare.contains("cancelInfluxRetry()"))
        assertTrue(prepare.contains("cancelTelegramTick()"))
        assertFalse(prepare.contains("stopAndJoin(2_000L)"))
        assertTrue(restore.contains("requireRuntimeOwner()"))
        val wiring = source.substringAfter("maintenanceCoordinator = DbMaintenanceCoordinator(")
            .substringBefore("createNotificationChannel()")
        assertInOrder(wiring, "onStoreReopened", "runOnRuntimeOwnerBlocking", "rebuildStoreBackedRuntime(newStore)")
        assertInOrder(
            wiring,
            "onDebugStoreReopened",
            "val storageReady = newStore.isCompactV2()",
            "runOnRuntimeOwnerBlocking",
            "rebindDebugStoreAfterMaintenance(newStore, storageReady)"
        )
        assertInOrder(finish, "maintenanceActive.set(false)", "restoreRuntimeAfterMaintenance(operation, snapshot)")
        assertTrue(completion.contains("mainHandler.post"))
        assertTrue(completion.contains("finishDatabaseMaintenanceOnRuntimeOwner(operation, snapshot, restoreAfterMaintenance)"))
        assertTrue(source.substringAfter("private fun acquireWakeLock").substringBefore("private fun releaseWakeLock").contains("requireRuntimeOwner()"))
        assertTrue(source.substringAfter("private fun releaseWakeLock").substringBefore("private fun handlePollCycleResult").contains("requireRuntimeOwner()"))
        assertTrue(source.contains("if (restoringRuntime.get() && isRuntimeOwner()) return false"))
        assertTrue(stop.contains("check(!isRuntimeOwner())"))
        listOf(mqttReset, influxReset, telegramReset).forEach { reset ->
            assertInOrder(reset, "runOnRuntimeOwnerBlocking", "awaitTermination(", "runOnRuntimeOwnerBlocking")
            assertTrue(reset.contains("requireRuntimeOwner()"))
        }
    }

    @Test
    fun maintenanceStatusHasSynchronousPersistenceAndInterruptedRecovery() {
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()
        val setStatus = settings.substringAfter("fun setDbMaintenanceStatus")
            .substringBefore("fun recoverInterruptedDbMaintenanceIfNeeded")

        assertTrue(settings.contains("fun setDbMaintenanceStatus(status: DbMaintenanceRuntimeStatus, synchronous: Boolean = false)"))
        assertTrue(setStatus.contains("return if (synchronous) editor.commit() else {"))
        assertTrue(setStatus.contains("editor.apply()"))
        assertTrue(setStatus.contains("true"))
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

        assertTrue(activity.contains("!CollectorService.isMaintenanceRunningInProcess() && !DatabaseMaintenanceService.isRunning()"))
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
        val reset = source.substringAfter("private fun resetMqttExecutorForMaintenance")
            .substringBefore("private fun shutdownMqttExecutor")

        assertTrue(stop.contains("resetMqttExecutorForMaintenance()"))
        assertInOrder(reset, "previous.awaitTermination", "mqttCoordinator.disconnectForMaintenance()")
        assertFalse(stop.contains("disconnectOfflineAsync()"))
        assertFalse(reset.contains("disconnectOfflineAsync()"))
    }

    @Test
    fun databaseMaintenanceCancelsScheduledAutostartAndAutostartSkipsDbWhileRunning() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val autoStart = sourceFile("com/bydcollector/collector/system/CollectorAutoStart.kt").readText()
        val startMaintenance = service.substringAfter("private fun startDatabaseMaintenance").substringBefore("private fun cancelDatabaseMaintenance")
        val handleBroadcast = autoStart.substringAfter("fun handleBroadcast").substringBefore("fun handleKeepAliveRecovery")
        val keepAliveRecovery = autoStart.substringAfter("fun handleKeepAliveRecovery").substringBefore("fun recoverFromForeground")
        val recoverFromForeground = autoStart.substringAfter("fun recoverFromForeground").substringBefore("fun scheduleRestartAfterTaskRemoved")
        val taskRemoved = autoStart.substringAfter("fun scheduleRestartAfterTaskRemoved").substringBefore("fun scheduleRestartAfterUiClosed")
        val uiClosed = autoStart.substringAfter("fun scheduleRestartAfterUiClosed").substringBefore("fun scheduleWatchdog")
        val watchdog = autoStart.substringAfter("fun scheduleWatchdog").substringBefore("fun cancelScheduled")
        val recoveryRequest = autoStart.substringAfter("fun handleRecoveryRequest").substringBefore("fun scheduleKeepAliveStopRetry")
        val onStart = service.substringAfter("override fun onStartCommand").substringBefore("override fun onDestroy")
        val keepAliveRetryGate = service.substringAfter("private fun keepAliveStopRetryBlockedByMaintenance")
            .substringBefore("private fun isRuntimeOwner")

        assertTrue(startMaintenance.contains("CollectorAutoStart.cancelRuntimeRecovery(applicationContext)"))
        assertFalse(startMaintenance.contains("CollectorAutoStart.cancelScheduled(applicationContext)"))
        assertTrue(autoStart.contains("fun cancelRuntimeRecovery(context: Context)"))
        val maintenanceCancellation = autoStart.substringAfter("fun cancelRuntimeRecovery")
            .substringBefore("private fun ensurePollingEnabled")
        assertTrue(maintenanceCancellation.contains("cancelRetry(appContext)"))
        assertTrue(maintenanceCancellation.contains("cancelWatchdog(appContext)"))
        assertFalse(maintenanceCancellation.contains("cancelKeepAliveStopRetry(appContext)"))
        assertInOrder(handleBroadcast, "CollectorSettings.isDbMaintenanceRunning(appContext)", "BydCollectorApplication.store(appContext)")
        assertTrue(handleBroadcast.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext)) return"))
        assertTrue(recoverFromForeground.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext)) return"))
        assertTrue(recoverFromForeground.contains("if (!demand.any) return"))
        assertTrue(taskRemoved.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext)) return"))
        assertTrue(uiClosed.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext)) return"))
        assertTrue(watchdog.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext)) return"))
        assertInOrder(keepAliveRecovery, "CollectorSettings.isDbMaintenanceRunning(appContext)", "BydCollectorApplication.store(appContext)")
        assertTrue(keepAliveRecovery.contains("if (CollectorSettings.isDbMaintenanceRunning(appContext)) return"))
        assertTrue(recoveryRequest.contains("CollectorService.isMaintenanceRunningInProcess()"))
        assertTrue(recoveryRequest.contains("deferKeepAliveStopRetry(appContext, retryAttempt)"))
        assertTrue(onStart.contains("if (keepAliveStopRetryBlockedByMaintenance())"))
        assertTrue(onStart.contains("CollectorAutoStart.deferKeepAliveStopRetry(applicationContext, retryAttempt)"))
        assertInOrder(onStart, "keepAliveStopRetryBlockedByMaintenance()", "reconcileKeepAliveStopRetry(retryAttempt)")
        assertTrue(keepAliveRetryGate.contains("maintenanceActive.get()"))
        assertTrue(keepAliveRetryGate.contains("CollectorSettings.isDbMaintenanceRunning(applicationContext)"))
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
        assertInOrder(
            coordinator,
            "closeCancelWindowAndCheck(operation)",
            "application.withExclusiveDatabaseMaintenance",
            "archive(operation)"
        )
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
        val pendingArchiveReconcile = service.substringAfter("private fun reconcilePendingCutoverArchiveStorage")
            .substringBefore("private fun enqueueArchiveDelete")
        assertTrue(pendingArchiveReconcile.contains("ACTION_RECONCILE_ARCHIVE_STORAGE"))
        assertTrue(service.contains("ACTION_DELETE_ARCHIVES"))
        assertTrue(service.contains("ArchiveStorageManager("))
        assertTrue(service.contains("elapsedRealtimeMs = { SystemClock.elapsedRealtime() }"))
        assertTrue(service.contains("isRetentionProtected = archiveShareLeaseRegistry::isActive"))
        assertInOrder(service, "archiveShareLeaseRegistry.forceRelease(ids)", "manager.deleteArchiveIds(ids")
        assertTrue(service.contains("fun isArchiveStorageActive(): Boolean = archiveStorageActiveInProcess.get()"))
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
        val prepare = service.substringAfter("private fun prepareRuntimeStopForMaintenance")
            .substringBefore("private fun restoreRuntimeAfterMaintenance")
        val debugBranch = prepare.substringAfter("if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE)")
            .substringBefore("cancelInfluxRetry()")

        assertTrue(service.contains("ACTION_ARCHIVE_DEBUG_DATABASE"))
        assertTrue(stop.contains("\"debug_database_maintenance\""))
        assertTrue(stop.contains("shutdownAndAwait(debugStopReason, 2_000L)"))
        assertTrue(debugBranch.contains("detachDebugPoller()"))
        assertFalse(debugBranch.contains("poller.stop()"))
        assertFalse(debugBranch.contains("mqttCoordinator"))
        assertFalse(debugBranch.contains("resetInfluxExecutorForMaintenance"))
        assertFalse(debugBranch.contains("setInfluxRuntime"))
        assertTrue(coordinator.contains("private fun archiveDebug(operation: DbMaintenanceOperation)"))
        assertTrue(coordinator.contains("StorageFormatCutoverCoordinator.checkpointDatabase(databaseFile)"))
        assertTrue(coordinator.contains("check(newStore.verifyWritableDatabase())"))
        assertTrue(service.contains("snapshot.debugRunning &&"))
        assertTrue(service.contains("maintenanceBlocksRuntimeStart(debugRuntime = true)"))
        assertTrue(service.contains("activeMaintenanceOperation != DbMaintenanceOperation.DEBUG_ARCHIVE || debugRuntime"))
        val finish = service.substringAfter("private fun finishDatabaseMaintenanceOnRuntimeOwner")
            .substringBefore("private fun resetTelegramExecutorForMaintenance")
        assertInOrder(finish, "maintenanceActive.set(false)", "restoreRuntimeAfterMaintenance(operation, snapshot)")
        assertInOrder(
            "$run\n$archiveDebug",
            "stopRuntime(operation)",
            "StorageFormatCutoverCoordinator.checkpointDatabase(databaseFile)",
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
            "StorageFormatCutoverCoordinator.checkpointDatabase(databaseFile)",
            "application.closeTelemetryStoreForMaintenance()",
            "val sourceNames = sourceNames(databaseFile)",
            "val archive = DatabaseArchiveManager.archive(",
            "val newStore = application.reopenTelemetryStoreForMaintenance()",
            "check(newStore.verifyWritableDatabase())"
        )
    }

    @Test
    fun unusableDatabaseCanReachManualRecoveryWithoutCollectorStoreBootstrap() {
        val controller = sourceFile("com/bydcollector/collector/service/CollectorServiceController.kt").readText()
        val recoveryService = sourceFile("com/bydcollector/collector/service/DatabaseMaintenanceService.kt").readText()
        val application = sourceFile("com/bydcollector/collector/BydCollectorApplication.kt").readText()
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).first(File::isFile).readText()

        assertTrue(controller.contains("if (CollectorService.isRunning())"))
        assertTrue(controller.contains("DatabaseMaintenanceService.archiveIntent"))
        assertTrue(controller.contains("DbMaintenanceOperation.ARCHIVE"))
        assertTrue(controller.contains("DbMaintenanceOperation.DEBUG_ARCHIVE"))
        assertFalse(recoveryService.contains("BydCollectorApplication.store("))
        assertFalse(recoveryService.contains("DirectDebugStore(applicationContext"))
        val stopRuntime = recoveryService.substringAfter("private fun stopCollectorRuntime")
            .substringBefore("private fun closeReopenedDebugStore")
        assertInOrder(
            stopRuntime,
            "stopService(Intent(applicationContext, CollectorService::class.java))",
            "check(!CollectorService.isRunning())"
        )
        assertTrue(recoveryService.contains("val result = coordinator.run(operation)"))
        assertTrue(recoveryService.contains("settings.setCutoverArchiveStoragePending(true)"))
        assertTrue(recoveryService.contains("CollectorServiceController.start(applicationContext)"))
        assertTrue(application.contains("check(coordinator().ensureMainReady())"))
        assertTrue(manifest.contains("com.bydcollector.collector.service.DatabaseMaintenanceService"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
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
    fun archiveFailureRequiresCompleteRollbackAndVerifiedOriginalBeforeRuntimeCanResume() {
        val source = sourceFile("com/bydcollector/collector/maintenance/DbMaintenanceCoordinator.kt").readText()
        val run = source.substringAfter("fun run(").substringBefore("private fun archive")
        val archiveMain = source.substringAfter("private fun archiveMain").substringBefore("private fun rollbackMain")
        val archiveDebug = source.substringAfter("private fun archiveDebug").substringBefore("private fun rollbackDebug")

        assertTrue(source.contains("private class TerminalArchiveFailure"))
        assertTrue(run.contains("var skipRestore = false"))
        assertTrue(run.contains("catch (error: TerminalArchiveFailure)"))
        assertTrue(run.contains("skipRestore = true"))
        assertTrue(run.contains("if (!restored && !skipRestore)"))
        assertTrue(archiveMain.contains("if (!archive.rollbackOk || !databaseFile.exists())"))
        assertTrue(archiveMain.contains("throw TerminalArchiveFailure(\"Database archive failed and original database was not restored:"))
        assertInOrder(archiveMain, "reopenMainAndVerifyRestored(databaseFile)", "settings.clearStorageCutoverJournal()")
        assertTrue(archiveDebug.contains("if (!archive.rollbackOk || !databaseFile.exists())"))
        assertTrue(archiveDebug.contains("throw TerminalArchiveFailure(\"Debug database archive failed and original database was not restored:"))
        assertInOrder(archiveDebug, "reopenDebugAndVerifyRestored(databaseFile)", "settings.clearStorageCutoverJournal()")
    }

    @Test
    fun failedNewDatabaseIsClosedDeletedRestoredAndVerifiedBeforeRebind() {
        val source = sourceFile("com/bydcollector/collector/maintenance/DbMaintenanceCoordinator.kt").readText()
        val archiveMain = source.substringAfter("private fun archiveMain").substringBefore("private fun rollbackMain")
        val rollbackMain = source.substringAfter("private fun rollbackMain").substringBefore("private fun reopenMainAndVerifyRestored")
        val archiveDebug = source.substringAfter("private fun archiveDebug").substringBefore("private fun rollbackDebug")
        val rollbackDebug = source.substringAfter("private fun rollbackDebug").substringBefore("private fun reopenDebugAndVerifyRestored")
        val exactDelete = source.substringAfter("private fun deleteExactNewDatabaseSet").substringBefore("private fun checkCancelled")

        assertInOrder(archiveMain, "val newStore = application.reopenTelemetryStoreForMaintenance()", "check(newStore.verifyWritableDatabase())", "rollbackMain(databaseFile, archive, createFailure, manual = true)")
        assertInOrder(rollbackMain, "application.closeTelemetryStoreForMaintenance()", "markRollbackPhase()", "deleteExactNewDatabaseSet(databaseFile)", "DatabaseArchiveManager.restore(databaseFile, archive.movedFiles)", "reopenMainAndVerifyRestored(databaseFile)")
        assertInOrder(archiveDebug, "val newStore = reopenDebugAndRebind()", "check(newStore.verifyWritableDatabase())", "rollbackDebug(databaseFile, archive, createFailure, manual = true)")
        assertInOrder(rollbackDebug, "closeDebugStore()", "markRollbackPhase()", "deleteExactNewDatabaseSet(databaseFile)", "DatabaseArchiveManager.restore(databaseFile, archive.movedFiles)", "reopenDebugAndVerifyRestored(databaseFile)")
        assertFalse(rollbackMain.contains("runCatching { markRollbackPhase() }"))
        assertFalse(rollbackDebug.contains("runCatching { markRollbackPhase() }"))
        assertTrue(source.contains("checkNotNull(settings.storageCutoverJournal()) { \"Database rollback journal is missing\" }"))
        assertTrue(exactDelete.contains("databaseFile.name == TelemetryDatabaseHelper.DATABASE_NAME"))
        assertTrue(exactDelete.contains("databaseFile.name == DirectDebugDatabaseHelper.DATABASE_NAME"))
        assertTrue(exactDelete.contains("context.getDatabasePath(databaseFile.name).canonicalFile"))
        assertTrue(exactDelete.contains("databaseFile.canonicalFile"))
        assertTrue(exactDelete.contains("DatabaseArchiveManager.sidecarFiles(expected)"))
    }

    @Test
    fun manualArchiveDoesNotOverwriteAnUnresolvedCutoverJournal() {
        val source = sourceFile("com/bydcollector/collector/maintenance/DbMaintenanceCoordinator.kt").readText()
        val archiveMain = source.substringAfter("private fun archiveMain").substringBefore("private fun rollbackMain")
        val archiveDebug = source.substringAfter("private fun archiveDebug").substringBefore("private fun rollbackDebug")

        assertInOrder(archiveMain, "check(settings.storageCutoverJournal() == null)", "application.closeTelemetryStoreForMaintenance()", "settings.setStorageCutoverJournal(")
        assertInOrder(archiveDebug, "check(settings.storageCutoverJournal() == null)", "closeDebugStore()", "settings.setStorageCutoverJournal(")
    }

    @Test
    fun manualDebugArchiveAcceptsAnIntegrityCheckedUnknownSchemaForRecovery() {
        val source = sourceFile("com/bydcollector/collector/maintenance/DbMaintenanceCoordinator.kt").readText()
        val archiveDebug = source.substringAfter("private fun archiveDebug").substringBefore("private fun rollbackDebug")
        val reopenDebug = source.substringAfter("private fun reopenDebugAndVerifyRestored")
            .substringBefore("private fun reopenDebugAndRebind")

        assertTrue(archiveDebug.contains("check(sourceFormat != StorageFormat.ABSENT)"))
        assertTrue(archiveDebug.contains("val quickCheck = runCatching { verifyWritableDatabaseFile(databaseFile) }.getOrDefault(false)"))
        assertTrue(archiveDebug.contains("warnings += inspectionWarning(\"quick_check\")"))
        assertFalse(archiveDebug.contains("sourceFormat in setOf(StorageFormat.LEGACY_V1, StorageFormat.COMPACT_V2)"))
        assertTrue(reopenDebug.contains("format == StorageFormat.ABSENT || !verifyWritableDatabaseFile(databaseFile)"))
        assertFalse(reopenDebug.contains("Restored debug database format is not recognized"))
    }

    @Test
    fun partialRestoreIsTerminalAndDoesNotRestartRuntime() {
        val source = sourceFile("com/bydcollector/collector/maintenance/DbMaintenanceCoordinator.kt").readText()
        val run = source.substringAfter("fun run(").substringBefore("private fun archive")
        val rollbackMain = source.substringAfter("private fun rollbackMain").substringBefore("private fun reopenMainAndVerifyRestored")
        val rollbackDebug = source.substringAfter("private fun rollbackDebug").substringBefore("private fun reopenDebugAndVerifyRestored")

        assertTrue(rollbackMain.contains("if (!DatabaseArchiveManager.restore(databaseFile, archive.movedFiles))"))
        assertTrue(rollbackMain.contains("throw TerminalArchiveFailure(\"Database archive restore was incomplete"))
        assertTrue(rollbackDebug.contains("if (!DatabaseArchiveManager.restore(databaseFile, archive.movedFiles))"))
        assertTrue(rollbackDebug.contains("throw TerminalArchiveFailure(\"Debug database archive restore was incomplete"))
        assertInOrder(run, "catch (error: TerminalArchiveFailure)", "skipRestore = true")
        assertTrue(run.contains("if (!restored && !skipRestore)"))
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
