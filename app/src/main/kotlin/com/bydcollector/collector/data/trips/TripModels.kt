package com.bydcollector.collector.data.trips

import com.bydcollector.collector.data.energy.EnergySnapshot

/** A persisted vehicle power session. A session may span process or kernel gaps. */
data class TripSession(
    val tripId: String,
    val state: String = STATE_OPEN,
    val startedAt: String,
    val endedAt: String? = null,
    val startElapsedMs: Long? = null,
    val endElapsedMs: Long? = null,
    val startBootId: String? = null,
    val endBootId: String? = null,
    val startSegmentId: String? = null,
    val endSegmentId: String? = null,
    val movementObserved: Boolean = false,
    val startSoc: Double? = null,
    val endSoc: Double? = null,
    val startOdometerKm: Double? = null,
    val lastOdometerKm: Double? = null,
    val startTripEnergyKwh: Double? = null,
    val lastTripEnergyKwh: Double? = null,
    val durationMs: Long? = null,
    val distanceKm: Double? = null,
    val energyKwh: Double? = null,
    val averageConsumptionKwhPer100Km: Double? = null,
    val dischargedKwh: Double? = null,
    val regeneratedKwh: Double? = null,
    val netKwh: Double? = null,
    val energyCoveredMs: Long? = null,
    val energyUncoveredMs: Long? = null,
    val energyPartial: Boolean? = null,
    val energyObservedAt: String? = null,
    val termination: String? = null,
    val quality: String = QUALITY_OK,
    val telegramEligible: Boolean = false,
    val telegramEnqueued: Boolean = false
) {
    companion object {
        const val STATE_OPEN = "open"
        const val STATE_CLOSED = "closed"
        const val QUALITY_OK = "ok"
    }
}

fun TripSession.withEnergySnapshot(snapshot: EnergySnapshot): TripSession {
    if (snapshot.powerSessionId != tripId) return this
    return copy(
        dischargedKwh = snapshot.dischargedKwh,
        regeneratedKwh = snapshot.regeneratedKwh,
        netKwh = snapshot.netKwh,
        energyCoveredMs = snapshot.energyCoveredMs,
        energyUncoveredMs = snapshot.energyUncoveredMs,
        energyPartial = snapshot.energyPartial,
        energyObservedAt = snapshot.observedAt
    )
}

data class RoutePoint(
    val tripId: String,
    val sequence: Long,
    val kind: String = KIND_VALID,
    val observedAt: String,
    val elapsedMs: Long? = null,
    val receiveWallTimeMs: Long? = null,
    val bootId: String? = null,
    val segmentId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Double? = null,
    val speedKmh: Double? = null,
    val instantaneousConsumptionKwhPer100Km: Double? = null,
    val altitudeM: Double? = null,
    val bearingDeg: Double? = null,
    val quality: String = "ok",
    val isFirst: Boolean = false,
    val isFinal: Boolean = false
) {
    init {
        require(sequence >= 0L) { "Route sequence must be non-negative" }
        if (kind == KIND_VALID || kind == KIND_UNTRUSTED) {
            require(isValidCoordinate(latitude, longitude)) { "Valid route points require finite coordinates" }
        } else {
            require(latitude == null && longitude == null) { "Gap route points must not carry coordinates" }
        }
    }

    companion object {
        const val KIND_VALID = "valid"
        const val KIND_GAP = "gap"
        const val KIND_UNTRUSTED = "untrusted"

        private fun isValidCoordinate(latitude: Double?, longitude: Double?): Boolean =
            latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}

data class TripSummary(
    val tripId: String,
    val startedAt: String,
    val endedAt: String?,
    val durationMs: Long?,
    val distanceKm: Double?,
    val startSoc: Double?,
    val endSoc: Double?,
    val energyKwh: Double?,
    val averageConsumptionKwhPer100Km: Double?,
    val quality: String,
    val movementObserved: Boolean,
    val dischargedKwh: Double? = null,
    val regeneratedKwh: Double? = null,
    val netKwh: Double? = null,
    val energyCoveredMs: Long? = null,
    val energyUncoveredMs: Long? = null,
    val energyPartial: Boolean? = null,
    val energyObservedAt: String? = null
)

data class TripDayGroup(
    val year: Int,
    val month: Int,
    val day: Int,
    val trips: List<TripSummary>
) {
    val tripCount: Int get() = trips.size
}

/** Hash-free internal route proof; diagnostics serializes only the safe fields. */
internal data class TripRouteDiagnosticPoint(
    val sequence: Long,
    val observedAt: String,
    val quality: String,
    val hasCoordinate: Boolean
)

internal data class TripRouteDiagnosticEvidence(
    val tripId: String,
    val storage: String,
    val pointCount: Long,
    val validCount: Long,
    val gapCount: Long,
    val untrustedCount: Long,
    val finalMarkerCount: Long,
    val finalPoint: TripRouteDiagnosticPoint?,
    val latestValidPoint: TripRouteDiagnosticPoint?,
    val sequenceContiguous: Boolean
) {
    val finalIsLatestValid: Boolean
        get() = finalPoint != null && finalPoint.sequence == latestValidPoint?.sequence
}

/** Streaming route proof; it never retains coordinates or the route itself. */
internal class TripRouteDiagnosticAccumulator(private val tripId: String) {
    private var pointCount = 0L
    private var validCount = 0L
    private var gapCount = 0L
    private var untrustedCount = 0L
    private var finalMarkerCount = 0L
    private var previousSequence: Long? = null
    private var sequenceContiguous = true
    private var finalPoint: TripRouteDiagnosticPoint? = null
    private var latestValidPoint: TripRouteDiagnosticPoint? = null

    fun accept(point: RoutePoint) {
        pointCount++
        if (pointCount == 1L) sequenceContiguous = point.sequence == 0L
        previousSequence?.let {
            sequenceContiguous = sequenceContiguous && it != Long.MAX_VALUE && point.sequence == it + 1L
        }
        previousSequence = point.sequence
        val summary = TripRouteDiagnosticPoint(
            sequence = point.sequence,
            observedAt = point.observedAt,
            quality = point.quality,
            hasCoordinate = point.latitude?.isFinite() == true && point.longitude?.isFinite() == true
        )
        when (point.kind) {
            RoutePoint.KIND_VALID -> {
                validCount++
                latestValidPoint = summary
            }
            RoutePoint.KIND_GAP -> gapCount++
            RoutePoint.KIND_UNTRUSTED -> untrustedCount++
        }
        if (point.isFinal) {
            finalMarkerCount++
            finalPoint = summary
        }
    }

    fun finish(storage: String): TripRouteDiagnosticEvidence = TripRouteDiagnosticEvidence(
        tripId = tripId,
        storage = storage,
        pointCount = pointCount,
        validCount = validCount,
        gapCount = gapCount,
        untrustedCount = untrustedCount,
        finalMarkerCount = finalMarkerCount,
        finalPoint = finalPoint,
        latestValidPoint = latestValidPoint,
        sequenceContiguous = sequenceContiguous
    )
}
