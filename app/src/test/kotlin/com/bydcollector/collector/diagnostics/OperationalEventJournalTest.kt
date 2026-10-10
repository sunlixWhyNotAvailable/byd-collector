package com.bydcollector.collector.diagnostics

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationalEventJournalTest {
    @Test
    fun maintenanceEvidenceSurvivesGeneralRotationAndHasIndependentClear() {
        assertEquals(64L * 1024 * 1024, OperationalEventJournal.MAX_FILE_BYTES * 4)
        assertEquals(8L * 1024 * 1024, OperationalEventJournal.MAINTENANCE_MAX_FILE_BYTES * 4)
        assertTrue(OperationalEventJournal.isMaintenanceEvent("archive_storage_phase", "progress"))
        assertTrue(OperationalEventJournal.isMaintenanceEvent("database_maintenance", "database_maintenance_end"))
        assertFalse(OperationalEventJournal.isMaintenanceEvent("database_maintenance", "database_maintenance_heartbeat"))
        assertFalse(OperationalEventJournal.isMaintenanceEvent("influx", "request"))
        val root = Files.createTempDirectory("bydcollector-journal-independence").toFile()
        try {
            val general = OperationalEventJournal(File(root, "general"), 512, bootId = "boot", pid = 1)
            val maintenance = OperationalEventJournal(File(root, "maintenance"), 512, bootId = "boot", pid = 1)
            maintenance.append("2026-09-28T10:00:00Z", 1, "archive_storage_terminal", "ready", null)
            repeat(100) { general.append("2026-09-28T10:01:00Z", it.toLong(), "influx", "progress", null) }
            assertTrue(general.retainedBytes() <= 512L * 4)
            assertEquals(1, maintenance.snapshotTo(File(root, "snapshot")))
            general.clear()
            assertEquals(0L, general.retainedBytes())
            assertTrue(maintenance.retainedBytes() > 0)
            assertEquals(1, maintenance.clear())
            assertEquals(0L, maintenance.retainedBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rotatesToActivePlusThreeSegmentsAndDropsTheOldest() {
        val root = Files.createTempDirectory("bydcollector-event-journal").toFile()
        try {
            val sample = OperationalEventJournal.journalLine(
                "2026-08-25T12:00:00Z", 1, "boot", 7, "event_0", "message", null
            )
            val journal = OperationalEventJournal(
                root = root,
                maxFileBytes = sample.toByteArray(Charsets.UTF_8).size.toLong() + 1L,
                retainedRotations = 3,
                bootId = "boot",
                pid = 7
            )

            repeat(6) { index ->
                journal.append(
                    timestamp = "2026-08-25T12:00:00Z",
                    elapsedMs = index.toLong(),
                    category = "event_$index",
                    message = "message",
                    detail = null
                )
            }

            val files = root.listFiles().orEmpty().filter(File::isFile)
            assertEquals(4, files.size)
            assertTrue(File(root, "operational_events.jsonl").readText().contains("event_5"))
            assertTrue(File(root, "operational_events.1.jsonl").readText().contains("event_4"))
            assertTrue(File(root, "operational_events.2.jsonl").readText().contains("event_3"))
            assertTrue(File(root, "operational_events.3.jsonl").readText().contains("event_2"))
            assertFalse(files.any { it.readText().contains("event_0") || it.readText().contains("event_1") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writesValidJsonWithNullableDetailAndExplicitTruncation() {
        val normal = JSONObject(
            OperationalEventJournal.journalLine(
                "2026-08-25T12:00:00Z", 123, "boot", 9, "category", "message", null
            )
        )
        val truncated = JSONObject(
            OperationalEventJournal.journalLine(
                "2026-08-25T12:00:00Z", 124, "boot", 9, "category", "message", "x".repeat(40_000)
            )
        )

        assertEquals(1, normal.getInt("schema_version"))
        assertTrue(normal.isNull("detail"))
        assertFalse(normal.getBoolean("truncated"))
        assertEquals(32_768, truncated.getString("detail").length)
        assertTrue(truncated.getBoolean("truncated"))
    }

    @Test
    fun tryAppendPersistsAValidEventWithoutTheOperationalExecutor() {
        val root = Files.createTempDirectory("bydcollector-event-journal-try-append").toFile()
        try {
            val journal = OperationalEventJournal(
                root = root,
                bootId = "boot-try-append",
                pid = 17
            )

            assertTrue(
                journal.tryAppend(
                    timestamp = "2026-09-27T12:00:00Z",
                    elapsedMs = 456,
                    category = "process_exit",
                    message = "fatal_exception",
                    detail = "evidence"
                )
            )

            val active = File(root, OperationalEventJournal.ACTIVE_FILE_NAME)
            val lines = active.readLines()
            assertEquals(1, lines.size)
            val event = JSONObject(lines.single())
            assertEquals("boot-try-append", event.getString("boot_id"))
            assertEquals(17, event.getInt("pid"))
            assertEquals("process_exit", event.getString("category"))
            assertEquals("fatal_exception", event.getString("message"))
            assertEquals("evidence", event.getString("detail"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun concurrentAppendsRemainCompleteJsonLinesAndSnapshotThenClearIsSerialized() {
        val root = Files.createTempDirectory("bydcollector-event-journal-concurrent").toFile()
        val snapshot = Files.createTempDirectory("bydcollector-event-journal-snapshot").toFile()
        try {
            val journal = OperationalEventJournal(
                root = root,
                maxFileBytes = 2L * 1024L * 1024L,
                retainedRotations = 3,
                bootId = "boot",
                pid = 11
            )
            val start = CountDownLatch(1)
            val workers = (0 until 4).map { worker ->
                thread(start = true) {
                    start.await()
                    repeat(50) { index ->
                        journal.append("2026-08-25T12:00:00Z", index.toLong(), "w$worker", "e$index", null)
                    }
                }
            }
            start.countDown()
            workers.forEach(Thread::join)

            assertEquals(1, journal.snapshotTo(snapshot))
            val lines = File(snapshot, "operational_events.jsonl").readLines().filter(String::isNotBlank)
            assertEquals(200, lines.size)
            lines.forEach { JSONObject(it) }
            assertEquals(1, journal.clear())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
            snapshot.deleteRecursively()
        }
    }

    @Test
    fun appendCanProceedDuringSnapshotCopyAndSnapshotKeepsItsCapturedPrefix() {
        val root = Files.createTempDirectory("bydcollector-event-journal-snapshot-source").toFile()
        val snapshot = Files.createTempDirectory("bydcollector-event-journal-snapshot-copy").toFile()
        try {
            val journal = OperationalEventJournal(
                root = root,
                maxFileBytes = 256L * 1024L * 1024L,
                retainedRotations = 3,
                bootId = "boot-snapshot-race",
                pid = 19
            )
            val active = File(root, OperationalEventJournal.ACTIVE_FILE_NAME)
            RandomAccessFile(active, "rw").use { file ->
                file.setLength(SNAPSHOT_RACE_SOURCE_BYTES)
                file.seek(0)
                file.write("captured-prefix".toByteArray(Charsets.UTF_8))
            }
            val capturedBytes = active.length()
            val snapshotFailure = AtomicReference<Throwable?>()
            val snapshotDone = CountDownLatch(1)
            val snapshotWorker = thread(name = "operational-journal-snapshot-gate") {
                try {
                    assertEquals(1, journal.snapshotTo(snapshot))
                } catch (error: Throwable) {
                    snapshotFailure.set(error)
                } finally {
                    snapshotDone.countDown()
                }
            }
            var appendWorker: Thread? = null

            try {
                val copy = File(snapshot, active.name)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var copyInProgress = false
                while (System.nanoTime() < deadline && snapshotDone.count > 0) {
                    val length = copy.length()
                    if (length > 0L && length < capturedBytes) {
                        copyInProgress = true
                        break
                    }
                    Thread.sleep(1)
                }
                assertTrue(copyInProgress, "snapshot copy did not expose a partial target within the gate timeout")

                val appendFailure = AtomicReference<Throwable?>()
                val appendDone = CountDownLatch(1)
                val appendDuringCopy = AtomicBoolean(false)
                val appender = thread(name = "operational-journal-append-gate") {
                    try {
                        journal.append("2026-10-10T10:00:00Z", 2, "snapshot_race", "append", null)
                        appendDuringCopy.set(snapshotDone.count > 0)
                    } catch (error: Throwable) {
                        appendFailure.set(error)
                    } finally {
                        appendDone.countDown()
                    }
                }
                appendWorker = appender
                val appendedWhileCopying = appendDone.await(5, TimeUnit.SECONDS) && appendDuringCopy.get()

                assertTrue(!appender.isAlive, "journal append did not finish")
                assertTrue(appendedWhileCopying, "append waited for the full snapshot copy")
                appendFailure.get()?.let { throw AssertionError("concurrent journal append failed", it) }
                assertTrue(snapshotDone.await(15, TimeUnit.SECONDS), "snapshot copy did not finish")
                snapshotFailure.get()?.let { throw AssertionError("concurrent journal snapshot failed", it) }
                assertEquals(capturedBytes, copy.length(), "snapshot must copy only the captured source prefix")
                val prefix = ByteArray("captured-prefix".length)
                copy.inputStream().use { assertEquals(prefix.size, it.read(prefix)) }
                assertTrue(prefix.contentEquals("captured-prefix".toByteArray(Charsets.UTF_8)))
                assertTrue(active.length() > capturedBytes, "append was not persisted to the active journal")
            } finally {
                appendWorker?.join(5_000)
                snapshotWorker.join(15_000)
            }
            assertTrue(!snapshotWorker.isAlive, "journal snapshot did not finish")
            snapshotFailure.get()?.let { throw AssertionError("concurrent journal snapshot failed", it) }
        } finally {
            root.deleteRecursively()
            snapshot.deleteRecursively()
        }
    }

    companion object {
        private const val SNAPSHOT_RACE_SOURCE_BYTES = 128L * 1024L * 1024L
    }
}
