package com.bydcollector.collector.system

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserShutdownContractTest {
    @Test
    fun settingsExposePersistedUserShutdownFlag() {
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(settings.contains("fun isUserShutdownRequested(): Boolean"))
        assertTrue(settings.contains("fun setUserShutdownRequested(enabled: Boolean)"))
        assertTrue(settings.contains("fun clearUserShutdownRequestIfSet(): Boolean"))
        assertTrue(settings.contains("const val KEY_USER_SHUTDOWN = \"userShutdown\""))
        assertTrue(settings.contains("putBoolean(KEY_USER_SHUTDOWN, enabled).commit()"))
    }

    @Test
    fun autoStartIsBlockedByUserShutdownUntilMainActivityClearsIt() {
        val autoStart = sourceFile("com/bydcollector/collector/system/CollectorAutoStart.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val updateRuntime = sourceFile("com/bydcollector/collector/update/UpdateRuntime.kt").readText()

        assertTrue(autoStart.contains("if (settings.isUserShutdownRequested()) {"))
        assertTrue(activity.contains("val clearedUserShutdown = settings.clearUserShutdownRequestIfSet()"))
        assertTrue(activity.contains("if (clearedUserShutdown)"))
        assertTrue(activity.contains("settings.clearRuntimeManualStops()"))
        assertTrue(activity.contains("CollectorAutoStart.recoverFromForeground(applicationContext, settings, runtimeStore)"))
        assertInOrder(activity, "settings.clearUserShutdownRequestIfSet()", "updateRuntime.start(\"activity\")")
        val updateStart = updateRuntime.substringAfter("fun start(source: String)").substringBefore("fun onSystemWake(")
        val updateWake = updateRuntime.substringAfter("fun onSystemWake(").substringBefore("private fun observeWake()")
        assertInOrder(updateStart, "if (settings.isUserShutdownRequested()) return", "val firstEntry = !started")
        assertInOrder(updateWake, "if (settings.isUserShutdownRequested()) return", "if (!started)")
    }

    @Test
    fun shutdownActionStopsRuntimeAndCancelsScheduledRecovery() {
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val controller = sourceFile("com/bydcollector/collector/service/CollectorServiceController.kt").readText()
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val autoStart = sourceFile("com/bydcollector/collector/system/CollectorAutoStart.kt").readText()

        assertTrue(actions.contains("fun onShutdownApp()"))
        assertTrue(activity.contains("override fun onShutdownApp()"))
        assertTrue(activity.contains("settings.setUserShutdownRequested(true)"))
        assertTrue(activity.contains("CollectorServiceController.shutdown(this@MainActivity)"))
        assertTrue(activity.contains("finishAndRemoveTask()"))
        assertTrue(controller.contains("fun shutdown(context: Context)"))
        assertTrue(service.contains("ACTION_SHUTDOWN"))
        assertTrue(service.contains("private fun shutdownByUser()"))
        assertTrue(service.contains("settings.setUserShutdownRequested(true)"))
        assertTrue(service.contains("settings.setPollingEnabled(false)"))
        assertTrue(service.contains("settings.setDebugPollingEnabled(false)"))
        assertTrue(service.contains("settings.setMqttEnabled(false)"))
        assertTrue(service.contains("settings.setInfluxEnabled(false)"))
        assertTrue(service.contains("KeepAliveConfig(false, false, false, false)"))
        assertTrue(autoStart.contains("fun cancelScheduled(context: Context)"))
        assertFalse(service.contains("settings.setAutoStartEnabled(false)"))
        assertFalse(service.contains("settings.setDebugAutoStartEnabled(false)"))
        assertFalse(service.contains("settings.setMqttAutoStartEnabled(false)"))
        assertFalse(service.contains("settings.setInfluxAutoStartEnabled(false)"))
        assertTrue(
            autoStart.contains(
                "if (!CollectorService.isRunning()) settings.setDebugPollingEnabled(demand.debug)"
            )
        )
    }

    @Test
    fun serviceSuppressesDirectStartsWhileUserShutdownIsSet() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()

        assertTrue(service.contains("if (action == ACTION_SHUTDOWN)"))
        assertTrue(service.contains("if (settings.isUserShutdownRequested())"))
        assertTrue(service.contains("return START_NOT_STICKY"))
        assertInOrder(service, "if (settings.isUserShutdownRequested())", "if (maintenanceActive.get()")
        assertInOrder(service, "if (action == ACTION_SHUTDOWN)", "if (settings.isUserShutdownRequested())")
    }

    @Test
    fun shutdownIsNotRestoredAfterDatabaseMaintenance() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val shutdownBlock = service
            .split("private fun shutdownByUser", limit = 2)[1]
            .split("private fun suppressStartAfterUserShutdown", limit = 2)[0]
        val suppressBlock = service
            .split("private fun suppressStartAfterUserShutdown", limit = 2)[1]
            .split("private fun stopRuntimeForUserShutdown", limit = 2)[0]
        val restoreBlock = service
            .split("private fun restoreRuntimeAfterMaintenance", limit = 2)[1]
            .split("private fun rebuildStoreBackedRuntime", limit = 2)[0]

        assertTrue(shutdownBlock.contains("if (deferStopForActiveMaintenance"))
        assertInOrder(shutdownBlock, "settings.setUserShutdownRequested(true)", "if (deferStopForActiveMaintenance")
        assertInOrder(shutdownBlock, "if (deferStopForActiveMaintenance", "stopRuntimeForUserShutdown()")
        assertTrue(suppressBlock.contains("if (deferStopForActiveMaintenance"))
        assertInOrder(suppressBlock, "if (deferStopForActiveMaintenance", "stopRuntimeForUserShutdown()")
        assertTrue(restoreBlock.contains("if (settings.isUserShutdownRequested())"))
        assertInOrder(restoreBlock, "stopRuntimeForUserShutdown()", "finishUserShutdown()")
        assertInOrder(restoreBlock, "if (settings.isUserShutdownRequested())", "settings.setPollingEnabled(snapshot.mainEnabled)")
    }

    @Test
    fun shutdownWaitsForMqttInfluxAndTelegramBeforeStopSelf() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val shutdown = service.substringAfter("private fun finishUserShutdown")
            .substringBefore("private fun stopServiceAfterUserShutdown")

        assertInOrder(shutdown, "awaitSerializedExecutorAction(", "influxCoordinator.stopExport()")
        assertInOrder(shutdown, "influxCoordinator.stopExport()", "mainHandler.post { stopServiceAfterUserShutdown() }")
        assertInOrder(shutdown, "quiesceTelegramForUserShutdown()", "mainHandler.post { stopServiceAfterUserShutdown() }")
        assertInOrder(shutdown, "shutdownMqttExecutor()", "awaitMqttWorkerTermination(")
        assertTrue(shutdown.contains("if (!mqttStopped || !influxStopped || !telegramStopped)"))
        assertTrue(shutdown.contains("if (!mqttStopped && influxStopped && telegramStopped)"))
        assertTrue(shutdown.contains("if (settings.isUserShutdownRequested()) finishUserShutdown()"))
        assertTrue(shutdown.contains("user_shutdown_worker_stop_timeout"))
    }

    @Test
    fun shutdownStopsServiceOnlyAfterKeepAliveShutdownIsConfirmed() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val ordinaryStop = service.substringAfter("private fun stopAfterKeepAliveReconcile")
            .substringBefore("private fun startMainIfNeeded")
        val userStop = service.substringAfter("private fun stopServiceAfterUserShutdown")
            .substringBefore("private fun stopMain")

        assertTrue(ordinaryStop.contains("{ reconciled ->"))
        assertInOrder(ordinaryStop, "if (reconciled)", "stopIfNoActiveRuntime()")
        assertTrue(ordinaryStop.contains("keep_alive_stop_deferred"))
        assertTrue(userStop.contains("{ reconciled ->"))
        assertInOrder(userStop, "if (!settings.isUserShutdownRequested())", "if (!reconciled)")
        assertInOrder(userStop, "if (!reconciled)", "stopSelf()")
        assertTrue(userStop.contains("userShutdownFinalizationStarted.set(false)"))
        assertTrue(userStop.contains("user_shutdown_keep_alive_failed"))
        assertTrue(userStop.contains("finishKeepAliveStopAfterFailure(retryAttempt = 0"))
        assertTrue(userStop.contains("CollectorAutoStart.cancelKeepAliveStopRetry(applicationContext)"))
        assertInOrder(userStop, "if (!settings.isUserShutdownRequested())", "CollectorAutoStart.cancelKeepAliveStopRetry(applicationContext)")

        val idleStop = service.substringAfter("private fun stopIfNoActiveRuntime")
            .substringBefore("private fun postMqttRetrySchedule")
        assertInOrder(idleStop, "settings.isUserShutdownRequested()", "stopSelf()")
        assertInOrder(idleStop, "userShutdownFinalizationStarted.get()", "stopSelf()")
    }

    @Test
    fun serializedStopRunsAfterCurrentWorkAndDoesNotDeadlockOnItsOwnWorker() {
        val releaseCurrent = CountDownLatch(1)
        val currentStarted = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "byd-influx") }
        try {
            executor.execute {
                currentStarted.countDown()
                releaseCurrent.await()
            }
            assertTrue(currentStarted.await(1, TimeUnit.SECONDS))
            var result = false
            val waiter = thread {
                result = com.bydcollector.collector.service.awaitSerializedExecutorAction(
                    executor,
                    "byd-influx",
                    2_000L
                ) { stopped.set(true) }
            }
            assertFalse(stopped.get())
            releaseCurrent.countDown()
            waiter.join(2_000L)
            assertFalse(waiter.isAlive)
            assertTrue(result)
            assertTrue(stopped.get())

            stopped.set(false)
            val sameWorker = executor.submit<Boolean> {
                com.bydcollector.collector.service.awaitSerializedExecutorAction(
                    executor,
                    "byd-influx",
                    100L
                ) { stopped.set(true) }
            }
            assertTrue(sameWorker.get(1, TimeUnit.SECONDS))
            assertTrue(stopped.get())
        } finally {
            releaseCurrent.countDown()
            executor.shutdownNow()
        }
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing first token: $first")
        assertTrue(secondIndex >= 0, "Missing second token: $second")
        assertTrue(firstIndex < secondIndex, "Expected `$first` before `$second`")
    }
}
