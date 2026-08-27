package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TripRuntimeReceiptClockContractTest {
    @Test
    fun pollAndGpsWritesUseReceiptClocksBeforeQueuedWork() {
        val runtime = sourceFile("com/bydcollector/collector/service/TripRuntimeCoordinator.kt").readText()
        val poll = runtime.substringAfter("fun onSuccessfulPoll(").substringBefore("fun resume()")

        assertTrue(poll.contains("val receivedElapsedMs = elapsedRealtimeMs()"))
        assertTrue(poll.contains("computeTelemetrySnapshot(observations, receivedElapsedMs)"))
        assertTrue(poll.contains("vehicleSpeedReference.observe(snapshot.speedKmh, receivedElapsedMs)"))
        assertTrue(poll.contains("latestBatteryPowerAtMs = snapshot.receivedElapsedMs"))
        assertTrue(runtime.contains("tripId = TripId.forPowerSession(bootId, snapshot.receivedElapsedMs)"))
        assertTrue(runtime.contains("startElapsedMs = snapshot.receivedElapsedMs"))
        assertTrue(runtime.contains("endElapsedMs = snapshot.receivedElapsedMs"))
        assertTrue(runtime.contains("LocationNormalizer.observations(sample, sample.receiveWallTimeMs)"))
        assertTrue(runtime.contains("sample.receiveElapsedRealtimeNanos / 1_000_000L - latestBatteryPowerAtMs"))
        assertTrue(runtime.contains("powerAgeMs in 0L..TELEMETRY_FRESH_MS"))
    }

    private fun sourceFile(path: String): File = listOf(
        File("src/main/kotlin/$path"),
        File("app/src/main/kotlin/$path")
    ).firstOrNull(File::isFile) ?: error("Missing source file: $path")
}
