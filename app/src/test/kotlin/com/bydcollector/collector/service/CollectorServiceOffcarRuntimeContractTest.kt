package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorServiceOffcarRuntimeContractTest {
    @Test
    fun persistedPollHeartbeatsBeforeNormalizationAndLateCallbacksCannotRearm() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val observer = service.substringAfter("override fun onSuccessfulPoll(")
            .substringBefore("private fun reconcileCollection")
        val heartbeat = service.substringAfter("private fun heartbeatAfterPersistedMainPoll")
            .substringBefore("private fun deactivateMainObserver")
        val deactivate = service.substringAfter("private fun deactivateMainObserver")
            .substringBefore("private fun telegramReachabilityMainPollState")
        val deactivateState = deactivate.substringAfter("private fun deactivateMainObserverState")

        assertInOrder(observer, "heartbeatAfterPersistedMainPoll(sessionId)", "vehicleStateNormalizer.normalize", "store.applyNormalizedObservations")
        assertTrue(heartbeat.contains("synchronized(mainObserverLock)"))
        assertInOrder(heartbeat, "activeMainSessionId != observedSessionId", "lastSuccessfulMainPollElapsedMs = nowElapsedMs", "offcarHelper.mainHeartbeat()")
        assertTrue(heartbeat.contains("MAIN_HEARTBEAT_INTERVAL_MS"))
        assertTrue(heartbeat.contains("runCatching { offcarHelper.mainHeartbeat() }"))
        assertInOrder(deactivate, "resetAndRunAtomically", "deactivateMainObserverState(disarmOffcar = true)")
        assertTrue(deactivateState.contains("synchronized(mainObserverLock)"))
        assertInOrder(deactivateState, "activeMainSessionId = null", "offcarHelper.offcarDisarm()")
    }

    @Test
    fun onlyIntentionalMainStopsDisarmAndDebugArchiveDoesNot() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val probe = sourceFile("com/bydcollector/collector/telegram/TelegramReachabilityProbe.kt").readText()
        val explicitStop = service.substringAfter("ACTION_STOP -> {").substringBefore("ACTION_START_DEBUG")
        val shutdown = service.substringAfter("private fun stopRuntimeForUserShutdown").substringBefore("private fun stopServiceAfterUserShutdown")
        val deferredShutdown = service.substringAfter("private fun deferStopForActiveMaintenance")
            .substringBefore("private fun stopRuntimeForUserShutdown")
        val maintenance = service.substringAfter("private fun stopRuntimeForMaintenance").substringBefore("private fun restoreRuntimeAfterMaintenance")
        val debugMaintenance = maintenance.substringAfter("if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE)")
            .substringBefore("deactivateMainObserver")
        val destroy = service.substringAfter("override fun onDestroy()").substringBefore("override fun onTaskRemoved")
        val stopCollection = service.substringAfter("private fun stopCollection").substringBefore("private fun shutdownByUser")
        val deactivate = service.substringAfter("private fun deactivateMainObserver")
            .substringBefore("private fun telegramReachabilityMainPollState")
        val atomicReset = probe.substringAfter("fun resetAndRunAtomically")
            .substringBefore("private fun resetLocked")

        assertTrue(explicitStop.contains("stopMain(\"polling_disabled\", disarmOffcar = true)"))
        assertTrue(shutdown.contains("stopMain(\"user_shutdown\", disarmOffcar = true)"))
        assertTrue(deferredShutdown.contains("deactivateMainObserver(disarmOffcar = true)"))
        assertTrue(maintenance.contains("deactivateMainObserver(disarmOffcar = true)"))
        assertFalse(deferredShutdown.contains("telegramReachabilityProbe.reset()"))
        assertFalse(shutdown.contains("telegramReachabilityProbe.reset()"))
        assertFalse(maintenance.contains("telegramReachabilityProbe.reset()"))
        assertInOrder(deactivate, "resetAndRunAtomically", "deactivateMainObserverState(disarmOffcar = true)")
        assertInOrder(atomicReset, "synchronized(this)", "resetLocked()", "action()")
        assertFalse(debugMaintenance.contains("offcarDisarm"))
        assertTrue(destroy.contains("stopCollection(\"service_destroyed\")"))
        assertFalse(destroy.contains("offcarDisarm"))
        assertTrue(stopCollection.contains("stopMain(reason, disarmOffcar = false)"))
    }

    @Test
    fun telegramAndInfluxUseExistingServiceTimersAndLifecycleGates() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val normalizedWrite = service.substringAfter("private fun exportInfluxAfterNormalizedWrite")
            .substringBefore("private fun requestInfluxCycle")
        val influxPath = service.substringAfter("private fun requestInfluxCycle")
            .substringBefore("private fun stopDebug")
        val mainMaintenance = service.substringAfter("private fun stopRuntimeForMaintenance")
            .substringBefore("private fun restoreRuntimeAfterMaintenance")
        val debugMaintenance = mainMaintenance.substringAfter("if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE)")
            .substringBefore("deactivateMainObserver")
        val noRuntime = service.substringAfter("private fun stopIfNoActiveRuntime")
            .substringBefore("private fun flushPendingMqttAsync")
        val stopInflux = service.substringAfter("private fun stopInfluxExport")
            .substringBefore("private fun reconcileTelegramRuntime")
        val telegramDisabled = service.substringAfter("if (!settings.isTelegramEnabled())")
            .substringBefore("ensureForegroundForChannel(\"Telegram notifications enabled\")")
        val shutdown = service.substringAfter("private fun stopRuntimeForUserShutdown")
            .substringBefore("private fun stopServiceAfterUserShutdown")

        assertTrue(service.contains("TELEGRAM_TICK_INTERVAL_MS = 15_000L"))
        assertTrue(service.contains("reachabilityMainPollState = ::telegramReachabilityMainPollState"))
        assertTrue(service.contains("offcar_poc/telegram_reachability.jsonl"))
        assertTrue(service.contains("requestObserver = telegramReachabilityProbe::onRequest"))
        assertTrue(service.contains("telegram_reachability_evidence_append_failed"))
        assertTrue(normalizedWrite.contains("requestInfluxCycle()"))
        assertTrue(service.contains("private val influxRetryTask"))
        assertTrue(influxPath.contains("influxRequestQueued.compareAndSet(false, true)"))
        assertTrue(influxPath.contains("influxCoordinator.runOneCycle(force = false)"))
        assertTrue(service.contains("influxCoordinator.resumeExport()"))
        assertTrue(mainMaintenance.contains("resetInfluxExecutorForMaintenance()"))
        assertTrue(mainMaintenance.contains("deactivateMainObserver(disarmOffcar = true)"))
        assertFalse(debugMaintenance.contains("resetInfluxExecutorForMaintenance"))
        assertFalse(debugMaintenance.contains("telegramReachabilityProbe.reset()"))
        assertTrue(stopInflux.contains("cancelInfluxRetry()"))
        assertTrue(stopInflux.contains("queueInfluxStop(stopServiceWhenIdle = true)"))
        assertFalse(stopInflux.contains("resetInfluxExecutorForMaintenance()"))
        assertTrue(telegramDisabled.contains("telegramReachabilityProbe.reset()"))
        assertTrue(shutdown.contains("stopMain(\"user_shutdown\", disarmOffcar = true)"))
        assertFalse(noRuntime.contains("settings.isInfluxEnabled()"))
        assertFalse(service.contains("AlarmManager"))
        assertFalse(service.contains("JobScheduler"))
    }

    @Test
    fun releaseAndDebugShardMetadataMatchV260() {
        val build = File("build.gradle.kts").takeIf { it.isFile } ?: File("app/build.gradle.kts")
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()

        assertTrue(build.readText().contains("versionCode = 260"))
        assertTrue(build.readText().contains("versionName = \"2.6.0\""))
        assertTrue(service.contains("val batchSize = DirectDebugParameterAsset.MAX_SHARD_SIZE"))
    }

    private fun sourceFile(path: String): File {
        return listOf(File("src/main/kotlin/$path"), File("app/src/main/kotlin/$path"))
            .firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun assertInOrder(source: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val current = source.indexOf(token, previous + 1)
            assertTrue(current > previous, "Missing or out-of-order token: $token")
            previous = current
        }
    }
}
