package com.bydcollector.collector.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchiveStorageSnapshotCacheTest {
    @Test
    fun secondaryNameFailureKeepsMainSnapshotAvailableAndExplicitlyErrored() {
        val root = Files.createTempDirectory("archive-cache-secondary-error").toFile()
        val main = root.resolve("main.db").apply { writeText("main") }
        val cache = ArchiveStorageSnapshotCache(
            archiveRoot = root.resolve("archive"),
            mainDatabaseFile = main,
            debugDatabaseFile = root.resolve("bydcollector_secondary.db"),
            debugDatabaseFileProvider = { error("secondary name ambiguous") },
            clock = { 0L },
            executor = java.util.concurrent.Executor { it.run() }
        )

        val result = cache.snapshot(1_024L, includeDetails = false)
        assertEquals(main.length(), result.snapshot.mainDatabaseSizeBytes)
        assertEquals(0L, result.snapshot.debugDatabaseSizeBytes)
        assertEquals("secondary name ambiguous", result.error?.message)
        cache.close()
    }

    @Test
    fun lightweightSnapshotRebindsSecondaryDatabaseAfterArchiveRename() {
        val root = Files.createTempDirectory("archive-cache-rebind").toFile()
        val archiveRoot = root.resolve("archive").apply { mkdirs() }
        val main = root.resolve("main.db").apply { writeText("main") }
        val legacy = root.resolve("bydcollector_debug_round_robin.db").apply { writeText("legacy") }
        val secondary = root.resolve("bydcollector_secondary.db").apply { writeText("secondary-new") }
        var active = legacy
        val cache = ArchiveStorageSnapshotCache(
            archiveRoot = archiveRoot,
            mainDatabaseFile = main,
            debugDatabaseFile = legacy,
            debugDatabaseFileProvider = { active },
            clock = { 0L },
            executor = java.util.concurrent.Executor { it.run() }
        )

        assertEquals(legacy.length(), cache.snapshot(1_024L, includeDetails = false).snapshot.debugDatabaseSizeBytes)
        active = secondary
        assertEquals(secondary.length(), cache.snapshot(1_024L, includeDetails = false).snapshot.debugDatabaseSizeBytes)
        cache.close()
    }

    @Test
    fun allThreeFootprintsStayFreshIncludingWalAndFailedArchiveScans() {
        val root = Files.createTempDirectory("byd-three-footprints").toFile()
        try {
            val main = root.resolve("bydcollector_telemetry.db").apply { writeText("main") }
            val debug = root.resolve("bydcollector_debug_round_robin.db").apply { writeText("debug") }
            val trips = root.resolve("bydcollector_trips.db").apply { writeBytes(ByteArray(10)) }
            val wal = root.resolve(trips.name + "-wal").apply { writeBytes(ByteArray(20)) }
            root.resolve(trips.name + "-shm").writeBytes(ByteArray(30))
            root.resolve(trips.name + "-journal").writeBytes(ByteArray(40))
            val archives = root.resolve("db_archive")
            var now = 100L
            var scans = 0
            var fail = false
            val cache = ArchiveStorageSnapshotCache(
                archiveRoot = archives, mainDatabaseFile = main, debugDatabaseFile = debug,
                tripsDatabaseFile = trips, clock = { now }, executor = { it.run() }
            ) { limit ->
                scans++
                if (fail) error("archive scan failed")
                com.bydcollector.collector.maintenance.ArchiveStorageManager(archives, main, debug, trips).snapshot(limit)
            }
            val light = cache.snapshot(1024L, includeDetails = false).snapshot
            assertEquals(100L, light.tripsDatabaseSizeBytes)
            assertEquals(109L, light.activeDatabaseSizeBytes)
            assertEquals(0, scans)
            cache.snapshot(1024L, includeDetails = true)
            assertEquals(1, scans)
            assertTrue(wal.delete())
            val cached = cache.snapshot(1024L, includeDetails = true).snapshot
            assertEquals(80L, cached.tripsDatabaseSizeBytes)
            assertEquals(89L, cached.activeDatabaseSizeBytes)
            now += 31_000L
            fail = true
            cache.snapshot(1024L, includeDetails = true)
            trips.writeBytes(ByteArray(5))
            val fallback = cache.snapshot(1024L, includeDetails = true)
            assertNotNull(fallback.error)
            assertEquals(75L, fallback.snapshot.tripsDatabaseSizeBytes)
            assertEquals(84L, fallback.snapshot.activeDatabaseSizeBytes)
            cache.close()
        } finally {
            root.deleteRecursively()
        }
    }

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

    @Test
    fun retiredEntriesDisappearImmediatelyAndForcedScanRunsAfterInFlightScan() {
        val root = Files.createTempDirectory("byd-archive-cache-delete").toFile()
        val active = root.resolve("bydcollector_telemetry.db").apply { writeText("active") }
        val debug = root.resolve("bydcollector_debug_round_robin.db").apply { writeText("debug") }
        val archiveRoot = root.resolve("db_archive").apply { mkdirs() }
        val archive = archiveRoot.resolve("bydcollector_telemetry_20260830_120000.zip").apply { writeText("zip") }
        val commands = mutableListOf<Runnable>()
        var scans = 0
        var now = 1_000L
        val requestedLimits = mutableListOf<Long>()
        val cache = ArchiveStorageSnapshotCache(
            archiveRoot = archiveRoot,
            mainDatabaseFile = active,
            debugDatabaseFile = debug,
            clock = { now },
            executor = { command -> commands += command }
        ) { limit ->
            scans += 1
            requestedLimits += limit
            com.bydcollector.collector.maintenance.ArchiveStorageManager(archiveRoot, active, debug).snapshot(limit)
        }

        cache.snapshot(1024L, includeDetails = true)
        assertEquals(1, commands.size)
        commands.removeAt(0).run()
        assertEquals(1, scans)
        assertEquals(1, cache.snapshot(1024L, includeDetails = true).snapshot.entries.size)

        now += 31_000L
        cache.snapshot(1024L, includeDetails = true)
        assertEquals(1, commands.size)
        cache.retire(listOf(archive.name))
        cache.invalidate()
        cache.snapshot(2048L, includeDetails = true)
        assertTrue(cache.snapshot(2048L, includeDetails = true).snapshot.entries.isEmpty())
        assertEquals(1, commands.size)

        //The first forced scan is queued while the scan is in flight; it must run once after completion.
        commands.removeAt(0).run()
        assertEquals(2, scans)
        assertEquals(1, commands.size)
        assertTrue(cache.snapshot(2048L, includeDetails = true).snapshot.entries.isEmpty())
        cache.completeRetiredAfterNextScan()
        archive.delete()
        commands.removeAt(0).run()
        assertEquals(3, scans)
        assertEquals(listOf(1024L, 1024L, 2048L), requestedLimits)
        assertTrue(cache.snapshot(2048L, includeDetails = true).snapshot.entries.isEmpty())
        cache.close()
    }
}
