package com.bydcollector.collector.data.debug

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectDebugRoundRobinPollerTest {
    @Test
    fun cursorEndsAtCatalogTailWithoutWrappingInsideBatch() {
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
        assertEquals(listOf("p11", "p12"), cursor.nextBatch(5).map { it.key })
        assertEquals((1..12).map { "p$it" }, cursor.nextBatch(20).map { it.key })
        assertEquals(listOf("p1"), cursor.nextBatch(0).map { it.key })
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
        assertEquals(100L, DirectDebugRoundRobinPoller.nextSleepMs(cycleElapsedMs = 400L))
        assertEquals(1_500L, DirectDebugRoundRobinPoller.nextSleepMs(cycleElapsedMs = 1_500L))
        assertEquals(DirectDebugRoundRobinPoller.MAX_OVERLOAD_BACKOFF_MS, DirectDebugRoundRobinPoller.nextSleepMs(cycleElapsedMs = 54_000L))
    }

    @Test
    fun debugIntervalIsHalfSecond() {
        assertEquals(500L, DirectDebugRoundRobinPoller.INTERVAL_MS)
    }

    @Test
    fun debugPollerHasAwaitableShutdownForMaintenance() {
        val source = sourceFile("com/bydcollector/collector/data/debug/DirectDebugRoundRobinPoller.kt").readText()

        assertTrue(source.contains("fun shutdownAndAwait(reason: String = \"shutdown\", timeoutMs: Long): Boolean"))
        assertTrue(source.contains("executor.shutdownNow()"))
        assertTrue(source.contains("executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)"))
        assertTrue(source.contains("fun shutdown(reason: String = \"shutdown\")"))
        assertTrue(source.contains("shutdownAndAwait(reason, 0L)"))
        assertTrue(source.contains("helper.readBatch(entries)"))
        assertTrue(!source.contains("helper.read(parameter.toDirectFidEntry())"))
    }

    @Test
    fun assetParserReadsCsvRows() {
        val csv = """
            key,feature_group,dev,fid,tx,feature_names,feature_refs,candidate_source
            ac_1000_1_5,AC,1000,1,5,Ac.TEST,Ac.TEST,unit
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
            key,feature_group,dev,fid,tx,feature_names,feature_refs,candidate_source
            ac_1000_1_8,AC,1000,1,8,Ac.TEST,Ac.TEST,unit
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
        val assets = debugAssetFiles()
        val shards = assets.map { DirectDebugParameterAsset.parse(it.readText(Charsets.UTF_8)) }
        val rows = shards.flatten()

        assertEquals("fid-catalog-20260804-6e29ad30-main81-roundrobin23096-both-read-tx-v1", DirectDebugParameterAsset.SOURCE_VERSION)
        assertEquals(listOf(7_699, 7_699, 7_698), shards.map { it.size })
        assertEquals(23_096, rows.size)
        assertEquals(7_699, DirectDebugParameterAsset.MAX_SHARD_SIZE)
        assertEquals(rows.size, com.bydcollector.collector.direct.CollectorHelperProtocol.MAX_BATCH_SIZE)
        assets.forEach { asset ->
            assertEquals(DirectDebugParameterAsset.EXPECTED_HEADER, asset.useLines(Charsets.UTF_8) { it.first().split(",") })
        }
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals(rows.size, rows.map { Triple(it.dev, it.fid, it.tx) }.distinct().size)
        assertTrue(rows.all { it.tx == 5 || it.tx == 7 })

        val dumpRows = rows.filter { it.candidateSource == "fid_catalog_20260804_6e29ad30" }
        val aliasesByPair = dumpRows.groupBy { it.dev to it.fid }.values.map { it.first().featureNames.split(";") }
        assertEquals(11_582, aliasesByPair.size)
        assertEquals(54, aliasesByPair.count { it.size > 1 })
        assertEquals(3, aliasesByPair.maxOf { it.size })
        assertTrue(aliasesByPair.any { aliases -> aliases.any { it.endsWith("_SET") } })
        assertEquals(13, rows.count { it.candidateSource == "live_reflection_device_map" })

        assertEquals(
            listOf(
                "7A448F762B52976501FBFFEEBEA8EAEADC443A5C5E3B476B84E8F61C37BD149A",
                "0A95E822EA78502EBC98B20361502E8D43E596B3E683A0E0F8AC266AC2B06CB1",
                "265A61C5098528D504BF2C08389E0D10CFB62BAED7B6CDE6E8ECFD27430DC132"
            ),
            assets.map(::sha256)
        )
    }

    @Test
    fun generatedDebugAssetDoesNotOverlapMainDirectRegistry() {
        val rows = debugAssetFiles().flatMap { DirectDebugParameterAsset.parse(it.readText(Charsets.UTF_8)) }
        val debugSignatures = rows
            .map { Triple(it.dev, it.fid, it.tx) }
        assertEquals(debugSignatures.size, debugSignatures.distinct().size)
        val prodSignatures = com.bydcollector.collector.data.direct.DirectFidRegistry.entries
            .map { Triple(it.dev, it.fid, it.tx) }
        assertEquals(prodSignatures.size, prodSignatures.distinct().size)

        assertEquals(
            setOf(Triple(1001, 315621418, 5)),
            prodSignatures.toSet().intersect(debugSignatures.toSet())
        )

        val allReadTxByDumpPair = (rows.filter { it.candidateSource == "fid_catalog_20260804_6e29ad30" }
            .map { Triple(it.dev, it.fid, it.tx) } + prodSignatures)
            .groupBy { it.first to it.second }
            .mapValues { (_, signatures) -> signatures.map { it.third }.toSet() }
        assertTrue(allReadTxByDumpPair.values.all { it == setOf(5, 7) })
    }

    @Test
    fun generatedShardsFlattenIntoOneCompleteHelperBatch() {
        val rows = debugAssetFiles().flatMap { DirectDebugParameterAsset.parse(it.readText(Charsets.UTF_8)) }
        val cursor = DirectDebugRoundRobinCursor(rows)
        val batch = cursor.nextBatch(DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT)

        assertEquals(23_096, batch.size)
        assertEquals(rows.map { it.key }, batch.map { it.key })
        assertEquals(rows.size, batch.map { Triple(it.dev, it.fid, it.tx) }.distinct().size)
        assertEquals(batch.map { it.key }, cursor.nextBatch(DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT).map { it.key })
    }

    private fun debugAssetFiles(): List<File> = DirectDebugParameterAsset.ASSET_NAMES.map { name ->
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .firstOrNull { it.isFile } ?: error("Missing debug asset: $name")
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02X".format(it) }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
