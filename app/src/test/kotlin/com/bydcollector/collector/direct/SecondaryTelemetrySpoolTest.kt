package com.bydcollector.collector.direct

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecondaryTelemetrySpoolTest {
    @Test
    fun initialDeltaNoChangeAndPresenceErrorTransitionsRoundTrip() {
        val root = tempDirectory()
        try {
            SecondaryTelemetrySpool.openForTest(root, 2L * 1024 * 1024, 3).use { spool ->
                assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, spool.append(cycle(1, values())))
                assertEquals(SecondaryTelemetrySpool.AppendResult.DELTA, spool.append(cycle(2, values())))
                assertEquals(
                    SecondaryTelemetrySpool.AppendResult.DELTA,
                    spool.append(cycle(3, values(second = value(1, -9, false, null, "missing"))))
                )

                val first = readAndAck(spool)
                assertEquals(SecondaryTelemetrySpool.Kind.FULL, first.kind)
                assertEquals(3, first.values.size)
                assertNull(first.predecessorIdentity)

                val second = readAndAck(spool)
                assertEquals(SecondaryTelemetrySpool.Kind.DELTA, second.kind)
                assertTrue(second.values.isEmpty())
                assertEquals(first.identity, second.predecessorIdentity)
                val secondState = second.materialize(first.identity, first.values)

                val third = readAndAck(spool)
                assertEquals(1, third.values.size)
                assertEquals(1, third.values.single().ordinal)
                assertFalse(third.values.single().rawPresent)
                assertEquals("missing", third.values.single().error)
                assertEquals(-9, third.values.single().status)
                assertEquals(1, third.okCount)
                assertEquals(2, third.errorCount)
                assertEquals("cycle-3", third.error)
                val state = third.materialize(second.identity, secondState)
                assertEquals(null, state[1].raw)
                assertNull(spool.oldest())
            }
        } finally { deleteRecursively(root) }
    }

    @Test
    fun completeIncomingFrameCardinalityAndOrderAreMandatory() {
        val root = tempDirectory()
        try {
            SecondaryTelemetrySpool.openForTest(root, 1_000_000, 3).use { spool ->
                assertFailsWith<IllegalArgumentException> { spool.append(cycle(1, values().take(2))) }
                assertFailsWith<IllegalArgumentException> {
                    spool.append(cycle(1, listOf(value(0, 0, true, 1), value(2, 0, true, 2), value(1, 0, true, 3))))
                }
                assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { deleteRecursively(root) }
    }

    @Test
    fun persistenceFailureLeavesBaselineUnchangedAndForcesFreshFull() {
        val root = tempDirectory()
        var fail = false
        val failures = SecondaryTelemetrySpool.FailureInjector { operation, _ ->
            if (operation == "append" && fail) {
                fail = false
                throw IOException("injected")
            }
        }
        try {
            SecondaryTelemetrySpool.openForTest(root, 2L * 1024 * 1024, 3, failures).use { spool ->
                assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, spool.append(cycle(1, values())))
                fail = true
                assertFailsWith<IllegalStateException> {
                    spool.append(cycle(2, values(second = value(1, 0, true, 99))))
                }
                assertEquals(
                    SecondaryTelemetrySpool.AppendResult.FULL,
                    spool.append(cycle(3, values(second = value(1, 0, true, 100))) )
                )
                val records = drain(spool)
                assertEquals(listOf(1L, 3L), records.map { it.identity.sequence })
                assertEquals(listOf(SecondaryTelemetrySpool.Kind.FULL, SecondaryTelemetrySpool.Kind.FULL), records.map { it.kind })
                assertEquals(3, records.last().values.size)
            }
        } finally { deleteRecursively(root) }
    }

    @Test
    fun capacityPreservesOldDataJournalsLossAndRecoveryIsFull() {
        val root = tempDirectory()
        try {
            SecondaryTelemetrySpool.openForTest(root, 64L * 1024, 3).use { spool ->
                assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, spool.append(cycle(1, values())))
                val firstDescriptor = assertNotNull(spool.oldest())
                val filler = File(root, "retained.bad")
                val targetFootprint = 64L * 1024 - 8L * 1024 - 100L
                RandomAccessFile(filler, "rw").use { it.setLength(targetFootprint - firstDescriptor.length) }

                assertEquals(SecondaryTelemetrySpool.AppendResult.CAP_REACHED, spool.append(cycle(2, values())))
                assertEquals(SecondaryTelemetrySpool.AppendResult.CAP_REACHED, spool.append(cycle(3, values())))
                assertEquals(firstDescriptor.identity, spool.oldest()!!.identity)
                assertEquals(2L, assertNotNull(spool.status().loss).count)
                assertEquals(SecondaryTelemetrySpool.AckResult.RELEASED, spool.acknowledge(firstDescriptor))
                assertTrue(filler.delete())

                assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, spool.append(cycle(4, values())))
                val recovery = SecondaryTelemetrySpool.Codec.decode(readAll(spool, assertNotNull(spool.oldest())))
                assertEquals(SecondaryTelemetrySpool.Kind.FULL, recovery.kind)
                assertEquals(2L, assertNotNull(recovery.lossBefore).count)
                assertEquals(2L, recovery.lossBefore!!.firstIdentity.sequence)
                assertEquals(3L, recovery.lossBefore!!.lastIdentity.sequence)
                assertNull(spool.status().loss)
            }
        } finally { deleteRecursively(root) }
    }

    @Test
    fun failedCapacityJournalStillInvalidatesProducerBaseline() {
        val root = tempDirectory()
        var failLoss = false
        val failures = SecondaryTelemetrySpool.FailureInjector { operation, _ ->
            if (operation == "loss" && failLoss) {
                failLoss = false
                throw IOException("loss journal injected failure")
            }
        }
        try {
            SecondaryTelemetrySpool.openForTest(root, 64L * 1024, 3, failures).use { spool ->
                spool.append(cycle(1, values()))
                val first = assertNotNull(spool.oldest())
                val filler = File(root, "capacity.bad")
                RandomAccessFile(filler, "rw").use { it.setLength(50_000 - first.length) }
                failLoss = true
                assertFailsWith<IllegalStateException> { spool.append(cycle(2, values())) }
                assertTrue(filler.delete())
                assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, spool.append(cycle(3, values())))
                assertEquals(
                    listOf(SecondaryTelemetrySpool.Kind.FULL, SecondaryTelemetrySpool.Kind.FULL),
                    drain(spool).map { it.kind }
                )
            }
        } finally { deleteRecursively(root) }
    }

    @Test
    fun restartOrdersByDurableSpoolSequenceAndExactAckDeletesOnlyMatch() {
        val root = tempDirectory()
        try {
            SecondaryTelemetrySpool.openForTest(root, 2L * 1024 * 1024, 3).use { spool ->
                spool.append(cycle(7, values(), generation = "random-z", wall = 900))
                spool.append(cycle(8, values(), generation = "random-z", wall = 100))
            }
            SecondaryTelemetrySpool.openForTest(root, 2L * 1024 * 1024, 3).use { spool ->
                val first = assertNotNull(spool.oldest())
                assertEquals(7L, first.identity.sequence)
                assertEquals(SecondaryTelemetrySpool.AckResult.RELEASED, spool.acknowledge(first))
                assertEquals(8L, spool.oldest()!!.identity.sequence)
                assertEquals(SecondaryTelemetrySpool.AckResult.NOT_FOUND, spool.acknowledge(first))
            }
        } finally { deleteRecursively(root) }
    }

    @Test
    fun full23083RecordReconstructsFromBoundedImmutablePagesAndDigest() {
        val root = tempDirectory()
        val count = 23_083
        try {
            val all = List(count) { ordinal -> value(ordinal, if (ordinal % 17 == 0) -7 else 0, true, ordinal, if (ordinal % 17 == 0) "err-$ordinal" else null) }
            SecondaryTelemetrySpool.openForTest(root, SecondaryTelemetrySpool.MAX_SPOOL_BYTES, count).use { spool ->
                assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, spool.append(cycle(1, all)))
                val descriptor = assertNotNull(spool.oldest())
                assertTrue(descriptor.length > SecondaryTelemetrySpool.MAX_SLICE_BYTES)
                val rebuilt = readAll(spool, descriptor)
                assertEquals(descriptor.sha256, sha256(rebuilt))
                val firstPage = spool.readSlice(descriptor, 0, SecondaryTelemetrySpool.MAX_SLICE_BYTES)
                firstPage[0] = (firstPage[0].toInt() xor 1).toByte()
                assertFalse(firstPage.contentEquals(spool.readSlice(descriptor, 0, SecondaryTelemetrySpool.MAX_SLICE_BYTES)))
                assertFailsWith<IllegalArgumentException> { spool.readSlice(descriptor, -1, 1) }
                assertFailsWith<IllegalArgumentException> { spool.readSlice(descriptor, 0, SecondaryTelemetrySpool.MAX_SLICE_BYTES + 1) }
                val record = SecondaryTelemetrySpool.Codec.decode(rebuilt)
                assertEquals(count, record.fieldCount)
                assertEquals(count, record.values.size)
                assertEquals(count, record.okCount + record.errorCount)
            }
        } finally { deleteRecursively(root) }
    }

    @Test
    fun corruptReadyIsRetainedAsBadEvidenceAndReported() {
        val root = tempDirectory()
        val events = mutableListOf<String>()
        try {
            File(root, "s00000000000000000000_broken.ready").writeText("not-json")
            SecondaryTelemetrySpool.openForTest(root, 1_000_000, 3).use { spool ->
                spool.setDiagnosticListener(object : SecondaryTelemetrySpool.DiagnosticListener {
                    override fun onQuarantine(fileName: String, bytes: Long, reason: String?) {
                        events += "$fileName:$bytes:$reason"
                    }
                })
                assertNull(spool.oldest())
                assertEquals(1, spool.status().quarantinedRecords)
            }
            assertTrue(root.listFiles()!!.single().name.contains(".ready.bad"))
            assertEquals(1, events.size)
        } finally { deleteRecursively(root) }
    }

    @Test
    fun corruptFullOrMiddleDeltaDurablyPoisonsDependentChainUntilFreshFull() {
        val corruptFullRoot = tempDirectory()
        val corruptDeltaRoot = tempDirectory()
        try {
            SecondaryTelemetrySpool.openForTest(corruptFullRoot, 2L * 1024 * 1024, 3).use { spool ->
                spool.append(cycle(1, values()))
                spool.append(cycle(2, values(second = value(1, 0, true, 20))))
                spool.append(cycle(3, values(second = value(1, 0, true, 30))))
                corruptOldestReady(corruptFullRoot)
                assertNull(spool.oldest())
                assertEquals(3, spool.status().quarantinedRecords)
            }
            SecondaryTelemetrySpool.openForTest(corruptFullRoot, 2L * 1024 * 1024, 3).use { spool ->
                assertNull(spool.oldest())
                assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, spool.append(cycle(4, values())))
                assertEquals(4L, assertNotNull(spool.oldest()).identity.sequence)
                assertTrue(corruptFullRoot.listFiles()!!.none { it.name.contains(".bad.poison") })
            }

            SecondaryTelemetrySpool.openForTest(corruptDeltaRoot, 2L * 1024 * 1024, 3).use { spool ->
                spool.append(cycle(1, values()))
                spool.append(cycle(2, values(second = value(1, 0, true, 20))))
                spool.append(cycle(3, values(second = value(1, 0, true, 30))))
                assertEquals(SecondaryTelemetrySpool.AckResult.RELEASED, spool.acknowledge(assertNotNull(spool.oldest())))
                corruptOldestReady(corruptDeltaRoot)
                assertNull(spool.oldest())
                assertEquals(2, spool.status().quarantinedRecords)
            }
            SecondaryTelemetrySpool.openForTest(corruptDeltaRoot, 2L * 1024 * 1024, 3).use { spool ->
                assertNull(spool.oldest())
            }
        } finally {
            deleteRecursively(corruptFullRoot)
            deleteRecursively(corruptDeltaRoot)
        }
    }

    @Test
    fun codecRejectsCoercedNumbersInvalidMetadataAndUnboundedIdentifiers() {
        val root = tempDirectory()
        try {
            SecondaryTelemetrySpool.openForTest(root, 1_000_000, 3).use { spool ->
                spool.append(cycle(1, values()))
                val encoded = readAll(spool, assertNotNull(spool.oldest())).toString(Charsets.UTF_8)
                val fractionalRaw = encoded.replaceFirst("\"raw\":1", "\"raw\":1.5")
                assertFailsWith<IllegalArgumentException> {
                    SecondaryTelemetrySpool.Codec.decode(fractionalRaw.toByteArray())
                }
                val negativeClock = encoded.replaceFirst("\"captured_wall_ms\":1001", "\"captured_wall_ms\":-1")
                assertFailsWith<IllegalArgumentException> {
                    SecondaryTelemetrySpool.Codec.decode(negativeClock.toByteArray())
                }
            }
            assertFailsWith<IllegalArgumentException> {
                SecondaryTelemetrySpool.CycleIdentity("boot\u0000suffix", "generation", "gap", 1)
            }
            assertFailsWith<IllegalArgumentException> {
                SecondaryTelemetrySpool.CycleIdentity("b".repeat(257), "generation", "gap", 1)
            }
        } finally { deleteRecursively(root) }
    }

    @Test
    fun quarantineRetainsPoisonedDeltaChainUntilNextFull() {
        val root = tempDirectory()
        try {
            SecondaryTelemetrySpool.openForTest(root, 2L * 1024 * 1024, 3).use { spool ->
                spool.append(cycle(1, values()))
                spool.append(cycle(2, values(second = value(1, 0, true, 20))))
                spool.append(cycle(3, values(second = value(1, 0, true, 30))))
                spool.append(cycle(1, values(), generation = "g2"))
                val full = assertNotNull(spool.oldest())
                spool.acknowledge(full)
                val poisoned = assertNotNull(spool.oldest())
                assertEquals(SecondaryTelemetrySpool.Kind.DELTA, poisoned.kind)
                assertEquals(2, spool.quarantine(poisoned, "consumer rejected"))
                assertEquals("g2", spool.oldest()!!.identity.helperGeneration)
                assertEquals(2, spool.status().quarantinedRecords)
            }
        } finally { deleteRecursively(root) }
    }

    @Test
    fun interruptedExplicitQuarantineRemainsPoisonedAcrossReopen() {
        val root = tempDirectory()
        var quarantineCalls = 0
        val failures = SecondaryTelemetrySpool.FailureInjector { operation, _ ->
            if (operation == "quarantine" && ++quarantineCalls == 2) {
                throw IOException("simulated process interruption")
            }
        }
        try {
            SecondaryTelemetrySpool.openForTest(root, 2L * 1024 * 1024, 3, failures).use { spool ->
                spool.append(cycle(1, values()))
                spool.append(cycle(2, values(second = value(1, 0, true, 20))))
                spool.append(cycle(3, values(second = value(1, 0, true, 30))))
                spool.acknowledge(assertNotNull(spool.oldest()))
                assertFailsWith<IllegalStateException> {
                    spool.quarantine(assertNotNull(spool.oldest()), "rejected")
                }
            }
            SecondaryTelemetrySpool.openForTest(root, 2L * 1024 * 1024, 3).use { spool ->
                assertNull(spool.oldest())
                assertEquals(2, spool.status().quarantinedRecords)
                assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, spool.append(cycle(4, values())))
                assertEquals(4L, assertNotNull(spool.oldest()).identity.sequence)
                assertTrue(root.listFiles()!!.none { it.name.contains(".bad.poison") })
            }
        } finally { deleteRecursively(root) }
    }

    @Test
    fun tmpAndBadBytesCountAgainstIndependentQuotaAndMainConstantIsUnchanged() {
        val root = tempDirectory()
        try {
            RandomAccessFile(File(root, "unfinished.tmp"), "rw").use { it.setLength(20_000) }
            RandomAccessFile(File(root, "evidence.bad"), "rw").use { it.setLength(20_000) }
            SecondaryTelemetrySpool.openForTest(root, 48_000, 3).use { spool ->
                assertEquals(40_000L, spool.status().bytes)
                assertEquals(SecondaryTelemetrySpool.AppendResult.CAP_REACHED, spool.append(cycle(1, values())))
                assertTrue(File(root, "unfinished.tmp").isFile)
                assertTrue(File(root, "evidence.bad").isFile)
            }
            assertEquals(128L * 1024 * 1024, SecondaryTelemetrySpool.MAX_SPOOL_BYTES)
            assertEquals(128L * 1024 * 1024, TelemetryWorkerSpool.MAX_SPOOL_BYTES)
            assertTrue(SecondaryTelemetrySpool.SPOOL_DIRECTORY_PATH.contains("secondary"))
            assertFalse(SecondaryTelemetrySpool.SPOOL_DIRECTORY_PATH == TelemetryWorkerSpool.SPOOL_DIRECTORY_PATH)
        } finally { deleteRecursively(root) }
    }

    @Test
    fun fsyncedRecordTemporariesRecoverAsAckableRecordsAndNextAppendIsFull() {
        for (crashOnSequence in listOf(1L, 2L)) {
            val root = tempDirectory()
            var publishCount = 0L
            val failures = SecondaryTelemetrySpool.FailureInjector { operation, _ ->
                if (operation == "append_publish" && ++publishCount == crashOnSequence) throw SimulatedCrash()
            }
            try {
                SecondaryTelemetrySpool.openForTest(root, 1_000_000, 3, failures).use { spool ->
                    if (crashOnSequence == 2L) spool.append(cycle(1, values()))
                    assertFailsWith<SimulatedCrash> { spool.append(cycle(crashOnSequence, values())) }
                }
                assertTrue(root.listFiles()!!.any { it.name.endsWith(".tmp") })
                SecondaryTelemetrySpool.openForTest(root, 1_000_000, 3).use { spool ->
                    val recovered = drain(spool)
                    assertEquals((1L..crashOnSequence).toList(), recovered.map { it.identity.sequence })
                    assertEquals(0L, spool.status().bytes)
                    assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, spool.append(cycle(3, values())))
                    assertTrue(root.listFiles()!!.none { it.name.endsWith(".tmp") })
                }
            } finally { deleteRecursively(root) }
        }
    }

    @Test
    fun fsyncedLossTemporaryReplacesPreviousLossSummaryOnReopen() {
        val root = tempDirectory()
        var crash = false
        val failures = SecondaryTelemetrySpool.FailureInjector { operation, _ ->
            if (operation == "loss_publish" && crash) throw SimulatedCrash()
        }
        try {
            SecondaryTelemetrySpool.openForTest(root, 64L * 1024, 3, failures).use { spool ->
                spool.append(cycle(1, values()))
                RandomAccessFile(File(root, "capacity.bad"), "rw").use { it.setLength(50_000) }
                assertEquals(SecondaryTelemetrySpool.AppendResult.CAP_REACHED, spool.append(cycle(2, values())))
                crash = true
                assertFailsWith<SimulatedCrash> { spool.append(cycle(3, values())) }
            }
            SecondaryTelemetrySpool.openForTest(root, 64L * 1024, 3).use { spool ->
                assertEquals(2L, assertNotNull(spool.status().loss).count)
                assertFalse(File(root, "secondary-loss.tmp").exists())
                assertTrue(File(root, "capacity.bad").delete())
                drain(spool)
                assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, spool.append(cycle(4, values())))
                assertEquals(2L, readAndAck(spool).lossBefore!!.count)
            }
        } finally { deleteRecursively(root) }
    }

    @Test
    fun incompleteRecordAndLossTempsBecomeVisibleRetainedEvidence() {
        val root = tempDirectory()
        try {
            SecondaryTelemetrySpool.openForTest(root, 1_000_000, 3).use { it.append(cycle(1, values())) }
            val ready = root.listFiles()!!.single()
            val temporary = File(root, ready.name.removeSuffix(".ready") + ".tmp")
            assertTrue(ready.renameTo(temporary))
            temporary.writeText("{incomplete")
            File(root, "secondary-loss.tmp").writeText("{incomplete")
            SecondaryTelemetrySpool.openForTest(root, 1_000_000, 3).use { spool ->
                assertEquals(2, spool.status().quarantinedRecords)
                assertNull(spool.oldest())
                assertTrue(root.listFiles()!!.none { it.name.endsWith(".tmp") })
                assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, spool.append(cycle(2, values())))
                assertEquals(2L, readAndAck(spool).identity.sequence)
            }
        } finally { deleteRecursively(root) }
    }

    private class SimulatedCrash : Error("simulated process death after fsync")

    private fun values(second: SecondaryTelemetrySpool.Value = value(1, -5, true, 2, "raw-on-error")) = listOf(
        value(0, 0, true, 1),
        second,
        value(2, -6, false, null, "no raw")
    )

    private fun value(ordinal: Int, status: Int, present: Boolean, raw: Int?, error: String? = null) =
        SecondaryTelemetrySpool.Value(ordinal, status, present, raw, error)

    private fun cycle(
        sequence: Long,
        values: List<SecondaryTelemetrySpool.Value>,
        generation: String = "g1",
        wall: Long = 1_000 + sequence
    ) = SecondaryTelemetrySpool.Cycle(
        SecondaryTelemetrySpool.CycleIdentity("boot", generation, "gap", sequence),
        "fid-catalog-20260908-main95-roundrobin23083-both-read-tx-v1",
        wall,
        2_000 + sequence,
        40 + sequence,
        0,
        1,
        true,
        3,
        0,
        0,
        1,
        "cycle-$sequence",
        values
    )

    private fun readAndAck(spool: SecondaryTelemetrySpool): SecondaryTelemetrySpool.Record {
        val descriptor = assertNotNull(spool.oldest())
        val record = SecondaryTelemetrySpool.Codec.decode(readAll(spool, descriptor))
        assertEquals(SecondaryTelemetrySpool.AckResult.RELEASED, spool.acknowledge(descriptor))
        return record
    }

    private fun drain(spool: SecondaryTelemetrySpool): List<SecondaryTelemetrySpool.Record> {
        val result = mutableListOf<SecondaryTelemetrySpool.Record>()
        while (spool.oldest() != null) result += readAndAck(spool)
        return result
    }

    private fun readAll(spool: SecondaryTelemetrySpool, descriptor: SecondaryTelemetrySpool.Descriptor): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        while (total.toLong() < descriptor.length) {
            val page = spool.readSlice(descriptor, total.toLong(), SecondaryTelemetrySpool.MAX_SLICE_BYTES)
            chunks += page
            total += page.size
        }
        return ByteArray(total).also { output ->
            var offset = 0
            chunks.forEach { page ->
                page.copyInto(output, offset)
                offset += page.size
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02X".format(it) }

    private fun tempDirectory(): File = Files.createTempDirectory("secondary-spool").toFile()

    private fun corruptOldestReady(root: File) {
        root.listFiles { _, name -> name.endsWith(".ready") }!!
            .minBy { it.name }
            .writeText("corrupt")
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        file.delete()
    }
}
