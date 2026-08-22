package com.bydcollector.collector.location

import android.location.Location
import android.os.SystemClock
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    val bearingDeg: Double?,
    /** Receipt clocks are deliberately retained beside source clocks for freshness checks. */
    val receiveWallTimeMs: Long = wallTimeMs,
    val receiveElapsedRealtimeNanos: Long = elapsedRealtimeNanos,
    val isMock: Boolean = false
) {
    val speedKmh: Double? get() = speedMps?.times(3.6)
    val sourceWallTimeMs: Long get() = wallTimeMs
    val sourceElapsedRealtimeNanos: Long get() = elapsedRealtimeNanos
    val receivedWallTimeMs: Long get() = receiveWallTimeMs
    val receivedElapsedRealtimeNanos: Long get() = receiveElapsedRealtimeNanos

    companion object {
        fun fromLocation(location: Location, bootId: String, segmentId: String, wallTimeMs: Long): GpsLocationSample? {
            val elapsed = location.elapsedRealtimeNanos
            val accuracy = location.accuracy.toDouble()
            val speed = if (location.hasSpeed()) location.speed.toDouble() else null
            val altitude = if (location.hasAltitude()) location.altitude else null
            val bearing = if (location.hasBearing()) location.bearing.toDouble() else null
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
                bearingDeg = bearing,
                receiveWallTimeMs = wallTimeMs,
                receiveElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                isMock = location.isFromMockProvider
            )
        }

        fun isValidCoordinate(latitude: Double, longitude: Double): Boolean = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}

enum class GpsTrustStatus { TRUSTED, PENDING, REJECTED }

data class GpsTrustDecision(
    val sample: GpsLocationSample,
    val status: GpsTrustStatus,
    val reason: String? = null,
    val callbackGap: Boolean = false
) {
    val trusted: Boolean get() = status == GpsTrustStatus.TRUSTED
    val pending: Boolean get() = status == GpsTrustStatus.PENDING
    val rejected: Boolean get() = status == GpsTrustStatus.REJECTED
}

typealias GpsTrustResult = GpsTrustDecision

