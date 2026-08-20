package com.bydcollector.collector.data.trips

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

data class RoutePoint(
    val tripId: String,
    val sequence: Long,
    val kind: String = KIND_VALID,
    val observedAt: String,
    val elapsedMs: Long? = null,
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
        if (kind == KIND_VALID) {
            require(isValidCoordinate(latitude, longitude)) { "Valid route points require finite coordinates" }
        } else {
            require(latitude == null && longitude == null) { "Gap route points must not carry coordinates" }
        }
    }

    companion object {
        const val KIND_VALID = "valid"
        const val KIND_GAP = "gap"

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
    val movementObserved: Boolean
)

data class TripDayGroup(
    val year: Int,
    val month: Int,
    val day: Int,
    val trips: List<TripSummary>
) {
    val tripCount: Int get() = trips.size
}
