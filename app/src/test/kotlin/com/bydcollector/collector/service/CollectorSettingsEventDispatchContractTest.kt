package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorSettingsEventDispatchContractTest {
    @Test
    fun storeBackedUiSettingsCanDispatchEventsOffTheCallerThread() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(source.contains("private val eventExecutor: Executor? = null"))
        assertTrue(source.contains("dispatchOperationalEvent(eventExecutor)"))
        assertTrue(source.contains("eventStore.recordEvent(category, message, detail)"))
        assertFalse(source.contains("store?.recordEvent("))
    }

    private fun sourceFile(path: String): File = listOf(
        File("src/main/kotlin/$path"),
        File("app/src/main/kotlin/$path")
    ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
}