/** Classifies post-1Hz fixes without using provider, accuracy, or reported speed as truth. */
class GpsTrustGate(
    private val maxClockSkewMs: Long = 5_000L,
    private val maxReceiveAgeMs: Long = 5_000L,
    private val maxImpliedSpeedMps: Double = 80.0,
    private val recoveryFixes: Int = 3
) {
    private var lastTrusted: GpsLocationSample? = null
    private var lastReceivedElapsedNanos: Long? = null
    private var recovering = false
    private var pending: GpsLocationSample? = null
    private var pendingCount = 0

    fun offer(sample: GpsLocationSample): GpsTrustDecision {
        val previousReceived = lastReceivedElapsedNanos
        val callbackGap = previousReceived != null && elapsedMs(sample.receiveElapsedRealtimeNanos, previousReceived) > maxReceiveAgeMs
        lastReceivedElapsedNanos = sample.receiveElapsedRealtimeNanos
        if (callbackGap) {
            recovering = true
            pending = null
            pendingCount = 0
        }

        val hardFailure = hardFailure(sample) ?: continuityFailure(sample).takeUnless { recovering }
        if (hardFailure != null) {
            recovering = true
            pending = null
            pendingCount = 0
            return GpsTrustDecision(sample, GpsTrustStatus.REJECTED, hardFailure, callbackGap)
        }

        if (lastTrusted == null && !recovering) {
            lastTrusted = sample
            recovering = false
            return GpsTrustDecision(sample, GpsTrustStatus.TRUSTED, callbackGap = callbackGap)
        }
        if (!recovering) {
            lastTrusted = sample
            return GpsTrustDecision(sample, GpsTrustStatus.TRUSTED, callbackGap = callbackGap)
        }

        val priorPending = pending
        if (priorPending != null && !mutuallyConsistent(priorPending, sample)) {
            pending = null
            pendingCount = 0
            return GpsTrustDecision(sample, GpsTrustStatus.REJECTED, "continuity_speed", callbackGap)
        }
        pending = sample
        pendingCount += 1
        if (pendingCount < recoveryFixes) {
            return GpsTrustDecision(sample, GpsTrustStatus.PENDING, "recovery_pending", callbackGap)
        }
        lastTrusted = sample
        recovering = false
        pending = null
        pendingCount = 0
        return GpsTrustDecision(sample, GpsTrustStatus.TRUSTED, "recovered", callbackGap)
    }

    fun reset() {
        lastTrusted = null
        lastReceivedElapsedNanos = null
        recovering = false
        pending = null
        pendingCount = 0
    }

    fun beginRecovery() {
        lastReceivedElapsedNanos = null
        recovering = true
        pending = null
        pendingCount = 0
    }

    fun lastTrusted(): GpsLocationSample? = lastTrusted

    private fun hardFailure(sample: GpsLocationSample): String? = when {
        sample.isMock -> "mock_source"
        !GpsLocationSample.isValidCoordinate(sample.latitude, sample.longitude) ||
            sample.wallTimeMs <= 0L || sample.elapsedRealtimeNanos <= 0L ||
            sample.receiveWallTimeMs <= 0L || sample.receiveElapsedRealtimeNanos <= 0L ||
            sample.accuracyM?.let { !it.isFinite() || it < 0.0 } == true ||
            sample.speedMps?.let { !it.isFinite() || it < 0.0 } == true ||
            sample.altitudeM?.let { !it.isFinite() } == true ||
            sample.bearingDeg?.let { !it.isFinite() || it < 0.0 || it >= 360.0 } == true -> "invalid_numeric"
        absDiff(sample.wallTimeMs, sample.receiveWallTimeMs) > maxClockSkewMs -> "source_clock_skew"
        elapsedMs(sample.receiveElapsedRealtimeNanos, sample.elapsedRealtimeNanos) !in 0L..maxReceiveAgeMs -> "receive_age"
        else -> null
    }

    private fun continuityFailure(sample: GpsLocationSample): String? {
        val previous = lastTrusted ?: return null
        return if (impliedSpeedMps(previous, sample)?.let { it > maxImpliedSpeedMps } == true) "continuity_speed" else null
    }

    private fun mutuallyConsistent(previous: GpsLocationSample, sample: GpsLocationSample): Boolean =
        impliedSpeedMps(previous, sample)?.let { it <= maxImpliedSpeedMps } == true

    private fun impliedSpeedMps(previous: GpsLocationSample, sample: GpsLocationSample): Double? {
        val elapsedNanos = sample.elapsedRealtimeNanos - previous.elapsedRealtimeNanos
        if (elapsedNanos <= 0L) return null
        val seconds = elapsedNanos.toDouble() / 1_000_000_000.0
        return distanceM(previous.latitude, previous.longitude, sample.latitude, sample.longitude) / seconds
    }

    private fun elapsedMs(later: Long, earlier: Long?): Long {
        if (earlier == null || later < 0L || earlier < 0L || later < earlier) return Long.MIN_VALUE
        val delta = later - earlier
        if (delta < 0L) return Long.MIN_VALUE
        return (delta / 1_000_000L).coerceAtLeast(0L)
    }

    private fun absDiff(left: Long, right: Long): Long = when {
        left >= right && right >= 0L -> (left - right).coerceAtLeast(0L)
        left < right && left >= 0L -> (right - left).coerceAtLeast(0L)
        else -> Long.MAX_VALUE
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latDelta = Math.toRadians(lat2 - lat1)
        val lonDelta = Math.toRadians(lon2 - lon1)
        val a = sin(latDelta / 2.0) * sin(latDelta / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(lonDelta / 2.0) * sin(lonDelta / 2.0)
        return 6_371_000.0 * 2.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

class GpsSampleGate(private val minIntervalNanos: Long = 1_000_000_000L) {
    private var lastAccepted: GpsLocationSample? = null
    private var pending: GpsLocationSample? = null

    fun offer(sample: GpsLocationSample): GpsLocationSample? {
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
