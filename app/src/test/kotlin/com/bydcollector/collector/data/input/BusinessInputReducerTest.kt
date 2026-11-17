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
    @Test fun globalFailureInvalidatesObservedOptionalSourcesWithoutInventingAbsentOnes() {
        val optional = "charging_1009_89128973_5"
        val all = mapOf("charging_charge_battery_volt" to "400", "charging_charge_current" to "50",
            "bodywork_power_level" to "2", "charging_1009_876609586_5" to "1", optional to "0")
        fun value(key: String, raw: String) = input("boot", 20, 2).let {
            it.copy(reading = PollReading(key, raw, raw), stamp = it.stamp.copy(sourceEpoch = 1, elapsedMs = 20_000))
        }
        val required = (all - optional).mapValues { (key, raw) -> value(key, raw) }
        fun available(input: NormalizedSourceInput) = BusinessInputContinuity(BusinessContinuityState.AVAILABLE,
            input.stamp, "callback", input.stamp.identity, input.stamp.sequence, true, "callback_value")
        val states = required.mapValues { available(it.value) }.toMutableMap()
        val ref = BusinessInputRef(1, BusinessInputKind.GET, null, emptyList(), "failed", "boot", "generation",
            10, null, 1_790_000_000_000, 15_000, null, null, BusinessInputOrigin.REPLAY, false, "failed", "io", "failed GET")
        val failure = BusinessInputDelta(ref)
        assertTrue(BusinessInputProjection.incoming(failure).isEmpty())
        states[optional] = available(value(optional, "0")).copy(state = BusinessContinuityState.LOST,
            stamp = input("boot", 10, 2).stamp.copy(elapsedMs = 15_000), seedValid = false, reason = "helper_lost")
        val timer = BusinessInputDelta(ref.copy(kind = BusinessInputKind.TIMER, id = 30, ok = true,
            acquiredElapsedMs = 60_000))
        fun proof(inputs: Map<String, NormalizedSourceInput>) =
            com.bydcollector.collector.data.energy.EnergyTelemetryProjection.intervalContinuity(inputs, states, timer)
        assertEquals(20_000L, proof(required)?.previousValidSinceMs)

        val knownOptional = value(optional, "0").let { it.copy(stamp = it.stamp.copy(elapsedMs = 10_000, sequence = 1)) }
        val failed = BusinessInputReducer.merge(mapOf(optional to knownOptional),
            BusinessInputProjection.incoming(failure, mapOf(optional to knownOptional)), "boot").inputs
        assertNull(failed.getValue(optional).reading.rawValue)
        assertEquals(20_000L, proof(required + failed)?.previousValidSinceMs)
        val recovered = value(optional, "0")
        states[optional] = available(recovered)
        assertEquals(20_000L, proof(required + (optional to recovered))?.previousValidSinceMs)
        assertEquals(20_000L, proof(required + (optional to recovered.copy(reading = PollReading(optional, "-1", "-1"))))?.previousValidSinceMs)
        val voltage = "charging_charge_battery_volt"
        assertNull(proof(required + (voltage to required.getValue(voltage).copy(reading = PollReading(voltage, null, null))))?.previousValidSinceMs)
    }

    @Test fun qualifiedContinuityCarriesItsActualBoundaryAndRejectsLossFutureAndOtherOwner() {
        val acquired = input("boot", 1, 2).let { it.copy(stamp = it.stamp.copy(sourceEpoch = 1L)) }
        val available = BusinessInputContinuity(BusinessContinuityState.AVAILABLE,
            acquired.stamp.copy(identity = "available", sequence = 2L, elapsedMs = 10_001L),
            "callback", acquired.stamp.identity, 1L, true, "registered")
        val inputs = mapOf(KEY to acquired)
        val states = mapOf(KEY to available)
        assertEquals(mapOf(KEY to 10_001L), BusinessInputProjection.continuousSources(inputs, states, "boot", 600_000L))
        assertEquals(acquired, BusinessInputProjection.freshInputs(inputs, "boot", 600_000L, states)[KEY])
        assertTrue(BusinessInputProjection.continuousSources(inputs, states, "boot", 10_000L).isEmpty())
        val lost = available.copy(state = BusinessContinuityState.LOST, seedValid = false)
        assertNull(BusinessInputProjection.freshInputs(inputs, "boot", 600_000L, mapOf(KEY to lost))[KEY]?.reading?.rawValue)
        val otherOwner = available.copy(stamp = available.stamp.copy(generatorId = "other"))
        assertTrue(BusinessInputProjection.continuousSources(inputs, mapOf(KEY to otherOwner), "boot", 600_000L).isEmpty())
    }

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
