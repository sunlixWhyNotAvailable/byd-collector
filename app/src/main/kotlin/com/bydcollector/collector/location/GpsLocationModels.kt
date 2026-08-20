package com.bydcollector.collector.location

import android.location.Location
import java.time.Instant

data class GpsLocationSample(
    val observedAt: String,
    val wallTimeMs: Long,
    val elapsedRealtimeNanos: Long,
    val bootId: String,
    val segmentId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double?,
    val speedMps: Double?,
    val altitudeM: Double?,
    val bearingDeg: Double?
) {
    val speedKmh: Double? get() = speedMps?.times(3.6)

    companion object {
        fun fromLocation(location: Location, bootId: String, segmentId: String, wallTimeMs: Long): GpsLocationSample? {
            if (!isValidCoordinate(location.latitude, location.longitude)) return null
            val elapsed = location.elapsedRealtimeNanos
            if (elapsed <= 0L) return null
            val accuracy = location.accuracy.toDouble().takeIf { it.isFinite() && it >= 0.0 }
            val speed = if (location.hasSpeed()) location.speed.toDouble().takeIf { it.isFinite() && it >= 0.0 } else null
            val altitude = if (location.hasAltitude()) location.altitude.takeIf { it.isFinite() } else null
            val bearing = if (location.hasBearing()) location.bearing.toDouble().takeIf { it.isFinite() && it >= 0.0 && it < 360.0 } else null
            return GpsLocationSample(
                observedAt = Instant.ofEpochMilli(location.time.takeIf { it > 0L } ?: wallTimeMs).toString(),
                wallTimeMs = location.time.takeIf { it > 0L } ?: wallTimeMs,
                elapsedRealtimeNanos = elapsed,
                bootId = bootId,
                segmentId = segmentId,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyM = accuracy,
                speedMps = speed,
                altitudeM = altitude,
                bearingDeg = bearing
            )
        }

        fun isValidCoordinate(latitude: Double, longitude: Double): Boolean = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}

class GpsSampleGate(private val minIntervalNanos: Long = 1_000_000_000L) {
    private var lastAccepted: GpsLocationSample? = null
    private var pending: GpsLocationSample? = null

    fun offer(sample: GpsLocationSample): GpsLocationSample? {
        if (!GpsLocationSample.isValidCoordinate(sample.latitude, sample.longitude)) return null
        val previous = lastAccepted
        val latest = pending ?: previous
        if (latest != null && sample.elapsedRealtimeNanos < latest.elapsedRealtimeNanos) return null
        if (previous == null) {
            lastAccepted = sample
            pending = null
            return sample
        }
        pending = sample
        if (sample.elapsedRealtimeNanos - previous.elapsedRealtimeNanos < minIntervalNanos) return null
        lastAccepted = sample
        pending = null
        return sample
    }

    fun flushFinal(): GpsLocationSample? {
        val final = pending ?: return null
        pending = null
        if (lastAccepted?.elapsedRealtimeNanos == final.elapsedRealtimeNanos) return null
        lastAccepted = final
        return final
    }
}
