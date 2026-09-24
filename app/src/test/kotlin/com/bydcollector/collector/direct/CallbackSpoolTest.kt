package com.bydcollector.collector.direct

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.RandomAccessFile

class CallbackSpoolTest {
    @Test fun shutdownPersistsBothUnacknowledgedLiveSlotsBeforeClosingSpools() {
        val mainRoot = Files.createTempDirectory("callback-shutdown-main").toFile()
        val secondaryRoot = Files.createTempDirectory("callback-shutdown-secondary").toFile()
        try {
            val mainBatch = batch(3, 3)
            val secondaryBatch = TelemetryCallbackBatch("boot-old", "generation-old", 2, 3, 4,
                mainBatch.events)
            val transport = CallbackSpoolBinder(CallbackSpool.openForTest(mainRoot, 128 * 1024L),
                CallbackSpool.openForTest(secondaryRoot, 128 * 1024L))
            assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(mainBatch, true))
            assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(secondaryBatch, true))
            assertTrue(transport.liveRetainedBytes(1) > 0)
            assertTrue(transport.liveRetainedBytes(2) > 0)
            assertTrue(transport.closeAndReport())
            for ((root, expected) in listOf(mainRoot to mainBatch, secondaryRoot to secondaryBatch)) {
                CallbackSpool.openForTest(root, 128 * 1024L).use { spool ->
                    val descriptor = spool.oldest()!!
                    assertArrayEquals(expected.encode(), spool.readSlice(descriptor, 0, CallbackSpool.MAX_SLICE_BYTES))
                }
            }
        } finally {
            mainRoot.deleteRecursively()
            secondaryRoot.deleteRecursively()
        }
    }

    @Test fun shutdownReportsUnpersistedTailWhenCapacityPreventsSpill() {
        val mainRoot = Files.createTempDirectory("callback-shutdown-full-main").toFile()
        val secondaryRoot = Files.createTempDirectory("callback-shutdown-full-secondary").toFile()
        try {
            val transport = CallbackSpoolBinder(CallbackSpool.openForTest(mainRoot, 12 * 1024L),
                CallbackSpool.openForTest(secondaryRoot, 12 * 1024L))
            assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(batch(1, 1, 5_000), true))
            val secondary = TelemetryCallbackBatch("boot", "gen", 2, 1, 1, batch(1, 1).events)
            assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(secondary, true))
            assertFalse(transport.closeAndReport())
            assertTrue(transport.liveRetainedBytes(1) > 0)
            CallbackSpool.openForTest(mainRoot, 12 * 1024L).use { spool ->
                assertEquals("shutdown_spill", spool.status().loss!!.reason)
                assertNull(spool.oldest())
            }
            CallbackSpool.openForTest(secondaryRoot, 12 * 1024L).use { spool ->
                assertEquals(secondary.identity(), spool.oldest()!!.identity)
            }
        } finally {
            mainRoot.deleteRecursively()
            secondaryRoot.deleteRecursively()
        }
    }

    @Test fun pagesExactImmutableBatchAndAckIsIdempotent() {
        val root = Files.createTempDirectory("callback-spool").toFile()
        CallbackSpool.openForTest(root, 128 * 1024L).use { spool ->
            val batch = batch(5, 9)
            assertEquals(CallbackSpool.AppendResult.SUCCESS, spool.append(batch))
            val descriptor = spool.oldest()!!
            val first = spool.readSlice(descriptor, 0, 16)
            val rest = spool.readSlice(descriptor, 16, CallbackSpool.MAX_SLICE_BYTES)
            val encoded = first + rest
            assertArrayEquals(batch.encode(), encoded)
            assertEquals(TelemetryCallbackBatch.digest(encoded), descriptor.sha256)
            assertEquals(batch.identity(), descriptor.identity)
            assertEquals(CallbackSpool.AckResult.RELEASED, spool.acknowledge(descriptor))
            assertEquals(CallbackSpool.AckResult.NOT_FOUND, spool.acknowledge(descriptor))
        }
    }

    @Test fun capacityPreservesOldBatchAndDurablyReportsNewLoss() {
        val root = Files.createTempDirectory("callback-cap").toFile()
        CallbackSpool.openForTest(root, 12 * 1024L).use { spool ->
            val old = batch(1, 1, 2_000)
            assertEquals(CallbackSpool.AppendResult.SUCCESS, spool.append(old))
            var rejected = false
            for (sequence in 2L..20L) {
                if (spool.append(batch(sequence, sequence, 2_000)) == CallbackSpool.AppendResult.CAP_REACHED) {
                    rejected = true
                    break
                }
            }
            assertTrue(rejected)
            assertEquals(old.identity(), spool.oldest()!!.identity)
            assertTrue(spool.status().loss!!.count > 0)
        }
        CallbackSpool.openForTest(root, 12 * 1024L).use { reopened ->
            assertNotNull(reopened.status().loss)
            assertEquals(batch(1, 1, 2_000).identity(), reopened.oldest()!!.identity)
        }
    }

    @Test fun completeCrashTemporaryPromotesWithoutClaimingLoss() {
        val root = Files.createTempDirectory("callback-recover").toFile()
        val batch = batch(8, 3)
        val bytes = batch.encode()
        val digest = TelemetryCallbackBatch.digest(bytes)
        val identityDigest = TelemetryCallbackBatch.digest(batch.identity().toByteArray(Charsets.UTF_8))
        val name = String.format(java.util.Locale.US, "cb_%020d_%d_%020d_%s_%s.cbtmp", 0L, batch.stream, batch.batchSequence, identityDigest, digest)
        root.resolve(name).writeBytes(bytes)

        CallbackSpool.openForTest(root, 128 * 1024L).use { spool ->
            assertEquals(batch.identity(), spool.oldest()!!.identity)
            assertNull(spool.status().loss)
            assertTrue(root.listFiles()!!.any { it.name.endsWith(".cbready") })
        }
    }

    @Test fun durableInsertionOrderSurvivesGenerationSequenceReset() {
        val root = Files.createTempDirectory("callback-order").toFile()
        CallbackSpool.openForTest(root, 128 * 1024L).use { spool ->
            val first = batch(90, 1)
            val reset = TelemetryCallbackBatch("new-boot", "new-generation", 1, 1, 0,
                listOf(TelemetryCallbackBatch.Event(0, 1, 2, TelemetryCallbackBatch.TYPE_INT, 3,
                    null, 200, 100, null, "ok")))
            assertEquals(CallbackSpool.AppendResult.SUCCESS, spool.append(first))
            assertEquals(CallbackSpool.AppendResult.SUCCESS, spool.append(reset))
            assertEquals(first.identity(), spool.oldest()!!.identity)
            assertEquals(CallbackSpool.AckResult.RELEASED, spool.acknowledge(spool.oldest()!!))
            assertEquals(reset.identity(), spool.oldest()!!.identity)
        }
        CallbackSpool.openForTest(root, 128 * 1024L).use { reopened ->
            assertTrue(reopened.oldest()!!.spoolOrder >= 1)
        }
    }

    @Test fun corruptIdentityRecordCannotCauseDuplicateDrop() {
        val root = Files.createTempDirectory("callback-duplicate").toFile()
        val batch = batch(4, 4)
        CallbackSpool.openForTest(root, 128 * 1024L).use { spool -> assertEquals(CallbackSpool.AppendResult.SUCCESS, spool.append(batch)) }
        root.listFiles()!!.single { it.name.endsWith(".cbready") }.writeBytes(byteArrayOf(1, 2, 3))
        CallbackSpool.openForTest(root, 128 * 1024L).use { spool ->
            assertEquals(CallbackSpool.AppendResult.SUCCESS, spool.append(batch))
            assertEquals(batch.identity(), spool.oldest()!!.identity)
            assertTrue(spool.status().quarantinedFiles >= 1)
            assertNotNull(spool.status().loss)
        }
    }

    @Test fun oversizedCrashTemporaryIsQuarantinedWithoutUnboundedRead() {
        val root = Files.createTempDirectory("callback-oversize").toFile()
        val oversized = root.resolve("cb_00000000000000000000_1_00000000000000000000_dead.cbtmp")
        RandomAccessFile(oversized, "rw").use { it.setLength(TelemetryCallbackBatch.MAX_BYTES.toLong() + 1) }
        CallbackSpool.openForTest(root, 8L * 1024L * 1024L).use { spool ->
            assertNull(spool.oldest())
            assertTrue(spool.status().quarantinedFiles >= 1)
            assertNotNull(spool.status().loss)
        }
    }

    @Test fun healthyBatchesStayInMemoryThenExpireToDiskInOrder() {
        val mainRoot = Files.createTempDirectory("callback-live-main").toFile()
        val secondaryRoot = Files.createTempDirectory("callback-live-secondary").toFile()
        val main = CallbackSpool.openForTest(mainRoot, 128 * 1024L)
        val secondary = CallbackSpool.openForTest(secondaryRoot, 128 * 1024L)
        CallbackSpoolBinder(main, secondary).use { transport ->
            val older = batch(1, 1)
            val newer = batch(2, 2)
            assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(older, true))
            val retained = transport.liveRetainedBytes(1)
            assertTrue(retained > 0)
            assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(newer, true))
            assertTrue(transport.liveRetainedBytes(1) > retained)
            assertNull(main.oldest())
            transport.spillExpired(1, System.nanoTime() / 1_000_000L + 5_001L)
            assertEquals(0, transport.liveRetainedBytes(1))
            assertEquals(older.identity(), main.oldest()!!.identity)
            main.acknowledge(main.oldest()!!)
            assertEquals(newer.identity(), main.oldest()!!.identity)
        }
    }

    @Test fun canonicalRootInstancesShareIndexedOrderAndIntegrityAcrossLargeBacklog() {
        val root = Files.createTempDirectory("callback-index-backlog").toFile()
        val count = 2_048
        try {
            for (order in 0 until count) {
                val sequence = order.toLong() + 1L
                val record = batch(sequence, sequence)
                val bytes = record.encode()
                val identityDigest = TelemetryCallbackBatch.digest(record.identity().toByteArray(Charsets.UTF_8))
                val digest = TelemetryCallbackBatch.digest(bytes)
                val name = String.format(java.util.Locale.US, "cb_%020d_%d_%020d_%s_%s.cbready",
                    order.toLong(), record.stream, record.batchSequence, identityDigest, digest)
                root.resolve(name).writeBytes(bytes)
            }

            val first = CallbackSpool.openForTest(root, 128 * 1024L * 1024L)
            val second = CallbackSpool.openForTest(java.io.File(root, "."), 128 * 1024L * 1024L)
            try {
                assertSame(CallbackSpool.rootState(root), CallbackSpool.rootState(java.io.File(root, ".")))
                assertSame(CallbackSpool.persistenceLock(root), CallbackSpool.persistenceLock(java.io.File(root, ".")))
                assertEquals(CallbackSpool.AppendResult.DUPLICATE,
                    second.append(batch(count.toLong(), count.toLong())))

                for (sequence in 1L..count.toLong()) {
                    val reader = if (sequence % 2L == 1L) first else second
                    val acknowledger = if (sequence % 2L == 1L) second else first
                    val descriptor = reader.oldest()!!
                    assertEquals(sequence, descriptor.batchSequence)
                    val raw = reader.readSlice(descriptor, 0L, CallbackSpool.MAX_SLICE_BYTES)
                    assertEquals(descriptor.length, raw.size.toLong())
                    assertEquals(descriptor.sha256, TelemetryCallbackBatch.digest(raw))
                    assertEquals(CallbackSpool.AckResult.RELEASED, acknowledger.acknowledge(descriptor))
                }

                assertNull(first.oldest())
                assertEquals(CallbackSpool.AppendResult.SUCCESS, first.append(batch(5_001, 5_001)))
                assertEquals(CallbackSpool.AppendResult.SUCCESS, second.append(batch(5_002, 5_002)))
                val appendedFirst = second.oldest()!!
                assertEquals(count.toLong(), appendedFirst.spoolOrder)
                assertEquals(5_001L, appendedFirst.batchSequence)
                assertEquals(CallbackSpool.AckResult.RELEASED, first.acknowledge(appendedFirst))
                val appendedSecond = first.oldest()!!
                assertEquals(count.toLong() + 1L, appendedSecond.spoolOrder)
                assertEquals(5_002L, appendedSecond.batchSequence)
                assertEquals(CallbackSpool.AckResult.RELEASED, second.acknowledge(appendedSecond))
            } finally {
                first.close()
                second.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun sameInstanceSelectedCorruptionFailsExactAckThenQuarantinesBeforeRetry() {
        val root = Files.createTempDirectory("callback-index-corruption").toFile()
        try {
            CallbackSpool.openForTest(root, 128 * 1024L).use { spool ->
                val record = batch(41, 41)
                assertEquals(CallbackSpool.AppendResult.SUCCESS, spool.append(record))
                val original = spool.oldest()!!
                assertEquals(CallbackSpool.AppendResult.DUPLICATE, spool.append(record))

                val stored = root.resolve(original.fileName)
                val changed = stored.readBytes()
                changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
                stored.writeBytes(changed)

                var exactAckRejected = false
                try { spool.acknowledge(original) }
                catch (_: IllegalArgumentException) { exactAckRejected = true }
                assertTrue("modified bytes must not satisfy the saved SHA descriptor", exactAckRejected)

                assertEquals(CallbackSpool.AppendResult.SUCCESS, spool.append(record))
                val replacement = spool.oldest()!!
                val raw = spool.readSlice(replacement, 0L, CallbackSpool.MAX_SLICE_BYTES)
                assertArrayEquals(record.encode(), raw)
                assertEquals(TelemetryCallbackBatch.digest(raw), replacement.sha256)
                assertEquals(1, spool.status().readyBatches)
                assertTrue(spool.status().quarantinedFiles >= 1)
                assertNotNull(spool.status().loss)
                assertEquals(CallbackSpool.AckResult.RELEASED, spool.acknowledge(replacement))
                assertEquals(CallbackSpool.AckResult.NOT_FOUND, spool.acknowledge(replacement))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun deletedCachedHeadIsRescannedAndNextBatchRemainsReadable() {
        val root = Files.createTempDirectory("callback-index-missing-head").toFile()
        try {
            CallbackSpool.openForTest(root, 128 * 1024L).use { spool ->
                val first = batch(71, 71)
                val second = batch(72, 72)
                assertEquals(CallbackSpool.AppendResult.SUCCESS, spool.append(first))
                assertEquals(CallbackSpool.AppendResult.SUCCESS, spool.append(second))
                val missing = spool.oldest()!!
                assertTrue(root.resolve(missing.fileName).delete())

                val next = spool.oldest()!!
                assertEquals(second.identity(), next.identity)
                assertEquals(1, spool.status().readyBatches)
                assertTrue(spool.status().loss!!.count > 0L)
                assertArrayEquals(second.encode(), spool.readSlice(next, 0L, CallbackSpool.MAX_SLICE_BYTES))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun batch(batchSequence: Long, eventSequence: Long, payload: Int = 8) = TelemetryCallbackBatch(
        "boot-old", "generation-old", CollectorHelperProtocol.STREAM_MAIN, 3, batchSequence,
        listOf(TelemetryCallbackBatch.Event(eventSequence, 1001, 42, TelemetryCallbackBatch.TYPE_BYTES,
            0, ByteArray(payload) { 7 }, 100, 90, null, "ok"))
    )
}
