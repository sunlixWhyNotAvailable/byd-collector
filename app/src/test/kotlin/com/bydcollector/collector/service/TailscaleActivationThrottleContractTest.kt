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
        assertTrue(service.contains("launch = { TailscaleActivator.launchIfNeeded(applicationContext) }"))
        assertTrue(service.contains("processCheck = { TailscaleActivator.checkProcess(applicationContext) }"))
        assertTrue(service.contains("TailscaleActivator.restoreForeground(applicationContext, target)"))
        assertFalse(service.contains("TailscaleActivator.activate(applicationContext)"))
        assertFalse(service.contains("TailscaleActivator.captureForeground(applicationContext)"))
        assertTrue(gate.contains("DEFAULT_THROTTLE_MS"))
        assertTrue(service.contains("settings.tailscaleActivationLastAttemptAtMs()"))
        assertTrue(gate.contains("tailscale_activation_throttled"))
        assertTrue(gate.contains("tailscale_activation_skipped_ha_reachable"))
        assertTrue(gate.contains("tailscale_activation_skipped_running"))
        assertTrue(gate.contains("tailscale_activation_process_check_failed"))
        assertTrue(gate.indexOf("val process = processCheck()") < gate.indexOf("setLastAttemptAtMs(now)"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
