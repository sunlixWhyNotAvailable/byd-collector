package com.bydcollector.collector.mqtt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

class HaMqttFieldFilterContractTest {
    @Test
    fun driverAssistBypassIsNotRetainedWithoutFields() {
        val filter = sourceFile("com/bydcollector/collector/mqtt/HaMqttFieldFilter.kt").readText()
        val config = sourceFile("com/bydcollector/collector/mqtt/HaMqttConfig.kt").readText()
        val models = sourceFile("com/bydcollector/collector/data/normalized/NormalizedModels.kt").readText()
        val categoryConst = "DRIVER_ASSIST" + "_CATEGORY"
        val enumToken = "DRIVER_ASSIST" + "(\"driver_assist\""

        assertFalse(filter.contains(categoryConst))
        assertFalse(config.contains(categoryConst))
        assertFalse(models.contains(enumToken))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
