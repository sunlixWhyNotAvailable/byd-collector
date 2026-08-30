package com.bydcollector.collector.data.trips

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.time.Instant

class TripStore(private val helper: TripDatabaseHelper) : AutoCloseable {
    /** Serializes all TripStore access; Step 8 leases this monitor for cutover. */
    private val changedTripSequences = linkedMapOf<String, Long?>()
    private var changeTracking = false
    private var fileAccessSuspended = false

    @Synchronized
    internal fun beginChangeTracking() {
        check(!changeTracking) { "Trips change tracking already active" }
        changedTripSequences.clear()
        changeTracking = true
    }

    @Synchronized
    internal fun changedTripSequences(): Map<String, Long?> = changedTripSequences.toMap()

    @Synchronized
    internal fun endChangeTracking() {
        check(changeTracking) { "Trips change tracking is not active" }
        changeTracking = false
        changedTripSequences.clear()
    }

    private fun markSessionChanged(tripId: String) {
        if (!changeTracking) return
        if (!changedTripSequences.containsKey(tripId)) changedTripSequences[tripId] = null
    }

    private fun markRouteChanged(tripId: String, firstSequence: Long) {
        if (!changeTracking) return
        val prior = changedTripSequences[tripId]
        changedTripSequences[tripId] = if (prior == null) firstSequence else minOf(prior, firstSequence)
    }

    @Synchronized
    fun upsertSession(session: TripSession) {
        val db = writableDb
        var committed = false
        db.beginTransaction()
        try {
            if (session.state == TripSession.STATE_OPEN) materializeChunksIfNeeded(db, session.tripId)
            val values = session.toContentValues()
            if (db.update("trip_sessions", values, "trip_id = ?", arrayOf(session.tripId)) == 0) {
                db.insertOrThrow("trip_sessions", null, values)
            }
            db.setTransactionSuccessful()
            committed = true
        } finally {
            db.endTransaction()
        }
        if (committed) markSessionChanged(session.tripId)
    }

    @Synchronized
    fun upsertRoutePoint(point: RoutePoint) {
        require(
            if (point.kind == RoutePoint.KIND_GAP) point.latitude == null && point.longitude == null
            else isValidCoordinate(point.latitude, point.longitude)
        )
        val db = writableDb
        var committed = false
        db.beginTransaction()
        try {
            materializeChunksIfNeeded(db, point.tripId)
            check(db.insertWithOnConflict("route_points", null, point.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                "Route point insert failed"
            }
            db.setTransactionSuccessful()
            committed = true
        } finally {
            db.endTransaction()
        }
        if (committed) markRouteChanged(point.tripId, point.sequence)
    }

    @Synchronized
    fun openSessions(): List<TripSession> = querySessions("state = ?", arrayOf(TripSession.STATE_OPEN))
    @Synchronized
    fun loadOpenSession(): TripSession? = openSessions().firstOrNull()
    @Synchronized
    fun updateSession(session: TripSession) = upsertSession(session)

    @Synchronized
    fun nextRouteSequence(tripId: String): Long {
        val db = readableDb
        val chunkBounds = readChunkBounds(db, tripId)
        val rawMax = db.rawQuery("SELECT MAX(sequence) FROM route_points WHERE trip_id = ?", arrayOf(tripId)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        if (chunkBounds != null && rawMax != null) throw IllegalStateException("Trip has both raw and chunked route data")
        return (chunkBounds?.lastSequence ?: rawMax ?: -1L) + 1L
    }

    @Synchronized
    fun checkpoint(): Boolean = writableDb.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null).use { it.moveToFirst() }
    /** Checkpoints the WAL completely; callers must hold the store lease before closing/replacing files. */
    @Synchronized
    internal fun checkpointTruncate() {
        writableDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            check(cursor.moveToFirst()) { "Trips WAL checkpoint returned no result" }
            val busy = cursor.getLong(0)
            val log = cursor.getLong(1)
            val checkpointed = cursor.getLong(2)
            check(busy == 0L && log == checkpointed) {
                "Trips WAL checkpoint incomplete: busy=$busy log=$log checkpointed=$checkpointed"
            }
        }
    }

