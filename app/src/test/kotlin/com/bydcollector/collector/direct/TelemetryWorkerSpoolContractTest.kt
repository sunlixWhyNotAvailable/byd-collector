package com.bydcollector.collector.direct

import java.io.File
import java.nio.file.Files
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryWorkerSpoolContractTest {
    @Test
    fun sampleRoundTripsAcrossReopenInCapturedOrder() {
        val root = tempDirectory()
        try {
            TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(sample("boot-a", "generation-a", 2, 200)))
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(sample("boot-a", "generation-a", 1, 100)))
            }
            TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                val pending = spool.pending(100)
                assertEquals(listOf(1L, 2L), pending.map { it.identity.pollSequence })
                assertEquals(listOf(100L, 200L), pending.map { it.capturedWallMs })
                assertEquals(listOf(11, null), pending.first().values.map { it.raw })
                assertEquals("read failed", pending.last().values.last().error)
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun pendingIgnoresTmpAndQuarantinesMalformedReadyWhileKeepingValidRecords() {
        val root = tempDirectory()
        try {
            TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(sample("boot-a", "generation-a", 1, 100)))
            }
            val ready = root.listFiles { _, name -> name.endsWith(".ready") }!!.single()
            File(root, "unfinished.tmp").writeText(ready.readText())
            File(root, "broken.ready").writeText("not-json")

            TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                assertEquals(listOf(1L), spool.pending(100).map { it.identity.pollSequence })
            }
            assertTrue(File(root, "broken.ready.bad").isFile)
            assertTrue(File(root, "unfinished.tmp").isFile)
            assertFalse(File(root, "broken.ready").exists())
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun pendingQuarantinesSemanticMismatchInsteadOfBlockingReplay() {
        val root = tempDirectory()
        try {
            TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(sample("boot-a", "generation-a", 1, 100)))
                val pending = spool.pending(100) {
                    throw IllegalArgumentException("catalog mismatch")
                }
                assertTrue(pending.isEmpty())
            }
            assertTrue(root.listFiles { _, name -> name.contains(".ready.bad") }!!.single().isFile)
            assertFalse(root.listFiles { _, name -> name.endsWith(".ready") }!!.any())
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun acknowledgeDeletesOnlyExactReadyRecordAndIsIdempotent() {
        val root = tempDirectory()
        try {
            val identity = TelemetryWorkerSampleIdentity("boot-a", "generation-a", 1)
            val other = TelemetryWorkerSampleIdentity("boot-a", "generation-a", 2)
            TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(sample(identity, 100)))
                assertEquals(TelemetryWorkerSpool.AckStatus.NOT_FOUND, spool.acknowledge(other, 101).status)
                val released = spool.acknowledge(identity, 102)
                assertEquals(TelemetryWorkerSpool.AckStatus.RELEASED, released.status)
                assertEquals(1, released.releasedRecords)
                assertTrue(released.releasedBytes > 0L)
                assertEquals(TelemetryWorkerSpool.AckStatus.NOT_FOUND, spool.acknowledge(identity, 103).status)
                assertTrue(spool.pending(100).isEmpty())
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun capRejectsNewSampleWithoutEvictingPendingData() {
        val sizingRoot = tempDirectory()
        val cappedRoot = tempDirectory()
        try {
            val first = sample("boot-a", "generation-a", 1, 100)
            val second = sample("boot-a", "generation-a", 2, 200)
            TelemetryWorkerSpool.openForTest(sizingRoot, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(first))
            }
            val recordBytes = sizingRoot.listFiles { _, name -> name.endsWith(".ready") }!!.single().length()
            TelemetryWorkerSpool.openForTest(cappedRoot, recordBytes).use { spool ->
                assertTrue(spool.canAppend())
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(first))
                assertFalse(spool.canAppend())
                assertEquals(TelemetryWorkerSpool.AppendResult.CAP_REACHED, spool.append(second))
                assertEquals(listOf(1L), spool.pending(100).map { it.identity.pollSequence })
            }
        } finally {
            deleteRecursively(sizingRoot)
            deleteRecursively(cappedRoot)
        }
    }

    @Test
    fun exact128MiBFootprintCountsTmpAndBadButOnlyReadyIsPending() {
        val root = tempDirectory()
        try {
            val tmp = File(root, "large.tmp")
            val bad = File(root, "old.ready.bad")
            RandomAccessFile(tmp, "rw").use { it.setLength(96L * 1024L * 1024L) }
            RandomAccessFile(bad, "rw").use { it.setLength(32L * 1024L * 1024L) }

            TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                val footprint = spool.observe()
                assertEquals(TelemetryWorkerSpool.MAX_SPOOL_BYTES, footprint.bytes)
                assertEquals(0, footprint.pendingReadyRecords)
                assertFalse(spool.canAppend())
                assertEquals(
                    TelemetryWorkerSpool.AppendResult.CAP_REACHED,
                    spool.append(sample("boot-a", "generation-a", 1, 100))
                )
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun diagnosticCallbackFailureCannotChangeSuccessfulAppendOrAck() {
        val root = tempDirectory()
        try {
            val identity = TelemetryWorkerSampleIdentity("boot-a", "generation-a", 1)
            TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                spool.setDiagnosticListener(object : TelemetryWorkerSpool.DiagnosticListener {
                    override fun onObservation(
                        footprint: TelemetryWorkerSpool.Footprint,
                        capacityBlocked: Boolean
                    ) = error("listener failed")
                    override fun onAppend(
                        result: TelemetryWorkerSpool.AppendResult,
                        footprint: TelemetryWorkerSpool.Footprint?
                    ) = error("listener failed")
                    override fun onPersistenceFailure(operation: String, error: Throwable) = error("listener failed")
                    override fun onAcknowledge(result: TelemetryWorkerSpool.AckResult) = error("listener failed")
                    override fun onQuarantine(recordBytes: Long) = error("listener failed")
                })

                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(sample(identity, 100)))
                val ack = spool.acknowledge(identity, 101)
                assertEquals(TelemetryWorkerSpool.AckStatus.RELEASED, ack.status)
                assertTrue(spool.pending(100).isEmpty())
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun corruptedAckIsFailedRatherThanNotFoundAndDoesNotDeleteRecord() {
        val root = tempDirectory()
        try {
            val identity = TelemetryWorkerSampleIdentity("boot-a", "generation-a", 1)
            TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(sample(identity, 100)))
                val ready = root.listFiles { _, name -> name.endsWith(".ready") }!!.single()
                ready.writeText("corrupt")

                val ack = spool.acknowledge(identity, 101)
                assertEquals(TelemetryWorkerSpool.AckStatus.FAILED, ack.status)
                assertEquals(0, ack.releasedRecords)
                assertEquals(0L, ack.releasedBytes)
                assertTrue(ready.isFile)
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun filesystemObservationFailureIsReportedWithoutInventingOccupancy() {
        val root = tempDirectory()
        val listener = RecordingDiagnosticListener()
        try {
            TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                spool.setDiagnosticListener(listener)
                assertTrue(root.delete())
                root.writeText("not a directory")
                assertFailsWith<IllegalStateException> {
                    spool.append(sample("boot-a", "generation-a", 1, 100))
                }
            }
            assertEquals(listOf("observe"), listener.persistenceOperations)
            assertTrue(listener.observations.isEmpty())
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun nonExactCapBlocksRepeatedPayloadAttemptUntilAckOrFootprintChange() {
        val sizingRoot = tempDirectory()
        val ackRoot = tempDirectory()
        val changedRoot = tempDirectory()
        try {
            val first = sample("boot-a", "generation-a", 1, 100)
            val second = sample("boot-a", "generation-a", 2, 200)
            TelemetryWorkerSpool.openForTest(sizingRoot, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(first))
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(second))
            }
            val recordBytes = sizingRoot.listFiles { _, name -> name.endsWith(".ready") }!!
                .associateBy({ it.name.substringAfterLast('_').substringBefore('.') }, { it.length() })
            val cap = recordBytes.getValue("1") + recordBytes.getValue("2") - 1L

            val sink = RecordingDiagnosticSink()
            val diagnostics = HelperDiagnostics(
                "boot-a", 1, "generation-a", cap,
                TelemetryWorkerSpool.Footprint(0, 0), sink, RealtimeClock(), 32, 25
            )
            TelemetryWorkerSpool.openForTest(ackRoot, cap).use { spool ->
                spool.setDiagnosticListener(diagnostics)
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(first))
                assertTrue(spool.observe().bytes < cap)
                assertEquals(TelemetryWorkerSpool.AppendResult.CAP_REACHED, spool.append(second))
                assertFalse(spool.canAppend())
                assertFalse(spool.canAppend())
                assertTrue(diagnostics.snapshotForTest().capReached)
                assertEquals(1L, diagnostics.snapshotForTest().capRefusals)

                assertEquals(TelemetryWorkerSpool.AckStatus.RELEASED, spool.acknowledge(first.identity, 300).status)
                assertTrue(spool.canAppend())
                assertFalse(diagnostics.snapshotForTest().capReached)
            }
            diagnostics.close()
            assertTrue(sink.lines.any { it.contains("\"event\":\"spool_cap_reached\"") })
            assertTrue(sink.lines.any { it.contains("\"event\":\"spool_cap_released\"") })

            TelemetryWorkerSpool.openForTest(changedRoot, cap).use { spool ->
                assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(first))
                assertEquals(TelemetryWorkerSpool.AppendResult.CAP_REACHED, spool.append(second))
                assertFalse(spool.canAppend())
                File(changedRoot, "external.tmp").writeText("x")
                assertTrue(spool.canAppend())
            }
        } finally {
            deleteRecursively(sizingRoot)
            deleteRecursively(ackRoot)
            deleteRecursively(changedRoot)
        }
    }

    @Test
    fun sampleAndValueValidationRemainsReadOnlyAndOrdered() {
        val identity = TelemetryWorkerSampleIdentity("boot-a", "generation-a", 42)
        val first = TelemetryWorkerSpool.Value(0, CollectorHelperProtocol.AUTO_TX_INT, 1001, 11, 0, 2, null)
        val duplicate = TelemetryWorkerSpool.Value(0, CollectorHelperProtocol.AUTO_TX_FLOAT, 1013, 12, 0, 3, null)

        assertFailsWith<IllegalArgumentException> {
            TelemetryWorkerSpool.Sample(
                identity, "catalog-v1", 100, 90, 10, 0, CollectorHelperProtocol.MODE_NATIVE,
                true, 0, null, listOf(first, duplicate)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TelemetryWorkerSpool.Value(0, 8, 1001, 11, 0, 2, null)
        }
        val source = sourceFile().readText()
        assertFalse(source.contains("android.database"))
        assertFalse(source.contains("SQLiteDatabase"))
        assertTrue(source.contains("descriptor.sync()"))
        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"))
        assertTrue(source.contains("TMP_SUFFIX"))
        assertTrue(source.contains("BAD_SUFFIX"))
    }

    private fun sample(boot: String, generation: String, sequence: Long, wall: Long): TelemetryWorkerSpool.Sample =
        sample(TelemetryWorkerSampleIdentity(boot, generation, sequence), wall)

    private fun sample(identity: TelemetryWorkerSampleIdentity, wall: Long): TelemetryWorkerSpool.Sample =
        TelemetryWorkerSpool.Sample(
            identity,
            "catalog-v1",
            wall,
            wall - 10,
            7,
            0,
            CollectorHelperProtocol.MODE_NATIVE,
            true,
            0,
            null,
            listOf(
                TelemetryWorkerSpool.Value(0, CollectorHelperProtocol.AUTO_TX_INT, 1001, 11, 0, 11, null),
                TelemetryWorkerSpool.Value(1, CollectorHelperProtocol.AUTO_TX_FLOAT, 1013, 12, -912, null, "read failed")
            )
        )

    private fun tempDirectory(): File = Files.createTempDirectory("telemetry-worker-spool").toFile()

    private class RecordingDiagnosticListener : TelemetryWorkerSpool.DiagnosticListener {
        val observations = mutableListOf<TelemetryWorkerSpool.Footprint>()
        val persistenceOperations = mutableListOf<String>()
        override fun onObservation(
            footprint: TelemetryWorkerSpool.Footprint,
            capacityBlocked: Boolean
        ) { observations += footprint }
        override fun onAppend(
            result: TelemetryWorkerSpool.AppendResult,
            footprint: TelemetryWorkerSpool.Footprint?
        ) = Unit
        override fun onPersistenceFailure(operation: String, error: Throwable) {
            persistenceOperations += operation
        }
        override fun onAcknowledge(result: TelemetryWorkerSpool.AckResult) = Unit
        override fun onQuarantine(recordBytes: Long) = Unit
    }

    private class RecordingDiagnosticSink : HelperDiagnostics.Sink {
        val lines = mutableListOf<String>()
        override fun persist(jsonLine: String, snapshotJson: String) {
            synchronized(lines) { lines += jsonLine }
        }
        override fun persistBootstrap(chunk: ByteArray) = Unit
    }

    private class RealtimeClock : HelperDiagnostics.Clock {
        override fun wallTimeMs() = System.currentTimeMillis()
        override fun elapsedTimeMs() = System.nanoTime() / 1_000_000L
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        file.delete()
    }

    private fun sourceFile(): File = listOf(
        File("app/src/main/java/com/bydcollector/collector/direct/TelemetryWorkerSpool.java"),
        File("src/main/java/com/bydcollector/collector/direct/TelemetryWorkerSpool.java")
    ).firstOrNull(File::isFile) ?: error("Missing TelemetryWorkerSpool.java")
}
