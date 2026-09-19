package com.bydcollector.collector.data.trips

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.data.energy.EnergyRuntimeRow
import com.bydcollector.collector.data.energy.EnergyRuntimeStorage
import com.bydcollector.collector.data.energy.EnergySnapshot
import com.bydcollector.collector.data.energy.EnergyStateCodec
import java.time.Instant

class TripStore(private val helper: TripDatabaseHelper) : AutoCloseable, EnergyRuntimeStorage {
    /** Serializes all TripStore access; Step 8 leases this monitor for cutover. */
    private val changedTripSequences = linkedMapOf<String, Long?>()
    private var changeTracking = false
    private var fileAccessSuspended = false
    private var historicalBackfillChanged = false
    private var tripCompletionsChanged = false

    @Synchronized
    internal fun beginChangeTracking() {
        check(!changeTracking) { "Trips change tracking already active" }
        changedTripSequences.clear()
        historicalBackfillChanged = false
        tripCompletionsChanged = false
        changeTracking = true
    }

    @Synchronized
    internal fun changedTripSequences(): Map<String, Long?> = changedTripSequences.toMap()

    @Synchronized
    internal fun historicalBackfillChanged(): Boolean = historicalBackfillChanged

    @Synchronized
    internal fun tripCompletionsChanged(): Boolean = tripCompletionsChanged

