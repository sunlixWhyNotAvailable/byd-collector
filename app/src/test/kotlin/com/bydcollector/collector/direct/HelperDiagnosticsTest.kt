package com.bydcollector.collector.direct

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelperDiagnosticsTest {
    @Test
    fun tracksBoundedSpoolOutcomesAckBytesAndUnknownHistoricalPeak() {
        val sink = RecordingSink()
        val clock = FakeClock()
        val diagnostics = HelperDiagnostics(
            "boot-a", 42, "generation-a", TelemetryWorkerSpool.MAX_SPOOL_BYTES,
            TelemetryWorkerSpool.Footprint(128L * 1024L * 1024L, 3), sink, clock, 32
        )
        try {
            diagnostics.onAppend(TelemetryWorkerSpool.AppendResult.SUCCESS, TelemetryWorkerSpool.Footprint(100, 1))
            diagnostics.onAppend(TelemetryWorkerSpool.AppendResult.DUPLICATE, null)
            diagnostics.onAppend(TelemetryWorkerSpool.AppendResult.CAP_REACHED, TelemetryWorkerSpool.Footprint(128L * 1024L * 1024L, 3))
            diagnostics.capSkippedPollCycle()
            diagnostics.onPersistenceFailure("append", IllegalStateException("disk full"))
            diagnostics.onAcknowledge(TelemetryWorkerSpool.AckResult.released(37))
            diagnostics.onAcknowledge(TelemetryWorkerSpool.AckResult.notFound())
            diagnostics.onAcknowledge(TelemetryWorkerSpool.AckResult.failed("delete failed"))
            diagnostics.onQuarantine(12)
            diagnostics.error("same error")
            diagnostics.error("same error")

            val snapshot = diagnostics.snapshotForTest()
            assertEquals(1L, snapshot.successfulAppends)
            assertEquals(1L, snapshot.duplicateRefusals)
            assertEquals(1L, snapshot.capRefusals)
            assertEquals(1L, snapshot.capSkippedPollCycles)
            assertEquals(1L, snapshot.persistenceFailures)
            assertEquals(1L, snapshot.ackReleasedRecords)
            assertEquals(37L, snapshot.ackReleasedBytes)
            assertEquals(1L, snapshot.ackNotFound)
            assertEquals(1L, snapshot.ackFailures)
            assertEquals(1L, snapshot.quarantinedRecords)
            assertEquals(128L * 1024L * 1024L, snapshot.observationPeakBytes)
            assertNull(snapshot.historicalPeakBytes)
            assertEquals(1L, diagnostics.repeatedErrorCountForTest())
        } finally {
            diagnostics.close()
        }
        assertTrue(sink.lines.any { it.contains("\"event\":\"helper_start\"") })
        assertTrue(sink.lines.any { it.contains("\"event\":\"helper_stop\"") })
    }

    @Test
    fun eachRestartStartsANewIntervalAndDoesNotInventHistoricalPeak() {
        val firstClock = FakeClock(100, 10)
        val first = HelperDiagnostics(
            "boot-a", 1, "generation-1", 1_000,
            TelemetryWorkerSpool.Footprint(700, 2), RecordingSink(), firstClock, 8
        )
        val firstSnapshot = first.snapshotForTest()
        first.close()

        val secondClock = FakeClock(200, 20)
        val second = HelperDiagnostics(
            "boot-a", 2, "generation-2", 1_000,
            TelemetryWorkerSpool.Footprint(300, 1), RecordingSink(), secondClock, 8
        )
        val secondSnapshot = second.snapshotForTest()
        second.close()

        assertEquals(100L, firstSnapshot.intervalStartedWallMs)
        assertEquals(200L, secondSnapshot.intervalStartedWallMs)
        assertEquals(700L, firstSnapshot.observationPeakBytes)
        assertEquals(300L, secondSnapshot.observationPeakBytes)
        assertNull(firstSnapshot.historicalPeakBytes)
        assertNull(secondSnapshot.historicalPeakBytes)
    }

    @Test
    fun reporterQueueOverflowAndDiskFailureAreCountedWithoutThrowingToCaller() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val blockingSink = object : HelperDiagnostics.Sink {
            override fun persist(jsonLine: String, snapshotJson: String) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                throw IllegalStateException("unwritable")
            }

            override fun persistBootstrap(chunk: ByteArray) {
                throw IllegalStateException("unwritable")
            }
        }
        val diagnostics = HelperDiagnostics(
            "boot-a", 1, "generation-a", 1_000,
            TelemetryWorkerSpool.Footprint(0, 0), blockingSink, FakeClock(), 1
        )
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        repeat(20) { diagnostics.context("event_$it", null) }
        diagnostics.enqueueBootstrap(ByteArray(HelperDiagnostics.MAX_BOOTSTRAP_CHUNK_BYTES + 100))
        assertTrue(diagnostics.snapshotForTest().diagnosticQueueDrops > 0)
        release.countDown()
        diagnostics.close()
        assertTrue(diagnostics.snapshotForTest().diagnosticDiskFailures > 0)
    }

    @Test
    fun immediateCapEventKeepsTransitionTimeAndStateWhileWriterIsDelayed() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val sink = object : HelperDiagnostics.Sink {
            val writes = mutableListOf<Pair<String, String>>()
            var first = true
            override fun persist(jsonLine: String, snapshotJson: String) {
                if (first) {
                    first = false
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                synchronized(writes) { writes += jsonLine to snapshotJson }
            }
            override fun persistBootstrap(chunk: ByteArray) = Unit
        }
        val clock = FakeClock(1_000, 100)
        val diagnostics = HelperDiagnostics(
            "boot-a", 1, "generation-a", 1_000,
            TelemetryWorkerSpool.Footprint(0, 0), sink, clock, 16
        )
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        clock.set(2_000, 200)
        diagnostics.onObservation(TelemetryWorkerSpool.Footprint(1_000, 2), true)
        clock.set(3_000, 300)
        diagnostics.onAcknowledge(TelemetryWorkerSpool.AckResult.released(1_000))
        release.countDown()
        diagnostics.close()

        val capWrite = synchronized(sink.writes) {
            sink.writes.single { it.first.contains("\"event\":\"spool_cap_reached\"") }
        }
        val capEvent = capWrite.first
        assertTrue(capEvent.contains("\"observed_wall_ms\":2000"))
        assertTrue(capEvent.contains("\"current_bytes\":1000"))
        assertTrue(capEvent.contains("\"pending_ready_records\":2"))
        assertTrue(capEvent.contains("\"cap_reached\":true"))
        assertTrue(capWrite.second.contains("\"current_bytes\":0"))
        assertTrue(capWrite.second.contains("\"pending_ready_records\":1"))
        assertTrue(capWrite.second.contains("\"cap_reached\":false"))
    }

    @Test
    fun diskFailureRetriesAtConfiguredIntervalInsteadOfBusyLooping() {
        val attempts = AtomicLong()
        val twoAttempts = CountDownLatch(2)
        val sink = object : HelperDiagnostics.Sink {
            override fun persist(jsonLine: String, snapshotJson: String) {
                attempts.incrementAndGet()
                twoAttempts.countDown()
                throw IllegalStateException("unwritable")
            }
            override fun persistBootstrap(chunk: ByteArray) {
                attempts.incrementAndGet()
                throw IllegalStateException("unwritable")
            }
        }
        val realtimeClock = object : HelperDiagnostics.Clock {
            override fun wallTimeMs() = System.currentTimeMillis()
            override fun elapsedTimeMs() = System.nanoTime() / 1_000_000L
        }
        val diagnostics = HelperDiagnostics(
            "boot-a", 1, "generation-a", 1_000,
            TelemetryWorkerSpool.Footprint(0, 0), sink, realtimeClock, 8, 25
        )
        assertTrue(twoAttempts.await(500, TimeUnit.MILLISECONDS))
        Thread.sleep(40)
        diagnostics.close()
        assertTrue(attempts.get() in 2L..6L, "unexpected retry count=${attempts.get()}")
        assertTrue(diagnostics.snapshotForTest().diagnosticDiskFailures >= attempts.get())
    }

    @Test
    fun appOwnerGetsDelayedSummaryOnlyAfterStateChangesWithoutPollLoop() {
        val summary = CountDownLatch(1)
        val sink = object : HelperDiagnostics.Sink {
            val lines = mutableListOf<String>()
            override fun persist(jsonLine: String, snapshotJson: String) {
                synchronized(lines) { lines += jsonLine }
                if (jsonLine.contains("\"event\":\"summary\"")) summary.countDown()
            }
            override fun persistBootstrap(chunk: ByteArray) = Unit
        }
        val realtimeClock = object : HelperDiagnostics.Clock {
            override fun wallTimeMs() = System.currentTimeMillis()
            override fun elapsedTimeMs() = System.nanoTime() / 1_000_000L
        }
        val diagnostics = HelperDiagnostics(
            "boot-a", 1, "generation-a", 1_000,
            TelemetryWorkerSpool.Footprint(0, 0), sink, realtimeClock, 8, 30
        )
        Thread.sleep(60)
        assertTrue(synchronized(sink.lines) { sink.lines.none { it.contains("\"event\":\"summary\"") } })
        diagnostics.onAppend(TelemetryWorkerSpool.AppendResult.DUPLICATE, null)
        assertTrue(summary.await(250, TimeUnit.MILLISECONDS))
        diagnostics.close()
    }

    @Test
    fun failedBootstrapWriteIsRetriedAndItsFailureReachesCurrentSnapshot() {
        val startPersisted = CountDownLatch(1)
        val bootstrapPersisted = CountDownLatch(1)
        val summaryPersisted = CountDownLatch(1)
        val bootstrapAttempts = AtomicLong()
        val snapshots = mutableListOf<String>()
        val sink = object : HelperDiagnostics.Sink {
            override fun persist(jsonLine: String, snapshotJson: String) {
                synchronized(snapshots) { snapshots += snapshotJson }
                if (jsonLine.contains("\"event\":\"helper_start\"")) startPersisted.countDown()
                if (jsonLine.contains("\"event\":\"summary\"")) summaryPersisted.countDown()
            }
            override fun persistBootstrap(chunk: ByteArray) {
                if (bootstrapAttempts.incrementAndGet() == 1L) throw IllegalStateException("first write fails")
                bootstrapPersisted.countDown()
            }
        }
        val realtimeClock = object : HelperDiagnostics.Clock {
            override fun wallTimeMs() = System.currentTimeMillis()
            override fun elapsedTimeMs() = System.nanoTime() / 1_000_000L
        }
        val diagnostics = HelperDiagnostics(
            "boot-a", 1, "generation-a", 1_000,
            TelemetryWorkerSpool.Footprint(0, 0), sink, realtimeClock, 8, 25
        )
        assertTrue(startPersisted.await(500, TimeUnit.MILLISECONDS))
        diagnostics.enqueueBootstrap("bootstrap\n".toByteArray())
        assertTrue(bootstrapPersisted.await(500, TimeUnit.MILLISECONDS))
        assertTrue(summaryPersisted.await(500, TimeUnit.MILLISECONDS))
        diagnostics.close()

        assertEquals(2L, bootstrapAttempts.get())
        assertTrue(synchronized(snapshots) { snapshots.any { it.contains("\"diagnostic_disk_failures\":1") } })
    }

    @Test
    fun eventAndErrorPayloadsAreBounded() {
        val sink = RecordingSink()
        val diagnostics = HelperDiagnostics(
            "boot-a", 1, "generation-a", 1_000,
            TelemetryWorkerSpool.Footprint(0, 0), sink, FakeClock(), 8
        )
        diagnostics.context("e".repeat(500), "m".repeat(2_000))
        diagnostics.error("x".repeat(2_000))
        diagnostics.close()
        assertTrue(sink.lines.any { it.contains("\"event\":\"${"e".repeat(HelperDiagnostics.MAX_EVENT_CHARS)}\"") })
        assertTrue(sink.lines.any { it.contains("\"message\":\"${"m".repeat(HelperDiagnostics.MAX_MESSAGE_CHARS)}\"") })
        assertEquals(HelperDiagnostics.MAX_MESSAGE_CHARS, diagnostics.snapshotForTest().lastError!!.length)
    }

    @Test
    fun callbackAggregateIsPersistedWithoutPerEventPayloads() {
        val sink = RecordingSink()
        val diagnostics = HelperDiagnostics(
            "boot-a", 1, "generation-a", 1_000,
            TelemetryWorkerSpool.Footprint(0, 0), sink, FakeClock(), 8
        )
        diagnostics.callback(HelperCallbackController.DiagnosticsSnapshot(
            10, 2, 3, 7, 1, 99, 100, 20, 150,
            200, -1, 250, 4, "listener_error_-1"
        ))
        val snapshot = diagnostics.snapshotForTest()
        assertEquals(10, snapshot.callbackAcceptedNativeKeys)
        assertEquals(2, snapshot.callbackFailedNativeKeys)
        assertEquals(99L, snapshot.callbacksReceived)
        assertEquals(4L, snapshot.callbackQueueLossCount)
        diagnostics.close()
        val stopped = sink.lines.last { it.contains("\"event\":\"helper_stop\"") }
        assertTrue(stopped.contains("\"callback_main_queue_bytes\":100"))
        assertTrue(stopped.contains("\"callback_secondary_queue_oldest_age_ms\":null"))
        assertTrue(stopped.contains("\"callback_retry_reason\":\"listener_error_-1\""))
        assertTrue(!stopped.contains("rawBits"))
    }

    @Test
    fun jsonlAndBootstrapRetainOnlyActivePlusThreeBoundedRotations() {
        val root = Files.createTempDirectory("helper-diagnostics-store").toFile()
        try {
            val store = HelperDiagnosticFileStore(root, 64)
            repeat(20) { store.persist("{\"event\":\"${it.toString().padStart(2, '0')}\"}", "{\"snapshot\":$it}") }
            repeat(20) { store.persistBootstrap("bootstrap-${it.toString().padStart(2, '0')}\n".toByteArray()) }

            val jsonLogs = root.listFiles { _, name ->
                name == HelperDiagnosticFileStore.ACTIVE_NAME ||
                    name.matches(Regex("${Regex.escape(HelperDiagnosticFileStore.ACTIVE_NAME)}\\.[1-3]"))
            }.orEmpty()
            val bootstrapLogs = root.listFiles { _, name ->
                name == HelperDiagnosticFileStore.BOOTSTRAP_NAME ||
                    name.matches(Regex("${Regex.escape(HelperDiagnosticFileStore.BOOTSTRAP_NAME)}\\.[1-3]"))
            }.orEmpty()
            assertEquals(4, jsonLogs.size)
            assertEquals(4, bootstrapLogs.size)
            assertTrue(jsonLogs.all { it.length() <= 64 })
            assertTrue(bootstrapLogs.all { it.length() <= 64 })
            assertTrue(File(root, HelperDiagnosticFileStore.SNAPSHOT_NAME).readText().contains("\"snapshot\":19"))
            assertTrue(File(root, HelperDiagnosticFileStore.LOCK_NAME).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preexistingOversizedHistoryIsTailBoundedBeforeRotation() {
        val root = Files.createTempDirectory("helper-diagnostics-oversized").toFile()
        try {
            val store = HelperDiagnosticFileStore(root, 64)
            File(root, HelperDiagnosticFileStore.ACTIVE_NAME).writeText(
                (0 until 10).joinToString("\n", postfix = "\n") { "{\"old\":$it}" }
            )
            File(root, HelperDiagnosticFileStore.ACTIVE_NAME + ".1").writeText(
                (10 until 20).joinToString("\n", postfix = "\n") { "{\"old\":$it}" }
            )
            File(root, HelperDiagnosticFileStore.BOOTSTRAP_NAME).writeBytes(ByteArray(120) { 'c'.code.toByte() })

            store.persist("{\"event\":\"new\"}", "{\"snapshot\":1}")
            store.persistBootstrap("bootstrap-new\n".toByteArray())

            val bounded = root.listFiles().orEmpty().filter {
                it.name.startsWith(HelperDiagnosticFileStore.ACTIVE_NAME) ||
                    it.name.startsWith(HelperDiagnosticFileStore.BOOTSTRAP_NAME)
            }
            assertTrue(bounded.isNotEmpty())
            assertTrue(bounded.all { it.length() <= 64 })
            root.listFiles().orEmpty()
                .filter { it.name.startsWith(HelperDiagnosticFileStore.ACTIVE_NAME) }
                .flatMap { it.readLines() }
                .filter { it.isNotEmpty() }
                .forEach { assertTrue(it.startsWith("{") && it.endsWith("}"), "partial JSONL line: $it") }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reporterSourceHasNoSpoolScanOrSynchronousTelemetryWrite() {
        val source = sourceFile("HelperDiagnostics.java").readText()
        val loop = sourceFile("HelperDualStreamRuntime.java").readText()
        assertTrue(!source.contains("listFiles("))
        assertTrue(source.contains("ArrayBlockingQueue"))
        assertTrue(source.contains("queue.offer"))
        assertTrue(loop.contains("POLL_INTERVAL_MS = 500L"))
        assertTrue(loop.contains("mainSpool.canAppend()"))
        val cappedBranch = loop.substringAfter("if (!mainSpool.canAppend())")
            .substringBefore("return;")
        assertTrue(cappedBranch.contains("diagnostics.capSkippedPollCycle()"))
    }

    @Test
    fun allPostBootstrapStartupAndRuntimeWorkHasGuaranteedDiagnosticLifecycle() {
        val daemon = sourceFile("CollectorHelperDaemon.java").readText()
        val lifecycle = daemon.substringAfter("HelperBootstrapOutput.install(helperDiagnostics);")
            .substringBefore("private static int runMain(")
        val body = daemon.substringAfter("private static int runMain(")
            .substringBefore("private static ReadValue scalarRead(")
        val resources = daemon.substringAfter("private static final class HelperResources")

        assertTrue(lifecycle.contains("try {"))
        assertTrue(lifecycle.contains("catch (Exception error)"))
        assertTrue(lifecycle.contains("catch (Error error)"))
        assertTrue(lifecycle.contains("helper startup/runtime failed:"))
        assertTrue(lifecycle.contains("finally"))
        assertTrue(lifecycle.indexOf("resources.close()") < lifecycle.indexOf("helperDiagnostics.close()"))
        assertTrue(lifecycle.indexOf("helperDiagnostics.close()") < lifecycle.indexOf("System.exit(exitCode)"))
        assertTrue(body.contains("prepareMainLooper();"))
        assertTrue(body.contains("loadWhitelist(apkPath, mainRows)"))
        assertTrue(body.contains("resources.workerSpool = workerSpool"))
        assertTrue(body.contains("resources.runtime = runtime"))
        assertTrue(!body.contains("System.exit("))
        assertTrue(resources.indexOf("runtime.close()") < resources.indexOf("workerSpool.close()"))
        assertTrue(resources.indexOf("workerSpool.close()") < resources.indexOf("ownerLock.close()"))
    }

    private class RecordingSink : HelperDiagnostics.Sink {
        val lines = mutableListOf<String>()
        override fun persist(jsonLine: String, snapshotJson: String) {
            synchronized(lines) { lines += jsonLine }
        }
        override fun persistBootstrap(chunk: ByteArray) = Unit
    }

    private class FakeClock(wall: Long = 1_000, elapsed: Long = 100) : HelperDiagnostics.Clock {
        private val wall = AtomicLong(wall)
        private val elapsed = AtomicLong(elapsed)
        override fun wallTimeMs(): Long = wall.get()
        override fun elapsedTimeMs(): Long = elapsed.get()
        fun set(wallMs: Long, elapsedMs: Long) {
            wall.set(wallMs)
            elapsed.set(elapsedMs)
        }
    }

    private fun sourceFile(name: String): File = listOf(
        File("app/src/main/java/com/bydcollector/collector/direct/$name"),
        File("src/main/java/com/bydcollector/collector/direct/$name")
    ).firstOrNull(File::isFile) ?: error("Missing $name")
}
