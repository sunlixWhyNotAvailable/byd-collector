package com.bydcollector.collector.data.direct

import com.bydcollector.collector.data.local.Clock
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectWideDiscoveryRunnerTest {
    @Test
    fun runWritesJsonlWithMetaResultsAndSummary() {
        val outputDir = Files.createTempDirectory("direct-wide-discovery-test").toFile()
        val candidates = listOf(
            DirectWideDiscoveryCandidate(1014, 1246777400, DirectFidRegistry.TX_GET_FLOAT, "soc", "test_seed", DirectValueDecoder.FLOAT_PERCENT),
            DirectWideDiscoveryCandidate(1001, 947912730, DirectFidRegistry.TX_GET_INT, "windowFL", "test_neighbor", DirectValueDecoder.INT_RAW)
        )
        val helper = object : DirectVehicleHelper {
            val reads = mutableListOf<DirectWideDiscoveryCandidate>()
            override fun isAlive(): Boolean = true
            override fun read(entry: DirectFidEntry): DirectHelperReadResult {
                reads += candidates.first { it.dev == entry.dev && it.fid == entry.fid && it.tx == entry.tx }
                return if (entry.tx == DirectFidRegistry.TX_GET_FLOAT) {
                    DirectHelperReadResult(0, java.lang.Float.floatToIntBits(82.5f))
                } else {
                    DirectHelperReadResult(-10013, null, "wrong transact")
                }
            }
        }

        val result = DirectWideDiscoveryRunner(
            helper = helper,
            candidates = candidates,
            clock = FakeClock(),
            throttleEvery = Int.MAX_VALUE
        ).run(outputDir)

        val latest = File(outputDir, DirectWideDiscoveryArtifacts.LATEST_FILE_NAME)
        val text = latest.readText(Charsets.UTF_8)
        assertEquals(candidates, helper.reads)
        assertEquals(2, result.candidateCount)
        assertEquals(1, result.okCount)
        assertEquals(latest.absolutePath, result.latestFile.absolutePath)
        assertTrue(text.contains("\"type\":\"meta\""))
        assertTrue(text.contains("\"type\":\"result\""))
        assertTrue(text.contains("\"type\":\"summary\""))
        assertTrue(text.contains("\"source\":\"wide_read_only_discovery\""))
        assertTrue(text.contains("\"float_value\":82.5"))
        assertTrue(text.contains("\"status\":-10013"))
    }

    private class FakeClock : Clock {
        override fun nowIso(): String = "2026-06-04T08:00:00+03:00"
        override fun elapsedRealtimeMs(): Long = 1000L
    }
}
