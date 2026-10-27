package com.bydcollector.collector.data.input

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull

class BusinessInputReducerTest {
    @Test fun sameMillisecondTransitionsAndEqualValuesKeepTheirSourceOrder() {
        val on = input("boot", 10, 2)
        val off = input("boot", 11, 0)
        val onAgain = input("boot", 12, 2)
        var retained = emptyMap<String, NormalizedSourceInput>()
        listOf(on, off, onAgain).forEach { next ->
            val merged = BusinessInputReducer.merge(retained, mapOf(KEY to next), "boot")
            assertEquals(next, merged.inputs[KEY])
            assertEquals(setOf(KEY), merged.updates.keys)
            retained = merged.inputs
        }
        val freshEqual = input("boot", 13, 2)
        val refreshed = BusinessInputReducer.merge(retained, mapOf(KEY to freshEqual), "boot")
        assertEquals(13L, refreshed.updates.getValue(KEY).stamp.sequence)
        assertTrue(BusinessInputReducer.merge(refreshed.inputs, mapOf(KEY to freshEqual), "boot").updates.isEmpty())
        assertEquals(1, BusinessInputReducer.merge(refreshed.inputs, mapOf(KEY to off), "boot").olderCount)
    }

    @Test fun conflictingIdentityFailsAndAnOlderBootCannotReplaceCurrentState() {
        val on = input("current", 1, 2)
        val retained = mapOf(KEY to on)
        assertFailsWith<IllegalStateException> {
            BusinessInputReducer.merge(retained, mapOf(KEY to on.copy(reading = PollReading(KEY, "0", "0"))), "current")
        }
        val prior = input("prior", 99, 0)
        val result = BusinessInputReducer.merge(retained, mapOf(KEY to prior), "current")
        assertEquals(on, result.inputs[KEY])
        assertEquals(1, result.olderCount)
    }

    @Test fun evaluationKeepsAcquisitionClocksAndExpiresOnlyAtTheSourceLifetime() {
        val acquired = input("boot", 1, 2)
        val retained = mapOf(KEY to acquired)
        assertEquals(acquired, BusinessInputProjection.freshInputs(retained, "boot", 15_000L)[KEY])
        val expired = BusinessInputProjection.freshInputs(retained, "boot", 40_001L).getValue(KEY)
        assertNull(expired.reading.rawValue)
        assertEquals(acquired.stamp, expired.stamp)
        assertEquals(acquired, retained[KEY])
        assertNull(BusinessInputProjection.freshInputs(retained, "other_boot", 10_000L).getValue(KEY).reading.rawValue)
        assertNull(BusinessInputProjection.freshInputs(retained, "boot", 9_999L).getValue(KEY).reading.rawValue)
    }

    private fun input(boot: String, sequence: Long, value: Int) = NormalizedSourceInput(
        PollReading(KEY, value.toString(), value.toString()),
        NormalizedSourceStamp(NormalizedSourceKind.CALLBACK, "$boot:$sequence", boot,
            "generation", sequence, 1_790_000_000_000L, 10_000L), null
    )

    companion object { private const val KEY = "bodywork_power_level" }
}
