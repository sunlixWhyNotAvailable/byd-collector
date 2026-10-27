package com.bydcollector.collector.direct

import android.app.Application
import com.bydcollector.collector.data.direct.*
import com.bydcollector.collector.data.local.*
import com.bydcollector.collector.data.polling.TelemetryWorkerSampleMapper
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class WorkerQuarantineRecoveryTest {
    private fun captured(sequence: Int) = checkNotNull(javaClass.classLoader?.getResourceAsStream(
        "com/bydcollector/collector/direct/worker-v3-20261027-$sequence.json"
    )).use { it.readBytes() }

    private fun filename(bytes: ByteArray): String {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val id = json.getJSONObject("identity")
        fun part(key: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(id.getString(key).toByteArray())
        return "v${json.getInt("record_version")}_${part("boot_id")}_${part("helper_generation")}_${id.getLong("poll_sequence")}.ready"
    }

    private val validator = TelemetryWorkerSpool.SampleValidator { sample ->
        CollectorHelperDaemon.validateWorkerSampleForReplay(sample, sample.catalogVersion,
            requireNotNull(CollectorHelperDaemon.loadWorkerReplayRows(sample.catalogVersion)))
    }

    @Test
    fun capturedQuarantineSurvivesReopenAndCommitsOnceBeforeAcknowledgement() {
        val root = Files.createTempDirectory("worker-recovery").toFile()
        val context = RuntimeEnvironment.getApplication()
        val database = "worker_recovery_${System.nanoTime()}.db"
        fun openStore() = TelemetryStore(context, TelemetryDatabaseHelper(context, database),
            operationalEventJournal = OperationalEventJournal(context))
        var spool = TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES)
        var store = openStore()
        try {
            val originals = listOf(captured(24), captured(28))
            originals.forEach { File(root, filename(it) + ".bad").writeBytes(it) }
            File(root, filename(originals.first()) + ".bad.1").writeBytes(originals.first())
            while (!spool.recoverQuarantined(validator)) { }
            spool.close()
            spool = TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES)
            while (!spool.recoverQuarantined(validator)) { }
            val samples = spool.pending(10, validator)
            assertEquals(listOf(24L, 28L), samples.map { it.identity.pollSequence })
            assertEquals(listOf(91, 76), samples.map { it.values.size })
            assertEquals(3, spool.observe().pendingReadyRecords)
            val session = store.openSession()
            for (sample in samples) {
                val entries = requireNotNull(DirectFidRegistry.workerReplayEntriesForCatalog(sample.catalogVersion))
                val transported = TelemetryWorkerSample(sample.identity, sample.catalogVersion, sample.capturedWallMs,
                    sample.capturedElapsedMs, sample.pollElapsedMs, sample.batchStatus, sample.batchMode,
                    sample.nativeAvailable, sample.groupFailureCount, sample.error, sample.values.map {
                        TelemetryWorkerFieldValue(it.fieldIndex, it.tx, it.dev, it.fid, it.status, it.raw, it.error, it.callbackSource)
                    }, sample.recordVersion)
                val snapshot = TelemetryWorkerSampleMapper.snapshot(transported, entries)
                val input = PersistedPollInput(timestamp = java.time.Instant.ofEpochMilli(sample.capturedWallMs).toString(),
                    ok = true, elapsedMs = sample.pollElapsedMs, requestCount = sample.values.size,
                    errors = null, rawResponseBody = null,
                    readings = snapshot.fields.map { PollReading(it.entry.key, it.raw?.toString(), it.decoded) })
                val parameters = store.getActiveCatalogParameters().filter { parameter ->
                    snapshot.fields.any { it.entry.key == parameter.key }
                }
                val first = store.insertWorkerPoll(session, sample.identity, input, parameters)
                assertTrue(first.inserted)
                store.close()
                store = openStore()
                val repeated = store.insertWorkerPoll(session, sample.identity, input, parameters)
                assertFalse(repeated.inserted)
                assertEquals(first.pollId, repeated.pollId)
                assertEquals(TelemetryWorkerSpool.AckStatus.RELEASED, spool.acknowledge(sample.identity, sample.capturedWallMs).status)
            }
            originals.forEach { assertContentEquals(it, File(root, filename(it) + ".bad.recovered").readBytes()) }
            assertContentEquals(originals.first(), File(root, filename(originals.first()) + ".bad.1.recovered").readBytes())
            assertEquals(0, spool.observe().pendingReadyRecords)
            assertTrue(spool.pending(10, validator).isEmpty())
            // Exercise the current v4 writer with the same real captured values, then the APP mapper.
            val old = samples.first()
            val current = TelemetryWorkerSpool.Sample(old.identity, old.catalogVersion, old.capturedWallMs,
                old.capturedElapsedMs, old.pollElapsedMs, old.batchStatus, old.batchMode, old.nativeAvailable,
                old.groupFailureCount, old.error, old.values)
            assertEquals(TelemetryWorkerSpool.AppendResult.SUCCESS, spool.append(current))
            val v4 = spool.pending(10, validator).single()
            assertEquals(4, v4.recordVersion)
            val transported = TelemetryWorkerSample(v4.identity, v4.catalogVersion, v4.capturedWallMs,
                v4.capturedElapsedMs, v4.pollElapsedMs, v4.batchStatus, v4.batchMode, v4.nativeAvailable,
                v4.groupFailureCount, v4.error, v4.values.map {
                    TelemetryWorkerFieldValue(it.fieldIndex, it.tx, it.dev, it.fid, it.status, it.raw, it.error)
                }, v4.recordVersion)
            assertEquals(v4.values.size, TelemetryWorkerSampleMapper.snapshot(transported,
                requireNotNull(DirectFidRegistry.workerReplayEntriesForCatalog(v4.catalogVersion))).fields.size)
        } finally {
            store.close(); spool.close(); context.deleteDatabase(database); root.deleteRecursively()
        }
    }

    @Test
    fun conflictingCapturedIdentityStaysQuarantinedWithOriginalError() {
        val root = Files.createTempDirectory("worker-conflict").toFile()
        val bytes = captured(24)
        val original = File(root, filename(bytes) + ".bad")
        val conflicting = File(root, filename(bytes))
        original.writeBytes(bytes)
        val changed = JSONObject(bytes.toString(Charsets.UTF_8)).apply {
            getJSONArray("values").getJSONObject(0).put("raw", 3)
        }.toString().toByteArray()
        conflicting.writeBytes(changed)
        try {
            TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES).use { spool ->
                while (!spool.recoverQuarantined(validator)) { }
                assertTrue(spool.pending(10, validator).isEmpty())
                assertContentEquals(bytes, original.readBytes())
                assertFalse(conflicting.exists())
                assertContentEquals(changed, File(conflicting.path + ".bad.1").readBytes())
                val evidence = JSONObject(File(original.path + ".quarantine_recovery.error.json").readText())
                assertEquals("java.io.IOException", evidence.getString("exception_type"))
                assertTrue(evidence.getString("exception_message").contains("conflicting worker payloads"))
                assertTrue(evidence.getString("stack_trace").contains("recoverQuarantined"))
            }
        } finally { root.deleteRecursively() }
    }
}
