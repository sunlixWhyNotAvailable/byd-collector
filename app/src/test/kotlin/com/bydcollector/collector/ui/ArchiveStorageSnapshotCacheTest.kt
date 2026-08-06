package com.bydcollector.collector.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchiveStorageSnapshotCacheTest {
    @Test
    fun storageScanRunsOnlyWhenRequestedAndCachesForTtl() {
        val root = Files.createTempDirectory("byd-archive-cache").toFile()
        val active = root.resolve("bydcollector_telemetry.db").apply { writeText("active") }
        val debug = root.resolve("bydcollector_debug_round_robin.db").apply { writeText("debug") }
        val archiveRoot = root.resolve("db_archive").apply { mkdirs() }
        archiveRoot.resolve("bydcollector_telemetry_20260806_120000.zip").writeText("archive")
        var now = 1_000L
        var scans = 0
        val requestedLimits = mutableListOf<Long>()
        val cache = ArchiveStorageSnapshotCache(
            archiveRoot = archiveRoot,
            mainDatabaseFile = active,
            debugDatabaseFile = debug,
            ttlMs = 30_000L,
            clock = { now },
            executor = { command -> command.run() }
        ) { limitBytes ->
            scans += 1
            requestedLimits += limitBytes
            com.bydcollector.collector.maintenance.ArchiveStorageManager(archiveRoot, active, debug).snapshot(limitBytes)
        }

        val idle = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = false)
        assertEquals(0, scans)
        assertFalse(idle.pending)
        assertEquals(active.length() + debug.length(), idle.snapshot.activeDatabaseSizeBytes)

        val first = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)
        assertEquals(1, scans)
        assertEquals(listOf(2L * 1024 * 1024 * 1024), requestedLimits)
        assertTrue(first.pending)

        now += 10_000L
        val cached = cache.snapshot(limitBytes = 3L * 1024 * 1024 * 1024, includeDetails = true)
        assertEquals(1, scans)
        assertFalse(cached.pending)
        assertEquals(3L * 1024 * 1024 * 1024, cached.snapshot.archiveLimitBytes)
        assertEquals(1, cached.snapshot.entries.size)

        now += 31_000L
        val stale = cache.snapshot(limitBytes = 3L * 1024 * 1024 * 1024, includeDetails = true)
        assertEquals(2, scans)
        assertEquals(listOf(2L * 1024 * 1024 * 1024, 3L * 1024 * 1024 * 1024), requestedLimits)
        assertTrue(stale.pending)
        assertEquals(cached.snapshot.entries, stale.snapshot.entries)
    }

    @Test
    fun pendingStaysTrueWhileBackgroundScanIsStillRunning() {
        val root = Files.createTempDirectory("byd-archive-cache-running").toFile()
        val active = root.resolve("bydcollector_telemetry.db").apply { writeText("active") }
        val debug = root.resolve("bydcollector_debug_round_robin.db").apply { writeText("debug") }
        val archiveRoot = root.resolve("db_archive").apply { mkdirs() }
        val commands = mutableListOf<Runnable>()
        val now = 1_000L
        var scans = 0
        val cache = ArchiveStorageSnapshotCache(
            archiveRoot = archiveRoot,
            mainDatabaseFile = active,
            debugDatabaseFile = debug,
            ttlMs = 30_000L,
            clock = { now },
            executor = { command -> commands += command }
        ) { limitBytes ->
            scans += 1
            com.bydcollector.collector.maintenance.ArchiveStorageManager(archiveRoot, active, debug).snapshot(limitBytes)
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

    @Test
    fun invalidationDropsAnInFlightResultWithoutDroppingTheOldSnapshot() {
        val root = Files.createTempDirectory("byd-archive-cache-aba").toFile()
        val active = root.resolve("bydcollector_telemetry.db").apply { writeText("active") }
        val debug = root.resolve("bydcollector_debug_round_robin.db").apply { writeText("debug") }
        val archiveRoot = root.resolve("db_archive").apply { mkdirs() }
        val commands = mutableListOf<Runnable>()
        var now = 1_000L
        val firstSnapshot = com.bydcollector.collector.maintenance.ArchiveStorageManager(archiveRoot, active, debug)
            .snapshot(2L * 1024 * 1024 * 1024)
            .copy(archiveBytes = 1L)
        var scanNumber = 0
        val cache = ArchiveStorageSnapshotCache(
            archiveRoot = archiveRoot,
            mainDatabaseFile = active,
            debugDatabaseFile = debug,
            ttlMs = 30_000L,
            clock = { now },
            executor = { command -> commands += command }
        ) {
            scanNumber += 1
            if (scanNumber == 1) firstSnapshot else firstSnapshot.copy(archiveBytes = 999L)
        }

        cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)
        commands.removeAt(0).run()
        now += 31_000L
        val stale = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)
        cache.invalidate()
        val duringInvalidation = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)

        commands.removeAt(0).run()

        assertTrue(stale.pending)
        assertTrue(duringInvalidation.pending)
        val retry = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)
        assertTrue(retry.pending)
        assertEquals(1L, retry.snapshot.archiveBytes)
        assertEquals(1, commands.size)
    }

    @Test
    fun failedScanRetainsLastGoodSnapshotAndExposesFailure() {
        val root = Files.createTempDirectory("byd-archive-cache-failure").toFile()
        val active = root.resolve("bydcollector_telemetry.db").apply { writeText("active") }
        val debug = root.resolve("bydcollector_debug_round_robin.db").apply { writeText("debug") }
        val archiveRoot = root.resolve("db_archive").apply { mkdirs() }
        val commands = mutableListOf<Runnable>()
        var now = 1_000L
        var fail = false
        val cache = ArchiveStorageSnapshotCache(
            archiveRoot = archiveRoot,
            mainDatabaseFile = active,
            debugDatabaseFile = debug,
            ttlMs = 30_000L,
            clock = { now },
            executor = { command -> commands += command }
        ) {
            if (fail) error("archive scan failed")
            com.bydcollector.collector.maintenance.ArchiveStorageManager(archiveRoot, active, debug).snapshot(it)
        }

        cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)
        commands.removeAt(0).run()
        now += 31_000L
        fail = true
        val failedRequest = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)
        commands.removeAt(0).run()
        val retained = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)

        assertTrue(failedRequest.pending)
        assertEquals(0L, failedRequest.snapshot.archiveBytes)
        assertFalse(retained.pending)
        assertEquals(0L, retained.snapshot.archiveBytes)
        assertNotNull(retained.error)
        assertEquals(0, commands.size)

        now += 31_000L
        val retry = cache.snapshot(limitBytes = 2L * 1024 * 1024 * 1024, includeDetails = true)
        assertTrue(retry.pending)
        assertEquals(1, commands.size)
    }
}
