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
    fun rejectedFixesStayOutOfNormalizedExportsAndGpsStartUsesPersistedAnchor() {
        val runtime = sourceFile("com/bydcollector/collector/service/TripRuntimeCoordinator.kt").readText()
        val gpsSource = sourceFile("com/bydcollector/collector/location/AndroidGpsLocationSource.kt").readText()
        val untrusted = runtime.substringAfter("private fun handleUntrusted")
            .substringBefore("private fun handleGap")
        val start = gpsSource.substringAfter("fun start(anchor:")
            .substringBefore("fun stop(")

        assertFalse(untrusted.contains("persistLocation("))
        assertTrue(untrusted.contains("TripMetrics.untrustedPoint"))
        assertTrue(start.contains("trustGate.beginRecovery(anchor)"))
        assertTrue(runtime.contains("locationSource.start(lastLocation)"))
        assertTrue(runtime.contains("GpsStartRetryGate()"))
        assertFalse(start.contains("sink.onGap("))
    }

    @Test
    fun gpsProviderReceiverResetsFailedStartWithoutPollSpam() {
        val gpsSource = sourceFile("com/bydcollector/collector/location/AndroidGpsLocationSource.kt").readText()
        val runtime = sourceFile("com/bydcollector/collector/service/TripRuntimeCoordinator.kt").readText()
        val start = gpsSource.substringAfter("fun start(anchor:")
            .substringBefore("fun stop(")
        val providerChanged = runtime.substringAfter("override fun onProviderChanged")
            .substringBefore("},\n        bootIdProvider")

        assertTrue(gpsSource.contains("LocationManager.PROVIDERS_CHANGED_ACTION"))
        assertTrue(gpsSource.contains("ContextCompat.RECEIVER_EXPORTED"))
        assertTrue(gpsSource.contains("sink.onProviderChanged(enabled)"))
        assertTrue(gpsSource.contains("if (!enabled) runCatching { locationManager.removeUpdates(listener) }"))
        assertTrue(providerChanged.contains("gpsStarted = false"))
        assertTrue(start.indexOf("registerProviderReceiver()") < start.indexOf("checkSelfPermission"))
        assertTrue(gpsSource.substringAfter("fun stop(").contains("unregisterProviderReceiver()"))
    }

    @Test
    fun tripDatabaseMigratesPersistedGpsReceiveClock() {
        val helper = sourceFile("com/bydcollector/collector/data/trips/TripDatabaseHelper.kt").readText()
        val store = sourceFile("com/bydcollector/collector/data/trips/TripStore.kt").readText()

        assertTrue(helper.contains("receive_wall_time_ms INTEGER"))
        assertTrue(helper.contains("oldVersion < 2"))
        assertTrue(helper.contains("ALTER TABLE route_points ADD COLUMN receive_wall_time_ms INTEGER"))
        assertTrue(helper.contains("const val DATABASE_VERSION = 2"))
        assertTrue(store.contains("putNullable(\"receive_wall_time_ms\", receiveWallTimeMs)"))
        assertTrue(store.contains("SELECT trip_id, sequence, kind, observed_at, elapsed_ms, receive_wall_time_ms"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
