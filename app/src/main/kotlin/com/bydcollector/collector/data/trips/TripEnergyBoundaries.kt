package com.bydcollector.collector.data.trips

import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.data.energy.EnergyCoveredInterval
import com.bydcollector.collector.data.energy.EnergyIntegrationQuality
import com.bydcollector.collector.data.energy.EnergySnapshot
import com.bydcollector.collector.data.energy.EnergyTotals
import org.json.JSONArray
import org.json.JSONObject

/** Accepted integration checkpoints, retained only while their session can still form a report. */
internal object TripEnergyBoundaries {
    private const val TABLE = "trip_energy_boundaries"

    fun create(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL, boot_id TEXT NOT NULL, elapsed_ms INTEGER NOT NULL,
                checkpoint TEXT NOT NULL,
                UNIQUE(session_id, boot_id, elapsed_ms)
            )
        """.trimIndent())
    }

    fun append(db: SQLiteDatabase, snapshots: Sequence<EnergySnapshot>) {
        db.compileStatement(
            "INSERT OR IGNORE INTO $TABLE(session_id, boot_id, elapsed_ms, checkpoint) VALUES(?, ?, ?, ?)"
        ).use { statement ->
            snapshots.forEach { point ->
                val session = point.powerSessionId ?: return@forEach
                statement.bindString(1, session)
                statement.bindString(2, point.sourceBootId)
                statement.bindLong(3, point.sourceElapsedMs)
                statement.bindString(4, encode(point))
                statement.executeInsert()
            }
        }
    }

    fun find(db: SQLiteDatabase, session: String, boot: String, elapsed: Long, through: Long): EnergySnapshot? =
        db.rawQuery(
            """SELECT elapsed_ms,checkpoint FROM $TABLE WHERE session_id=? AND boot_id=? AND elapsed_ms BETWEEN ? AND ?
                ORDER BY elapsed_ms LIMIT 1""".trimIndent(),
            arrayOf(session, boot, elapsed.toString(), through.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else decode(session, boot, cursor.getLong(0), cursor.getString(1)).atBoundary(boot, elapsed)
        }

    // The index already holds the session, boot and clock. Store only the inputs needed to split the interval.
    private fun encode(point: EnergySnapshot): String = JSONArray()
        .put(point.sourceIdentity).put(point.startedAt).put(point.observedAt).put(point.active)
        .put(point.dischargedKwh ?: JSONObject.NULL).put(point.regeneratedKwh ?: JSONObject.NULL)
        .put(point.energyCoveredMs).put(point.energyUncoveredMs).put(point.energyPartial)
        .put(point.integrationQuality.name).put(point.reason)
        .put(point.coveredInterval?.let { interval ->
            val totals = interval.totalsBefore
            JSONArray().put(interval.startedElapsedMs).put(interval.startPowerKw).put(interval.endPowerKw)
                .put(totals.dischargedKwh).put(totals.regeneratedKwh).put(totals.coveredMs)
                .put(totals.uncoveredMs).put(totals.partial).put(interval.held)
        } ?: JSONObject.NULL).toString()

    private fun decode(session: String, boot: String, elapsed: Long, encoded: String): EnergySnapshot {
        val row = JSONArray(encoded)
        val source = row.getString(0)
        val discharge = if (row.isNull(4)) null else row.getDouble(4)
        val regen = if (row.isNull(5)) null else row.getDouble(5)
        return EnergySnapshot(
            snapshotId = "energy:$source", sourceIdentity = source,
            powerSessionId = session, startedAt = row.getString(1), observedAt = row.getString(2),
            sourceBootId = boot, sourceElapsedMs = elapsed, active = row.getBoolean(3),
            dischargedKwh = discharge, regeneratedKwh = regen, netKwh = discharge?.minus(requireNotNull(regen)),
            energyCoveredMs = row.getLong(6), energyUncoveredMs = row.getLong(7), energyPartial = row.getBoolean(8),
            integrationQuality = EnergyIntegrationQuality.valueOf(row.getString(9)), reason = row.getString(10),
            coveredInterval = row.optJSONArray(11)?.let {
                EnergyCoveredInterval(it.getLong(0), it.getDouble(1), it.getDouble(2),
                    EnergyTotals(it.getDouble(3), it.getDouble(4), it.getLong(5), it.getLong(6), it.getBoolean(7)),
                    it.getBoolean(8))
            }
        )
    }

    fun prune(db: SQLiteDatabase, activeSession: String?) {
        if (activeSession == null) db.delete(TABLE, null, null)
        else db.delete(TABLE, "session_id<>?", arrayOf(activeSession))
    }

    private fun sessions(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT DISTINCT session_id FROM $TABLE", null).use { rows ->
            buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
        }

    /** Copy from a stable source: either the immutable snapshot or the final writer lease. */
    fun sync(source: SQLiteDatabase, target: SQLiteDatabase, checkReserve: () -> Unit) {
        val watermark = target.rawQuery("SELECT COALESCE(MAX(id),0) FROM $TABLE", null).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
        target.beginTransaction()
        try {
            (sessions(target) - sessions(source)).forEach {
                target.delete(TABLE, "session_id=?", arrayOf(it))
            }
            source.rawQuery("SELECT id,session_id,boot_id,elapsed_ms,checkpoint FROM $TABLE WHERE id>? ORDER BY id",
                arrayOf(watermark.toString())).use { rows ->
                target.compileStatement("INSERT INTO $TABLE VALUES(?,?,?,?,?)").use { insert ->
                    var count = 0
                    while (rows.moveToNext()) {
                        if (count++ % 512 == 0) checkReserve()
                        insert.bindLong(1, rows.getLong(0))
                        insert.bindString(2, rows.getString(1))
                        insert.bindString(3, rows.getString(2))
                        insert.bindLong(4, rows.getLong(3))
                        insert.bindString(5, rows.getString(4))
                        check(insert.executeInsert() == rows.getLong(0)) { "Trips energy boundary copy failed" }
                    }
                }
            }
            check(signature(source) == signature(target)) { "Trips energy boundary rows changed during copy" }
            target.setTransactionSuccessful()
        } finally {
            target.endTransaction()
        }
    }

    fun signature(db: SQLiteDatabase): Pair<Long, Long> =
        db.rawQuery("SELECT COUNT(*),COALESCE(MAX(id),0) FROM $TABLE", null).use {
            check(it.moveToFirst())
            it.getLong(0) to it.getLong(1)
        }
}
