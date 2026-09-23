package com.bydcollector.collector.data.debug

import com.bydcollector.collector.data.direct.DirectStreamCredentials
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.maintenance.DatabaseMaintenanceGate
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectDebugRoundRobinPollerTest {
    @Test
    fun immutableParameterReusesEntryWithoutSharingCopiedMetadata() {
        val parameter = DirectDebugParameter("sample", "BODY", 1001, 42, 5,
            "中文名称", "reference", "source")
        val entry = parameter.toDirectFidEntry()
        assertTrue(entry === parameter.toDirectFidEntry())
        assertEquals("中文名称", entry.featureNames)
        assertEquals("debug_body", entry.groupName)
        val copied = parameter.copy(key = "changed", fid = 43, featureNames = "新名称")
            .toDirectFidEntry()
        assertEquals("changed", copied.key)
        assertEquals(43, copied.fid)
        assertEquals("新名称", copied.featureNames)
        assertEquals(42, entry.fid)
    }

    @Test
    fun oldOwnerWaitingForMaintenanceCannotWriteIntoTheReplacementDatabase() {
        val gate = DatabaseMaintenanceGate()
        val executor = Executors.newFixedThreadPool(2)
        val maintenanceEntered = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(1)
        val writerAttempted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val ownerActive = AtomicBoolean(true)
        val writes = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        try {
            val maintenance = executor.submit {
                gate.withExclusive {
                    maintenanceEntered.countDown()
                    check(releaseMaintenance.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(maintenanceEntered.await(1, TimeUnit.SECONDS))
            executor.execute {
                writerAttempted.countDown()
                try {
                    withSecondaryDatabaseRead(gate, { ownerActive.get() }, { true }) {
                        writes.incrementAndGet()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    writerFinished.countDown()
                }
            }
            assertTrue(writerAttempted.await(1, TimeUnit.SECONDS))
            assertFalse(writerFinished.await(50, TimeUnit.MILLISECONDS))
            ownerActive.set(false)
            releaseMaintenance.countDown()
            assertTrue(writerFinished.await(1, TimeUnit.SECONDS))
            maintenance.get(1, TimeUnit.SECONDS)

            assertTrue(failure.get() is InterruptedException)
            assertEquals(0, writes.get())
        } finally {
            releaseMaintenance.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun secondaryReadReturningAfterStopCannotCommit() {
        val executor = Executors.newSingleThreadExecutor()
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val workerActive = AtomicBoolean(true)
        val writes = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        try {
            executor.execute {
                readStarted.countDown()
                try {
                    check(releaseRead.await(2, TimeUnit.SECONDS))
                    withSecondaryDatabaseRead(null, { true }, { workerActive.get() }) {
                        writes.incrementAndGet()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    writerFinished.countDown()
                }
            }
            assertTrue(readStarted.await(1, TimeUnit.SECONDS))
            workerActive.set(false)
            releaseRead.countDown()
            assertTrue(writerFinished.await(1, TimeUnit.SECONDS))

            assertTrue(failure.get() is InterruptedException)
            assertEquals(0, writes.get())
        } finally {
            releaseRead.countDown()
            executor.shutdownNow()
        }
    }

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
    fun secondaryHandoverFencesOnceForAnOwnershipAndSkipsLaterCycles() {
        val events = mutableListOf<String>()
        var identity = DirectStreamCredentials(controllerToken = 17L, epoch = 10L)

        val handover = SecondaryOwnershipHandover(
            currentIdentity = { identity },
            pause = { events += "pause"; identity = identity.nextEpoch(); true },
            drain = { sessionId -> events += "drain:$sessionId"; SecondaryReplayDrainResult(true, 1, 0, 0) },
            resume = { events += "resume"; identity = identity.nextEpoch(); true }
        )

        assertEquals(42, handover.run(7L) { events += "live"; 42 })
        assertEquals(12L, identity.epoch)
        assertEquals(43, handover.run(8L) { events += "live"; 43 })
        assertEquals(listOf("pause", "drain:7", "resume", "live", "live"), events)
    }

    @Test
    fun changedControllerTokenOrEpochRequiresANewHandover() {
        val events = mutableListOf<String>()
        var identity = DirectStreamCredentials(controllerToken = 17L, epoch = 10L)
        val handover = SecondaryOwnershipHandover(
            currentIdentity = { identity },
            pause = { events += "pause"; identity = identity.nextEpoch(); true },
            drain = { events += "drain"; SecondaryReplayDrainResult(true, 1, 0, 0) },
            resume = { events += "resume"; identity = identity.nextEpoch(); true }
        )

        handover.run(1L) { events += "live" }
        identity = identity.copy(epoch = 20L)
        handover.run(2L) { events += "live" }
        identity = DirectStreamCredentials(controllerToken = 99L, epoch = 1L)
        handover.run(3L) { events += "live" }

        assertEquals(
            listOf(
                "pause", "drain", "resume", "live",
                "pause", "drain", "resume", "live",
                "pause", "drain", "resume", "live"
            ),
            events
        )
        assertEquals(3L, identity.epoch)
    }

    @Test
    fun helperGenerationChangeWithRepeatedTokenAndEpochRejectsHandover() {
        val events = mutableListOf<String>()
        var identity = DirectStreamCredentials(controllerToken = 17L, epoch = 10L, generation = 4L)
        val handover = SecondaryOwnershipHandover(
            currentIdentity = { identity },
            pause = { events += "pause"; identity = identity.copy(generation = 5L); true },
            drain = { events += "drain"; SecondaryReplayDrainResult(true, 1, 0, 0) },
            resume = { events += "resume"; true }
        )

        assertFailsWith<SecondaryReplayPendingException> {
            handover.run(5L) { events += "live" }
        }

        assertEquals(17L, identity.controllerToken)
        assertEquals(10L, identity.epoch)
        assertEquals(5L, identity.generation)
        assertEquals(listOf("pause", "resume"), events)
    }

    @Test
    fun nonRetryableReplayFailureRemainsAnErrorAndNeverPerformsLiveRead() {
        val events = mutableListOf<String>()
        var identity = DirectStreamCredentials(controllerToken = 17L, epoch = 10L)
        val handover = SecondaryOwnershipHandover(
            currentIdentity = { identity },
            pause = { events += "pause"; identity = identity.nextEpoch(); true },
            drain = {
                events += "drain"
                SecondaryReplayDrainResult(false, 0, 0, 0, "archive receipt rejected")
            },
            resume = { events += "resume"; identity = identity.nextEpoch(); true }
        )

        val error = assertFailsWith<IllegalStateException> {
            handover.run(9L) { events += "live" }
        }

        assertTrue(error.message!!.contains("archive receipt rejected"))
        assertEquals(listOf("pause", "drain", "resume"), events)
    }

    @Test
    fun retryableReplayPendingResumesAndNeverPerformsLiveReadUntilDrainCompletes() {
        val events = mutableListOf<String>()
        var identity = DirectStreamCredentials(controllerToken = 17L, epoch = 10L)
        var drainAttempts = 0
        val handover = SecondaryOwnershipHandover(
            currentIdentity = { identity },
            pause = { events += "pause"; identity = identity.nextEpoch(); true },
            drain = {
                events += "drain"
                drainAttempts++
                if (drainAttempts == 1) {
                    SecondaryReplayDrainResult(false, 0, 0, 0, "archive receipt pending", retryable = true)
                } else {
                    SecondaryReplayDrainResult(true, 1, 0, 0)
                }
            },
            resume = { events += "resume"; identity = identity.nextEpoch(); true }
        )

        assertFailsWith<SecondaryReplayPendingException> {
            handover.run(3L) { events += "live" }
        }
        assertEquals(listOf("pause", "drain", "resume"), events)
        assertEquals(12L, identity.epoch)

        handover.run(3L) { events += "live" }
        assertEquals(14L, identity.epoch)
        assertEquals(listOf("pause", "drain", "resume", "pause", "drain", "resume", "live"), events)
    }

    @Test
    fun ownershipChangeDuringPauseDoesNotDrainOrReadUnderTheNewToken() {
        val events = mutableListOf<String>()
        var identity = DirectStreamCredentials(controllerToken = 17L, epoch = 10L)
        var firstPause = true
        val handover = SecondaryOwnershipHandover(
            currentIdentity = { identity },
            pause = {
                events += "pause"
                if (firstPause) {
                    firstPause = false
                    identity = DirectStreamCredentials(controllerToken = 99L, epoch = 1L)
                } else {
                    identity = identity.nextEpoch()
                }
                true
            },
            drain = { events += "drain"; SecondaryReplayDrainResult(true, 1, 0, 0) },
            resume = {
                events += "resume"
                if (!firstPause && identity.controllerToken == 99L && identity.epoch > 1L) {
                    identity = identity.nextEpoch()
                }
                true
            }
        )

        assertFailsWith<SecondaryReplayPendingException> {
            handover.run(4L) { events += "live" }
        }
        assertEquals(listOf("pause", "resume"), events)

        handover.run(4L) { events += "live" }
        assertEquals(listOf("pause", "resume", "pause", "drain", "resume", "live"), events)
        assertEquals(3L, identity.epoch)
    }

    @Test
    fun pendingHandoverUsesShortBoundedRetryScheduleWithoutChangingLiveCadence() {
        assertEquals(listOf(100L, 250L, 500L, 1_000L, 1_000L), (0..4).map {
            DirectDebugRoundRobinPoller.handoverRetryDelayMs(it)
        })
        assertEquals(100L, DirectDebugRoundRobinPoller.nextSleepMs(cycleElapsedMs = 400L))
        assertEquals(1_500L, DirectDebugRoundRobinPoller.nextSleepMs(cycleElapsedMs = 1_500L))
    }

    @Test
    fun stopOrInterruptSuppressesBothFailureCallbacks() {
        assertFalse(DirectDebugRoundRobinPoller.shouldReportFailureCallbacks(stopRequested = true, interrupted = false))
        assertFalse(DirectDebugRoundRobinPoller.shouldReportFailureCallbacks(stopRequested = false, interrupted = true))
        assertTrue(DirectDebugRoundRobinPoller.shouldReportFailureCallbacks(stopRequested = false, interrupted = false))
    }

    @Test
    fun staleOrReplayPendingLiveStatusRetriesHandoverInsteadOfStoppingPoller() {
        assertTrue(DirectDebugRoundRobinPoller.isOwnershipRetryStatus(CollectorHelperProtocol.STATUS_REPLAY_PENDING))
        assertTrue(DirectDebugRoundRobinPoller.isOwnershipRetryStatus(CollectorHelperProtocol.STATUS_STALE_TOKEN))
        assertFalse(DirectDebugRoundRobinPoller.isOwnershipRetryStatus(CollectorHelperProtocol.STATUS_OK))
        assertFalse(DirectDebugRoundRobinPoller.isOwnershipRetryStatus(-900))
    }

    @Test
    fun executorTerminationCleanupCoversCancelledBeforeStartAfterActiveWorkEnds() {
        val activeTaskStarted = CountDownLatch(1)
        val releaseActiveTask = CountDownLatch(1)
        val activeTaskFinished = AtomicBoolean(false)
        val queuedTaskStarted = AtomicBoolean(false)
        val terminatedTooEarly = AtomicBoolean(false)
        val terminatedCount = AtomicInteger(0)
        val terminated = CountDownLatch(1)
        val executor = newRoundRobinPollerExecutor {
            terminatedTooEarly.set(!activeTaskFinished.get())
            terminatedCount.incrementAndGet()
            terminated.countDown()
        }

        executor.execute {
            activeTaskStarted.countDown()
            try {
                while (true) {
                    try {
                        if (releaseActiveTask.await(10L, TimeUnit.MILLISECONDS)) break
                    } catch (_: InterruptedException) {
                        // Keep the active task alive until its simulated write section is released.
                    }
                }
            } finally {
                activeTaskFinished.set(true)
            }
        }
        try {
            assertTrue(activeTaskStarted.await(1L, TimeUnit.SECONDS))
            val queued = executor.submit { queuedTaskStarted.set(true) }
            assertTrue(queued.cancel(false))
            executor.shutdownNow()
            assertFalse(terminated.await(50L, TimeUnit.MILLISECONDS))
            assertFalse(activeTaskFinished.get())
        } finally {
            releaseActiveTask.countDown()
            executor.shutdownNow()
        }

        assertTrue(terminated.await(1L, TimeUnit.SECONDS))
        assertTrue(executor.awaitTermination(1L, TimeUnit.SECONDS))
        assertFalse(terminatedTooEarly.get())
        assertFalse(queuedTaskStarted.get())
        assertEquals(1, terminatedCount.get())
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

    private fun DirectStreamCredentials.nextEpoch(): DirectStreamCredentials = copy(epoch = epoch + 1L)

}
