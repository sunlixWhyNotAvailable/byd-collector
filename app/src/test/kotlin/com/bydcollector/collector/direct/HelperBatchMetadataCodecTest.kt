package com.bydcollector.collector.direct

import android.app.Application
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class HelperBatchMetadataCodecTest {
    @Test
    fun sparseAndFullCatalogIndicesRoundTripAndMalformedInputFails() {
        val sparse = intArrayOf(0, 127, 128, 16_383, 16_384, 23_068)
        assertContentEquals(sparse, HelperBatchMetadataCodec.decodeIndices(
            HelperBatchMetadataCodec.encodeIndices(sparse), sparse.size, 23_069
        ))

        val dense = IntArray(23_069) { it }
        val packed = HelperBatchMetadataCodec.encodeIndices(dense)
        assertTrue(packed.size < dense.size * 2)
        assertContentEquals(dense, HelperBatchMetadataCodec.decodeIndices(packed, dense.size, dense.size))

        assertFailsWith<IllegalArgumentException> { HelperBatchMetadataCodec.encodeIndices(intArrayOf(2, 2)) }
        assertFailsWith<IllegalArgumentException> { HelperBatchMetadataCodec.decodeIndices(byteArrayOf(0), 1, 10) }
        assertFailsWith<IllegalArgumentException> { HelperBatchMetadataCodec.decodeIndices(byteArrayOf(2), 1, 1) }
        assertFailsWith<IllegalArgumentException> { HelperBatchMetadataCodec.decodeIndices(byteArrayOf(0x80.toByte()), 1, 10) }
        assertFailsWith<IllegalArgumentException> { HelperBatchMetadataCodec.decodeIndices(byteArrayOf(1, 1), 1, 10) }
    }

    @Test
    fun boundedErrorMetadataRoundTripsAndMarksTruncation() {
        val exact = arrayOf(null, "GET failed: binder unavailable", "中文字段读取失败")
        val encoded = HelperBatchMetadataCodec.encodeErrors(exact)
        assertEquals(false, encoded.truncated)
        assertEquals(mapOf(1 to exact[1], 2 to exact[2]),
            HelperBatchMetadataCodec.decodeErrors(encoded.bytes, exact.size))

        val longSingle = HelperBatchMetadataCodec.encodeErrors(arrayOf("GET failed: " + "x".repeat(1000)))
        assertTrue(longSingle.truncated)
        val retained = HelperBatchMetadataCodec.decodeErrors(longSingle.bytes, 1).getValue(0)
        assertTrue(retained.startsWith("GET failed: ") && retained.endsWith("...[truncated]"))

        val oversized = Array(100) { "x".repeat(512) }
        val capped = HelperBatchMetadataCodec.encodeErrors(oversized)
        assertTrue(capped.truncated)
        assertTrue(capped.bytes.size <= HelperBatchMetadataCodec.MAX_ERROR_DETAIL_BYTES)
        assertTrue(HelperBatchMetadataCodec.decodeErrors(capped.bytes, oversized.size).isNotEmpty())
    }

}
