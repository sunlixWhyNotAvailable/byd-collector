package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorSettingsEventDispatchContractTest {
    @Test
    fun storeBackedSettingsAlwaysDispatchEventsOffTheCallerThread() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(source.contains("private val eventExecutor: Executor = sharedOperationalEventExecutor"))
        assertTrue(source.contains("dispatchOperationalEvent(eventExecutor)"))
        assertTrue(source.contains("eventStore.recordEvent(category, message, detail)"))
        assertFalse(source.contains("Executor? = null"))
        assertFalse(source.contains("store?.recordEvent("))
    }

    @Test
    fun unchangedRuntimeFlagsDoNotRewritePreferencesOrDuplicateEvents() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(source.contains("if (isDebugPollingEnabled() == enabled) return"))
        assertTrue(source.contains("if (isMqttEnabled() == enabled) return"))
        assertTrue(source.contains("if (isInfluxEnabled() == enabled) return"))
    }

    @Test
    fun autoStartEventsAcceptUiSourceDetail() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(source.contains("fun setAutoStartEnabled(enabled: Boolean, detail: String? = null)"))
        assertTrue(source.contains("fun setDebugAutoStartEnabled(enabled: Boolean, detail: String? = null)"))
    }

    private fun sourceFile(path: String): File = listOf(
        File("src/main/kotlin/$path"),
        File("app/src/main/kotlin/$path")
    ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
}
