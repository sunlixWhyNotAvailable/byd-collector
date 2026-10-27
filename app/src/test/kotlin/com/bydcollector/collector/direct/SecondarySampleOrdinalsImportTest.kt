package com.bydcollector.collector.direct

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.debug.DirectDebugParameterAsset
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.debug.SecondaryImportResult
import com.bydcollector.collector.data.callback.CallbackDelivery
import com.bydcollector.collector.data.callback.CallbackImportResult
import org.json.JSONObject
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SecondarySampleOrdinalsImportTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun catalogSpoolPersistsSparseGets() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "secondary_sample_ordinals_${System.nanoTime()}.db"
        val helper = DirectDebugDatabaseHelper(context, databaseName)
        val store = DirectDebugStore(context, helper)
        val parameters = DirectDebugParameterAsset.load(context)
        val spoolDirectory = temporaryFolder.newFolder("secondary-v5")
        var spool: SecondaryTelemetrySpool? = null
        try {
            val sessionId = store.openSession(parameters, batchSize = parameters.size)
            val activeSpool = SecondaryTelemetrySpool.openForTest(
                spoolDirectory,
                SecondaryTelemetrySpool.MAX_SPOOL_BYTES,
                parameters.size
            )
            spool = activeSpool
            // A fresh writer/importer must accept a high catalog ordinal without a FULL baseline.
            assertEquals(SecondaryTelemetrySpool.AppendResult.SPARSE, activeSpool.append(cycle(
                SecondaryTelemetrySpool.CycleIdentity("boot-a", "generation-a", "gap-a", 1L),
                9_000L, 4_000L, listOf(value(parameters.lastIndex, parameters.lastIndex)),
                listOf(parameters.lastIndex)
            )))
            val (initialDescriptor, initialRecord) = readOldest(activeSpool)
            assertEquals(SecondaryTelemetrySpool.Kind.SPARSE, initialRecord.kind)
            assertEquals(parameters.size, initialRecord.fieldCount)
            assertEquals(null, initialRecord.predecessorIdentity)
            assertIs<SecondaryImportResult.Committed>(
                store.importSecondaryRecord(sessionId, initialRecord, initialDescriptor.sha256)
            )
            assertEquals(1L, rowCount(helper.writableDatabase, "debug_direct_readings"))
            assertEquals(SecondaryTelemetrySpool.AckResult.RELEASED, activeSpool.acknowledge(initialDescriptor))

            val epoch = SecondaryTelemetrySpool.CycleIdentity("boot-a", "generation-a", "gap-a", 2L)
            val full = cycle(
                identity = epoch,
                wallMs = 10_000L,
                elapsedMs = 5_000L,
                values = parameters.indices.map { ordinal ->
                    value(ordinal, when (ordinal) {
                        0 -> 10
                        1 -> 20
                        else -> ordinal
                    })
                },
                sampledOrdinals = parameters.indices.toList()
            )
            assertEquals(SecondaryTelemetrySpool.AppendResult.FULL, activeSpool.append(full))

            val (fullDescriptor, fullRecord) = readOldest(activeSpool)
            assertEquals(5, fullRecord.recordVersion)
            assertEquals(parameters.size, fullRecord.fieldCount)
            assertEquals(parameters.indices.toList(), fullRecord.sampledOrdinals)
            assertIs<SecondaryImportResult.Committed>(
                store.importSecondaryRecord(sessionId, fullRecord, fullDescriptor.sha256)
            )
            assertEquals(SecondaryTelemetrySpool.AckResult.RELEASED, activeSpool.acknowledge(fullDescriptor))

            val nextEpoch = SecondaryTelemetrySpool.CycleIdentity("boot-a", "generation-a", "gap-a", 3L)
            val unchangedSubset = cycle(
                identity = nextEpoch,
                wallMs = 10_500L,
                elapsedMs = 5_500L,
                values = listOf(value(1, 20)),
                sampledOrdinals = listOf(1)
            )
            assertEquals(SecondaryTelemetrySpool.AppendResult.SPARSE, activeSpool.append(unchangedSubset))

            val (deltaDescriptor, deltaRecord) = readOldest(activeSpool)
            assertEquals(SecondaryTelemetrySpool.Kind.SPARSE, deltaRecord.kind)
            assertEquals(listOf(1), deltaRecord.sampledOrdinals)
            assertEquals(20, deltaRecord.values.single().raw)
            assertEquals(null, deltaRecord.predecessorIdentity)
            assertIs<SecondaryImportResult.Committed>(
                store.importSecondaryRecord(sessionId, deltaRecord, deltaDescriptor.sha256)
            )
            assertEquals(SecondaryTelemetrySpool.AckResult.RELEASED, activeSpool.acknowledge(deltaDescriptor))

            val cycles = helper.writableDatabase.rawQuery(
                "SELECT attempted_count, ok_count, error_count FROM debug_direct_cycles ORDER BY id",
                emptyArray()
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)))
                }
            }
            assertEquals(
                listOf(Triple(1, 1, 0), Triple(parameters.size, parameters.size, 0), Triple(1, 1, 0)),
                cycles
            )
            val db = helper.writableDatabase
            assertEquals(parameters.size.toLong(), rowCount(db, "debug_direct_readings"))
            assertEquals(
                parameters.size.toLong() + 2L,
                scalarLong(db, "SELECT attempted_count FROM debug_direct_sessions WHERE id = $sessionId")
            )

            // A new GET with unchanged raw data must retain its own capture window and callback race.
            val lineage = HelperGetLineage(
                "boot-a", "generation-a", CollectorHelperProtocol.STREAM_SECONDARY, 0L,
                "secondary-get-3", 100L, 11_000L, 6_000L, 11_010L, 6_010L,
                98L, 99L, true
            )
            val repeated = cycle(
                identity = SecondaryTelemetrySpool.CycleIdentity("boot-a", "generation-a", "gap-a", 4L),
                wallMs = 11_020L,
                elapsedMs = 6_020L,
                values = listOf(SecondaryTelemetrySpool.Value(1, 0, true, 20, null, false, lineage)),
                sampledOrdinals = listOf(1)
            )
            assertEquals(SecondaryTelemetrySpool.AppendResult.SPARSE, activeSpool.append(repeated))
            val (repeatedDescriptor, repeatedRecord) = readOldest(activeSpool)
            val captured = requireNotNull(repeatedRecord.values.single().getLineage)
            assertEquals(20, repeatedRecord.values.single().raw)
            assertEquals(lineage.toJson().toString(), captured.toJson().toString())
            assertTrue(captured.supersededByCallback)
            assertIs<SecondaryImportResult.Committed>(
                store.importSecondaryRecord(sessionId, repeatedRecord, repeatedDescriptor.sha256)
            )
            assertEquals(SecondaryTelemetrySpool.AckResult.RELEASED, activeSpool.acknowledge(repeatedDescriptor))
            val fixture = javaClass.getResourceAsStream(
                "/com/bydcollector/collector/data/input/callback-batch-20260922.json"
            )!!.bufferedReader().use { JSONObject(it.readText()) }
            val capturedBatch = fixture.getJSONObject("batch")
            val capturedEvent = fixture.getJSONArray("events").getJSONObject(0)
            // Original vehicle value and capture clocks, delivered to the independent Secondary store.
            val event = TelemetryCallbackBatch.Event(
                capturedEvent.getLong("event_sequence"), capturedEvent.getInt("device"),
                capturedEvent.getInt("fid"), capturedEvent.getInt("native_type"),
                capturedEvent.getInt("raw_bits"), null, capturedEvent.getLong("received_wall_ms"),
                capturedEvent.getLong("received_elapsed_ms"), null, capturedEvent.getString("quality")
            )
            val callback = TelemetryCallbackBatch(capturedBatch.getString("boot_id"),
                capturedBatch.getString("helper_generation"), CollectorHelperProtocol.STREAM_SECONDARY,
                capturedBatch.getLong("epoch"), capturedBatch.getLong("batch_sequence"), listOf(event))
            val callbackDigest = TelemetryCallbackBatch.digest(callback.encode())
            assertIs<CallbackImportResult.Committed>(store.importCallbackBatch(callback, callbackDigest, CallbackDelivery.LIVE))
            assertTrue(assertIs<CallbackImportResult.Committed>(
                store.importCallbackBatch(callback, callbackDigest, CallbackDelivery.REPLAY)).duplicate)
            assertEquals(1L, rowCount(db, "raw_callback_events"))
            assertEquals(parameters.size.toLong(), rowCount(db, "debug_direct_readings"))
            assertEquals(parameters.size.toLong() + 1L, store.dashboardReadingCount())
            assertEquals(Instant.ofEpochMilli(event.receivedWallMs).toString(), store.status().lastReadingAt)
            store.close()
            val reopened = DirectDebugDatabaseHelper(context, databaseName)
            try {
                reopened.readableDatabase.rawQuery(
                    "SELECT source_key, started_elapsed_ms, completed_elapsed_ms, callback_watermark_start, " +
                        "callback_watermark_end, superseded_by_callback, raw_int FROM debug_secondary_get_lineage " +
                        "WHERE read_identity = ? AND record_sequence = ?",
                    arrayOf(lineage.readIdentity, lineage.recordSequence.toString())
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(parameters[1].key, cursor.getString(0))
                    assertEquals(lineage.startedElapsedMs, cursor.getLong(1))
                    assertEquals(lineage.completedElapsedMs, cursor.getLong(2))
                    assertEquals(lineage.callbackWatermarkStart, cursor.getLong(3))
                    assertEquals(lineage.callbackWatermarkEnd, cursor.getLong(4))
                    assertEquals(1, cursor.getInt(5))
                    assertEquals(20, cursor.getInt(6))
                    assertTrue(!cursor.moveToNext())
                }
            } finally {
                reopened.close()
            }
        } finally {
            spool?.close()
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun cycle(
        identity: SecondaryTelemetrySpool.CycleIdentity,
        wallMs: Long,
        elapsedMs: Long,
        values: List<SecondaryTelemetrySpool.Value>,
        sampledOrdinals: List<Int>
    ) = SecondaryTelemetrySpool.Cycle(
        identity,
        DirectDebugParameterAsset.SOURCE_VERSION,
        wallMs,
        elapsedMs,
        10L,
        0,
        CollectorHelperProtocol.MODE_SCALAR_FALLBACK,
        false,
        0,
        0,
        values.size,
        0,
        null,
        values,
        sampledOrdinals
    )

    private fun value(ordinal: Int, raw: Int) =
        SecondaryTelemetrySpool.Value(ordinal, 0, true, raw, null, false)

    private fun readOldest(
        spool: SecondaryTelemetrySpool
    ): Pair<SecondaryTelemetrySpool.Descriptor, SecondaryTelemetrySpool.Record> {
        val descriptor = requireNotNull(spool.oldest())
        val encoded = ByteArrayOutputStream()
        var offset = 0L
        while (offset < descriptor.length) {
            val page = spool.readSlice(descriptor, offset, SecondaryTelemetrySpool.MAX_SLICE_BYTES)
            assertTrue("secondary spool page must make progress", page.isNotEmpty())
            encoded.write(page)
            offset += page.size.toLong()
        }
        val bytes = encoded.toByteArray()
        assertEquals(descriptor.length, bytes.size.toLong())
        return descriptor to SecondaryTelemetrySpool.Codec.decode(bytes)
    }

    private fun rowCount(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT id FROM $table", emptyArray()).use { cursor -> cursor.count.toLong() }

    private fun scalarLong(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, emptyArray()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