    @Synchronized
    internal fun endChangeTracking() {
        check(changeTracking) { "Trips change tracking is not active" }
        changeTracking = false
        changedTripSequences.clear()
        historicalBackfillChanged = false
        tripCompletionsChanged = false
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

    private fun markHistoricalBackfillChanged() {
        if (changeTracking) historicalBackfillChanged = true
    }

    private fun markTripCompletionsChanged() {
        if (changeTracking) tripCompletionsChanged = true
    }

    @Synchronized
    internal fun nextHistoricalEnergyCandidate(): TripSession? = querySessions(
        "state = ? AND ended_at IS NOT NULL AND discharged_kwh IS NULL AND regenerated_kwh IS NULL " +
            "AND net_kwh IS NULL AND energy_covered_ms IS NULL AND energy_uncovered_ms IS NULL " +
            "AND energy_partial IS NULL AND energy_observed_at IS NULL " +
            "AND $HISTORICAL_PROGRESS_ELIGIBLE",
        arrayOf(TripSession.STATE_CLOSED),
        limit = 1
    ).firstOrNull()

    @Synchronized
    internal fun historicalEnergyCandidatePage(afterTripId: String?, limit: Int): List<TripSession> {
        require(limit in 1..100)
        val eligibility = "state = ? AND ended_at IS NOT NULL AND discharged_kwh IS NULL AND regenerated_kwh IS NULL " +
            "AND net_kwh IS NULL AND energy_covered_ms IS NULL AND energy_uncovered_ms IS NULL " +
            "AND energy_partial IS NULL AND energy_observed_at IS NULL " +
            "AND $HISTORICAL_PROGRESS_ELIGIBLE"
        return if (afterTripId == null) {
            querySessions(eligibility, arrayOf(TripSession.STATE_CLOSED), "trip_id", limit)
        } else {
            querySessions("$eligibility AND trip_id > ?", arrayOf(TripSession.STATE_CLOSED, afterTripId), "trip_id", limit)
        }
    }

    @Synchronized
    internal fun closedTripBoundsPage(afterTripId: String?, limit: Int): List<TripSession> {
        require(limit in 1..100)
        return if (afterTripId == null) {
            querySessions("state = ? AND ended_at IS NOT NULL", arrayOf(TripSession.STATE_CLOSED), "trip_id", limit)
        } else {
            querySessions(
                "state = ? AND ended_at IS NOT NULL AND trip_id > ?",
                arrayOf(TripSession.STATE_CLOSED, afterTripId),
                "trip_id",
                limit
            )
        }
    }

    @Synchronized
    internal fun commitHistoricalEnergyBackfill(record: HistoricalEnergyBackfillRecord, snapshot: EnergySnapshot?): Boolean {
        require(record.tripId.isNotBlank() && record.startedAt.isNotBlank() && record.endedAt.isNotBlank())
        require(record.sourceIdentity.isNotBlank() && record.reason.isNotBlank() && record.updatedAt.isNotBlank())
        require(record.algorithmVersion == HistoricalEnergyBackfillRecord.ALGORITHM_VERSION)
        require((snapshot == null) == (record.outcome == "rejected"))
        if (snapshot != null) require(snapshot.powerSessionId == record.tripId && !snapshot.energyPartial && snapshot.energyCoveredMs > 0L)
        val db = writableDb
        var committed = false
        db.beginTransaction()
        try {
            val eligibleWhere = "trip_id = ? AND state = 'closed' AND started_at = ? AND ended_at = ? " +
                "AND discharged_kwh IS NULL AND regenerated_kwh IS NULL AND net_kwh IS NULL " +
                "AND energy_covered_ms IS NULL AND energy_uncovered_ms IS NULL AND energy_partial IS NULL " +
                "AND energy_observed_at IS NULL AND $HISTORICAL_PROGRESS_ELIGIBLE"
            val args = arrayOf(record.tripId, record.startedAt, record.endedAt)
            val eligible = if (snapshot == null) {
                db.rawQuery("SELECT 1 FROM trip_sessions WHERE $eligibleWhere", args).use { it.moveToFirst() }
            } else {
                db.update("trip_sessions", snapshot.toEnergyContentValues(), eligibleWhere, args) == 1
            }
            if (eligible) {
                db.delete("historical_energy_backfill", "trip_id = ? AND outcome = 'rejected' AND reason = ? AND algorithm_version < ?",
                    arrayOf(record.tripId, HistoricalEnergyBackfillRecord.RETRY_REASON, record.algorithmVersion.toString()))
                db.insertOrThrow("historical_energy_backfill", null, record.toContentValues())
                db.setTransactionSuccessful()
                committed = true
            }
        } finally {
            db.endTransaction()
        }
        if (committed) {
            if (snapshot != null) markSessionChanged(record.tripId)
            markHistoricalBackfillChanged()
        }
        return committed
    }

    @Synchronized
    internal fun historicalEnergyBackfillRecords(): List<HistoricalEnergyBackfillRecord> = readableDb.rawQuery(
        "SELECT trip_id, started_at, ended_at, source_identity, outcome, reason, updated_at, algorithm_version FROM historical_energy_backfill ORDER BY trip_id",
        null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toHistoricalEnergyBackfillRecord()) } }

    @Synchronized
    internal fun replaceHistoricalEnergyBackfillRecords(records: List<HistoricalEnergyBackfillRecord>) {
        val db = writableDb
        db.beginTransaction()
        try {
            db.delete("historical_energy_backfill", null, null)
            records.forEach { db.insertOrThrow("historical_energy_backfill", null, it.toContentValues()) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun upsertSession(session: TripSession) {
        upsertSessionInternal(session, preserveDurableEnergy = true)
    }

    /** Copies an authoritative source row without overlaying this candidate's older checkpoint. */
    @Synchronized
    internal fun copySessionFrom(source: TripStore, tripId: String) {
        require(source !== this) { "Source and destination stores must differ" }
        upsertSessionInternal(
            requireNotNull(source.session(tripId)) { "Source trip disappeared" },
            preserveDurableEnergy = false
        )
    }

    private fun upsertSessionInternal(session: TripSession, preserveDurableEnergy: Boolean) {
        val db = writableDb
        var committed = false
        db.beginTransaction()
        try {
            upsertSession(db, session, preserveDurableEnergy)
            db.setTransactionSuccessful()
            committed = true
        } finally {
            db.endTransaction()
        }
        if (committed) markSessionChanged(session.tripId)
    }

    private fun upsertSession(db: SQLiteDatabase, session: TripSession, preserveDurableEnergy: Boolean) {
        if (session.state == TripSession.STATE_OPEN) materializeChunksIfNeeded(db, session.tripId)
        val durableSnapshot = durableEnergySnapshot(db).takeIf { preserveDurableEnergy }
        val values = (durableSnapshot?.let { session.withEnergySnapshot(it) } ?: session).toContentValues()
        if (db.update("trip_sessions", values, "trip_id = ?", arrayOf(session.tripId)) == 0) {
            db.insertOrThrow("trip_sessions", null, values)
        }
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

    /** Atomically persists a CLOSED session and its immutable Telegram completion intent. */
    @Synchronized
    fun closeSessionWithCompletion(
        session: TripSession?,
        completion: TripCompletionIntent?
    ): TripCompletionIntent? {
        require(session == null || session.state == TripSession.STATE_CLOSED) { "Completion session must be closed" }
        val db = writableDb
        var result: TripCompletionIntent? = null
        var inserted = false
        db.beginTransaction()
        try {
            session?.let { upsertSession(db, it, preserveDurableEnergy = true) }
            if (completion != null) {
                result = completionByIdentity(db, completion.identity)
                if (result != null) {
                    require(completion.sequence == 0L || completion.sequence == result?.sequence) {
                        "Completion retry sequence does not match stored identity"
                    }
                } else {
                    require(completion.sequence == 0L) { "Completion sequence is assigned by storage" }
                    val highWater = completionWatermark(db)
                    check(highWater < Long.MAX_VALUE) { "Trip completion sequence exhausted" }
                    val stored = completion.copy(sequence = highWater + 1L)
                    val values = ContentValues().apply {
                        put("sequence", stored.sequence)
                        put("identity", stored.identity)
                        put("observed_at", stored.observedAt)
                        put("payload", TripCompletionIntentCodec.encode(stored))
                    }
                    db.insertOrThrow("trip_completion_outbox", null, values)
                    check(
                        db.update(
                            "trip_completion_state",
                            ContentValues().apply { put("high_water_sequence", stored.sequence) },
                            "singleton_id = 1 AND high_water_sequence = ?",
                            arrayOf(highWater.toString())
                        ) == 1
                    ) { "Trip completion high-water update failed" }
                    result = stored
                    inserted = true
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        session?.let { markSessionChanged(it.tripId) }
        if (inserted) markTripCompletionsChanged()
        return result
    }

    @Synchronized
    fun completionWatermark(): Long = completionWatermark(readableDb)

    @Synchronized
    fun pendingCompletions(
        upToSequence: Long,
        limit: Int = DEFAULT_COMPLETION_LIMIT
    ): List<TripCompletionIntent> {
        require(upToSequence >= 0L) { "Completion watermark must be non-negative" }
        require(limit in 1..MAX_COMPLETION_LIMIT) { "Completion limit must be 1..$MAX_COMPLETION_LIMIT" }
        return readableDb.rawQuery(
            "SELECT sequence, identity, observed_at, payload FROM trip_completion_outbox " +
                "WHERE sequence <= ? ORDER BY sequence LIMIT ?",
            arrayOf(upToSequence.toString(), limit.toString())
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTripCompletionIntent()) } }
    }

    @Synchronized
    fun acknowledgeCompletion(sequence: Long, identity: String): Boolean {
        if (sequence <= 0L || identity.isBlank()) return false
        val deleted = writableDb.delete(
            "trip_completion_outbox",
            "sequence = ? AND identity = ?",
            arrayOf(sequence.toString(), identity)
        ) == 1
        if (deleted) markTripCompletionsChanged()
        return deleted
    }

    @Synchronized
    internal fun allPendingCompletions(): List<TripCompletionIntent> = readableDb.rawQuery(
        "SELECT sequence, identity, observed_at, payload FROM trip_completion_outbox ORDER BY sequence",
        null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTripCompletionIntent()) } }

    @Synchronized
    internal fun replaceCompletionState(highWater: Long, completions: List<TripCompletionIntent>) {
        require(highWater >= 0L)
        require(completions.all { it.sequence in 1L..highWater })
        require(completions.map { it.sequence }.toSet().size == completions.size)
        require(completions.map { it.identity }.toSet().size == completions.size)
        val db = writableDb
        db.beginTransaction()
        try {
            db.delete("trip_completion_outbox", null, null)
            check(
                db.update(
                    "trip_completion_state",
                    ContentValues().apply { put("high_water_sequence", highWater) },
                    "singleton_id = 1",
                    null
                ) == 1
            ) { "Trip completion state is missing" }
            completions.sortedBy { it.sequence }.forEach { completion ->
                db.insertOrThrow(
                    "trip_completion_outbox",
                    null,
                    ContentValues().apply {
                        put("sequence", completion.sequence)
                        put("identity", completion.identity)
                        put("observed_at", completion.observedAt)
                        put("payload", TripCompletionIntentCodec.encode(completion))
                    }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun readEnergyRuntimeRow(): EnergyRuntimeRow? = readableDb.rawQuery(
        "SELECT state_json, pending_projection_json, updated_at FROM energy_runtime_state WHERE singleton_id = 1",
        null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else EnergyRuntimeRow(
            stateJson = cursor.getString(0),
            pendingProjectionJson = cursor.getStringOrNull(1),
            updatedAt = cursor.getString(2)
        )
    }

    @Synchronized
    override fun quarantineEnergyRuntimeRow(row: EnergyRuntimeRow, reason: String, updatedAt: String) {
        val bytes = (row.stateJson + "\u0000" + row.pendingProjectionJson.orEmpty()).toByteArray(Charsets.UTF_8)
        val identity = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val values = row.toContentValues().apply {
            put("identity", identity)
            put("reason", reason)
            put("quarantined_at", updatedAt)
        }
        // A failed evidence write must prevent replacement of the poisoned checkpoint.
        writableDb.insertWithOnConflict("energy_runtime_quarantine", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        check(readableDb.rawQuery("SELECT 1 FROM energy_runtime_quarantine WHERE identity = ?", arrayOf(identity))
            .use { it.moveToFirst() }) { "Energy quarantine was not persisted" }
    }

    @Synchronized
    internal fun energyQuarantineRecords(): List<EnergyQuarantineRecord> = readableDb.rawQuery(
        "SELECT identity, state_json, pending_projection_json, updated_at, reason, quarantined_at FROM energy_runtime_quarantine ORDER BY identity", null
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(EnergyQuarantineRecord(cursor.getString(0),
            EnergyRuntimeRow(cursor.getString(1), cursor.getStringOrNull(2), cursor.getString(3)),
            cursor.getString(4), cursor.getString(5)))
    } }

    @Synchronized
    internal fun replaceEnergyQuarantineRecords(records: List<EnergyQuarantineRecord>) {
        val db = writableDb
        db.beginTransaction()
        try {
            db.delete("energy_runtime_quarantine", null, null)
            records.forEach { record ->
                val values = record.row.toContentValues().apply {
                    put("identity", record.identity); put("reason", record.reason); put("quarantined_at", record.quarantinedAt)
                }
                db.insertOrThrow("energy_runtime_quarantine", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun commitEnergyRuntimeRow(stateJson: String, pendingProjectionJson: String?, updatedAt: String) {
        require(stateJson.isNotBlank() && updatedAt.isNotBlank() && updatedAt.length <= 128)
        val snapshot = EnergyStateCodec.decodeState(stateJson).currentSnapshot
        pendingProjectionJson?.let { encoded ->
            val pending = EnergyStateCodec.decodeProjection(encoded)
            require(pending.snapshot == snapshot) {
                "Energy pending projection does not match checkpoint"
            }
        }
        val db = writableDb
        var mirroredTripId: String? = null
        db.beginTransaction()
        try {
            val values = EnergyRuntimeRow(stateJson, pendingProjectionJson, updatedAt).toContentValues()
            if (db.update("energy_runtime_state", values, "singleton_id = 1", null) == 0) {
                values.put("singleton_id", 1)
                db.insertOrThrow("energy_runtime_state", null, values)
            }
            snapshot?.powerSessionId?.let { tripId ->
                if (db.update("trip_sessions", snapshot.toEnergyContentValues(), "trip_id = ?", arrayOf(tripId)) == 1) {
                    mirroredTripId = tripId
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        mirroredTripId?.let(::markSessionChanged)
    }

    @Synchronized
    override fun clearEnergyPending(expectedPendingJson: String, updatedAt: String): Boolean {
        require(expectedPendingJson.isNotBlank() && updatedAt.isNotBlank())
        val db = writableDb
        var changed = false
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                putNull("pending_projection_json")
                put("updated_at", updatedAt)
            }
            changed = db.update(
                "energy_runtime_state",
                values,
                "singleton_id = 1 AND pending_projection_json = ?",
                arrayOf(expectedPendingJson)
            ) == 1
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    /** Exact bounded copy used by Trips compression for its one service-state row. */
    @Synchronized
    internal fun replaceEnergyRuntimeRow(row: EnergyRuntimeRow?) {
        val snapshot = row?.let { EnergyStateCodec.decodeState(it.stateJson).currentSnapshot }
        row?.pendingProjectionJson?.let { encoded ->
            require(EnergyStateCodec.decodeProjection(encoded).snapshot == snapshot) {
                "Energy pending projection does not match checkpoint"
            }
        }
        val db = writableDb
        db.beginTransaction()
        try {
            db.delete("energy_runtime_state", null, null)
            if (row != null) {
                val values = row.toContentValues().apply { put("singleton_id", 1) }
                db.insertOrThrow("energy_runtime_state", null, values)
            }
            snapshot?.powerSessionId?.let { tripId ->
                db.update("trip_sessions", snapshot.toEnergyContentValues(), "trip_id = ?", arrayOf(tripId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

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
        "SELECT trip_id, started_at, ended_at, duration_ms, distance_km, start_soc, end_soc, energy_kwh, average_consumption_kwh_per_100km, discharged_kwh, regenerated_kwh, net_kwh, energy_covered_ms, energy_uncovered_ms, energy_partial, energy_observed_at, quality, movement_observed FROM trip_sessions $where ORDER BY started_at DESC", args
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
            "SELECT trip_id, state, started_at, ended_at, start_elapsed_ms, end_elapsed_ms, start_boot_id, end_boot_id, start_segment_id, end_segment_id, movement_observed, start_soc, end_soc, start_odometer_km, last_odometer_km, start_trip_energy_kwh, last_trip_energy_kwh, duration_ms, distance_km, energy_kwh, average_consumption_kwh_per_100km, discharged_kwh, regenerated_kwh, net_kwh, energy_covered_ms, energy_uncovered_ms, energy_partial, energy_observed_at, termination, quality, telegram_eligible, telegram_enqueued FROM trip_sessions WHERE $where ORDER BY $orderBy$limitClause",
            args
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTripSession()) } }
    }

    private fun TripSession.toContentValues() = ContentValues().apply {
        put("trip_id", tripId); put("state", state); put("started_at", startedAt); put("ended_at", endedAt)
        putNullable("start_elapsed_ms", startElapsedMs); putNullable("end_elapsed_ms", endElapsedMs); put("start_boot_id", startBootId); put("end_boot_id", endBootId)
        put("start_segment_id", startSegmentId); put("end_segment_id", endSegmentId); put("movement_observed", if (movementObserved) 1 else 0)
        putNullable("start_soc", startSoc); putNullable("end_soc", endSoc); putNullable("start_odometer_km", startOdometerKm); putNullable("last_odometer_km", lastOdometerKm); putNullable("start_trip_energy_kwh", startTripEnergyKwh); putNullable("last_trip_energy_kwh", lastTripEnergyKwh)
        putNullable("duration_ms", durationMs); putNullable("distance_km", distanceKm); putNullable("energy_kwh", energyKwh)
        putNullable("average_consumption_kwh_per_100km", averageConsumptionKwhPer100Km)
        putNullable("discharged_kwh", dischargedKwh); putNullable("regenerated_kwh", regeneratedKwh); putNullable("net_kwh", netKwh)
        putNullable("energy_covered_ms", energyCoveredMs); putNullable("energy_uncovered_ms", energyUncoveredMs)
        when (energyPartial) { null -> putNull("energy_partial"); else -> put("energy_partial", if (energyPartial) 1 else 0) }
        put("energy_observed_at", energyObservedAt); put("termination", termination); put("quality", quality)
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

    private fun Cursor.toTripSummary() = TripSummary(
        tripId = getString(0),
        startedAt = getString(1),
        endedAt = getStringOrNull(2),
        durationMs = getLongOrNull(3),
        distanceKm = getDoubleOrNull(4),
        startSoc = getDoubleOrNull(5),
        endSoc = getDoubleOrNull(6),
        energyKwh = getDoubleOrNull(7),
        averageConsumptionKwhPer100Km = getDoubleOrNull(8),
        dischargedKwh = getDoubleOrNull(9),
        regeneratedKwh = getDoubleOrNull(10),
        netKwh = getDoubleOrNull(11),
        energyCoveredMs = getLongOrNull(12),
        energyUncoveredMs = getLongOrNull(13),
        energyPartial = getBooleanOrNull(14),
        energyObservedAt = getStringOrNull(15),
        quality = getString(16),
        movementObserved = getInt(17) == 1
    )

    private fun Cursor.toTripSession() = TripSession(
        getString(0), getString(1), getString(2), getStringOrNull(3), getLongOrNull(4), getLongOrNull(5), getStringOrNull(6), getStringOrNull(7),
        getStringOrNull(8), getStringOrNull(9), getInt(10) == 1, getDoubleOrNull(11), getDoubleOrNull(12), getDoubleOrNull(13), getDoubleOrNull(14),
        getDoubleOrNull(15), getDoubleOrNull(16), getLongOrNull(17), getDoubleOrNull(18), getDoubleOrNull(19), getDoubleOrNull(20),
        getDoubleOrNull(21), getDoubleOrNull(22), getDoubleOrNull(23), getLongOrNull(24), getLongOrNull(25), getBooleanOrNull(26),
        getStringOrNull(27), getStringOrNull(28), getString(29), getInt(30) == 1, getInt(31) == 1
    )

    private fun Cursor.toTripCompletionIntent(): TripCompletionIntent {
        val sequence = getLong(0)
        val identity = getString(1)
        val observedAt = getString(2)
        return TripCompletionIntentCodec.decode(getString(3)).also { intent ->
            check(intent.sequence == sequence && intent.identity == identity && intent.observedAt == observedAt) {
                "Trip completion payload identity mismatch"
            }
        }
    }

    private fun completionByIdentity(db: SQLiteDatabase, identity: String): TripCompletionIntent? = db.rawQuery(
        "SELECT sequence, identity, observed_at, payload FROM trip_completion_outbox WHERE identity = ?",
        arrayOf(identity)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toTripCompletionIntent() else null }

    private fun completionWatermark(db: SQLiteDatabase): Long = db.rawQuery(
        "SELECT high_water_sequence FROM trip_completion_state WHERE singleton_id = 1",
        null
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Trip completion state is missing" }
        cursor.getLong(0).also { check(it >= 0L) { "Trip completion high-water is invalid" } }
    }

    private fun Cursor.toRoutePoint(): RoutePoint {
        val kind = getString(2)
        val quality = getString(15)
        return RoutePoint(getString(0), getLong(1), if (kind == RoutePoint.KIND_VALID && quality.startsWith("untrusted:")) RoutePoint.KIND_UNTRUSTED else kind, getString(3), getLongOrNull(4), getLongOrNull(5), getStringOrNull(6), getStringOrNull(7), getDoubleOrNull(8), getDoubleOrNull(9), getDoubleOrNull(10), getDoubleOrNull(11), getDoubleOrNull(12), getDoubleOrNull(13), getDoubleOrNull(14), quality, getInt(16) == 1, getInt(17) == 1)
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun Cursor.getDoubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
    private fun Cursor.getBooleanOrNull(index: Int): Boolean? = if (isNull(index)) null else getInt(index) == 1
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

    private fun EnergyRuntimeRow.toContentValues() = ContentValues().apply {
        put("state_json", stateJson)
        if (pendingProjectionJson == null) putNull("pending_projection_json") else put("pending_projection_json", pendingProjectionJson)
        put("updated_at", updatedAt)
    }

    private fun HistoricalEnergyBackfillRecord.toContentValues() = ContentValues().apply {
        put("trip_id", tripId)
        put("started_at", startedAt)
        put("ended_at", endedAt)
        put("source_identity", sourceIdentity)
        put("outcome", outcome)
        put("reason", reason)
        put("updated_at", updatedAt)
        put("algorithm_version", algorithmVersion)
    }

    private fun Cursor.toHistoricalEnergyBackfillRecord() = HistoricalEnergyBackfillRecord(
        tripId = getString(0),
        startedAt = getString(1),
        endedAt = getString(2),
        sourceIdentity = getString(3),
        outcome = getString(4),
        reason = getString(5),
        updatedAt = getString(6),
        algorithmVersion = getInt(7)
    )

    private fun EnergySnapshot.toEnergyContentValues() = ContentValues().apply {
        putNullable("discharged_kwh", dischargedKwh)
        putNullable("regenerated_kwh", regeneratedKwh)
        putNullable("net_kwh", netKwh)
        put("energy_covered_ms", energyCoveredMs)
        put("energy_uncovered_ms", energyUncoveredMs)
        put("energy_partial", if (energyPartial) 1 else 0)
        put("energy_observed_at", observedAt)
    }

    private fun durableEnergySnapshot(db: SQLiteDatabase): EnergySnapshot? = db.rawQuery(
        "SELECT state_json FROM energy_runtime_state WHERE singleton_id = 1",
        null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else try {
            EnergyStateCodec.decodeState(cursor.getString(0)).currentSnapshot
        } catch (_: org.json.JSONException) {
            // Energy recovery owns preservation/repair; optional projection parsing
            // must not prevent a valid Trips close. SQLite failures still propagate.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val DEFAULT_COMPLETION_LIMIT = 100
        const val MAX_COMPLETION_LIMIT = 1_000
        private const val HISTORICAL_PROGRESS_ELIGIBLE =
            "NOT EXISTS (SELECT 1 FROM historical_energy_backfill b WHERE b.trip_id = trip_sessions.trip_id " +
                "AND NOT (b.outcome = 'rejected' AND b.reason = '${HistoricalEnergyBackfillRecord.RETRY_REASON}' " +
                "AND b.algorithm_version < ${HistoricalEnergyBackfillRecord.ALGORITHM_VERSION}))"
        private const val CHUNK_SELECT =
            "SELECT trip_id, chunk_index, first_sequence, last_sequence, point_count, first_observed_at, last_observed_at, uncompressed_size, compressed_size, payload FROM route_chunks WHERE trip_id = ? ORDER BY chunk_index"
        private const val RAW_ROUTE_FROM_SELECT =
            "SELECT trip_id, sequence, kind, observed_at, elapsed_ms, receive_wall_time_ms, boot_id, segment_id, latitude, longitude, accuracy_m, speed_kmh, instantaneous_consumption_kwh_per_100km, altitude_m, bearing_deg, quality, is_first, is_final FROM route_points WHERE trip_id = ? AND sequence >= ? ORDER BY sequence"
    }
}

object TripId {
    fun forPowerSession(bootId: String, startElapsedMs: Long): String = "${bootId.trim()}:$startElapsedMs"
}
