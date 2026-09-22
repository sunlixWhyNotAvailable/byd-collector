package com.bydcollector.collector.data.normalized

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NormalizedStateStoreTest {
    @Test
    fun compactQualityCodesAreStableAndRoundTrip() {
        val expected = mapOf(
            NormalizedQuality.OK to 0,
            NormalizedQuality.STALE to 1,
            NormalizedQuality.MISSING to 2,
            NormalizedQuality.INVALID to 3,
            NormalizedQuality.UNSUPPORTED to 4
        )

        expected.forEach { (quality, code) ->
            assertEquals(code, quality.storageCode)
            assertEquals(quality, NormalizedQuality.fromStorageCode(code))
        }
        assertFailsWith<IllegalStateException> { NormalizedQuality.fromStorageCode(5) }
    }

    @Test
    fun compactHistoryTimeUsesEpochMillisecondsAndUtcIsoAtBoundary() {
        val epochMs = normalizedEpochMillis("2026-08-06T12:34:56.789+03:00")

        assertEquals(1_786_008_896_789L, epochMs)
        assertEquals("2026-08-06T09:34:56.789Z", normalizedIsoTime(epochMs))
    }

    @Test
    fun retiredCurrentKeyIsDeletedOnlyWhenNotActive() {
        assertEquals(
            setOf("hv_battery_current_a", "charging_state"),
            retiredNormalizedFieldKeysToDelete(activeFieldKeys = setOf("charge_current_a", "battery_power_kw"))
        )
        assertEquals(
            setOf("charging_state"),
            retiredNormalizedFieldKeysToDelete(activeFieldKeys = setOf("hv_battery_current_a"))
        )
    }

}
