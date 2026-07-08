package com.bydcollector.collector.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveStorageSnapshotCacheTest {
    @Test
    fun storageScanRunsOnlyWhenRequestedAndCachesForTtl() {
        val root = Files.createTempDirectory("byd-archive-cache").toFile()
        val active = root.resolve("bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = root.resolve("db_archive").apply { mkdirs() }
        var now = 1_000L
        var scans = 0
        val cache = ArchiveStorageSnapshotCache(
            archiveRoot = archiveRoot,
            activeDatabaseFile = active,
            ttlMs = 30_000L,
            clock = { now },
            executor = { command -> command.run() }
        ) { limitBytes ->
            scans += 1
            com.bydcollector.collector.maintenance.ArchiveStorageManager(archiveRoot, active).snapshot(limitBytes)
        }

        val idle = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = false)
        assertEquals(0, scans)
        assertFalse(idle.pending)
        assertEquals(active.absolutePath, idle.snapshot.activeDatabasePath)

        val first = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)
        assertEquals(1, scans)
        assertTrue(first.pending)

        now += 10_000L
        val cached = cache.snapshot(limitBytes = 3L * 1024 * 1024 * 1024, includeDetails = true)
        assertEquals(1, scans)
        assertFalse(cached.pending)
        assertEquals(3L * 1024 * 1024 * 1024, cached.snapshot.archiveLimitBytes)

        now += 31_000L
        val stale = cache.snapshot(limitBytes = 3L * 1024 * 1024 * 1024, includeDetails = true)
        assertEquals(2, scans)
        assertTrue(stale.pending)
    }

    @Test
    fun pendingStaysTrueWhileBackgroundScanIsStillRunning() {
        val root = Files.createTempDirectory("byd-archive-cache-running").toFile()
        val active = root.resolve("bydcollector_telemetry.db").apply { writeText("active") }
        val archiveRoot = root.resolve("db_archive").apply { mkdirs() }
        val commands = mutableListOf<Runnable>()
        val now = 1_000L
        var scans = 0
        val cache = ArchiveStorageSnapshotCache(
            archiveRoot = archiveRoot,
            activeDatabaseFile = active,
            ttlMs = 30_000L,
            clock = { now },
            executor = { command -> commands += command }
        ) { limitBytes ->
            scans += 1
            com.bydcollector.collector.maintenance.ArchiveStorageManager(archiveRoot, active).snapshot(limitBytes)
        }

        val first = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)
        val second = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)

        assertTrue(first.pending)
        assertTrue(second.pending)
        assertEquals(1, commands.size)
        assertEquals(0, scans)

        commands.single().run()

        val completed = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)
        assertFalse(completed.pending)
        assertEquals(1, scans)
    }
}
