package com.bydcollector.collector.data.normalized

import com.bydcollector.collector.data.local.PollReading
import org.junit.Test
import kotlin.test.assertEquals

class VehicleNumericPrecisionTest {
    @Test
    fun decimalPrecisionAndSignedZero() {
        val field = NormalizedFieldCatalog.cumulativeEnergy
        val normalizer = VehicleStateNormalizer(listOf(field))
        val cases = listOf(
            "12.3456784" to 12.345678,
            "12.3456785" to 12.345679,
            "-12.3456785" to -12.345679,
            "0.0000005" to 0.000001,
            "-0.0000004" to -0.0,
            // The existing scale + offset step converts an exact negative zero to positive zero.
            "-0.0" to 0.0,
            "0.30000000000000004" to 0.3,
            "999999.1234567" to 999999.123457
        )
        cases.forEach { (input, expected) ->
            val result = normalizer.normalize(
                1L, "2026-10-26T00:00:00Z",
                listOf(PollReading(field.sourceKeys.single(), input, input))
            ).single()
            assertEquals(NormalizedQuality.OK, result.quality, input)
            assertEquals(expected.toRawBits(), result.value.number?.toRawBits(), input)
        }
    }
}
