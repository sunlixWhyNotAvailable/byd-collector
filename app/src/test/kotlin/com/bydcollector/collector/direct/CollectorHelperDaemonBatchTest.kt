package com.bydcollector.collector.direct

import com.bydcollector.collector.data.debug.DirectDebugParameterAsset
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
    fun apkWhitelistLoadsBothCatalogsAndRejectsUnknownReads() {
        val asset = listOf(
            File("src/main/assets/${DirectDebugParameterAsset.ASSET_NAME}"),
            File("app/src/main/assets/${DirectDebugParameterAsset.ASSET_NAME}")
        ).firstOrNull { it.isFile } ?: error("Missing ${DirectDebugParameterAsset.ASSET_NAME}")
        val apk = Files.createTempFile("bydcollector-whitelist", ".apk").toFile()
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/${DirectDebugParameterAsset.ASSET_NAME}"))
                asset.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            val whitelist = CollectorHelperDaemon.loadWhitelist(apk.absolutePath)
            val known = address(5, 1014, 1145045040)

            assertEquals(6513, whitelist.size)
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

    private fun address(tx: Int, dev: Int, fid: Int) = CollectorHelperDaemon.Address(tx, dev, fid)

    private fun unavailableNative(reason: String) = object : CollectorHelperDaemon.NativeReader {
        override fun isAvailable() = false
        override fun unavailableReason() = reason
        override fun readInts(dev: Int, fids: IntArray): IntArray = error("native read not expected")
        override fun readFloats(dev: Int, fids: IntArray): FloatArray = error("native read not expected")
    }
}
