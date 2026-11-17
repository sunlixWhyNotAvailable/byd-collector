package com.bydcollector.collector.data.trips

import com.bydcollector.collector.location.GpsLocationSample

/** Only the short history needed by delayed GPS fixes; loss markers close held power. */
internal data class RoutePowerHistory(private val samples: List<Sample> = emptyList()) {
    data class Sample(val bootId: String, val elapsedMs: Long, val powerKw: Double?, val continuous: Boolean)

    fun observe(bootId: String, elapsedMs: Long, powerKw: Double?, continuous: Boolean): RoutePowerHistory {
        if (bootId.isBlank() || elapsedMs < 0L) return this
        val sample = Sample(bootId, elapsedMs, powerKw?.takeIf(Double::isFinite), continuous)
        val current = samples.takeIf { it.lastOrNull()?.bootId == bootId }.orEmpty()
        val last = current.lastOrNull()
        if (last != null && elapsedMs < last.elapsedMs) return this
        if (sample == last) return this
        val retained = if (last?.elapsedMs == elapsedMs) current.dropLast(1) else current
        // GPS rejects fixes over five seconds old; retain the interval crossing that boundary.
        val first = retained.indexOfLast { it.elapsedMs <= elapsedMs - 5_000L }.coerceAtLeast(0)
        return RoutePowerHistory((retained.drop(first).takeLast(4_095) + sample))
    }

    fun powerAt(bootId: String?, elapsedMs: Long): Double? {
        val sample = samples.lastOrNull { it.bootId == bootId && it.elapsedMs <= elapsedMs } ?: return null
        return sample.powerKw.takeIf { sample.continuous || elapsedMs - sample.elapsedMs <= 2_000L }
    }
}

data class EnergyCounterUpdate(
    val accumulatedKwh: Double?,
    val lastCounterKwh: Double?,
    val resetObserved: Boolean
)

object TripMetrics {
    fun advanceEnergyCounter(
        accumulatedKwh: Double?,
        lastCounterKwh: Double?,
        currentCounterKwh: Double?,
        resetToleranceKwh: Double = 0.1
    ): EnergyCounterUpdate {
        val accumulated = accumulatedKwh?.takeIf { it.isFinite() && it >= 0.0 }
        val previous = lastCounterKwh?.takeIf { it.isFinite() && it >= 0.0 }
        val current = currentCounterKwh?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return EnergyCounterUpdate(accumulated, previous, false)
        if (previous == null) return EnergyCounterUpdate(accumulated ?: 0.0, current, false)

        val delta = current - previous
        return when {
            delta > 0.0 -> EnergyCounterUpdate((accumulated ?: 0.0) + delta, current, false)
            delta < -(resetToleranceKwh.coerceAtLeast(0.0) + 1e-9) ->
                EnergyCounterUpdate(accumulated ?: 0.0, current, true)
            else -> EnergyCounterUpdate(accumulated, previous, false)
        }
    }

    fun delta(start: Double?, last: Double?): Double? {
        if (start == null || last == null || !start.isFinite() || !last.isFinite()) return null
        return (last - start).takeIf { it.isFinite() && it >= 0.0 }
    }

    fun averageConsumptionKwhPer100Km(energyKwh: Double?, distanceKm: Double?): Double? {
        if (energyKwh == null || distanceKm == null || !energyKwh.isFinite() || !distanceKm.isFinite() || distanceKm <= 0.0) return null
        return (energyKwh * 100.0 / distanceKm).takeIf { it.isFinite() }
    }

    /** Signed instantaneous value; stationary or missing power is intentionally null. */
    fun instantaneousConsumptionKwhPer100Km(batteryPowerKw: Double?, speedKmh: Double?): Double? {
        if (batteryPowerKw == null || speedKmh == null || !batteryPowerKw.isFinite() || !speedKmh.isFinite() || speedKmh < 1.0) return null
        return (batteryPowerKw * 100.0 / speedKmh).takeIf { it.isFinite() }
    }

    fun routePoint(
        tripId: String,
        sequence: Long,
        sample: GpsLocationSample,
        batteryPowerKw: Double? = null,
        isFirst: Boolean = false,
        isFinal: Boolean = false
    ): RoutePoint = RoutePoint(
        tripId = tripId,
        sequence = sequence,
        observedAt = sample.observedAt,
        elapsedMs = sample.elapsedRealtimeNanos / 1_000_000L,
        receiveWallTimeMs = sample.receiveWallTimeMs,
        bootId = sample.bootId,
        segmentId = sample.segmentId,
        latitude = sample.latitude,
        longitude = sample.longitude,
        accuracyM = sample.accuracyM,
        speedKmh = sample.speedKmh,
        instantaneousConsumptionKwhPer100Km = instantaneousConsumptionKwhPer100Km(batteryPowerKw, sample.speedKmh),
        altitudeM = sample.altitudeM,
        bearingDeg = sample.bearingDeg,
        quality = if (sample.accuracyM != null && sample.accuracyM > 100.0) "degraded" else "ok",
        isFirst = isFirst,
        isFinal = isFinal
    )

    fun gapPoint(tripId: String, sequence: Long, observedAt: String, bootId: String?, segmentId: String?, reason: String): RoutePoint = RoutePoint(
        tripId = tripId,
        sequence = sequence,
        kind = RoutePoint.KIND_GAP,
        observedAt = observedAt,
        bootId = bootId,
        segmentId = segmentId,
        quality = "gap:$reason"
    )

    fun untrustedPoint(
        tripId: String,
        sequence: Long,
        sample: GpsLocationSample,
        reason: String
    ): RoutePoint {
        val quality = "untrusted:$reason"
        if (!GpsLocationSample.isValidCoordinate(sample.latitude, sample.longitude)) {
            return gapPoint(tripId, sequence, sample.observedAt, sample.bootId, sample.segmentId, reason)
                .copy(quality = quality)
        }
        return routePoint(tripId, sequence, sample).copy(
            kind = RoutePoint.KIND_UNTRUSTED,
            quality = quality,
            isFirst = false,
            isFinal = false
        )
    }
}
