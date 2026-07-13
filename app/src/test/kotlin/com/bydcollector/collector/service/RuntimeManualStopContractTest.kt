package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeManualStopContractTest {
    @Test
    fun settingsExposeManualStopHoldsPerRuntimeChannel() {
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(settings.contains("fun isMainManuallyStopped(): Boolean"))
        assertTrue(settings.contains("fun setMainManuallyStopped(stopped: Boolean)"))
        assertTrue(settings.contains("fun isDebugManuallyStopped(): Boolean"))
        assertTrue(settings.contains("fun setDebugManuallyStopped(stopped: Boolean)"))
        assertTrue(settings.contains("fun isMqttManuallyStopped(): Boolean"))
        assertTrue(settings.contains("fun setMqttManuallyStopped(stopped: Boolean)"))
        assertTrue(settings.contains("fun isInfluxManuallyStopped(): Boolean"))
        assertTrue(settings.contains("fun setInfluxManuallyStopped(stopped: Boolean)"))
        assertTrue(settings.contains("fun clearRuntimeManualStops()"))
    }

    @Test
    fun serviceDoesNotAutoStartChannelsHeldByManualStop() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()

        assertTrue(service.contains("val mainAllowed = mainEnabled && !settings.isMainManuallyStopped()"))
        assertTrue(service.contains("val debugAllowed = debugEnabled && !settings.isDebugManuallyStopped()"))
        assertTrue(service.contains("if (settings.isMqttAutoStartEnabled() && !settings.isMqttManuallyStopped()) startMqttExport(clearManualStop = false)"))
        assertTrue(service.contains("if (settings.isInfluxAutoStartEnabled() && !settings.isInfluxManuallyStopped()) startInfluxExport(clearManualStop = false)"))
        assertTrue(service.contains("settings.setMqttManuallyStopped(true)"))
        assertTrue(service.contains("settings.setInfluxManuallyStopped(true)"))
    }

    @Test
    fun explicitStartsAndExternalAutoStartEventsClearManualStops() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val autoStart = sourceFile("com/bydcollector/collector/system/CollectorAutoStart.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        assertTrue(service.contains("startMqttExport(clearManualStop = true)"))
        assertTrue(service.contains("startInfluxExport(clearManualStop = true)"))
        assertTrue(autoStart.contains("settings.clearRuntimeManualStops()"))
        assertTrue(activity.contains("settings.clearRuntimeManualStops()"))
    }

    @Test
    fun stopActionsDoNotChangePersistedAutoStartPreferences() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val stopDebug = service.substringAfter("ACTION_STOP_DEBUG -> {").substringBefore("ACTION_RECONCILE_KEEP_ALIVE")
        val stopMain = service.substringAfter("ACTION_STOP -> {").substringBefore("ACTION_START_DEBUG")
        val stopMqtt = service.substringAfter("private fun stopMqttExport").substringBefore("private fun startInfluxExport")
        val stopInflux = service.substringAfter("private fun stopInfluxExport").substringBefore("private fun stopIfNoActiveRuntime")

        assertFalse(stopMain.contains("setAutoStartEnabled"))
        assertFalse(stopDebug.contains("setDebugAutoStartEnabled"))
        assertFalse(stopMqtt.contains("setMqttAutoStartEnabled"))
        assertFalse(stopInflux.contains("setInfluxAutoStartEnabled"))

        val activityStops = listOf(
            activity.substringAfter("override fun onStopMain()").substringBefore("override fun onToggleMainAutoStart"),
            activity.substringAfter("override fun onStopDebug()").substringBefore("override fun onToggleDebugAutoStart"),
            activity.substringAfter("override fun onStopMqtt()").substringBefore("override fun onTestMqtt"),
            activity.substringAfter("override fun onStopInflux()").substringBefore("override fun onTestInflux")
        ).joinToString("\n")
        assertFalse(activityStops.contains("setAutoStartEnabled"))
        assertFalse(activityStops.contains("setDebugAutoStartEnabled"))
        assertFalse(activityStops.contains("setMqttAutoStartEnabled"))
        assertFalse(activityStops.contains("setInfluxAutoStartEnabled"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
