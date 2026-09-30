package com.bydcollector.collector.data.local

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val EC_MEAN_MAX_AGE_MS = 5 * 60 * 1000L

internal enum class EcMeanStatus {
    NOT_READ,
    AVAILABLE,
    SOURCE_MISSING,
    PERMISSION_MISSING,
    READ_FAILED,
    NO_VALID_TRIPS,
    INVALID_MEAN,
    STALE,
    CLOSED
}

internal data class EcMeanSnapshot(
    val meanKwhPer100Km: Double?,
    val status: EcMeanStatus,
    val sourcePath: String? = null,
    val completedElapsedRealtimeMs: Long? = null
)

internal data class EcMeanRow(
    val isDeleted: Int?,
    val tripKm: Double?,
    val electricityKwh: Double?
)

internal fun interface EcMeanReader {
    fun read(): EcMeanSnapshot
}

internal class EcCurrentMeanReader(
    private val sourceCandidates: List<File> = EcDatabaseSource.defaultCandidates,
    private val hasAllFilesAccess: () -> Boolean = {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }
) : EcMeanReader {
    override fun read(): EcMeanSnapshot {
        val sourceFile = EcDatabaseSource.findExisting(sourceCandidates)
            ?: return EcMeanSnapshot(
                meanKwhPer100Km = null,
                status = if (!hasAllFilesAccess()) EcMeanStatus.PERMISSION_MISSING else EcMeanStatus.SOURCE_MISSING,
                sourcePath = sourceCandidates.joinToString(";") { it.absolutePath }
            )

        return try {
            SQLiteDatabase.openDatabase(sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT trip, electricity FROM EnergyConsumption WHERE is_deleted=0 AND trip>0",
                    emptyArray()
                ).use { cursor ->
                    meanFromRows(cursor, sourceFile.absolutePath)
                }
            }
        } catch (_: Exception) {
            EcMeanSnapshot(null, EcMeanStatus.READ_FAILED, sourceFile.absolutePath)
        }
    }

    private fun meanFromRows(cursor: Cursor, sourcePath: String): EcMeanSnapshot =
        meanFromRows(buildList {
            while (cursor.moveToNext()) {
                add(
                    EcMeanRow(
                        isDeleted = 0,
                        tripKm = cursor.numericDoubleOrNull(0),
                        electricityKwh = cursor.numericDoubleOrNull(1)
                    )
                )
            }
        }, sourcePath)

    private fun Cursor.numericDoubleOrNull(index: Int): Double? {
        if (isNull(index)) return null
        return when (getType(index)) {
            Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
            else -> null
        }
    }

    companion object {
        internal fun meanFromRows(rows: Iterable<EcMeanRow>, sourcePath: String? = null): EcMeanSnapshot {
            var distanceKm = 0.0
            var electricityKwh = 0.0
            var validRows = 0

            rows.forEach { row ->
                val trip = row.tripKm ?: return@forEach
                val electricity = row.electricityKwh ?: return@forEach
                if (row.isDeleted != 0 || !trip.isFinite() || trip <= 0.0 || !electricity.isFinite()) {
                    return@forEach
                }
                distanceKm += trip
                electricityKwh += electricity
                validRows++
            }

            if (validRows == 0) return EcMeanSnapshot(null, EcMeanStatus.NO_VALID_TRIPS, sourcePath)
            val mean = electricityKwh / distanceKm * 100.0
            return if (distanceKm.isFinite() && electricityKwh.isFinite() && mean.isFinite() && mean > 0.0) {
                EcMeanSnapshot(mean, EcMeanStatus.AVAILABLE, sourcePath)
            } else {
                EcMeanSnapshot(null, EcMeanStatus.INVALID_MEAN, sourcePath)
            }
        }
    }
}

internal class EcCurrentMeanProvider(
    private val reader: EcMeanReader = EcCurrentMeanReader(),
    executor: Executor? = null,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }
) : Closeable {
    private val ownedExecutor = if (executor == null) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "EcMeanReader").apply { isDaemon = true } }
    } else null
    private val executor = executor ?: ownedExecutor!!
    private val refreshing = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val current = AtomicReference(EcMeanSnapshot(null, EcMeanStatus.NOT_READ))

    fun snapshot(): EcMeanSnapshot {
        val value = current.get()
        if (closed.get()) return value.copy(meanKwhPer100Km = null, status = EcMeanStatus.CLOSED)
        if (value.status != EcMeanStatus.AVAILABLE) return value

        val completedAt = value.completedElapsedRealtimeMs
        val age = completedAt?.let { elapsedRealtimeMs() - it }
        return if (age == null || age < 0 || age >= EC_MEAN_MAX_AGE_MS) {
            value.copy(meanKwhPer100Km = null, status = EcMeanStatus.STALE)
        } else {
            value
        }
    }

    fun refreshAsync(): Boolean {
        if (closed.get() || !refreshing.compareAndSet(false, true)) return false
        return try {
            executor.execute {
                try {
                    if (closed.get()) return@execute
                    val result = try {
                        reader.read()
                    } catch (_: Exception) {
                        EcMeanSnapshot(null, EcMeanStatus.READ_FAILED)
                    }
                    val usable = if (result.status == EcMeanStatus.AVAILABLE &&
                        result.meanKwhPer100Km != null && result.meanKwhPer100Km.isFinite() && result.meanKwhPer100Km > 0.0
                    ) {
                        result
                    } else if (result.status == EcMeanStatus.AVAILABLE) {
                        result.copy(meanKwhPer100Km = null, status = EcMeanStatus.INVALID_MEAN)
                    } else {
                        result.copy(meanKwhPer100Km = null)
                    }
                    if (!closed.get()) current.set(usable.copy(completedElapsedRealtimeMs = elapsedRealtimeMs()))
                } finally {
                    refreshing.set(false)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            refreshing.set(false)
            if (!closed.get()) current.set(EcMeanSnapshot(null, EcMeanStatus.READ_FAILED, completedElapsedRealtimeMs = elapsedRealtimeMs()))
            false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        current.set(EcMeanSnapshot(null, EcMeanStatus.CLOSED, completedElapsedRealtimeMs = elapsedRealtimeMs()))
        ownedExecutor?.shutdownNow()
    }
}
