package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorSettingsHaDiscoveryContractTest {
    @Test
    fun homeAssistantDiscoveryIsAlwaysOnWithoutAStoredToggle() {
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(settings.contains("discoveryEnabled = true"))
        assertFalse(settings.contains("isHaDiscoveryEnabled"))
        assertFalse(settings.contains("setHaDiscoveryEnabled"))
        assertFalse(settings.contains("KEY_HA_DISCOVERY_ENABLED"))
        assertFalse(settings.contains("haDiscoveryEnabled"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
