package com.bydcollector.collector.data.debug

import com.bydcollector.collector.data.direct.SecondarySpoolActionResult
import com.bydcollector.collector.data.direct.SecondarySpoolPage
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.SecondaryTelemetrySpool
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecondaryReplayCoordinatorTest {
    @Test
    fun readsLargeRecordInBoundedPagesThenImportsBeforeAck() {
        val fixture = fixture(error = "x".repeat(SecondaryTelemetrySpool.MAX_SLICE_BYTES + 1024))
        val events = mutableListOf<String>()
        val offsets = mutableListOf<Long>()
        var offered = true
        val coordinator = SecondaryReplayCoordinator(
            fetchPage = { requested, offset, limit ->
                offsets += offset
                if (!offered) emptyPage() else page(fixture, requested, offset, limit)
            },
            acknowledge = {
                events += "ack"
                offered = false
                okAction(1)
            },
            quarantine = { _, _ -> error("unexpected quarantine") },
            importRecord = { record, digest ->
                assertEquals(fixture.descriptor.identity, record.identity)
                assertEquals(fixture.descriptor.sha256, digest)
                events += "import"
                SecondaryImportResult.Committed(7L, duplicate = false)
            }
        )

        val result = coordinator.drain()

        assertTrue(result.drained)
        assertEquals(1, result.committedRecords)
        assertEquals(listOf("import", "ack"), events)
        assertEquals(listOf(0L, SecondaryTelemetrySpool.MAX_SLICE_BYTES.toLong(), 0L), offsets)
    }

    @Test
    fun descriptorMismatchAcrossPagesBlocksWithoutImportOrAck() {
        val fixture = fixture(error = "x".repeat(SecondaryTelemetrySpool.MAX_SLICE_BYTES + 1024))
        val altered = fixture(error = "other", sequence = 2L).descriptor
        var imports = 0
        var acks = 0
        val coordinator = SecondaryReplayCoordinator(
            fetchPage = { requested, offset, limit ->
                if (offset == 0L) page(fixture, requested, offset, limit)
                else page(fixture, requested, offset, limit).copy(descriptor = altered)
            },
            acknowledge = { acks++; okAction(1) },
            quarantine = { _, _ -> error("unexpected quarantine") },
            importRecord = { _, _ -> imports++; SecondaryImportResult.Committed(1L, false) }
        )

        val result = coordinator.drain()

        assertFalse(result.drained)
        assertTrue(result.blockedReason!!.contains("descriptor changed"))
        assertEquals(0, imports)
        assertEquals(0, acks)
    }

    @Test
    fun importFailureBeforeCommitNeverAcknowledges() {
        val fixture = fixture()
        var acks = 0
        val coordinator = oneRecordCoordinator(
            fixture,
            importer = { _, _ -> error("database rollback") },
            ack = { acks++; okAction(1) }
        )

        val result = coordinator.drain()

        assertFalse(result.drained)
        assertTrue(result.blockedReason!!.contains("database rollback"))
        assertEquals(0, acks)
    }

    @Test
    fun durableCommitBeforeAckRetriesAsDuplicateWithoutSecondCycle() {
        val fixture = fixture()
        var imports = 0
        var firstAck = true
        var offered = true
        fun coordinator() = SecondaryReplayCoordinator(
            fetchPage = { requested, offset, limit ->
                if (offered) page(fixture, requested, offset, limit) else emptyPage()
            },
            acknowledge = {
                if (firstAck) {
                    firstAck = false
                    SecondarySpoolActionResult(99, error = "binder lost")
                } else {
                    offered = false
                    okAction(1)
                }
            },
            quarantine = { _, _ -> error("unexpected quarantine") },
            importRecord = { _, _ ->
                imports++
                SecondaryImportResult.Committed(44L, duplicate = imports > 1)
            }
        )

        val first = coordinator().drain()
        val second = coordinator().drain()

        assertFalse(first.drained)
        assertEquals(1, first.committedRecords)
        assertTrue(second.drained)
        assertEquals(0, second.committedRecords)
        assertEquals(1, second.duplicateRecords)
        assertEquals(2, imports)
    }

    @Test
    fun durableRejectionPrecedesQuarantineAndNeverAcknowledges() {
        val fixture = fixture(catalog = "wrong-catalog")
        val events = mutableListOf<String>()
        var offered = true
        val coordinator = SecondaryReplayCoordinator(
            fetchPage = { requested, offset, limit ->
                if (offered) page(fixture, requested, offset, limit) else emptyPage()
            },
            acknowledge = { events += "ack"; okAction(1) },
            quarantine = { _, reason ->
                events += "quarantine:$reason"
                offered = false
                okAction(1)
            },
            importRecord = { _, _ ->
                events += "reject-persisted"
                SecondaryImportResult.Rejected("catalog mismatch")
            }
        )

        val result = coordinator.drain()

        assertTrue(result.drained)
        assertEquals(listOf("reject-persisted", "quarantine:catalog mismatch"), events)
        assertEquals(1, result.quarantinedFiles)
    }

    @Test
    fun interruptionPropagatesAndLeavesQueueUntouched() {
        val coordinator = SecondaryReplayCoordinator(
            fetchPage = { _, _, _ -> throw InterruptedException("stop") },
            acknowledge = { error("unexpected ACK") },
            quarantine = { _, _ -> error("unexpected quarantine") },
            importRecord = { _, _ -> error("unexpected import") }
        )

        assertFailsWith<InterruptedException> { coordinator.drain() }
    }

    @Test
    fun preexistingThreadInterruptionStopsBeforeReadingQueue() {
        var fetches = 0
        val coordinator = SecondaryReplayCoordinator(
            fetchPage = { _, _, _ -> fetches++; emptyPage() },
            acknowledge = { error("unexpected ACK") },
            quarantine = { _, _ -> error("unexpected quarantine") },
            importRecord = { _, _ -> error("unexpected import") }
        )

        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> { coordinator.drain() }
            assertEquals(0, fetches)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun repeatedFailureDiagnosticsAreRateLimitedAndCallbackSafe() {
        var diagnostics = 0
        val coordinator = SecondaryReplayCoordinator(
            fetchPage = { _, _, _ -> SecondarySpoolPage(99, null, 0L, byteArrayOf(), "down") },
            acknowledge = { error("unexpected ACK") },
            quarantine = { _, _ -> error("unexpected quarantine") },
            importRecord = { _, _ -> error("unexpected import") },
            diagnostic = {
                diagnostics++
                error("diagnostic sink unavailable")
            }
        )

        assertFalse(coordinator.drain().drained)
        assertFalse(coordinator.drain().drained)
        assertEquals(1, diagnostics)
    }

    private fun oneRecordCoordinator(
        fixture: Fixture,
        importer: (SecondaryTelemetrySpool.Record, String) -> SecondaryImportResult,
        ack: (SecondaryTelemetrySpool.Descriptor) -> SecondarySpoolActionResult
    ): SecondaryReplayCoordinator {
        var offered = true
        return SecondaryReplayCoordinator(
            fetchPage = { requested, offset, limit ->
                if (offered) page(fixture, requested, offset, limit) else emptyPage()
            },
            acknowledge = {
                val result = ack(it)
                if (result.ok) offered = false
                result
            },
            quarantine = { _, _ -> error("unexpected quarantine") },
            importRecord = importer
        )
    }

    private fun page(
        fixture: Fixture,
        requested: SecondaryTelemetrySpool.Descriptor?,
        offset: Long,
        limit: Int
    ): SecondarySpoolPage {
        if (requested != null) assertEquals(fixture.descriptor.identity, requested.identity)
        val end = minOf(fixture.bytes.size.toLong(), offset + limit).toInt()
        return SecondarySpoolPage(
            CollectorHelperProtocol.STATUS_OK,
            fixture.descriptor,
            offset,
            fixture.bytes.copyOfRange(offset.toInt(), end)
        )
    }

    private fun emptyPage() = SecondarySpoolPage(
        CollectorHelperProtocol.STATUS_OK,
        null,
        0L,
        byteArrayOf()
    )

    private fun okAction(affected: Int) = SecondarySpoolActionResult(
        CollectorHelperProtocol.STATUS_OK,
        affected
    )

    private fun fixture(
        error: String? = null,
        sequence: Long = 1L,
        catalog: String = DirectDebugParameterAsset.SOURCE_VERSION
    ): Fixture {
        val identity = SecondaryTelemetrySpool.CycleIdentity("boot", "helper", "gap", sequence)
        val identityJson = JSONObject()
            .put("boot_id", identity.bootId)
            .put("helper_generation", identity.helperGeneration)
            .put("gap_id", identity.gapId)
            .put("sequence", identity.sequence)
        val bytes = JSONObject()
            .put("record_version", SecondaryTelemetrySpool.RECORD_VERSION)
            .put("spool_order", sequence)
            .put("kind", "FULL")
            .put("identity", identityJson)
            .put("catalog_version", catalog)
            .put("captured_wall_ms", 1000L + sequence)
            .put("captured_elapsed_ms", 2000L + sequence)
            .put("cycle_elapsed_ms", 10L)
            .put("batch_status", 0)
            .put("batch_mode", 1)
            .put("native_available", true)
            .put("native_group_count", 1)
            .put("fallback_group_count", 0)
            .put("fallback_read_count", 0)
            .put("group_failure_count", 0)
            .put("error", JSONObject.NULL)
            .put("field_count", 1)
            .put("attempted_count", 1)
            .put("ok_count", 0)
            .put("error_count", 1)
            .put("predecessor_identity", JSONObject.NULL)
            .put("loss_before", JSONObject.NULL)
            .put(
                "values",
                JSONArray().put(
                    JSONObject()
                        .put("ordinal", 0)
                        .put("status", -1)
                        .put("raw_present", false)
                        .put("raw", JSONObject.NULL)
                        .put("error", error ?: "missing")
                )
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        val digest = sha256(bytes)
        val identityKey = sha256(
            buildList<Byte> {
                addAll(identity.bootId.toByteArray().toList()); add(0)
                addAll(identity.helperGeneration.toByteArray().toList()); add(0)
                addAll(identity.gapId.toByteArray().toList()); add(0)
                addAll(identity.sequence.toString().toByteArray().toList())
            }.toByteArray()
        ).lowercase()
        val descriptor = SecondaryTelemetrySpool.Descriptor(
            sequence,
            identity,
            SecondaryTelemetrySpool.Kind.FULL,
            1000L + sequence,
            2000L + sequence,
            "s%020d_%s.ready".format(sequence, identityKey),
            bytes.size.toLong(),
            digest
        )
        return Fixture(bytes, descriptor)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02X".format(it) }

    private data class Fixture(
        val bytes: ByteArray,
        val descriptor: SecondaryTelemetrySpool.Descriptor
    )
}
