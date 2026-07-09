package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TailscaleActivationThrottleContractTest {
    @Test
    fun settingsPersistTailscaleActivationAttemptState() {
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(settings.contains("fun tailscaleActivationLastAttemptAtMs(): Long"))
        assertTrue(settings.contains("fun setTailscaleActivationLastAttemptAtMs(value: Long)"))
        assertTrue(settings.contains("fun clearTailscaleActivationAttempt()"))
        assertTrue(settings.contains("KEY_TAILSCALE_ACTIVATION_LAST_ATTEMPT_AT_MS"))
    }

    @Test
    fun serviceThrottlesTailscaleActivation() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val gate = sourceFile("com/bydcollector/collector/ha/TailscaleActivationGate.kt").readText()

        assertFalse(service.contains("activateTailscaleIfEnabled(\"mqtt\")"))
        assertFalse(service.contains("activateTailscaleIfEnabled(\"influx\")"))
        assertTrue(service.contains("maybeActivateTailscaleAfterHaFailure(\"mqtt\")"))
        assertTrue(service.contains("maybeActivateTailscaleAfterHaFailure(\"influx\")"))
        assertTrue(service.contains("onFailedAction?.invoke()"))
        assertTrue(service.contains("TailscaleActivator.activate(applicationContext)"))
        assertTrue(gate.contains("DEFAULT_THROTTLE_MS"))
        assertTrue(service.contains("settings.tailscaleActivationLastAttemptAtMs()"))
        assertTrue(gate.contains("tailscale_activation_throttled"))
        assertTrue(gate.contains("tailscale_activation_skipped_ha_reachable"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
