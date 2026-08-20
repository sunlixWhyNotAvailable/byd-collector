package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorServiceWorkerHandoffContractTest {
    @Test
    fun autonomousMainUsesSpoolImportWithoutTheAppLiveReader() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val runner = sourceFile("com/bydcollector/collector/data/polling/TelemetryWorkerReplayCoordinator.kt").readText()
        val create = service.substringAfter("private fun createTelemetryPoller").substringBefore("private fun createSuccessfulPollObserver")

        assertTrue(create.contains("ownerMode: DirectHelperOwnerMode = DirectHelperOwnerMode.AUTONOMOUS_WORKER"))
        assertTrue(create.contains("val live = if (ownerMode == DirectHelperOwnerMode.APP)"))
        assertTrue(create.contains("liveClient.ensureHelperReady(ownerMode)"))
        assertTrue(create.contains("TelemetryWorkerReplayPollCycleRunner("))
        assertTrue(runner.contains("if (replayPending || live == null)"))
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
        assertTrue(settings.contains("DirectHelperOwnerMode.AUTONOMOUS_WORKER"))
        assertTrue(service.contains("ownerMode: DirectHelperOwnerMode = DirectHelperOwnerMode.AUTONOMOUS_WORKER"))
        assertTrue(service.contains("ownerMode = settings.mainHelperOwnerMode()"))
        assertTrue(activity.contains("helperOwnerMode = settings.mainHelperOwnerMode()"))
        assertTrue(autoStart.contains("helperOwnerMode = settings.mainHelperOwnerMode()"))
        assertTrue(access.contains("DirectVehicleHelperClient().ownerMode() == helperOwnerMode"))
        assertTrue(access.contains("ownerMode = helperOwnerMode"))
    }

    @Test
    fun intentionalMainStopStopsOnlyTheAutonomousOwner() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val stopMain = service.substringAfter("private fun stopMain").substringBefore("private fun exportInfluxAfterNormalizedWrite")
        val stopWorker = stopMain.substringAfter("private fun stopAutonomousMainWorker")

        assertTrue(stopMain.indexOf("poller.stop()") < stopMain.indexOf("stopAutonomousMainWorker(reason)"))
        assertTrue(stopWorker.contains("if (reason == \"service_destroyed\") return"))
        assertTrue(stopWorker.contains("helper.ownerMode() != DirectHelperOwnerMode.AUTONOMOUS_WORKER"))
        assertTrue(stopWorker.indexOf("stopDebug(\"helper_owner_handoff\")") < stopWorker.indexOf("helper.requestStop"))
        assertTrue(stopWorker.contains("helper.requestStop(DirectHelperOwnerMode.AUTONOMOUS_WORKER)"))
        assertTrue(service.contains("debugOwnerHandoffPending.getAndSet(false)"))
        assertTrue(service.contains("DirectVehicleHelperClient().ownerMode() != settings.mainHelperOwnerMode()"))
    }

    private fun sourceFile(path: String): File {
        return listOf(File("src/main/kotlin/$path"), File("app/src/main/kotlin/$path"))
            .firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
