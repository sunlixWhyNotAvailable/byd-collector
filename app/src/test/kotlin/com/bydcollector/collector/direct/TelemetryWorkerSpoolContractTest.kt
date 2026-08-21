package com.bydcollector.collector.direct

import java.io.File
import java.nio.file.Files
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
                assertTrue(spool.append(sample("boot-a", "generation-a", 2, 200)))
                assertTrue(spool.append(sample("boot-a", "generation-a", 1, 100)))
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
                assertTrue(spool.append(sample("boot-a", "generation-a", 1, 100)))
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
                assertTrue(spool.append(sample("boot-a", "generation-a", 1, 100)))
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
                assertTrue(spool.append(sample(identity, 100)))
                assertEquals(0, spool.acknowledge(other, 101))
                assertEquals(1, spool.acknowledge(identity, 102))
                assertEquals(0, spool.acknowledge(identity, 103))
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
                assertTrue(spool.append(first))
            }
            val recordBytes = sizingRoot.listFiles { _, name -> name.endsWith(".ready") }!!.single().length()
            TelemetryWorkerSpool.openForTest(cappedRoot, recordBytes).use { spool ->
                assertTrue(spool.canAppend())
                assertTrue(spool.append(first))
                assertFalse(spool.canAppend())
                assertFalse(spool.append(second))
                assertEquals(listOf(1L), spool.pending(100).map { it.identity.pollSequence })
            }
        } finally {
            deleteRecursively(sizingRoot)
            deleteRecursively(cappedRoot)
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

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        file.delete()
    }

    private fun sourceFile(): File = listOf(
        File("app/src/main/java/com/bydcollector/collector/direct/TelemetryWorkerSpool.java"),
        File("src/main/java/com/bydcollector/collector/direct/TelemetryWorkerSpool.java")
    ).firstOrNull(File::isFile) ?: error("Missing TelemetryWorkerSpool.java")
}