    @Synchronized
    internal fun verifyForeignKeys(): Boolean = readableDb.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
        !cursor.moveToFirst()
    }

    @Synchronized
    internal fun sessionCount(): Long = readableDb.rawQuery("SELECT COUNT(*) FROM trip_sessions", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }

    @Synchronized
    internal fun schemaVersion(): Int = readableDb.rawQuery("PRAGMA user_version", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    @Synchronized
    fun verify(): Boolean = readableDb.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
    val databaseFile get() = helper.databaseFile

    @Synchronized
    fun queryHierarchy(includeZeroMotion: Boolean = false): List<TripDayGroup> {
        val rows = querySummaries(
            if (includeZeroMotion) "WHERE state = 'closed'" else "WHERE state = 'closed' AND movement_observed = 1",
            emptyArray()
        )
        return rows.groupBy { summary ->
            val date = TripTime.localDate(summary.startedAt)
            date?.let { Triple(it.year, it.monthValue, it.dayOfMonth) } ?: Triple(0, 0, 0)
        }.entries.sortedWith(compareByDescending<Map.Entry<Triple<Int, Int, Int>, List<TripSummary>>> { it.key.first }.thenByDescending { it.key.second }.thenByDescending { it.key.third })
            .map { (date, trips) ->
                TripDayGroup(
                    date.first,
                    date.second,
                    date.third,
                    trips.sortedWith(compareByDescending<TripSummary> { TripTime.instant(it.startedAt) ?: Instant.MIN }.thenByDescending { it.startedAt })
                )
            }
    }

    @Synchronized
    fun queryRoutePoints(tripId: String): List<RoutePoint> = buildList { forEachRoutePoint(tripId, ::add) }

    /** Streams one selected route while retaining strict raw/chunk exclusivity checks. */
    @Synchronized
    internal fun forEachRoutePoint(tripId: String, consumer: (RoutePoint) -> Unit) {
        forEachRoutePointFrom(tripId, 0L, consumer)
    }

    /** Streams one route tail without materializing its verified prefix. */
    @Synchronized
    internal fun forEachRoutePointFrom(tripId: String, firstSequence: Long, consumer: (RoutePoint) -> Unit) {
        require(firstSequence >= 0L) { "Route sequence must be non-negative" }
        val db = readableDb
        val hasChunks = chunkCount(db, tripId) != 0L
        if (hasChunks && session(tripId)?.state == TripSession.STATE_OPEN) {
            throw IllegalStateException("Open trip cannot have route chunks")
        }
        val rawCount = db.rawQuery("SELECT COUNT(*) FROM route_points WHERE trip_id = ?", arrayOf(tripId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (hasChunks && rawCount != 0L) throw IllegalStateException("Trip has both raw and chunked route data")
        if (hasChunks) {
            streamChunks(db, tripId, firstSequence, consumer)
            return
        }
        db.rawQuery(
            RAW_ROUTE_FROM_SELECT,
            arrayOf(tripId, firstSequence.toString())
        ).use { cursor -> while (cursor.moveToNext()) consumer(cursor.toRoutePoint()) }
    }

    @Synchronized
    internal fun allSessions(): List<TripSession> = querySessions("1 = 1", emptyArray())

    @Synchronized
    internal fun session(tripId: String): TripSession? = querySessions("trip_id = ?", arrayOf(tripId)).firstOrNull()

    /** Returns the newest closed session without materializing trip history. */
    @Synchronized
    internal fun diagnosticLatestClosedSession(): TripSession? = querySessions(
        where = "state = ?",
        args = arrayOf(TripSession.STATE_CLOSED),
        orderBy = "started_at DESC, trip_id DESC",
        limit = 1
    ).firstOrNull()

    /** Streams a route under the store monitor and reports only redacted proof fields. */
    @Synchronized
    internal fun diagnosticRouteEvidence(tripId: String): TripRouteDiagnosticEvidence {
        val db = readableDb
        val chunks = chunkCount(db, tripId) != 0L
        val raw = db.rawQuery(
            "SELECT COUNT(*) FROM route_points WHERE trip_id = ?",
            arrayOf(tripId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        val storage = when {
            chunks && raw != 0L -> "mixed"
            chunks -> "chunks"
            raw != 0L -> "raw"
            else -> "none"
        }
        val accumulator = TripRouteDiagnosticAccumulator(tripId)
        forEachRoutePoint(tripId, accumulator::accept)
        return accumulator.finish(storage)
    }

    /** Replaces a route with raw rows atomically; used when a candidate must fall back to raw. */
    @Synchronized
    internal fun replaceRouteRaw(tripId: String, points: List<RoutePoint>) {
        require(points.all { it.tripId == tripId }) { "Route trip mismatch" }
        val db = writableDb
        var committed = false
        db.beginTransaction()
        try {
            db.delete("route_chunks", "trip_id = ?", arrayOf(tripId))
            db.delete("route_points", "trip_id = ?", arrayOf(tripId))
            points.forEach { point ->
                require(point.kind == RoutePoint.KIND_VALID || point.kind == RoutePoint.KIND_GAP || point.kind == RoutePoint.KIND_UNTRUSTED)
                db.insertOrThrow("route_points", null, point.toContentValues())
            }
            db.setTransactionSuccessful()
            committed = true
        } finally {
            db.endTransaction()
        }
        if (committed) markRouteChanged(tripId, 0L)
    }

    /** Copies one route directly into raw rows or bounded chunks, never raw-then-delete. */
    @Synchronized
    internal fun copyRouteFrom(source: TripStore, tripId: String, compress: Boolean, beforeWrite: () -> Unit = {}): Boolean {
        require(source !== this) { "Source and destination stores must differ" }
        val shouldCompress = compress && source.session(tripId)?.state == TripSession.STATE_CLOSED
        val db = writableDb
        fun clearRoute() {
            db.delete("route_chunks", "trip_id = ?", arrayOf(tripId))
            db.delete("route_points", "trip_id = ?", arrayOf(tripId))
        }
        if (!shouldCompress) {
            var committed = false
            db.beginTransaction()
            try {
                clearRoute()
                source.forEachRoutePoint(tripId) { point ->
                    beforeWrite()
                    db.insertOrThrow("route_points", null, point.toContentValues())
                }
                db.setTransactionSuccessful()
                committed = true
            } finally {
                db.endTransaction()
            }
            if (committed) markRouteChanged(tripId, 0L)
            return false
        }

        try {
            var committed = false
            db.beginTransaction()
            try {
                clearRoute()
                copyCompressedRoute(db, source, tripId, beforeWrite)
                db.setTransactionSuccessful()
                committed = true
            } finally {
                db.endTransaction()
            }
            if (committed) markRouteChanged(tripId, 0L)
            return true
        } catch (_: CompressionFallback) {
            var committed = false
            db.beginTransaction()
            try {
                clearRoute()
                source.forEachRoutePoint(tripId) { point ->
                    beforeWrite()
                    db.insertOrThrow("route_points", null, point.toContentValues())
                }
                db.setTransactionSuccessful()
                committed = true
            } finally {
                db.endTransaction()
            }
            if (committed) markRouteChanged(tripId, 0L)
            return false
        }
    }

    /** Replaces only a changed route tail in a candidate store, preserving its verified prefix. */
    @Synchronized
    internal fun copyRouteTailFrom(source: TripStore, tripId: String, firstSequence: Long, beforeWrite: () -> Unit = {}) {
        require(source !== this) { "Source and destination stores must differ" }
        require(firstSequence >= 0L) { "Route sequence must be non-negative" }
        val db = writableDb
        var committed = false
        db.beginTransaction()
        try {
            materializeChunksIfNeeded(db, tripId, beforeWrite)
            db.delete("route_points", "trip_id = ? AND sequence >= ?", arrayOf(tripId, firstSequence.toString()))
            source.forEachRoutePointFrom(tripId, firstSequence) { point ->
                beforeWrite()
                check(point.tripId == tripId && point.sequence >= firstSequence) { "Route tail mismatch" }
                db.insertOrThrow("route_points", null, point.toContentValues())
            }
            db.setTransactionSuccessful()
            committed = true
        } finally {
            db.endTransaction()
        }
        if (committed) markRouteChanged(tripId, firstSequence)
    }

    /** Holds the store monitor for a snapshot/cutover barrier. */
    @Synchronized
    internal fun <T> withLease(block: () -> T): T = block()

    @Synchronized
    fun closeDatabase() = helper.close()

    /** Closes the helper and rejects all normal access until a non-empty replacement is reopened. */
    @Synchronized
    internal fun closeForReplacement() {
        fileAccessSuspended = true
        helper.close()
    }

    @Synchronized
    fun reopenDatabase() {
        if (fileAccessSuspended) {
            check(databaseFile.isFile && databaseFile.length() > 0L) {
                "Trips database replacement file is missing or empty"
            }
            helper.writableDatabase
            fileAccessSuspended = false
            return
        }
        helper.writableDatabase
    }
    override fun close() = closeDatabase()

    private val writableDb: SQLiteDatabase
        get() {
            check(!fileAccessSuspended) { "Trips database access suspended for replacement" }
            return helper.writableDatabase
        }

    private val readableDb: SQLiteDatabase
        get() {
            check(!fileAccessSuspended) { "Trips database access suspended for replacement" }
            return helper.readableDatabase
        }

    private data class ChunkRow(
        val tripId: String,
        val chunkIndex: Int,
        val firstSequence: Long,
        val lastSequence: Long,
        val pointCount: Int,
        val firstObservedAt: String,
        val lastObservedAt: String,
        val uncompressedBytes: Int,
        val compressedBytes: Int,
        val payload: ByteArray
    ) {
        fun toEncodedChunk() = RouteChunkCodec.EncodedChunk(
            tripId,
            chunkIndex,
            firstSequence,
            lastSequence,
            pointCount,
            firstObservedAt,
            lastObservedAt,
            uncompressedBytes,
            compressedBytes,
            payload
        )
    }

    private data class ChunkBounds(val lastSequence: Long)

    private fun chunkCount(db: SQLiteDatabase, tripId: String): Long = db.rawQuery(
        "SELECT COUNT(*) FROM route_chunks WHERE trip_id = ?", arrayOf(tripId)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun readChunkBounds(db: SQLiteDatabase, tripId: String): ChunkBounds? {
        var expectedIndex = 0
        var expectedSequence: Long? = null
        var lastSequence: Long? = null
        db.rawQuery(
            "SELECT chunk_index, first_sequence, last_sequence, point_count FROM route_chunks WHERE trip_id = ? ORDER BY chunk_index",
            arrayOf(tripId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val index = cursor.getInt(0)
                val first = cursor.getLong(1)
                val last = cursor.getLong(2)
                val count = cursor.getInt(3)
                require(index == expectedIndex++) { "Chunk order mismatch" }
                if (expectedSequence != null) require(first == expectedSequence) { "Chunk sequence gap" }
                require(first >= 0L && count > 0 && first <= Long.MAX_VALUE - count + 1L && last == first + count - 1L) { "Chunk sequence bounds mismatch" }
                expectedSequence = last + 1L
                lastSequence = last
            }
        }
        return lastSequence?.let(::ChunkBounds)
    }

    private fun streamChunks(
        db: SQLiteDatabase,
        tripId: String,
        firstSequence: Long = 0L,
        consumer: (RoutePoint) -> Unit
    ) {
        var expectedIndex = 0
        var expectedSequence: Long? = null
        db.rawQuery(CHUNK_SELECT, arrayOf(tripId)).use { cursor ->
            while (cursor.moveToNext()) {
                val chunk = cursor.toChunkRow()
                require(chunk.chunkIndex == expectedIndex++) { "Chunk order mismatch" }
                if (expectedSequence != null) require(chunk.firstSequence == expectedSequence) { "Chunk sequence gap" }
                require(chunk.pointCount > 0 && chunk.firstSequence <= Long.MAX_VALUE - chunk.pointCount + 1L && chunk.lastSequence == chunk.firstSequence + chunk.pointCount - 1L) { "Chunk sequence bounds mismatch" }
                if (chunk.lastSequence >= firstSequence) {
                    RouteChunkCodec.forEachPoint(chunk.toEncodedChunk(), tripId) { point ->
                        if (point.sequence >= firstSequence) consumer(point)
                    }
                }
                expectedSequence = chunk.lastSequence + 1L
            }
        }
    }

    private fun Cursor.toChunkRow() = ChunkRow(
        tripId = getString(0),
        chunkIndex = getInt(1),
        firstSequence = getLong(2),
        lastSequence = getLong(3),
        pointCount = getInt(4),
        firstObservedAt = getString(5),
        lastObservedAt = getString(6),
        uncompressedBytes = getInt(7),
        compressedBytes = getInt(8),
        payload = getBlob(9)
    )

    private fun materializeChunksIfNeeded(db: SQLiteDatabase, tripId: String, beforeWrite: () -> Unit = {}) {
        if (chunkCount(db, tripId) == 0L) return
        val rawCount = db.rawQuery("SELECT COUNT(*) FROM route_points WHERE trip_id = ?", arrayOf(tripId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (rawCount != 0L) throw IllegalStateException("Trip has both raw and chunked route data")
        var count = 0
        streamChunks(db, tripId) { point ->
            beforeWrite()
            db.insertOrThrow("route_points", null, point.toContentValues())
            count++
        }
        require(count > 0) { "Chunk point count mismatch" }
        db.delete("route_chunks", "trip_id = ?", arrayOf(tripId))
    }

    private fun copyCompressedRoute(db: SQLiteDatabase, source: TripStore, tripId: String, beforeWrite: () -> Unit) {
        val current = ArrayList<RoutePoint>()
        var chunkIndex = 0
        var currentBytes = 4 + 1 + 4
        var expectedSequence: Long? = null
        var sawPoint = false
        source.forEachRoutePoint(tripId) { point ->
            sawPoint = true
            if (point.tripId != tripId || (expectedSequence != null && point.sequence != expectedSequence)) throw CompressionFallback()
            val pointBytes = RouteChunkCodec.encodedPointSize(point) ?: throw CompressionFallback()
            if (current.isNotEmpty() &&
                (current.size >= RouteChunkCodec.MAX_POINTS_PER_CHUNK || currentBytes + pointBytes > RouteChunkCodec.MAX_UNCOMPRESSED_BYTES)
            ) {
                val flushed = RouteChunkCodec.encodeSingleChunk(chunkIndex, current) ?: throw CompressionFallback()
                beforeWrite()
                db.insertOrThrow("route_chunks", null, flushed.toContentValues())
                chunkIndex++
                current.clear()
                currentBytes = 4 + 1 + 4
            }
            if (currentBytes + pointBytes > RouteChunkCodec.MAX_UNCOMPRESSED_BYTES) throw CompressionFallback()
            current.add(point)
            currentBytes += pointBytes
            expectedSequence = point.sequence + 1L
        }
        if (!sawPoint) throw CompressionFallback()
        val last = RouteChunkCodec.encodeSingleChunk(chunkIndex, current) ?: throw CompressionFallback()
        beforeWrite()
        db.insertOrThrow("route_chunks", null, last.toContentValues())
    }

    private class CompressionFallback : RuntimeException()

    private fun querySummaries(where: String, args: Array<String>): List<TripSummary> = readableDb.rawQuery(
        "SELECT trip_id, started_at, ended_at, duration_ms, distance_km, start_soc, end_soc, energy_kwh, average_consumption_kwh_per_100km, quality, movement_observed FROM trip_sessions $where ORDER BY started_at DESC", args
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTripSummary()) } }

    private fun querySessions(
        where: String,
        args: Array<String>,
        orderBy: String = "started_at",
        limit: Int? = null
    ): List<TripSession> {
        require(limit == null || limit > 0) { "Session query limit must be positive" }
        val limitClause = limit?.let { " LIMIT $it" }.orEmpty()
        return readableDb.rawQuery(
            "SELECT trip_id, state, started_at, ended_at, start_elapsed_ms, end_elapsed_ms, start_boot_id, end_boot_id, start_segment_id, end_segment_id, movement_observed, start_soc, end_soc, start_odometer_km, last_odometer_km, start_trip_energy_kwh, last_trip_energy_kwh, duration_ms, distance_km, energy_kwh, average_consumption_kwh_per_100km, termination, quality, telegram_eligible, telegram_enqueued FROM trip_sessions WHERE $where ORDER BY $orderBy$limitClause",
            args
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTripSession()) } }
    }

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
        // The schema permits valid/gap; retain untrusted coordinates as a valid-shaped row and recover the model kind from quality.
        put("trip_id", tripId); put("sequence", sequence); put("kind", if (kind == RoutePoint.KIND_UNTRUSTED) RoutePoint.KIND_VALID else kind); put("observed_at", observedAt); putNullable("elapsed_ms", elapsedMs); putNullable("receive_wall_time_ms", receiveWallTimeMs)
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
        val quality = getString(15)
        return RoutePoint(getString(0), getLong(1), if (kind == RoutePoint.KIND_VALID && quality.startsWith("untrusted:")) RoutePoint.KIND_UNTRUSTED else kind, getString(3), getLongOrNull(4), getLongOrNull(5), getStringOrNull(6), getStringOrNull(7), getDoubleOrNull(8), getDoubleOrNull(9), getDoubleOrNull(10), getDoubleOrNull(11), getDoubleOrNull(12), getDoubleOrNull(13), getDoubleOrNull(14), quality, getInt(16) == 1, getInt(17) == 1)
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun Cursor.getDoubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
    private fun isValidCoordinate(latitude: Double?, longitude: Double?) = latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun RouteChunkCodec.EncodedChunk.toContentValues() = ContentValues().apply {
        put("trip_id", tripId)
        put("chunk_index", chunkIndex)
        put("first_sequence", firstSequence)
        put("last_sequence", lastSequence)
        put("point_count", pointCount)
        put("first_observed_at", firstObservedAt)
        put("last_observed_at", lastObservedAt)
        put("uncompressed_size", uncompressedBytes)
        put("compressed_size", compressedBytes)
        put("payload", payload)
    }

    companion object {
        private const val CHUNK_SELECT =
            "SELECT trip_id, chunk_index, first_sequence, last_sequence, point_count, first_observed_at, last_observed_at, uncompressed_size, compressed_size, payload FROM route_chunks WHERE trip_id = ? ORDER BY chunk_index"
        private const val RAW_ROUTE_FROM_SELECT =
            "SELECT trip_id, sequence, kind, observed_at, elapsed_ms, receive_wall_time_ms, boot_id, segment_id, latitude, longitude, accuracy_m, speed_kmh, instantaneous_consumption_kwh_per_100km, altitude_m, bearing_deg, quality, is_first, is_final FROM route_points WHERE trip_id = ? AND sequence >= ? ORDER BY sequence"
    }
}

object TripId {
    fun forPowerSession(bootId: String, startElapsedMs: Long): String = "${bootId.trim()}:$startElapsedMs"
}
