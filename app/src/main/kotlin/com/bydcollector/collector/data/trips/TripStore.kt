package com.bydcollector.collector.data.trips

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.time.OffsetDateTime

class TripStore(private val helper: TripDatabaseHelper) : AutoCloseable {
    fun upsertSession(session: TripSession) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val values = session.toContentValues()
            if (db.update("trip_sessions", values, "trip_id = ?", arrayOf(session.tripId)) == 0) {
                db.insertOrThrow("trip_sessions", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertRoutePoint(point: RoutePoint) {
        require(
            if (point.kind == RoutePoint.KIND_GAP) point.latitude == null && point.longitude == null
            else isValidCoordinate(point.latitude, point.longitude)
        )
        helper.writableDatabase.insertWithOnConflict("route_points", null, point.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun openSessions(): List<TripSession> = querySessions("state = ?", arrayOf(TripSession.STATE_OPEN))
    fun loadOpenSession(): TripSession? = openSessions().firstOrNull()
    fun updateSession(session: TripSession) = upsertSession(session)

    fun nextRouteSequence(tripId: String): Long = helper.readableDatabase.rawQuery(
        "SELECT COALESCE(MAX(sequence), -1) + 1 FROM route_points WHERE trip_id = ?", arrayOf(tripId)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val result = block(db)
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    fun checkpoint(): Boolean = helper.writableDatabase.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null).use { it.moveToFirst() }
    fun verify(): Boolean = helper.readableDatabase.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
    val databaseFile get() = helper.databaseFile

    fun queryHierarchy(includeZeroMotion: Boolean = false): List<TripDayGroup> {
        val rows = querySummaries(
            if (includeZeroMotion) "WHERE state = 'closed'" else "WHERE state = 'closed' AND movement_observed = 1",
            emptyArray()
        )
        return rows.groupBy { summary ->
            val date = runCatching { OffsetDateTime.parse(summary.startedAt).toLocalDate() }.getOrNull()
            date?.let { Triple(it.year, it.monthValue, it.dayOfMonth) } ?: Triple(0, 0, 0)
        }.entries.sortedWith(compareByDescending<Map.Entry<Triple<Int, Int, Int>, List<TripSummary>>> { it.key.first }.thenByDescending { it.key.second }.thenByDescending { it.key.third })
            .map { (date, trips) -> TripDayGroup(date.first, date.second, date.third, trips.sortedByDescending { it.startedAt }) }
    }

    fun queryRoutePoints(tripId: String): List<RoutePoint> = helper.readableDatabase.rawQuery(
        "SELECT trip_id, sequence, kind, observed_at, elapsed_ms, boot_id, segment_id, latitude, longitude, accuracy_m, speed_kmh, instantaneous_consumption_kwh_per_100km, altitude_m, bearing_deg, quality, is_first, is_final FROM route_points WHERE trip_id = ? ORDER BY sequence",
        arrayOf(tripId)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRoutePoint()) } }

    fun closeDatabase() = helper.close()
    fun reopenDatabase() { helper.writableDatabase }
    override fun close() = closeDatabase()

    private fun querySummaries(where: String, args: Array<String>): List<TripSummary> = helper.readableDatabase.rawQuery(
        "SELECT trip_id, started_at, ended_at, duration_ms, distance_km, start_soc, end_soc, energy_kwh, average_consumption_kwh_per_100km, quality, movement_observed FROM trip_sessions $where ORDER BY started_at DESC", args
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTripSummary()) } }

