package com.bydcollector.collector.location

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class GpsSpeedMismatchContractTest {
    @Test
    fun sourcePlumbsRollingCanReferenceIntoGpsTrustGate() {
        val gpsSource = sourceFile("com/bydcollector/collector/location/AndroidGpsLocationSource.kt").readText()
        val runtime = sourceFile("com/bydcollector/collector/service/TripRuntimeCoordinator.kt").readText()
        val models = sourceFile("com/bydcollector/collector/location/GpsLocationModels.kt").readText()

        assertTrue(gpsSource.contains("@Volatile private var lastGpsEnabled"))
        assertTrue(gpsSource.contains("private val vehicleSpeedReferenceKmh: () -> Double? = { null }"))
        assertTrue(gpsSource.contains("trustGate.offer(sample, vehicleSpeedReferenceKmh())"))
        assertTrue(runtime.contains("VehicleSpeedReference(nowElapsedMs = elapsedRealtimeMs)"))
        assertTrue(runtime.contains("vehicleSpeedReferenceKmh = vehicleSpeedReference::current"))
        assertTrue(runtime.contains("if (liveTelemetry) vehicleSpeedReference.observe(snapshot.speedKmh, receivedElapsedMs)"))
        assertTrue(runtime.contains("val receivedElapsedMs = elapsedRealtimeMs()"))
        assertTrue(runtime.contains("liveTelemetry: Boolean"))
        assertTrue(models.contains("vehicle_speed_mismatch"))
        assertTrue(models.contains("lastSequenceCandidate = null"))
        assertTrue(models.contains("VEHICLE_SPEED_MARGIN_KMH = 40.0"))
    }

    private fun sourceFile(path: String): File = listOf(
        File("src/main/kotlin/$path"),
        File("app/src/main/kotlin/$path")
    ).firstOrNull(File::isFile) ?: error("Missing source file: $path")
}
