package com.bydcollector.collector.ui

import android.os.SystemClock
import com.bydcollector.collector.maintenance.ArchiveStorageManager
import com.bydcollector.collector.maintenance.ArchiveStorageSnapshot
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

data class ArchiveStorageSnapshotResult(
    val snapshot: ArchiveStorageSnapshot,
    val pending: Boolean
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

    fun snapshot(limitBytes: Long, includeDetails: Boolean): ArchiveStorageSnapshotResult {
        val nowMs = clock()
        var shouldStartScan = false
        var scanPending = false
        val resultSnapshot = synchronized(lock) {
            val current = cached
            val fresh = current != null && nowMs - current.loadedAtMs < ttlMs
            when {
                !includeDetails -> current?.snapshot?.withCurrentActiveDatabases(limitBytes) ?: lightweightSnapshot(limitBytes)
                fresh -> current.snapshot.withCurrentActiveDatabases(limitBytes)
                else -> {
                    scanPending = true
                    if (!running) {
                        running = true
                        shouldStartScan = true
                    }
                    current?.snapshot?.withCurrentActiveDatabases(limitBytes) ?: lightweightSnapshot(limitBytes)
                }
            }
        }

        if (shouldStartScan) {
            executor.execute {
                val loaded = runCatching { loader(limitBytes) }.getOrNull()
                synchronized(lock) {
                    if (loaded != null) {
                        cached = CachedSnapshot(loaded, clock())
                    }
                    running = false
                }
            }
        }

        return ArchiveStorageSnapshotResult(
            snapshot = resultSnapshot,
            pending = includeDetails && scanPending
        )
    }

    fun invalidate() {
        synchronized(lock) {
            cached = null
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

    private fun databaseSize(file: File): Long = file.takeIf { it.exists() }?.length() ?: 0L

    private data class CachedSnapshot(
        val snapshot: ArchiveStorageSnapshot,
        val loadedAtMs: Long
    )
}