    private fun querySessions(where: String, args: Array<String>): List<TripSession> = helper.readableDatabase.rawQuery(
        "SELECT trip_id, state, started_at, ended_at, start_elapsed_ms, end_elapsed_ms, start_boot_id, end_boot_id, start_segment_id, end_segment_id, movement_observed, start_soc, end_soc, start_odometer_km, last_odometer_km, start_trip_energy_kwh, last_trip_energy_kwh, duration_ms, distance_km, energy_kwh, average_consumption_kwh_per_100km, termination, quality, telegram_eligible, telegram_enqueued FROM trip_sessions WHERE $where ORDER BY started_at", args
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTripSession()) } }

    private fun TripSession.toContentValues() = ContentValues().apply {
        put("trip_id", tripId); put("state", state); put("started_at", startedAt); put("ended_at", endedAt)
        putNullable("start_elapsed_ms", startElapsedMs); putNullable("end_elapsed_ms", endElapsedMs); put("start_boot_id", startBootId); put("end_boot_id", endBootId)
        put("start_segment_id", startSegmentId); put("end_segment_id", endSegmentId); put("movement_observed", if (movementObserved) 1 else 0)
        putNullable("start_soc", startSoc); putNullable("end_soc", endSoc); putNullable("start_odometer_km", startOdometerKm); putNullable("last_odometer_km", lastOdometerKm); putNullable("start_trip_energy_kwh", startTripEnergyKwh); putNullable("last_trip_energy_kwh", lastTripEnergyKwh)
        putNullable("duration_ms", durationMs); putNullable("distance_km", distanceKm); putNullable("energy_kwh", energyKwh)
        putNullable("average_consumption_kwh_per_100km", averageConsumptionKwhPer100Km); put("termination", termination); put("quality", quality)
        put("telegram_eligible", if (telegramEligible) 1 else 0); put("telegram_enqueued", if (telegramEnqueued) 1 else 0)
    }

    private fun RoutePoint.toContentValues() = ContentValues().apply {
        // SQLite v1 only permits valid/gap; retain untrusted coordinates as a valid-shaped row and recover the model kind from quality.
        put("trip_id", tripId); put("sequence", sequence); put("kind", if (kind == RoutePoint.KIND_UNTRUSTED) RoutePoint.KIND_VALID else kind); put("observed_at", observedAt); putNullable("elapsed_ms", elapsedMs)
        put("boot_id", bootId); put("segment_id", segmentId); putNullable("latitude", latitude); putNullable("longitude", longitude); putNullable("accuracy_m", accuracyM); putNullable("speed_kmh", speedKmh)
        putNullable("instantaneous_consumption_kwh_per_100km", instantaneousConsumptionKwhPer100Km); putNullable("altitude_m", altitudeM); putNullable("bearing_deg", bearingDeg); put("quality", quality)
        put("is_first", if (isFirst) 1 else 0); put("is_final", if (isFinal) 1 else 0)
    }

    private fun ContentValues.putNullable(key: String, value: Any?) = when (value) {
        null -> putNull(key)
        is Long -> put(key, value)
        is Double -> put(key, value)
        else -> put(key, value.toString())
    }

    private fun Cursor.toTripSummary() = TripSummary(getString(0), getString(1), getStringOrNull(2), getLongOrNull(3), getDoubleOrNull(4), getDoubleOrNull(5), getDoubleOrNull(6), getDoubleOrNull(7), getDoubleOrNull(8), getString(9), getInt(10) == 1)

    private fun Cursor.toTripSession() = TripSession(getString(0), getString(1), getString(2), getStringOrNull(3), getLongOrNull(4), getLongOrNull(5), getStringOrNull(6), getStringOrNull(7), getStringOrNull(8), getStringOrNull(9), getInt(10) == 1, getDoubleOrNull(11), getDoubleOrNull(12), getDoubleOrNull(13), getDoubleOrNull(14), getDoubleOrNull(15), getDoubleOrNull(16), getLongOrNull(17), getDoubleOrNull(18), getDoubleOrNull(19), getDoubleOrNull(20), getStringOrNull(21), getString(22), getInt(23) == 1, getInt(24) == 1)

    private fun Cursor.toRoutePoint(): RoutePoint {
        val kind = getString(2)
        val quality = getString(14)
        return RoutePoint(getString(0), getLong(1), if (kind == RoutePoint.KIND_VALID && quality.startsWith("untrusted:")) RoutePoint.KIND_UNTRUSTED else kind, getString(3), getLongOrNull(4), getStringOrNull(5), getStringOrNull(6), getDoubleOrNull(7), getDoubleOrNull(8), getDoubleOrNull(9), getDoubleOrNull(10), getDoubleOrNull(11), getDoubleOrNull(12), getDoubleOrNull(13), quality, getInt(15) == 1, getInt(16) == 1)
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun Cursor.getDoubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
    private fun isValidCoordinate(latitude: Double?, longitude: Double?) = latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

object TripId {
    fun forPowerSession(bootId: String, startElapsedMs: Long): String = "${bootId.trim()}:$startElapsedMs"
}
