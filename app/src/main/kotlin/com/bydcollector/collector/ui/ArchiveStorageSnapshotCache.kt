package com.bydcollector.collector.ui

import android.os.SystemClock
import com.bydcollector.collector.maintenance.ArchiveStorageManager
import com.bydcollector.collector.maintenance.ArchiveStorageSnapshot
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
    private val ttlMs: Long = 30_000L,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val executor: Executor = namedSingleThreadExecutor("byd-archive-snapshot"),
    private val loader: (Long) -> ArchiveStorageSnapshot = { limitBytes ->
        ArchiveStorageManager(
            archiveRoot = archiveRoot,
            mainDatabaseFile = mainDatabaseFile,
            debugDatabaseFile = debugDatabaseFile
        ).snapshot(limitBytes)
    }
) {
    private val lock = Any()
    private var cached: CachedSnapshot? = null
    private var running = false
    private var generation = 0L
    private var lastError: Throwable? = null
    private var retryAfterMs = 0L

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
                        ?: lightweightSnapshot(requestedLimitBytes),
                    pending = false
                )
                fresh -> ArchiveStorageSnapshotResult(
                    snapshot = current.snapshot.withCurrentActiveDatabases(requestedLimitBytes),
                    pending = false,
                    error = lastError
                )
                retryCoolingDown -> ArchiveStorageSnapshotResult(
                    snapshot = current?.snapshot?.withCurrentActiveDatabases(requestedLimitBytes)
                        ?: lightweightSnapshot(requestedLimitBytes),
                    pending = false,
                    error = lastError
                )
                else -> {
                    if (!running) {
                        running = true
                        shouldStartScan = true
                        scanGeneration = generation
                    }
                    ArchiveStorageSnapshotResult(
                        snapshot = current?.snapshot?.withCurrentActiveDatabases(requestedLimitBytes)
                            ?: lightweightSnapshot(requestedLimitBytes),
                        pending = true,
                        error = lastError
                    )
                }
            }
        }

        if (shouldStartScan) {
            val scan = Runnable {
                val loaded = runCatching { loader(requestedLimitBytes) }
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
                        }.onFailure { error ->
                            //Keep the last good snapshot visible while a later request retries.
                            lastError = error
                            retryAfterMs = clock() + ttlMs
                        }
                    }
                    running = false
                }
            }
            runCatching { executor.execute(scan) }
                .onFailure { error ->
                    synchronized(lock) {
                        if (scanGeneration == generation) lastError = error
                        if (scanGeneration == generation) retryAfterMs = clock() + ttlMs
                        running = false
                    }
                }
        }

        return result
    }

    fun invalidate() {
        synchronized(lock) {
            generation += 1L
            retryAfterMs = 0L
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
            archiveBytes = 0L,
            archiveLimitBytes = limitBytes,
            entries = emptyList()
        )
    }

    private fun ArchiveStorageSnapshot.withCurrentActiveDatabases(limitBytes: Long): ArchiveStorageSnapshot {
        return copy(
            mainDatabaseSizeBytes = databaseSize(mainDatabaseFile),
            debugDatabaseSizeBytes = databaseSize(debugDatabaseFile),
            archiveLimitBytes = limitBytes
        )
    }

    private fun databaseSize(file: File): Long = sqliteFootprintBytes(file)

    private data class CachedSnapshot(
        val snapshot: ArchiveStorageSnapshot,
        val loadedAtMs: Long,
        val generation: Long
    )
}
