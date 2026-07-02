package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
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

        assertTrue(service.contains("TAILSCALE_ACTIVATION_THROTTLE_MS"))
        assertTrue(service.contains("settings.tailscaleActivationLastAttemptAtMs()"))
        assertTrue(service.contains("tailscale_activation_throttled"))
        assertTrue(service.contains("settings.setTailscaleActivationLastAttemptAtMs(now)"))
        assertTrue(service.contains("TailscaleActivator.activate(applicationContext)"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
