package com.bydcollector.collector.data.trips

import com.bydcollector.collector.data.energy.EnergyPendingProjection
import com.bydcollector.collector.data.energy.EnergySnapshot
import com.bydcollector.collector.data.energy.EnergyStateCodec
import org.json.JSONObject

data class TripCompletionLocation(
    val latitude: Double,
    val longitude: Double,
    val capturedAt: String
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(capturedAt.isNotBlank() && capturedAt.length <= 128)
    }
}

data class TripCompletionIntent(
    val sequence: Long = 0L,
    val identity: String,
    val observedAt: String,
    val session: TripSession? = null,
    val lastLocation: TripCompletionLocation? = null,
    val energySnapshot: EnergySnapshot? = null,
    val odometerKm: Double? = session?.lastOdometerKm,
    val soc: Double? = session?.endSoc,
    val tripEnergyKwh: Double? = session?.lastTripEnergyKwh
) {
    init {
        require(sequence >= 0L)
        require(identity.isNotBlank() && identity.length <= 512)
        require(observedAt.isNotBlank() && observedAt.length <= 128)
        require(session == null || session.state == TripSession.STATE_CLOSED)
        require(listOfNotNull(odometerKm, soc, tripEnergyKwh).all(Double::isFinite))
    }
}

internal object TripCompletionIntentCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(intent: TripCompletionIntent): String {
        intent.session?.let(::requireFiniteSession)
        return JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("sequence", intent.sequence)
            put("identity", intent.identity)
            put("observed_at", intent.observedAt)
            putNullable("session", intent.session?.let(::encodeSession))
            putNullable("last_location", intent.lastLocation?.let(::encodeLocation))
            putNullable(
                "energy_snapshot",
                intent.energySnapshot?.let { EnergyStateCodec.encodeProjection(EnergyPendingProjection(it)) }
            )
            putNullable("odometer_km", intent.odometerKm)
            putNullable("soc", intent.soc)
            putNullable("trip_energy_kwh", intent.tripEnergyKwh)
        }.toString()
    }

    fun decode(value: String): TripCompletionIntent {
        val json = JSONObject(value)
        require(json.getInt("schema_version") == SCHEMA_VERSION) { "Unsupported trip completion schema" }
        val session = json.objectOrNull("session")?.let(::decodeSession)
        return TripCompletionIntent(
            sequence = json.getLong("sequence"),
            identity = json.getString("identity"),
            observedAt = json.getString("observed_at"),
            session = session,
            lastLocation = json.objectOrNull("last_location")?.let(::decodeLocation),
            energySnapshot = json.stringOrNull("energy_snapshot")
                ?.let(EnergyStateCodec::decodeProjection)
                ?.snapshot,
            odometerKm = json.finiteDoubleOrNull("odometer_km"),
            soc = json.finiteDoubleOrNull("soc"),
            tripEnergyKwh = json.finiteDoubleOrNull("trip_energy_kwh")
        )
    }

    private fun encodeLocation(location: TripCompletionLocation) = JSONObject()
        .put("latitude", location.latitude)
        .put("longitude", location.longitude)
        .put("captured_at", location.capturedAt)

    private fun decodeLocation(json: JSONObject) = TripCompletionLocation(
        latitude = json.finiteDouble("latitude"),
        longitude = json.finiteDouble("longitude"),
        capturedAt = json.getString("captured_at")
    )

    private fun encodeSession(session: TripSession) = JSONObject().apply {
        put("trip_id", session.tripId)
        put("state", session.state)
        put("started_at", session.startedAt)
        putNullable("ended_at", session.endedAt)
        putNullable("start_elapsed_ms", session.startElapsedMs)
        putNullable("end_elapsed_ms", session.endElapsedMs)
        putNullable("start_boot_id", session.startBootId)
        putNullable("end_boot_id", session.endBootId)
        putNullable("start_segment_id", session.startSegmentId)
        putNullable("end_segment_id", session.endSegmentId)
        put("movement_observed", session.movementObserved)
        putNullable("start_soc", session.startSoc)
        putNullable("end_soc", session.endSoc)
        putNullable("start_odometer_km", session.startOdometerKm)
        putNullable("last_odometer_km", session.lastOdometerKm)
        putNullable("start_trip_energy_kwh", session.startTripEnergyKwh)
        putNullable("last_trip_energy_kwh", session.lastTripEnergyKwh)
        putNullable("duration_ms", session.durationMs)
        putNullable("distance_km", session.distanceKm)
        putNullable("energy_kwh", session.energyKwh)
        putNullable("average_consumption_kwh_per_100km", session.averageConsumptionKwhPer100Km)
        putNullable("discharged_kwh", session.dischargedKwh)
        putNullable("regenerated_kwh", session.regeneratedKwh)
        putNullable("net_kwh", session.netKwh)
        putNullable("energy_covered_ms", session.energyCoveredMs)
        putNullable("energy_uncovered_ms", session.energyUncoveredMs)
        putNullable("energy_partial", session.energyPartial)
        putNullable("energy_observed_at", session.energyObservedAt)
        putNullable("termination", session.termination)
        put("quality", session.quality)
        put("telegram_eligible", session.telegramEligible)
        put("telegram_enqueued", session.telegramEnqueued)
    }

    private fun decodeSession(json: JSONObject): TripSession = TripSession(
        tripId = json.getString("trip_id"),
        state = json.getString("state"),
        startedAt = json.getString("started_at"),
        endedAt = json.stringOrNull("ended_at"),
        startElapsedMs = json.longOrNull("start_elapsed_ms"),
        endElapsedMs = json.longOrNull("end_elapsed_ms"),
        startBootId = json.stringOrNull("start_boot_id"),
        endBootId = json.stringOrNull("end_boot_id"),
        startSegmentId = json.stringOrNull("start_segment_id"),
        endSegmentId = json.stringOrNull("end_segment_id"),
        movementObserved = json.getBoolean("movement_observed"),
        startSoc = json.finiteDoubleOrNull("start_soc"),
        endSoc = json.finiteDoubleOrNull("end_soc"),
        startOdometerKm = json.finiteDoubleOrNull("start_odometer_km"),
        lastOdometerKm = json.finiteDoubleOrNull("last_odometer_km"),
        startTripEnergyKwh = json.finiteDoubleOrNull("start_trip_energy_kwh"),
        lastTripEnergyKwh = json.finiteDoubleOrNull("last_trip_energy_kwh"),
        durationMs = json.longOrNull("duration_ms"),
        distanceKm = json.finiteDoubleOrNull("distance_km"),
        energyKwh = json.finiteDoubleOrNull("energy_kwh"),
        averageConsumptionKwhPer100Km = json.finiteDoubleOrNull("average_consumption_kwh_per_100km"),
        dischargedKwh = json.finiteDoubleOrNull("discharged_kwh"),
        regeneratedKwh = json.finiteDoubleOrNull("regenerated_kwh"),
        netKwh = json.finiteDoubleOrNull("net_kwh"),
        energyCoveredMs = json.longOrNull("energy_covered_ms"),
        energyUncoveredMs = json.longOrNull("energy_uncovered_ms"),
        energyPartial = json.booleanOrNull("energy_partial"),
        energyObservedAt = json.stringOrNull("energy_observed_at"),
        termination = json.stringOrNull("termination"),
        quality = json.getString("quality"),
        telegramEligible = json.getBoolean("telegram_eligible"),
        telegramEnqueued = json.getBoolean("telegram_enqueued")
    ).also(::requireFiniteSession)

    private fun requireFiniteSession(session: TripSession) {
        require(
            listOfNotNull(
                session.startSoc,
                session.endSoc,
                session.startOdometerKm,
                session.lastOdometerKm,
                session.startTripEnergyKwh,
                session.lastTripEnergyKwh,
                session.distanceKm,
                session.energyKwh,
                session.averageConsumptionKwhPer100Km,
                session.dischargedKwh,
                session.regeneratedKwh,
                session.netKwh
            ).all(Double::isFinite)
        ) { "Trip completion session contains a non-finite metric" }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.objectOrNull(key: String): JSONObject? =
        if (isNull(key)) null else getJSONObject(key)

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.longOrNull(key: String): Long? =
        if (isNull(key)) null else getLong(key)

    private fun JSONObject.booleanOrNull(key: String): Boolean? =
        if (isNull(key)) null else getBoolean(key)

    private fun JSONObject.finiteDouble(key: String): Double =
        getDouble(key).also { require(it.isFinite()) { "Non-finite trip completion value: $key" } }

    private fun JSONObject.finiteDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else finiteDouble(key)
}
