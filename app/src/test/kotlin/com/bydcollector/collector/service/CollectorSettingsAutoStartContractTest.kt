package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CollectorSettingsAutoStartContractTest {
    @Test
    fun runtimeSwitchesDefaultOff() {
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(settings.contains("fun isAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_START, false)"))
        assertTrue(settings.contains("prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_USER_SHUTDOWN, false)"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_POLLING_ENABLED, false)"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_DEBUG_POLLING_ENABLED, false)"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_DEBUG_AUTO_START, false)"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_MQTT_ENABLED, false)"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_MQTT_AUTO_START, false)"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_INFLUX_ENABLED, false)"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_INFLUX_AUTO_START, false)"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_KEEP_WIFI, false)"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_KEEP_MOBILE_DATA, false)"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_KEEP_BLUETOOTH, false)"))
        assertTrue(settings.contains("prefs.getBoolean(KEY_RECOVER_COLLECTOR_SERVICE, false)"))
    }

    private fun sourceFile(path: String): File {
        return listOf(File("src/main/kotlin/$path"), File("app/src/main/kotlin/$path"))
            .firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
