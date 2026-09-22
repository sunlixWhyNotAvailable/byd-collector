package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorServiceWorkerHandoffContractTest {
    @Test
    fun callbackDrainSharesMainWriterButCannotRetriggerBusinessConsumers() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val cycle = service.substringAfter("val callbacks = callbackDrain(helper, CollectorHelperProtocol.STREAM_MAIN)")
            .substringBefore("mainPollerOwnerMode = ownerMode")
        assertTrue(cycle.indexOf("normalizeCallbackPage()") < cycle.indexOf("liveClient.ensureHelperReady(ownerMode)"))
        assertTrue(cycle.contains("callbacks.drain(maxBatches = 2)"))
        assertTrue(cycle.indexOf("callbacks.drain") < cycle.indexOf("pollCycles.pollOnce(sessionId)"))
        val rawPath = service.substringAfter("private fun callbackDrain(").substringBefore("private fun createSuccessfulPollObserver")
        assertTrue(rawPath.contains("store.importCallbackBatch(batch, digest, delivery)"))
        assertTrue(rawPath.contains("debugStore.importCallbackBatch(batch, digest, delivery)"))
        assertTrue(rawPath.contains("store.normalizePendingCallbackPage(vehicleStateNormalizer)"))
        assertTrue(rawPath.contains("val backlog = helper.callbackSpoolStatus(stream)"))
        assertTrue(rawPath.contains("loss_first_wall_ms="))
        assertTrue(rawPath.contains("loss_reason="))
        assertFalse(rawPath.contains("tripRuntime.onSuccessfulPoll"))
        assertFalse(rawPath.contains("energy.process"))
        assertFalse(rawPath.contains("coordinator.onSuccessfulPoll"))
        val observer = service.substringAfter("override fun onSourcePoll(").substringBefore("override fun onSourceFailure(")
        assertTrue(observer.contains("vehicleStateNormalizer.normalize("))
        assertTrue(observer.contains("store.applySourcePollNormalization(pollId, timestamp, source, readings, vehicleStateNormalizer)"))
        assertTrue(observer.contains("source, timestamp, readings, observations"))
        assertTrue(observer.contains("coordinator.onSuccessfulPoll(observations, energySnapshot)"))
    }

    @Test
    fun appMainUsesAuthoritativeLiveWriterWithSpoolReplayBeforeEveryRead() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val runner = sourceFile("com/bydcollector/collector/data/polling/TelemetryWorkerReplayCoordinator.kt").readText()
        val create = service.substringAfter("private fun createTelemetryPoller").substringBefore("private fun createSuccessfulPollObserver")

        assertTrue(create.contains("ownerMode: DirectHelperOwnerMode = DirectHelperOwnerMode.APP_GAP_SPOOL"))
        assertTrue(create.contains("val live = PollPersistenceCoordinator("))
        assertTrue(create.contains("expectedOwnerMode = ownerMode"))
        assertTrue(create.contains("liveClient.ensureHelperReady(ownerMode)"))
        assertTrue(create.contains("TelemetryWorkerReplayPollCycleRunner("))
        assertTrue(runner.contains("val result = replay.replayNextBatch(sessionId)"))
        assertTrue(runner.contains("result.cycleResult?.let { return it }"))
        assertTrue(runner.contains("return live?.pollOnce(sessionId)"))
    }

    @Test
    fun everyHelperRepairPathUsesTheStandardOwnerMode() {
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val autoStart = sourceFile("com/bydcollector/collector/system/CollectorAutoStart.kt").readText()
        val access = sourceFile("com/bydcollector/collector/adb/AdbAuthorizationManager.kt").readText()

        assertFalse(settings.substringAfter("fun mainHelperOwnerMode").substringBefore("fun isDebugPollingEnabled").contains("isAutonomousMainWorkerEnabled()"))
        assertTrue(settings.contains("DirectHelperOwnerMode.APP_GAP_SPOOL"))
        val ownerMode = settings.substringAfter("fun mainHelperOwnerMode").substringBefore("fun isDebugPollingEnabled")
        assertTrue(ownerMode.contains("isPollingEnabled()"))
        assertTrue(ownerMode.contains("!isMainManuallyStopped()"))
        assertTrue(ownerMode.contains("DirectHelperOwnerMode.APP"))
        assertTrue(service.contains("ownerMode: DirectHelperOwnerMode = DirectHelperOwnerMode.APP_GAP_SPOOL"))
        assertTrue(service.contains("ownerMode = settings.mainHelperOwnerMode()"))
        assertTrue(activity.contains("helperOwnerMode = settings.mainHelperOwnerMode()"))
        assertTrue(autoStart.contains("helperOwnerMode = settings.mainHelperOwnerMode()"))
        assertTrue(access.contains("DirectVehicleHelperClient().ownerMode() == helperOwnerMode"))
        assertTrue(access.contains("ownerMode = helperOwnerMode"))
    }

    @Test
    fun ordinaryServiceTeardownReleasesOnlyTheAppLockAndPreservesAutonomousHelper() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val destroy = service.substringAfter("override fun onDestroy()").substringBefore("override fun onTaskRemoved")
        val stopCollection = service.substringAfter("private fun stopCollection").substringBefore("private fun shutdownByUser")
        val stopWorker = service.substringAfter("private fun stopAppGapSpoolHelper").substringBefore("private fun exportInfluxAfterNormalizedWrite")
        val shutdown = service.substringAfter("private fun stopRuntimeForUserShutdown").substringBefore("private fun finishUserShutdown")

        assertTrue(destroy.contains("stopCollection(\"service_destroyed\")"))
        assertTrue(destroy.contains("DirectStreamController.releaseApp()"))
        assertFalse(destroy.contains("setPollingEnabled(false)"))
        assertTrue(stopCollection.contains("stopMain(reason)"))
        assertTrue(stopCollection.contains("releaseWakeLock()"))
        val preservedTeardown = stopWorker.indexOf("if (reason == \"service_destroyed\") return")
        assertTrue(preservedTeardown >= 0)
        assertTrue(preservedTeardown < stopWorker.indexOf("DirectVehicleHelperClient()"))
        assertTrue(shutdown.contains("settings.setPollingEnabled(false)"))
        assertTrue(shutdown.contains("stopMain(\"user_shutdown\")"))
    }

    @Test
    fun intentionalStopsDisableOnlyTheirStreamAndShutdownStopsHelperProcess() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val stopMain = service.substringAfter("private fun stopMain").substringBefore("private fun exportInfluxAfterNormalizedWrite")
        val stopWorker = stopMain.substringAfter("private fun stopAppGapSpoolHelper")

        assertTrue(stopMain.contains("setDesired(CollectorHelperProtocol.STREAM_MAIN, false)"))
        assertTrue(stopMain.contains("if (reason == \"user_shutdown\") stopAppGapSpoolHelper(reason)"))
        assertTrue(stopWorker.contains("if (reason == \"service_destroyed\") return"))
        assertTrue(stopWorker.contains("helper.requestStop(DirectHelperOwnerMode.APP_GAP_SPOOL)"))
        assertFalse(service.contains("debugOwnerHandoffPending"))
        assertFalse(service.contains("helper_owner_handoff"))
        assertFalse(service.contains("stopAppGapSpoolHelper(\"database_maintenance\")"))
    }

    private fun sourceFile(path: String): File {
        return listOf(File("src/main/kotlin/$path"), File("app/src/main/kotlin/$path"))
            .firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
