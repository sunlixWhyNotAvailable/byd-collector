package com.bydcollector.collector.maintenance

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MaintenanceDiagnosticsTest {
    @Test
    fun durationsAndRemainingReflectActualProgressNotRepeatedPolling() {
        var now = 100L
        val events = mutableListOf<Pair<String, String>>()
        MaintenanceDiagnostics(elapsedRealtimeMs = { now }, appendEvent = { event, detail -> events += event to detail }).use { log ->
            log.begin("debug_archive", "/data/secondary.db")
            now = 200L
            log.beginPhase("drain")
            log.reportDrainProgress(10L, "batches")
            now = 400L
            log.reportDrainProgress(10L, "batches")
            assertEquals(200L, log.cachedSnapshot().lastProgressAgeMs)
            now = 500L
            log.reportDrainProgress(9L, "batches")
            assertEquals(0L, log.cachedSnapshot().lastProgressAgeMs)
            now = 600L
            log.endPhase()
            log.finish("success")
            now = 1_000L
            val finished = log.cachedSnapshot()
            assertFalse(finished.active)
            assertEquals(500L, finished.runDurationMs)
            assertEquals(400L, finished.phaseDurationMs)
            assertEquals(9L, finished.remainingCount)
            assertTrue(events.any { it.first == "database_maintenance_phase_end" && "phase=\"drain\"" in it.second })
            assertTrue(events.last().second.contains("database_path=\"/data/secondary.db\""))
        }
    }

    @Test
    fun negativeResultAndErrorRemainTruthfulThroughRestoreRequest() {
        val events = mutableListOf<Pair<String, String>>()
        MaintenanceDiagnostics(elapsedRealtimeMs = { 100L }, appendEvent = { event, detail -> events += event to detail }).use { log ->
            log.begin("archive", "/data/main.db")
            assertFalse(log.runPhase("quick_check") { false })
            assertTrue(events.any { it.first == "database_maintenance_phase_end" && "status=\"negative_result\"" in it.second })
            val original = IllegalStateException("write failed", IllegalArgumentException("underlying cause"))
            assertSame(original, assertFailsWith<IllegalStateException> {
                log.runPhase("create") { throw original }
            })
            assertTrue(events.any { it.first == "database_maintenance_error" && "underlying cause" in it.second })
            log.runPhase("runtime_restore_requested") { Unit }
            log.finish("failed")
            assertTrue(log.cachedSnapshot().error!!.contains("write failed"))
        }
        MaintenanceDiagnostics(elapsedRealtimeMs = { 0L }, appendEvent = { _, _ -> error("journal unavailable") }).use { log ->
            log.begin("archive", null)
            assertEquals("preserved", log.runPhase("create") { "preserved" })
            log.finish("success")
        }
    }

    @Test
    fun heartbeatReportsBlockedPhaseWithoutNeedingTheMaintenanceWorker() {
        val now = AtomicLong(100L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heartbeat = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val log = MaintenanceDiagnostics(elapsedRealtimeMs = now::get, heartbeatIntervalMs = 10L) { event, detail ->
            if (event == "database_maintenance_heartbeat" && "phase=\"checkpoint\"" in detail &&
                "last_progress_age_ms=900" in detail && "remaining_count=unknown" in detail) heartbeat.countDown()
        }
        try {
            log.begin("archive", "/data/main.db")
            val task = worker.submit {
                log.runPhase("checkpoint") {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            now.set(1_000L)
            assertTrue(heartbeat.await(2, TimeUnit.SECONDS))
            release.countDown()
            task.get(1, TimeUnit.SECONDS)
            log.finish("success")
        } finally {
            release.countDown()
            log.close()
            worker.shutdownNow()
        }
    }
}
