package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecondaryRuntimeActivationContractTest {
    @Test
    fun persistedSettingsConfigureBothStreamsAndOrdinaryDestroyOnlyReleasesApp() {
        val service = source("service/CollectorService.kt")

        assertTrue(service.contains("DirectStreamController.configureDesired("))
        assertTrue(service.contains("settings.isPollingEnabled() && !settings.isMainManuallyStopped()"))
        assertTrue(service.contains("settings.isDebugPollingEnabled() && !settings.isDebugManuallyStopped()"))
        assertTrue(service.contains("reconcilePersistedHelperStateAsync()"))
        val destroy = service.substringAfter("override fun onDestroy()").substringBefore("override fun onTaskRemoved")
        assertTrue(destroy.indexOf("stopCollection(\"service_destroyed\")") < destroy.indexOf("DirectStreamController.releaseApp()"))
        assertFalse(destroy.contains("setDesired("))
        assertTrue(destroy.contains("closeDebugStoreAfterLocalWorkers()"))
        assertFalse(destroy.contains("debugStore.close()"))
        assertTrue(service.contains("onStopped = ::onDebugPollerStopped"))
        assertTrue(destroy.indexOf("debugStartExecutor.shutdownNow()") < destroy.indexOf("closeDebugStoreAfterDebugStartExecutorStops()"))
    }

    @Test
    fun individualStopsDisableOnlyTheirOwnHelperStreamWithoutOwnerRestart() {
        val service = source("service/CollectorService.kt")
        val stopMain = service.substringAfter("private fun stopMain(").substringBefore("private fun stopAppGapSpoolHelper")
        val stopSecondary = service.substringAfter("private fun stopDebug(").substringBefore("private fun setMainRuntime")

        assertTrue(stopMain.contains("setDesired(CollectorHelperProtocol.STREAM_MAIN, false)"))
        assertFalse(stopMain.contains("STREAM_SECONDARY"))
        assertTrue(stopSecondary.contains("setDesired(CollectorHelperProtocol.STREAM_SECONDARY, false)"))
        assertFalse(stopSecondary.contains("STREAM_MAIN"))
        assertFalse(service.contains("helper_owner_handoff"))
    }

    @Test
    fun secondaryStartupClaimsOnlySecondaryAndBuildsReplayBeforeLivePoller() {
        val service = source("service/CollectorService.kt")
        val start = service.substringAfter("private fun startDebugIfNeeded(").substringBefore("private fun handleStartFailure")

        assertTrue(start.contains("ensureReady(CollectorHelperProtocol.STREAM_SECONDARY)"))
        assertTrue(start.contains("SecondaryReplayCoordinator("))
        assertTrue(start.contains("val callbacks = callbackDrain(helper, CollectorHelperProtocol.STREAM_SECONDARY)"))
        assertTrue(start.contains("callbacks.drain(maxBatches = Int.MAX_VALUE)"))
        assertTrue(start.contains("if (!callbackReplay.drained)"))
        assertTrue(start.contains("retryable = callbackReplay.retryable"))
        assertTrue(start.contains("debugStore.importSecondaryRecord(openedSessionId, record, digest)"))
        assertTrue(start.contains("pauseAndFence(CollectorHelperProtocol.STREAM_SECONDARY)"))
        assertTrue(start.contains("resume(CollectorHelperProtocol.STREAM_SECONDARY)"))
        assertTrue(start.contains("releaseLease(CollectorHelperProtocol.STREAM_SECONDARY)"))
        assertTrue(start.contains("if (startGeneration == debugWorkGeneration.get())"))
        assertTrue(start.contains("nextPoller.start(batchSize)"))
        assertFalse(start.contains("nextPoller.start(batchSize)\n                            debugPoller = nextPoller\n                            setDebugRuntime(DebugRuntimeStatus.RUNNING"))
    }

    @Test
    fun secondaryArchiveDrainsOnlyWhenActiveAndStoppedBacklogAborts() {
        val service = source("service/CollectorService.kt")
        val archive = service.substringAfter("private fun prepareSecondaryArchive(").substringBefore("private fun restoreRuntimeAfterMaintenance")

        assertTrue(archive.contains("if (!wasRunning)"))
        assertTrue(archive.contains("setDesired(CollectorHelperProtocol.STREAM_SECONDARY, false)"))
        assertTrue(archive.indexOf("setDesired(CollectorHelperProtocol.STREAM_SECONDARY, false)") < archive.indexOf("helper.secondarySpoolStatus()"))
        assertTrue(archive.contains("helper.secondarySpoolStatus()"))
        assertTrue(archive.contains("helper.callbackSpoolStatus(CollectorHelperProtocol.STREAM_SECONDARY)"))
        assertTrue(archive.contains("callbackBacklog.readyBatches == 0"))
        assertTrue(archive.contains("callbackDrain(helper, CollectorHelperProtocol.STREAM_SECONDARY).drain(Int.MAX_VALUE)"))
        assertTrue(archive.contains("check(!backlog.pending)"))
        assertTrue(archive.contains("Start secondary collection to drain it first"))
        assertTrue(archive.contains("pauseAndFence(CollectorHelperProtocol.STREAM_SECONDARY)"))
        assertTrue(archive.contains("SecondaryReplayCoordinator("))
        assertTrue(archive.contains("debugStore.endSession(archiveSessionId, \"debug_database_maintenance\")"))
        assertTrue(archive.indexOf("pauseAndFence") < archive.indexOf("SecondaryReplayCoordinator"))
        assertTrue(archive.indexOf("SecondaryReplayCoordinator") < archive.indexOf("debugStore.endSession"))
        val maintenance = service.substringAfter("private fun stopRuntimeForMaintenance(").substringBefore("private fun resetKeepAliveSupervisorForMaintenance")
        assertFalse(maintenance.contains("stopAppGapSpoolHelper(\"database_maintenance\")"))
        assertTrue(maintenance.contains("store.normalizePendingCallbackPage(vehicleStateNormalizer)"))
        assertTrue(maintenance.indexOf("stopAndJoin") < maintenance.indexOf("store.normalizePendingCallbackPage"))
    }

    @Test
    fun mainReadinessIsScopedToMainStream() {
        val client = source("data/remote/DirectTelemetryClient.kt")
        val bridge = source("data/remote/DirectBridgeManager.kt")

        assertTrue(client.contains("ensureReady(CollectorHelperProtocol.STREAM_MAIN)"))
        assertTrue(client.contains("if (!helper.isAlive())"))
        assertFalse(client.contains("helper.ownerMode() != ownerMode"))
        assertTrue(bridge.contains("if (helper.isAlive())"))
        assertFalse(bridge.contains("if (helper.ownerMode() == ownerMode)"))
    }

    private fun source(relative: String): String {
        val path = "com/bydcollector/collector/$relative"
        return listOf(File("src/main/kotlin/$path"), File("app/src/main/kotlin/$path"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("Missing source file: $path")
    }
}
