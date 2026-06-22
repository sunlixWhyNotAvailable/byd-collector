package com.bydcollector.collector.data.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectDebugRoundRobinPollerTest {
    @Test
    fun cursorWrapsRequestedBatchSizeAcrossFullCycle() {
        val parameters = (1..12).map { index ->
            DirectDebugParameter(
                key = "p$index",
                featureGroup = "TEST",
                dev = 1000,
                fid = index,
                tx = 5,
                featureNames = "P_$index",
                featureRefs = "P_$index",
                candidateSource = "test"
            )
        }
        val cursor = DirectDebugRoundRobinCursor(parameters)

        assertEquals((1..5).map { "p$it" }, cursor.nextBatch(5).map { it.key })
        assertEquals((6..10).map { "p$it" }, cursor.nextBatch(5).map { it.key })
        assertEquals(listOf("p11", "p12", "p1", "p2", "p3"), cursor.nextBatch(5).map { it.key })
        assertEquals((4..12).map { "p$it" } + (1..3).map { "p$it" }, cursor.nextBatch(20).map { it.key })
        assertEquals(listOf("p4"), cursor.nextBatch(0).map { it.key })
    }

    @Test
    fun changeDetectorWritesInitialAndChangedValuesOnly() {
        val initial = DirectDebugObserved(status = 0, rawPresent = true, raw = 10, error = null)
        val same = DirectDebugPrevious(status = 0, rawPresent = true, raw = 10, error = null)
        val changed = DirectDebugObserved(status = 0, rawPresent = true, raw = 11, error = null)
        val error = DirectDebugObserved(status = -10011, rawPresent = false, raw = null, error = "wrong direction")

        assertEquals("initial", DirectDebugChangeDetector.reason(null, initial))
        assertNull(DirectDebugChangeDetector.reason(same, initial))
        assertEquals("change", DirectDebugChangeDetector.reason(same, changed))
        assertEquals("error_change", DirectDebugChangeDetector.reason(same, error))
    }

    @Test
    fun debugPollerBacksOffWhenCycleOverrunsInterval() {
        assertEquals(600L, DirectDebugRoundRobinPoller.nextSleepMs(cycleElapsedMs = 400L))
        assertEquals(1_500L, DirectDebugRoundRobinPoller.nextSleepMs(cycleElapsedMs = 1_500L))
        assertEquals(DirectDebugRoundRobinPoller.MAX_OVERLOAD_BACKOFF_MS, DirectDebugRoundRobinPoller.nextSleepMs(cycleElapsedMs = 54_000L))
    }

    @Test
    fun assetParserReadsCsvRows() {
        val csv = """
            key,feature_group,dev,fid,tx,feature_names,feature_refs,candidate_source,source_read_count,source_write_count,source_change_count,seed_last_status,seed_last_raw_present,seed_last_raw_int,seed_last_error,raw_sample,float_sample
            ac_1000_1_5,AC,1000,1,5,Ac.TEST,Ac.TEST,unit,1,0,0,0,1,7,,,
        """.trimIndent()

        val rows = DirectDebugParameterAsset.parse(csv)

        assertEquals(1, rows.size)
        assertEquals("ac_1000_1_5", rows.single().key)
        assertEquals(1000, rows.single().dev)
        assertEquals(5, rows.single().tx)
    }

    @Test
    fun assetParserRejectsMalformedCsvOrUnsupportedTransactions() {
        val malformedHeader = """
            key,feature_group,dev,fid,feature_names,feature_refs,candidate_source
            ac_1000_1_5,AC,1000,1,Ac.TEST,Ac.TEST,unit
        """.trimIndent()
        val writeTx = """
            key,feature_group,dev,fid,tx,feature_names,feature_refs,candidate_source,source_read_count,source_write_count,source_change_count,seed_last_status,seed_last_raw_present,seed_last_raw_int,seed_last_error,raw_sample,float_sample
            ac_1000_1_8,AC,1000,1,8,Ac.TEST,Ac.TEST,unit,1,0,0,0,1,7,,,
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            DirectDebugParameterAsset.parse(malformedHeader)
        }
        assertFailsWith<IllegalArgumentException> {
            DirectDebugParameterAsset.parse(writeTx)
        }
        assertFailsWith<IllegalArgumentException> {
            DirectDebugParameter(
                key = "ac_1000_1_8",
                featureGroup = "AC",
                dev = 1000,
                fid = 1,
                tx = 8,
                featureNames = "Ac.TEST",
                featureRefs = "Ac.TEST",
                candidateSource = "unit"
            )
        }
    }

    @Test
    fun assetParserReadsGeneratedDebugAssetRows() {
        val asset = listOf(
            File("src/main/assets/${DirectDebugParameterAsset.ASSET_NAME}"),
            File("app/src/main/assets/${DirectDebugParameterAsset.ASSET_NAME}")
        ).firstOrNull { it.isFile } ?: error("Missing ${DirectDebugParameterAsset.ASSET_NAME}")

        val rows = DirectDebugParameterAsset.parse(asset.readText(Charsets.UTF_8))

        assertEquals("wide-poll-session-20260605_161751-curated-main80-roundrobin6433-v1", DirectDebugParameterAsset.SOURCE_VERSION)
        assertEquals(6433, rows.size)
        assertTrue(rows.none { it.key == "statistic_1014_1145045040_5" })
        assertTrue(rows.any { it.key == "charging_1009_842006544_5" })
        assertEquals(
            DirectDebugParameterAsset.EXPECTED_HEADER,
            asset.useLines(Charsets.UTF_8) { it.first().split(",") }
        )
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals(rows.size, rows.map { Triple(it.dev, it.fid, it.tx) }.distinct().size)
        assertTrue(rows.all { it.tx == 5 || it.tx == 7 })
    }

    @Test
    fun generatedDebugAssetDoesNotOverlapMainDirectRegistry() {
        val asset = listOf(
            File("src/main/assets/${DirectDebugParameterAsset.ASSET_NAME}"),
            File("app/src/main/assets/${DirectDebugParameterAsset.ASSET_NAME}")
        ).firstOrNull { it.isFile } ?: error("Missing ${DirectDebugParameterAsset.ASSET_NAME}")

        val debugSignatures = DirectDebugParameterAsset.parse(asset.readText(Charsets.UTF_8))
            .map { Triple(it.dev, it.fid, it.tx) }
        assertEquals(debugSignatures.size, debugSignatures.distinct().size)
        val prodSignatures = com.bydcollector.collector.data.direct.DirectFidRegistry.entries
            .map { Triple(it.dev, it.fid, it.tx) }
        assertEquals(prodSignatures.size, prodSignatures.distinct().size)

        assertEquals(emptySet(), prodSignatures.toSet().intersect(debugSignatures.toSet()))
    }
}
