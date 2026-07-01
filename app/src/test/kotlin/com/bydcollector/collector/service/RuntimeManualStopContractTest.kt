package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
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

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
