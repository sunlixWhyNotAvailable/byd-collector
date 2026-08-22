package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorServiceLocationGateContractTest {
    @Test
    fun gpsCaptureUsesCategoryMembershipAndExistingReasons() {
        val source = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val gate = source.substringAfter("locationCaptureEnabled = {")
            .substringBefore("persistLocation =")
        assertTrue(gate.contains("settings.isTripHistoryEnabled()"))
        assertTrue(gate.contains("settings.isTelegramSendLocationEnabled()"))
        assertTrue(gate.contains("settings.mqttEnabledCategories().contains(\"location\")"))
        assertTrue(gate.contains("settings.effectiveInfluxCategories().contains(\"location\")"))
        assertFalse(gate.contains("isMqttLocationEnabled"))
        assertFalse(gate.contains("isInfluxLocationEnabled"))
    }

    @Test
    fun rejectedFixesStayOutOfNormalizedExportsAndEveryGpsStartEntersRecovery() {
        val runtime = sourceFile("com/bydcollector/collector/service/TripRuntimeCoordinator.kt").readText()
        val gpsSource = sourceFile("com/bydcollector/collector/location/AndroidGpsLocationSource.kt").readText()
        val untrusted = runtime.substringAfter("private fun handleUntrusted")
            .substringBefore("private fun handleGap")
        val start = gpsSource.substringAfter("fun start(): Boolean")
            .substringBefore("fun stop(")

        assertFalse(untrusted.contains("persistLocation("))
        assertTrue(untrusted.contains("TripMetrics.untrustedPoint"))
        assertTrue(start.contains("trustGate.beginRecovery()"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
