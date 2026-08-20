package com.bydcollector.collector.data.trips

import com.bydcollector.collector.location.GpsLocationSample

object TripMetrics {
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
}
