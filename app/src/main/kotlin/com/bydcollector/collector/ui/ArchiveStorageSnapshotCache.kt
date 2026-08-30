package com.bydcollector.collector.ui

import android.os.SystemClock
import com.bydcollector.collector.maintenance.ArchiveStorageManager
import com.bydcollector.collector.maintenance.ArchiveStorageSnapshot
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.util.namedSingleThreadExecutor
import com.bydcollector.collector.util.sqliteFootprintBytes
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

data class ArchiveStorageSnapshotResult(
    val snapshot: ArchiveStorageSnapshot,
    val pending: Boolean,
    val error: Throwable? = null
)

class ArchiveStorageSnapshotCache(
    private val archiveRoot: File,
    private val mainDatabaseFile: File,
    private val debugDatabaseFile: File,
    private val tripsDatabaseFile: File = File(mainDatabaseFile.parentFile, TripDatabaseHelper.DATABASE_NAME),
    private val ttlMs: Long = 30_000L,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val executor: Executor = namedSingleThreadExecutor("byd-archive-snapshot"),
    private val loader: (Long) -> ArchiveStorageSnapshot = { limitBytes ->
        ArchiveStorageManager(
            archiveRoot = archiveRoot,
            mainDatabaseFile = mainDatabaseFile,
            debugDatabaseFile = debugDatabaseFile,
            tripsDatabaseFile = tripsDatabaseFile
        ).snapshot(limitBytes)
    }
) {
    private val lock = Any()
    private var cached: CachedSnapshot? = null
    private var running = false
    private var generation = 0L
    private var lastError: Throwable? = null
    private var retryAfterMs = 0L
    private var pendingForcedScan = false
    private var pendingScanLimitBytes: Long? = null
    private var releaseRetiredAfterScan = false
    private val retiredIds = mutableSetOf<String>()

    fun snapshot(limitBytes: Long, includeDetails: Boolean): ArchiveStorageSnapshotResult {
        val nowMs = clock()
        val requestedLimitBytes = limitBytes
        var shouldStartScan = false
        var scanGeneration = 0L
        val result = synchronized(lock) {
            val current = cached
            val fresh = current != null &&
                current.generation == generation &&
                nowMs >= current.loadedAtMs &&
                nowMs - current.loadedAtMs < ttlMs
            val retryCoolingDown = lastError != null && nowMs < retryAfterMs
            when {
                !includeDetails -> ArchiveStorageSnapshotResult(
                    snapshot = current?.snapshot?.withCurrentActiveDatabases(requestedLimitBytes)
                        ?.visible()
                        ?: lightweightSnapshot(requestedLimitBytes),
                    pending = false
                )
                fresh -> ArchiveStorageSnapshotResult(
                    snapshot = current.snapshot.withCurrentActiveDatabases(requestedLimitBytes).visible(),
                    pending = false,
                    error = lastError
                )
                retryCoolingDown -> ArchiveStorageSnapshotResult(
                    snapshot = current?.snapshot?.withCurrentActiveDatabases(requestedLimitBytes)
                        ?.visible()
                        ?: lightweightSnapshot(requestedLimitBytes),
                    pending = false,
                    error = lastError
                )
                else -> {
                    if (running && pendingForcedScan) {
                        pendingScanLimitBytes = requestedLimitBytes
                    }
                    if (!running) {
                        running = true
                        shouldStartScan = true
                        scanGeneration = generation
                    }
                    ArchiveStorageSnapshotResult(
                        snapshot = current?.snapshot?.withCurrentActiveDatabases(requestedLimitBytes)
                            ?.visible()
                            ?: lightweightSnapshot(requestedLimitBytes),
                        pending = true,
                        error = lastError
                    )
                }
            }
        }

        if (shouldStartScan) launchScan(requestedLimitBytes, scanGeneration)

        return result
    }

    fun retire(ids: Collection<String>) {
        synchronized(lock) {
            retiredIds += ids
        }
    }

    fun restoreRetired(ids: Collection<String>? = null) {
        synchronized(lock) {
            if (ids == null) retiredIds.clear() else retiredIds.removeAll(ids.toSet())
        }
    }

    fun invalidate() {
        synchronized(lock) {
            generation += 1L
            retryAfterMs = 0L
            if (running) pendingForcedScan = true
        }
    }

    fun completeRetiredAfterNextScan() {
        synchronized(lock) {
            releaseRetiredAfterScan = true
            generation += 1L
            retryAfterMs = 0L
            if (running) pendingForcedScan = true
        }
    }

    fun close() {
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun lightweightSnapshot(limitBytes: Long): ArchiveStorageSnapshot {
        return ArchiveStorageSnapshot(
            archiveRootPath = archiveRoot.absolutePath,
            mainDatabaseSizeBytes = databaseSize(mainDatabaseFile),
            debugDatabaseSizeBytes = databaseSize(debugDatabaseFile),
            tripsDatabaseSizeBytes = databaseSize(tripsDatabaseFile),
            archiveBytes = 0L,
            archiveLimitBytes = limitBytes,
            entries = emptyList()
        )
    }

    private fun ArchiveStorageSnapshot.withCurrentActiveDatabases(limitBytes: Long): ArchiveStorageSnapshot {
        return copy(
            mainDatabaseSizeBytes = databaseSize(mainDatabaseFile),
            debugDatabaseSizeBytes = databaseSize(debugDatabaseFile),
            tripsDatabaseSizeBytes = databaseSize(tripsDatabaseFile),
            archiveLimitBytes = limitBytes
        )
    }

    private fun ArchiveStorageSnapshot.visible(): ArchiveStorageSnapshot {
        if (retiredIds.isEmpty()) return this
        return copy(entries = entries.filterNot { it.id in retiredIds })
    }

    private fun databaseSize(file: File): Long = sqliteFootprintBytes(file)

    private fun launchScan(requestedLimitBytes: Long, scanGeneration: Long) {
        val scan = Runnable {
            val loaded = runCatching { loader(requestedLimitBytes) }
            var rerun: Pair<Long, Long>? = null
            synchronized(lock) {
                if (scanGeneration == generation) {
                    loaded.onSuccess { snapshot ->
                        cached = CachedSnapshot(
                            snapshot = snapshot.copy(archiveLimitBytes = requestedLimitBytes),
                            loadedAtMs = clock(),
                            generation = generation
                        )
                        lastError = null
                        retryAfterMs = 0L
                        if (releaseRetiredAfterScan) {
                            retiredIds.clear()
                            releaseRetiredAfterScan = false
                        }
                    }.onFailure { error ->
                        //Keep the last good snapshot visible while a later request retries.
                        lastError = error
                        retryAfterMs = clock() + ttlMs
                    }
                }
                if (pendingForcedScan) {
                    pendingForcedScan = false
                    val nextLimit = pendingScanLimitBytes ?: requestedLimitBytes
                    pendingScanLimitBytes = null
                    running = true
                    rerun = nextLimit to generation
                } else {
                    running = false
                }
            }
            rerun?.let { launchScan(it.first, it.second) }
        }
        runCatching { executor.execute(scan) }
            .onFailure { error ->
                synchronized(lock) {
                    if (scanGeneration == generation) {
                        lastError = error
                        retryAfterMs = clock() + ttlMs
                    }
                    if (pendingForcedScan) {
                        pendingForcedScan = false
                        pendingScanLimitBytes = null
                    }
                    running = false
                }
            }
    }

    private data class CachedSnapshot(
        val snapshot: ArchiveStorageSnapshot,
        val loadedAtMs: Long,
        val generation: Long
    )
}
