package com.bydcollector.collector.data.debug

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertTrue(source.contains("helper.readSecondaryBatch(entries)"))
        assertTrue(!source.contains("helper.read(parameter.toDirectFidEntry())"))
        assertTrue(source.indexOf("store.openSession(parameters, safeBatchSize)") > source.indexOf("executor.submit"))
        assertTrue(source.contains("openedSessionId?.let { opened ->"))
        assertTrue(source.contains("running.set(false)"))
        assertTrue(source.contains("onTerminalFailure"))
        assertTrue(source.contains("onRuntimeError(error)"))
        assertTrue(source.contains("if (!stopRequested.get()) runCatching(onTerminalFailure)"))
        assertTrue(source.indexOf("store.endSession(opened, stopReason)") < source.indexOf("runCatching(onStopped)"))
        assertTrue(
            source.indexOf("batchResult.diagnostics.status == CollectorHelperProtocol.STATUS_OK") <
                source.indexOf("return store.recordCycle(")
        )
        assertTrue(source.contains("batchResult.results.size == batch.size"))
    }

    @Test
    fun secondaryCycleFencesDrainsAndResumesBeforeLiveRead() {
        val events = mutableListOf<String>()

        val value = SecondaryLiveCycleGate.run(
            pause = { events += "pause"; true },
            drain = {
                events += "drain"
                SecondaryReplayDrainResult(true, 1, 0, 0)
            },
            resume = { events += "resume"; true },
            live = { events += "live"; 42 }
        )

        assertEquals(42, value)
        assertEquals(listOf("pause", "drain", "resume", "live"), events)
    }

    @Test
    fun replayFailureNeverPerformsLiveReadAndRestoresFallback() {
        val events = mutableListOf<String>()
        var live = false

        val error = assertFailsWith<IllegalStateException> {
            SecondaryLiveCycleGate.run(
                pause = { events += "pause"; true },
                drain = {
                    events += "drain"
                    SecondaryReplayDrainResult(false, 0, 0, 0, "receipt commit failed")
                },
                resume = { events += "resume"; true },
                live = { live = true }
            )
        }

        assertTrue(error.message!!.contains("receipt commit failed"))
        assertFalse(live)
        assertEquals(listOf("pause", "drain", "resume"), events)
    }

    @Test
    fun retryableReplayPendingNeverPerformsLiveReadAndRestoresFallback() {
        val events = mutableListOf<String>()
        var live = false

        assertFailsWith<SecondaryReplayPendingException> {
            SecondaryLiveCycleGate.run(
                pause = { events += "pause"; true },
                drain = {
                    events += "drain"
                    SecondaryReplayDrainResult(false, 0, 0, 0, "callback pending", retryable = true)
                },
                resume = { events += "resume"; true },
                live = { live = true }
            )
        }

        assertFalse(live)
        assertEquals(listOf("pause", "drain", "resume"), events)
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

        assertEquals("fid-catalog-20260908-main95-roundrobin23083-both-read-tx-v1", DirectDebugParameterAsset.LEGACY_SOURCE_VERSION)
        assertEquals("fid-catalog-20260922-main95-roundrobin23069-exclusions7-v2", DirectDebugParameterAsset.SOURCE_VERSION)
        assertEquals(listOf(7_692, 7_693, 7_698), shards.map { it.size })
        assertEquals(23_083, rows.size)
        assertEquals(7_698, DirectDebugParameterAsset.MAX_SHARD_SIZE)
        assertTrue(rows.size <= com.bydcollector.collector.direct.CollectorHelperProtocol.MAX_BATCH_SIZE)
        assets.forEach { asset ->
            assertEquals(DirectDebugParameterAsset.EXPECTED_HEADER, asset.useLines(Charsets.UTF_8) { it.first().split(",") })
        }
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals(rows.size, rows.map { Triple(it.dev, it.fid, it.tx) }.distinct().size)
        assertTrue(rows.all { it.tx == 5 || it.tx == 7 })

        val active = rows.filter(DirectDebugParameterAsset::isRuntimeSelected)
        assertEquals(23_069, active.size)
        assertEquals(11_588, active.map { it.dev to it.fid }.distinct().size)
        assertEquals(DirectDebugParameterAsset.ACTIVE_FINGERPRINT, DirectDebugParameterAsset.fingerprint(active))
        assertEquals(14, rows.size - active.size)
        assertEquals(
            setOf(
                1061 to -1728053216,
                1039 to -1728053217,
                1034 to -1728053215,
                1033 to -1728052891,
                1043 to -1728052722,
                1023 to -1728052840,
                1001 to -1728052203
            ),
            (rows - active.toSet()).map { it.dev to it.fid }.toSet()
        )
        assertTrue(active.any { it.dev == 1001 && it.fid == 148898864 }) // steering angle
        assertTrue(active.any { it.dev == 1049 && it.fid == 304087048 }) // wheel speed FL
        assertTrue(active.any { it.dev == 1038 && it.fid == 327155736 }) // vehicle speed

        val dumpRows = rows.filter { it.candidateSource == "fid_catalog_20260804_6e29ad30" }
        val aliasesByPair = dumpRows.groupBy { it.dev to it.fid }.values.map { it.first().featureNames.split(";") }
        assertEquals(11_582, aliasesByPair.size)
        assertEquals(54, aliasesByPair.count { it.size > 1 })
        assertEquals(3, aliasesByPair.maxOf { it.size })
        assertTrue(aliasesByPair.any { aliases -> aliases.any { it.endsWith("_SET") } })
        assertEquals(13, rows.count { it.candidateSource == "live_reflection_device_map" })

        assertEquals(
            listOf(
                "C50FF5A6BCEAEBC374EC16E6797D06BBB6F07DDC841BC743F98076C6CCA07452",
                "89E07B554DD8E305CEF1A6778032CFFCF1E2C1E2A13DC62C4449E1F425F569F0",
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
        val promoted = prodSignatures.drop(82)
        assertEquals(13, promoted.size)
        promoted.forEach { signature ->
            assertTrue(signature !in debugSignatures)
            val otherReadTx = if (signature.third == 5) 7 else 5
            assertTrue(Triple(signature.first, signature.second, otherReadTx) in debugSignatures)
        }
        assertTrue(Triple(1014, 877658152, 5) in debugSignatures)
        assertTrue(Triple(1009, 666894360, 7) in debugSignatures)
        assertTrue(Triple(1023, 1267728400, 5) in debugSignatures)

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
        val active = rows.filter(DirectDebugParameterAsset::isRuntimeSelected)
        val cursor = DirectDebugRoundRobinCursor(active)
        val batch = cursor.nextBatch(DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT)

        assertEquals(23_069, batch.size)
        assertEquals(active.map { it.key }, batch.map { it.key })
        assertEquals(active.size, batch.map { Triple(it.dev, it.fid, it.tx) }.distinct().size)
        assertEquals(batch.map { it.key }, cursor.nextBatch(DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT).map { it.key })
    }

    @Test
    fun oldAndNewCatalogVersionsKeepTheirOwnExplicitOrdinalMaps() {
        val definitions = (0 until 4).map { index -> parameter(index, 1000, index) }
        val active = definitions.drop(1)

        assertEquals(active, DirectDebugParameterAsset.parametersForCatalog(
            DirectDebugParameterAsset.SOURCE_VERSION, definitions, active
        ))
        assertEquals(definitions, DirectDebugParameterAsset.parametersForCatalog(
            DirectDebugParameterAsset.LEGACY_SOURCE_VERSION, definitions, active
        ))
        assertNull(DirectDebugParameterAsset.parametersForCatalog("unknown", definitions, active))
    }

    private fun parameter(index: Int, dev: Int, fid: Int) = DirectDebugParameter(
        key = "p$index",
        featureGroup = "TEST",
        dev = dev,
        fid = fid,
        tx = 5,
        featureNames = "P$index",
        featureRefs = "P$index",
        candidateSource = "test"
    )

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
