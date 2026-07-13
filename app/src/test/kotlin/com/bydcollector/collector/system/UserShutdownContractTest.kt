package com.bydcollector.collector.system

import java.io.File
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

        assertTrue(autoStart.contains("if (settings.isUserShutdownRequested()) return false"))
        assertTrue(activity.contains("val clearedUserShutdown = settings.clearUserShutdownRequestIfSet()"))
        assertTrue(activity.contains("if (clearedUserShutdown)"))
        assertTrue(activity.contains("settings.clearRuntimeManualStops()"))
        assertTrue(activity.contains("CollectorAutoStart.recoverFromForeground(applicationContext, settings, currentStore())"))
        assertTrue(activity.indexOf("settings.clearUserShutdownRequestIfSet()") < activity.indexOf("startRuntimeUpdateAutoCheck()"))
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
        assertTrue(autoStart.contains("syncDebugAutoStart(settings)"))
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
        assertInOrder(restoreBlock, "if (settings.isUserShutdownRequested())", "settings.setPollingEnabled(snapshot.mainEnabled)")
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
