package com.bydcollector.collector.direct

import com.bydcollector.collector.data.debug.DirectDebugParameterAsset
import com.bydcollector.collector.data.direct.DirectFidRegistry
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectorHelperDaemonBatchTest {
    @Test
    fun nativeGroupsPreserveRequestOrderDuplicatesAndFloatBits() {
        val rows = listOf(
            address(5, 1000, 11),
            address(7, 1001, 21),
            address(5, 1000, 12),
            address(5, 1000, 11)
        )
        val native = object : CollectorHelperDaemon.NativeReader {
            override fun isAvailable() = true
            override fun unavailableReason(): String? = null
            override fun readInts(dev: Int, fids: IntArray): IntArray {
                assertEquals(1000, dev)
                assertContentEquals(intArrayOf(11, 12, 11), fids)
                return intArrayOf(110, 120, 111)
            }
            override fun readFloats(dev: Int, fids: IntArray): FloatArray {
                assertEquals(1001, dev)
                assertContentEquals(intArrayOf(21), fids)
                return floatArrayOf(81.5f)
            }
        }

        val result = CollectorHelperDaemon.BatchEngine.run(rows, { error("scalar fallback not expected") }, native)

        assertEquals(CollectorHelperProtocol.MODE_NATIVE, result.mode)
        assertEquals(2, result.nativeGroupCount)
        assertEquals(0, result.fallbackReadCount)
        assertEquals(listOf(110, 81.5f.toRawBits(), 120, 111), result.values.map { it.raw })
        assertTrue(result.values.all { it.status == 0 })
    }

    @Test
    fun unavailableNativeReaderFallsBackInsideOneBatch() {
        val rows = listOf(address(5, 1000, 11), address(7, 1001, 21))
        val scalarRows = mutableListOf<CollectorHelperDaemon.Address>()
        val native = unavailableNative("BYDAutoManager missing")

        val result = CollectorHelperDaemon.BatchEngine.run(rows, { row ->
            scalarRows += row
            CollectorHelperDaemon.ReadValue.ok(row.fid)
        }, native)

        assertEquals(CollectorHelperProtocol.MODE_SCALAR_FALLBACK, result.mode)
        assertEquals(rows, scalarRows)
        assertEquals(2, result.fallbackGroupCount)
        assertEquals(2, result.fallbackReadCount)
        assertEquals("BYDAutoManager missing", result.error)
    }

    @Test
    fun nativeFailureFallsBackOnlyForTheFailedGroup() {
        val rows = listOf(address(5, 1000, 11), address(7, 1001, 21), address(5, 1000, 12))
        val scalarRows = mutableListOf<CollectorHelperDaemon.Address>()
        val native = object : CollectorHelperDaemon.NativeReader {
            override fun isAvailable() = true
            override fun unavailableReason(): String? = null
            override fun readInts(dev: Int, fids: IntArray) = intArrayOf(110, 120)
            override fun readFloats(dev: Int, fids: IntArray) = floatArrayOf()
        }

        val result = CollectorHelperDaemon.BatchEngine.run(rows, { row ->
            scalarRows += row
            CollectorHelperDaemon.ReadValue.ok(210)
        }, native)

        assertEquals(CollectorHelperProtocol.MODE_NATIVE_WITH_FALLBACK, result.mode)
        assertEquals(listOf(address(7, 1001, 21)), scalarRows)
        assertEquals(1, result.nativeGroupCount)
        assertEquals(1, result.fallbackGroupCount)
        assertEquals(1, result.fallbackReadCount)
        assertEquals(1, result.groupFailureCount)
        assertTrue(result.error?.contains("length mismatch") == true)
        assertEquals(listOf(110, 210, 120), result.values.map { it.raw })
    }

    @Test
    fun workerSamplePreservesCatalogOrderIdentityAndBatchMetadata() {
        val rows = listOf(address(5, 1000, 11), address(7, 1001, 21))
        val result = CollectorHelperDaemon.BatchResult(
            CollectorHelperProtocol.STATUS_OK,
            CollectorHelperProtocol.MODE_NATIVE_WITH_FALLBACK,
            true,
            1,
            1,
            1,
            1,
            17,
            arrayOf(
                CollectorHelperDaemon.ReadValue.ok(110),
                CollectorHelperDaemon.ReadValue.error(CollectorHelperProtocol.STATUS_READ_ERROR, "read failed")
            ),
            "group failed"
        )
        val identity = TelemetryWorkerSampleIdentity("boot-a", "generation-a", 3)

        val sample = CollectorHelperDaemon.workerSample(identity, "catalog-a", 100, 90, rows, result)

        assertEquals(identity, sample.identity)
        assertEquals("catalog-a", sample.catalogVersion)
        assertEquals(100, sample.capturedWallMs)
        assertEquals(90, sample.capturedElapsedMs)
        assertEquals(17, sample.pollElapsedMs)
        assertEquals(CollectorHelperProtocol.MODE_NATIVE_WITH_FALLBACK, sample.batchMode)
        assertEquals(1, sample.groupFailureCount)
        assertEquals("group failed", sample.error)
        assertEquals(listOf(0, 1), sample.values.map { it.fieldIndex })
        assertEquals(rows.map { Triple(it.tx, it.dev, it.fid) }, sample.values.map { Triple(it.tx, it.dev, it.fid) })
        assertEquals(listOf(110, null), sample.values.map { it.raw })
        assertEquals(listOf(null, "read failed"), sample.values.map { it.error })
        assertEquals(500L, CollectorHelperDaemon.WorkerPollLoop.FALLBACK_INTERVAL_MS)

        assertFailsWith<IllegalArgumentException> {
            CollectorHelperDaemon.workerSample(identity, "catalog-a", 100, 90, rows.dropLast(1), result)
        }
    }

    @Test
    fun fallbackCatalogKeepsCurrent95AndExactLegacy82StrictAndOrdered() {
        val rows = CollectorHelperDaemon.loadMainRows()
        val legacyRows = requireNotNull(
            CollectorHelperDaemon.loadWorkerReplayRows(DirectFidRegistry.LEGACY_WORKER_CATALOG_VERSION)
        )
        val expected = DirectFidRegistry.entries

        assertEquals(95, rows.size)
        assertEquals(82, legacyRows.size)
        assertEquals(rows.take(82), legacyRows)
        assertNull(CollectorHelperDaemon.loadWorkerReplayRows("unknown-catalog"))
        assertEquals(
            expected.map { Triple(it.tx, it.dev, it.fid) },
            rows.map { Triple(it.tx, it.dev, it.fid) }
        )
        val result = CollectorHelperDaemon.BatchResult(
            CollectorHelperProtocol.STATUS_OK,
            CollectorHelperProtocol.MODE_NATIVE,
            true,
            1,
            0,
            0,
            0,
            1,
            Array(rows.size) { CollectorHelperDaemon.ReadValue.ok(it) },
            null
        )
        val sample = CollectorHelperDaemon.workerSample(
            TelemetryWorkerSampleIdentity("boot-a", "generation-a", 1),
            DirectFidRegistry.CATALOG_VERSION,
            100,
            90,
            rows,
            result
        )

        CollectorHelperDaemon.validateWorkerSampleForReplay(
            sample,
            DirectFidRegistry.CATALOG_VERSION,
            rows,
            DirectFidRegistry.LEGACY_WORKER_CATALOG_VERSION,
            legacyRows
        )
        val legacyResult = CollectorHelperDaemon.BatchResult(
            CollectorHelperProtocol.STATUS_OK,
            CollectorHelperProtocol.MODE_NATIVE,
            true,
            1,
            0,
            0,
            0,
            1,
            Array(legacyRows.size) { CollectorHelperDaemon.ReadValue.ok(it) },
            null
        )
        val legacySample = CollectorHelperDaemon.workerSample(
            TelemetryWorkerSampleIdentity("boot-a", "generation-a", 2),
            DirectFidRegistry.LEGACY_WORKER_CATALOG_VERSION,
            100,
            90,
            legacyRows,
            legacyResult
        )
        CollectorHelperDaemon.validateWorkerSampleForReplay(
            legacySample,
            DirectFidRegistry.CATALOG_VERSION,
            rows,
            DirectFidRegistry.LEGACY_WORKER_CATALOG_VERSION,
            legacyRows
        )
        assertFailsWith<IllegalArgumentException> {
            CollectorHelperDaemon.validateWorkerSampleForReplay(
                CollectorHelperDaemon.workerSample(
                    TelemetryWorkerSampleIdentity("boot-a", "generation-a", 3),
                    "wrong-catalog",
                    100,
                    90,
                    rows,
                    result
                ),
                DirectFidRegistry.CATALOG_VERSION,
                rows,
                DirectFidRegistry.LEGACY_WORKER_CATALOG_VERSION,
                legacyRows
            )
        }
        val corruptRows = rows.toMutableList().also {
            val first = it.first()
            it[0] = address(first.tx, first.dev, first.fid + 1)
        }
        val corruptSample = CollectorHelperDaemon.workerSample(
            TelemetryWorkerSampleIdentity("boot-a", "generation-a", 4),
            DirectFidRegistry.CATALOG_VERSION,
            100,
            90,
            corruptRows,
            result
        )
        assertFailsWith<IllegalArgumentException> {
            CollectorHelperDaemon.validateWorkerSampleForReplay(
                corruptSample,
                DirectFidRegistry.CATALOG_VERSION,
                rows,
                DirectFidRegistry.LEGACY_WORKER_CATALOG_VERSION,
                legacyRows
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CollectorHelperDaemon.validateWorkerSampleForReplay(
                CollectorHelperDaemon.workerSample(
                    TelemetryWorkerSampleIdentity("boot-a", "generation-a", 5),
                    DirectFidRegistry.CATALOG_VERSION,
                    100,
                    90,
                    legacyRows,
                    legacyResult
                ),
                DirectFidRegistry.CATALOG_VERSION,
                rows,
                DirectFidRegistry.LEGACY_WORKER_CATALOG_VERSION,
                legacyRows
            )
        }
    }

    @Test
    fun apkWhitelistLoadsMainAndThreeDebugShardsAndRejectsUnknownReads() {
        val assets = DirectDebugParameterAsset.ASSET_NAMES.map(::assetFile)
        val apk = Files.createTempFile("bydcollector-whitelist", ".apk").toFile()
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                assets.forEach { asset ->
                    zip.putNextEntry(ZipEntry("assets/${asset.name}"))
                    asset.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            val whitelist = CollectorHelperDaemon.loadWhitelist(apk.absolutePath)
            val known = address(5, 1014, 1145045040)
            val debugRows = assets.flatMap { DirectDebugParameterAsset.parse(it.readText(Charsets.UTF_8)) }
            val debugAddresses = debugRows.map { address(it.tx, it.dev, it.fid) }

            assertEquals(3, assets.size)
            assertEquals(23177, whitelist.size)
            assertEquals(23083, debugAddresses.size)
            assertTrue(debugAddresses.size <= CollectorHelperProtocol.MAX_BATCH_SIZE)
            assertNull(CollectorHelperDaemon.validateRows(debugAddresses, whitelist))
            assets.forEach { asset ->
                val shard = DirectDebugParameterAsset.parse(asset.readText(Charsets.UTF_8))
                    .map { address(it.tx, it.dev, it.fid) }
                assertTrue(shard.size <= CollectorHelperProtocol.MAX_BATCH_SIZE)
                assertNull(CollectorHelperDaemon.validateRows(shard, whitelist))
            }
            assertNull(CollectorHelperDaemon.validateRows(listOf(known), whitelist))
            assertContains(
                CollectorHelperDaemon.validateRows(listOf(address(8, 1014, 1145045040)), whitelist).orEmpty(),
                "unsupported read transaction"
            )
            assertContains(
                CollectorHelperDaemon.validateRows(listOf(address(5, 9999, 9999)), whitelist).orEmpty(),
                "not whitelisted"
            )
        } finally {
            apk.delete()
        }
    }

    @Test
    fun completeRoundRobinCatalogFitsOneRequestAndAboveMaxIsRejected() {
        val assets = DirectDebugParameterAsset.ASSET_NAMES.map(::assetFile)
        val client = sourceFile("com/bydcollector/collector/data/direct/DirectVehicleHelperClient.kt").readText()
        val daemon = sourceFile("com/bydcollector/collector/direct/CollectorHelperDaemon.java").readText()
        val shardSizes = assets.map { DirectDebugParameterAsset.parse(it.readText(Charsets.UTF_8)).size }

        assertEquals(listOf(7692, 7693, 7698), shardSizes)
        assertEquals(23083, shardSizes.sum())
        assertTrue(shardSizes.sum() <= CollectorHelperProtocol.MAX_BATCH_SIZE)
        assertEquals(23_096, CollectorHelperProtocol.MAX_BATCH_SIZE)
        assertTrue(client.contains("entries.isEmpty() || entries.size > CollectorHelperProtocol.MAX_BATCH_SIZE"))
        assertTrue(client.contains("return synchronized(lock)"))
        assertTrue(client.contains("data.writeInt(entries.size)"))
        assertTrue(client.contains("binder.transact(CollectorHelperProtocol.TX_READ_BATCH, data, reply, 0)"))
        val gateIndex = client.indexOf("entries.isEmpty() || entries.size > CollectorHelperProtocol.MAX_BATCH_SIZE")
        val writeCountIndex = client.indexOf("data.writeInt(entries.size)")
        val transactIndex = client.indexOf("binder.transact(CollectorHelperProtocol.TX_READ_BATCH, data, reply, 0)")
        assertTrue(
            gateIndex <
                client.indexOf("return synchronized(lock)")
        )
        assertTrue(gateIndex < writeCountIndex)
        assertTrue(writeCountIndex < transactIndex)
        assertTrue(23_097 > CollectorHelperProtocol.MAX_BATCH_SIZE)
        assertTrue(daemon.contains("if (count < 1 || count > CollectorHelperProtocol.MAX_BATCH_SIZE)"))
        assertTrue(daemon.contains("throw new IllegalArgumentException(\"invalid batch size: \" + count)"))
    }

    @Test
    fun consumerLeaseStartsActiveRestoresOnceAndEntersFallbackOnlyAfterExpiry() {
        val lease = CollectorHelperDaemon.ConsumerLease(1_000L, 2_000L)

        assertTrue(lease.isActive(1_000L))
        assertTrue(lease.isActive(2_999L))
        assertTrue(!lease.isActive(3_000L))
        assertTrue(lease.beginFallback(3_000L))
        assertTrue(!lease.beginFallback(3_001L))
        assertTrue(lease.renew(3_100L))
        assertTrue(lease.isActive(5_099L))
        assertTrue(!lease.renew(3_200L))
    }

    @Test
    fun protocolV8RejectsStaleOrWrongModeHelpersAndExposesReadOnlyEndpointsOnly() {
        val protocol = sourceFile("com/bydcollector/collector/direct/CollectorHelperProtocol.java").readText()
        val daemon = sourceFile("com/bydcollector/collector/direct/CollectorHelperDaemon.java").readText()
        val client = sourceFile("com/bydcollector/collector/data/direct/DirectVehicleHelperClient.kt").readText()

        assertEquals(8, CollectorHelperProtocol.PROTOCOL_VERSION)
        assertTrue(client.contains("protocolVersion != CollectorHelperProtocol.PROTOCOL_VERSION"))
        assertTrue(client.contains("DirectHelperOwnerMode.fromProtocolValue(ownerMode)"))
        assertTrue(client.contains("TX_PING"))
        assertTrue(client.contains("TX_READ"))
        assertTrue(client.contains("TX_READ_BATCH"))
        assertTrue(client.contains("TX_WORKER_PENDING"))
        assertTrue(client.contains("TX_WORKER_ACK"))
        assertTrue(client.contains("TX_STOP_OWNER"))
        assertTrue(!protocol.contains("HEARTBEAT", ignoreCase = true))
        assertTrue(!protocol.contains("DISARM", ignoreCase = true))
        assertTrue(!daemon.contains("offcar", ignoreCase = true))
        assertTrue(!client.contains("transactControl"))
        assertTrue(!protocol.contains("TX_WRITE"))
        assertTrue(!daemon.contains("sendCmd"))
        assertTrue(!daemon.contains("setXD"))
        assertTrue(!daemon.contains("setTrigger"))
        assertTrue(!daemon.contains("wakeUpMcu"))
        assertTrue(!daemon.contains("setAction"))
    }

    private fun address(tx: Int, dev: Int, fid: Int) = CollectorHelperDaemon.Address(tx, dev, fid)

    private fun assetFile(name: String): File = listOf(
        File("src/main/assets/$name"),
        File("app/src/main/assets/$name")
    ).firstOrNull { it.isFile } ?: error("Missing $name")

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/java/$path"),
            File("app/src/main/java/$path"),
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun unavailableNative(reason: String) = object : CollectorHelperDaemon.NativeReader {
        override fun isAvailable() = false
        override fun unavailableReason() = reason
        override fun readInts(dev: Int, fids: IntArray): IntArray = error("native read not expected")
        override fun readFloats(dev: Int, fids: IntArray): FloatArray = error("native read not expected")
    }
}
