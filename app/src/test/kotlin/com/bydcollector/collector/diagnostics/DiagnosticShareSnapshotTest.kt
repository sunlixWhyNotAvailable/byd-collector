package com.bydcollector.collector.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import com.bydcollector.collector.maintenance.ArchiveStorageItemPhase
import com.bydcollector.collector.maintenance.ArchiveStorageItemState
import com.bydcollector.collector.maintenance.ArchiveStorageJobMode
import com.bydcollector.collector.maintenance.ArchiveStorageJobStatus
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.maintenance.DbMaintenanceRuntimeStatus
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull

class DiagnosticShareSnapshotTest {
    @Test
    fun shareCopiesAreSanitizedBeforeZipWithoutChangingRecordingsOrDatabaseArchives() {
        val root = Files.createTempDirectory("collector-share-privacy").toFile()
        try {
            val original = File(root, "recordings").apply { mkdirs() }
            val event = JSONObject().put("version", "2.7.7").put("model", "BYD Sea Lion 07")
                .put("host", "100.100.1.2").put("port", 8086).put("pid", 512)
                .put("password", "fixture-secret").put("latitude", 50.123456)
                .put("longitude", 30.654321).put("vin", "L123456789012345X")
                .put("chat_id", "-1001234567890").put("SSID", "Private Home Network")
                .put("BSSID", "aa:bb:cc:dd:ee:ff").toString()
            val source = File(original, "operational_events.jsonl").apply { writeText(event + "\n") }
            val sourceBytes = source.readBytes()
            val databaseArchive = File(root, "database.zip").apply { writeBytes(sourceBytes) }
            val snapshot = File(root, "snapshot_test").apply { mkdirs() }
            original.copyRecursively(snapshot, overwrite = true)
            File(snapshot, "helper.log").writeText("vin=L123456789012345X version=2.7.7 host=100.100.1.2\n" +
                "WifiInfo: SSID: Private Home Network, BSSID: aa:bb:cc:dd:ee:ff\n" +
                "CarPropertyService: getVin() -> L123456789012345X\n")

            assertEquals("ok", sanitizeDiagnosticSnapshot(snapshot))
            val zip = File(root, "shared.zip")
            DiagnosticZipWriter.writeLatestZip(zip, snapshot)
            val entries = ZipInputStream(zip.inputStream()).use { input ->
                buildMap {
                    while (true) {
                        val entry = input.nextEntry ?: break
                        put(entry.name, input.readBytes().toString(Charsets.UTF_8))
                        input.closeEntry()
                    }
                }
            }
            val shared = entries.values.joinToString("\n")
            assertFalse(shared.contains("fixture-secret"))
            assertFalse(shared.contains("50.123456"))
            assertFalse(shared.contains("30.654321"))
            assertFalse(shared.contains("L123456789012345X"))
            assertFalse(shared.contains("-1001234567890"))
            assertFalse(shared.contains("Private Home Network"))
            assertFalse(shared.contains("aa:bb:cc:dd:ee:ff"))
            assertTrue(shared.contains("2.7.7"))
            assertTrue(shared.contains("BYD Sea Lion 07"))
            assertTrue(shared.contains("100.100.1.2"))
            val redacted = JSONObject(entries.getValue("operational_events.jsonl"))
            assertEquals(8086, redacted.getInt("port"))
            assertEquals(512, redacted.getInt("pid"))
            assertTrue(entries.getValue("helper.log").contains(redacted.getString("vin")))
            assertTrue(entries.getValue("privacy_status.txt").contains("not guaranteed anonymous"))
            assertContentEquals(sourceBytes, source.readBytes())
            assertContentEquals(sourceBytes, databaseArchive.readBytes())
            assertFalse(snapshot.walkTopDown().any { it.name.endsWith(".tmp") })
            assertFailsWith<IllegalArgumentException> { sanitizeDiagnosticSnapshot(original) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedMiddleAndTornUtf8TailPreserveEveryOtherCompleteJsonRecord() {
        val root = Files.createTempDirectory("collector-share-records").toFile()
        try {
            val snapshot = File(root, "snapshot_records").apply { mkdirs() }
            val journal = File(snapshot, "events.jsonl")
            journal.writeBytes(("{\"sequence\":1}\n{\"broken\":\n{\"sequence\":2}\n{\"tail\":\"".toByteArray() +
                byteArrayOf(0xC3.toByte())))
            assertEquals("partial", sanitizeDiagnosticSnapshot(snapshot))
            assertEquals(listOf(1, 2), journal.readLines().map { JSONObject(it).getInt("sequence") })
            assertTrue(File(snapshot, "privacy_status.txt").readText().contains("omitted_records=2"))

            val completeWithoutNewline = File(snapshot, "complete.jsonl")
            completeWithoutNewline.writeText("{\"sequence\":3}")
            sanitizeDiagnosticSnapshot(snapshot)
            assertEquals(3, JSONObject(completeWithoutNewline.readText()).getInt("sequence"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedRecordAndUnknownComponentAreOmittedWithoutRawFallback() {
        val root = Files.createTempDirectory("collector-share-omission").toFile()
        try {
            val snapshot = File(root, "snapshot_omission").apply { mkdirs() }
            val journal = File(snapshot, "events.jsonl").apply {
                writeText("{\"huge\":\"" + "x".repeat(256 * 1024) + "\"}\n{\"retained\":true}\n")
            }
            val unsupported = File(snapshot, "private.bin").apply { writeText("fixture-secret") }
            assertEquals("partial", sanitizeDiagnosticSnapshot(snapshot))
            assertFalse(unsupported.exists())
            assertEquals(1, journal.readLines().size)
            assertTrue(JSONObject(journal.readText()).getBoolean("retained"))
            val status = File(snapshot, "privacy_status.txt").readText()
            assertTrue(status.contains("reason=unsupported_type"))
            assertTrue(status.contains("omitted_records=1"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun outputCapsRemainUtf8SafeAndPreserveExistingTruncationAfterMasking() {
        val root = Files.createTempDirectory("collector-share-limits").toFile()
        try {
            val snapshot = File(root, "snapshot_limits").apply { mkdirs() }
            val influx = File(snapshot, "influx_evidence.txt").apply {
                writeText("schema_version=1\n" + "подія=так password=x\n".repeat(10_000) + "truncated=0\n")
            }
            val trips = File(snapshot, "trips_telegram_evidence.txt").apply {
                writeText("trip_ref=abcdef\ntruncated=1\n")
            }
            assertEquals("partial", sanitizeDiagnosticSnapshot(snapshot))
            assertTrue(influx.length() <= DiagnosticLogRecorder.INFLUX_EVIDENCE_MAX_BYTES)
            assertTrue(trips.length() <= DiagnosticLogRecorder.TRIPS_TELEGRAM_EVIDENCE_MAX_BYTES)
            val text = influx.readText()
            assertFalse(text.contains('\uFFFD'))
            assertTrue(text.endsWith("truncated=1\n"))
            assertEquals(1, text.lineSequence().count { it.startsWith("truncated=") })
            assertFalse(text.contains("password=x"))
            assertEquals("trip_ref=abcdef\ntruncated=1\n", trips.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun timeBoundsUseActualStreamingJournalTimestampsAcrossRotations() {
        val root = Files.createTempDirectory("collector-share-time-bounds").toFile()
        try {
            val operational = File(root, "operational_journal").apply { mkdirs() }
            val maintenance = File(root, "maintenance_journal").apply { mkdirs() }
            File(operational, "operational_events.1.jsonl").apply {
                writeText(eventAt("2026-09-28T10:00:00Z") + "\n")
                setLastModified(1L)
            }
            File(operational, "operational_events.jsonl").apply {
                writeText(
                    eventAt("2026-09-28T12:00:00Z") + "\n" +
                        "{broken}\n" + eventAt("2026-09-28T11:00:00Z") + "\n"
                )
                setLastModified(Long.MAX_VALUE)
            }
            File(maintenance, "operational_events.jsonl").writeText(eventAt("2026-09-28T09:30:00Z") + "\n")

            val operationalBounds = diagnosticJournalTimeBounds(operational)
            val maintenanceBounds = diagnosticJournalTimeBounds(maintenance)

            assertEquals("2026-09-28T10:00:00Z", operationalBounds.firstTimestamp)
            assertEquals("2026-09-28T12:00:00Z", operationalBounds.lastTimestamp)
            assertEquals(3, operationalBounds.records)
            assertEquals(1, operationalBounds.invalidRecords)
            assertEquals(0, operationalBounds.failedFiles)
            assertEquals("2026-09-28T09:30:00Z", maintenanceBounds.firstTimestamp)
            assertEquals("2026-09-28T09:30:00Z", maintenanceBounds.lastTimestamp)
            assertEquals(1, maintenanceBounds.records)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun journalTimeBoundsSkipOversizedAndInvalidUtf8RecordsButKeepValidRows() {
        val root = Files.createTempDirectory("collector-share-time-bounds-limits").toFile()
        try {
            val journal = File(root, "operational_journal").apply { mkdirs() }
            File(journal, "operational_events.jsonl").writeBytes(
                ("{\"timestamp\":\"2026-09-28T10:00:00Z\",\"detail\":\"" +
                    "x".repeat(256 * 1024) + "\"}\n").toByteArray() +
                    byteArrayOf(0xC3.toByte(), 0x0A) +
                    (eventAt("2026-09-28T11:00:00Z") + "\n").toByteArray()
            )

            val bounds = diagnosticJournalTimeBounds(journal)

            assertEquals("2026-09-28T11:00:00Z", bounds.firstTimestamp)
            assertEquals("2026-09-28T11:00:00Z", bounds.lastTimestamp)
            assertEquals(1, bounds.records)
            assertEquals(2, bounds.invalidRecords)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sharePreflightSourceSizeIncludesBothJournalRings() {
        assertEquals(1_500L, diagnosticShareSourceBytes(1_000L, 300L, 200L))
        assertTrue(hasDiagnosticShareSpace(1_500L * 4L + 64L, 1_500L, 64L))
        assertFailsWith<ArithmeticException> {
            diagnosticShareSourceBytes(Long.MAX_VALUE, 1L, 0L)
        }
    }

    @Test
    fun eventBarrierWaitsForEarlierWorkAndReportsTimeoutOrRejection() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            executor.execute {
                entered.countDown()
                release.await()
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals("timeout", awaitOperationalEventBarrier(executor, 25L))
            release.countDown()
            assertEquals("ok", awaitOperationalEventBarrier(executor, 1_000L))
            assertTrue(awaitOperationalEventBarrier({ throw java.util.concurrent.RejectedExecutionException() }, 1_000L)
                .startsWith("rejected="))
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun journalClearAttemptsBothRingsAndPreservesPerRingFailure() {
        val result = clearDiagnosticJournalHistory(
            clearOperational = { throw IllegalStateException("operational failed") },
            clearMaintenance = { 2 }
        )

        assertEquals(2, result.removed)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().startsWith("operational_journal=IllegalStateException"))
    }

    @Test
    fun timedOutQueuedClearIsCancelledBeforeItCanEraseLaterEvidence() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clearCalled = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            executor.execute {
                entered.countDown()
                release.await()
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))

            val execution = clearDiagnosticJournalsOnExecutor(
                executor = executor,
                timeoutMs = 25L,
                clearOperational = { clearCalled.set(true); 1 },
                clearMaintenance = { 1 }
            )

            assertNull(execution.result)
            assertEquals("timeout started=false outcome=cancelled_before_start", execution.warning)
            release.countDown()
            assertEquals("ok", awaitOperationalEventBarrier(executor, 1_000L))
            assertFalse(clearCalled.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun timedOutRunningClearReportsUnknownOutcomeWithoutRemovedCount() {
        val executor = Executors.newSingleThreadExecutor()
        val clearStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val maintenanceAttempted = CountDownLatch(1)
        try {
            val execution = clearDiagnosticJournalsOnExecutor(
                executor = executor,
                timeoutMs = 25L,
                clearOperational = {
                    clearStarted.countDown()
                    release.await()
                    1
                },
                clearMaintenance = { maintenanceAttempted.countDown(); 1 }
            )

            assertTrue(clearStarted.await(1, TimeUnit.SECONDS))
            assertNull(execution.result)
            assertEquals("timeout started=true outcome=unknown", execution.warning)
            release.countDown()
            assertTrue(maintenanceAttempted.await(1, TimeUnit.SECONDS))
            assertEquals("ok", awaitOperationalEventBarrier(executor, 1_000L))
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun archiveStateSnapshotKeepsPerItemOutcomeAndOmitsFullMaintenancePath() {
        val state = ArchiveStorageItemState(
            archiveId = "main_20260928_120000",
            operationId = "owner-1",
            phase = ArchiveStorageItemPhase.FAILED,
            stepIndex = 4,
            stepCount = 5,
            startedAtMs = 10L,
            updatedAtMs = 20L,
            completedAtMs = 20L,
            error = "CRC verification failed"
        )
        val json = buildArchiveStorageStateJson(
            job = ArchiveStorageJobStatus(
                mode = ArchiveStorageJobMode.COMPRESS,
                running = true,
                operationId = "owner-1",
                phase = ArchiveStorageItemPhase.VERIFYING_ZIP,
                updatedAtMs = 19L
            ),
            items = listOf(state),
            pendingAuditOperationId = "audit-2",
            maintenance = DbMaintenanceRuntimeStatus(
                operation = DbMaintenanceOperation.ARCHIVE,
                running = true,
                stepIndex = 3,
                stepCount = 7,
                archivePath = "/private/vehicle/archive.db",
                startedAtMs = 5L,
                updatedAtMs = 20L
            )
        )

        assertEquals("audit-2", json.getString("pending_audit_operation_id"))
        assertEquals("VERIFYING_ZIP", json.getJSONObject("archive_job").getString("phase"))
        val savedItem = json.getJSONArray("archive_items").getJSONObject(0)
        assertEquals("main_20260928_120000", savedItem.getString("archive_id"))
        assertEquals("FAILED", savedItem.getString("phase"))
        assertEquals(10L, savedItem.getLong("started_at_ms"))
        assertEquals("CRC verification failed", savedItem.getString("error"))
        assertEquals("archive.db", json.getJSONObject("database_maintenance").getString("archive_basename"))
        assertFalse(json.toString().contains("/private/vehicle"))
    }

    private fun eventAt(timestamp: String): String =
        JSONObject().put("timestamp", timestamp).put("message", "test").toString()
}
