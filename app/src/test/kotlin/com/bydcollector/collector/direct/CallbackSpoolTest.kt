package com.bydcollector.collector.direct

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.RandomAccessFile

class CallbackSpoolTest {
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

    @Test fun newerBatchCannotPersistAheadOfOlderLiveBatch() {
        val mainRoot = Files.createTempDirectory("callback-live-main").toFile()
        val secondaryRoot = Files.createTempDirectory("callback-live-secondary").toFile()
        val main = CallbackSpool.openForTest(mainRoot, 128 * 1024L)
        val secondary = CallbackSpool.openForTest(secondaryRoot, 128 * 1024L)
        CallbackSpoolBinder(main, secondary).use { transport ->
            val older = batch(1, 1)
            val newer = batch(2, 2)
            assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(older, true))
            assertTrue(transport.liveRetainedBytes(1) > 0)
            assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(newer, true))
            assertEquals(0, transport.liveRetainedBytes(1))
            assertEquals(older.identity(), main.oldest()!!.identity)
            main.acknowledge(main.oldest()!!)
            assertEquals(newer.identity(), main.oldest()!!.identity)
        }
    }

    private fun batch(batchSequence: Long, eventSequence: Long, payload: Int = 8) = TelemetryCallbackBatch(
        "boot-old", "generation-old", CollectorHelperProtocol.STREAM_MAIN, 3, batchSequence,
        listOf(TelemetryCallbackBatch.Event(eventSequence, 1001, 42, TelemetryCallbackBatch.TYPE_BYTES,
            0, ByteArray(payload) { 7 }, 100, 90, null, "ok"))
    )
}
