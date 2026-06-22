package com.bydcollector.collector.data.direct

import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class DirectWideDiscoveryRunner(
    private val helper: DirectVehicleHelper,
    private val candidates: List<DirectWideDiscoveryCandidate> = DirectWideDiscoveryRegistry.candidates,
    private val clock: Clock = SystemClockAdapter(),
    private val throttleEvery: Int = 50,
    private val throttleMs: Long = 10L
) {
    fun run(outputDir: File): DirectWideDiscoveryRunResult {
        require(candidates.size <= DirectWideDiscoveryRegistry.MAX_CANDIDATES) {
            "Too many direct discovery candidates: ${candidates.size}"
        }
        outputDir.mkdirs()
        val startedAt = clock.nowIso()
        val startedMs = clock.elapsedRealtimeMs()
        val outputFile = File(outputDir, "wide_discovery_${fileTimestamp()}.jsonl")
        var okCount = 0
        var errorCount = 0

        outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendJsonLine(
                JSONObject()
                    .put("type", "meta")
                    .put("source", "wide_read_only_discovery")
                    .put("started_at", startedAt)
                    .put("candidate_count", candidates.size)
                    .put("max_candidates", DirectWideDiscoveryRegistry.MAX_CANDIDATES)
                    .put("tx_values", JSONArray().put(DirectFidRegistry.TX_GET_INT).put(DirectFidRegistry.TX_GET_FLOAT))
            )

            candidates.forEachIndexed { index, candidate ->
                val before = clock.elapsedRealtimeMs()
                val result = helper.read(candidate.toEntry())
                val elapsedMs = clock.elapsedRealtimeMs() - before
                if (result.ok) okCount++ else errorCount++
                writer.appendJsonLine(resultJson(candidate, result, elapsedMs))
                if (throttleEvery > 0 && throttleMs > 0 && (index + 1) % throttleEvery == 0) {
                    Thread.sleep(throttleMs)
                }
            }

            writer.appendJsonLine(
                JSONObject()
                    .put("type", "summary")
                    .put("source", "wide_read_only_discovery")
                    .put("finished_at", clock.nowIso())
                    .put("elapsed_ms", clock.elapsedRealtimeMs() - startedMs)
                    .put("candidate_count", candidates.size)
                    .put("ok_count", okCount)
                    .put("error_count", errorCount)
            )
        }

        val latestFile = File(outputDir, DirectWideDiscoveryArtifacts.LATEST_FILE_NAME)
        outputFile.copyTo(latestFile, overwrite = true)
        return DirectWideDiscoveryRunResult(
            outputFile = outputFile,
            latestFile = latestFile,
            candidateCount = candidates.size,
            okCount = okCount,
            errorCount = errorCount,
            elapsedMs = clock.elapsedRealtimeMs() - startedMs
        )
    }

    private fun resultJson(
        candidate: DirectWideDiscoveryCandidate,
        result: DirectHelperReadResult,
        elapsedMs: Long
    ): JSONObject {
        val json = JSONObject()
            .put("type", "result")
            .put("source", "wide_read_only_discovery")
            .put("candidate_source", candidate.source)
            .put("dev", candidate.dev)
            .put("fid", candidate.fid)
            .put("tx", candidate.tx)
            .put("status", result.status)
            .put("elapsed_ms", elapsedMs)
        candidate.seedKey?.let { json.put("seed_key", it) }
        result.raw?.let { raw ->
            json.put("raw_int", raw)
            if (candidate.tx == DirectFidRegistry.TX_GET_FLOAT) {
                Float.fromBits(raw)
                    .takeIf { it.isFinite() }
                    ?.let { json.put("float_value", it.toDouble()) }
            }
        }
        result.error?.let { json.put("error", it) }
        return json
    }

    private fun Appendable.appendJsonLine(json: JSONObject) {
        append(json.toString())
        append('\n')
    }

    private fun fileTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
}

data class DirectWideDiscoveryRunResult(
    val outputFile: File,
    val latestFile: File,
    val candidateCount: Int,
    val okCount: Int,
    val errorCount: Int,
    val elapsedMs: Long
)

object DirectWideDiscoveryCoordinator {
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun tryStart(): Boolean = running.compareAndSet(false, true)

    fun finish() {
        running.set(false)
    }
}
