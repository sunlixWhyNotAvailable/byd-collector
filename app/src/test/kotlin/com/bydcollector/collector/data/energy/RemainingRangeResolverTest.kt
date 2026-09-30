package com.bydcollector.collector.data.energy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemainingRangeResolverTest {
    @Test
    fun ecAverageWinsAndZeroRemainingEnergyProducesZeroRange() {
        val estimate = RemainingRangeResolver.resolve(23.85934, 500.0, 2_000.0, 0.0, 100.0)

        assertEquals(RangeSource.EC, estimate.source)
        assertEquals(0.0, estimate.rangeKm)
        assertEquals(23.85934, estimate.averageKwhPer100Km)
        assertNull(estimate.fallbackReason)
    }

    @Test
    fun lifetimeRatioIsUsedWhenEcAverageIsUnavailableOrInvalid() {
        val unavailable = RemainingRangeResolver.resolve(null, 1_000.0, 4_000.0, 10.0, 50.0)
        val invalid = RemainingRangeResolver.resolve(Double.NaN, 1_000.0, 4_000.0, 10.0, 50.0)

        assertEquals(RangeSource.LIFETIME_COUNTERS, unavailable.source)
        assertEquals(25.0, unavailable.averageKwhPer100Km)
        assertEquals(40.0, unavailable.rangeKm)
        assertEquals(RangeFallbackReason.EC_UNAVAILABLE, unavailable.fallbackReason)
        assertEquals(RangeSource.LIFETIME_COUNTERS, invalid.source)
        assertEquals(RangeFallbackReason.EC_INVALID, invalid.fallbackReason)
    }

    @Test
    fun oemRangeIsUsedWhenCalculatedTiersCannotProduceAValue() {
        val noEnergy = RemainingRangeResolver.resolve(25.0, 1_000.0, 4_000.0, Double.NaN, 38.0)
        val invalidCounters = RemainingRangeResolver.resolve(null, 0.0, 4_000.0, 10.0, 38.0)

        assertEquals(RangeSource.OEM, noEnergy.source)
        assertEquals(38.0, noEnergy.rangeKm)
        assertEquals(RangeFallbackReason.REMAINING_ENERGY_INVALID, noEnergy.fallbackReason)
        assertEquals(RangeSource.OEM, invalidCounters.source)
        assertEquals(RangeFallbackReason.LIFETIME_COUNTERS_INVALID, invalidCounters.fallbackReason)
    }

    @Test
    fun invalidOemAndCalculatedInputsStayUnavailable() {
        val estimate = RemainingRangeResolver.resolve(
            ecMeanKwhPer100Km = 0.0,
            cumulativeEnergyKwh = Double.POSITIVE_INFINITY,
            odometerKm = 0.0,
            remainingEnergyKwh = -1.0,
            oemRangeKm = Double.NaN
        )

        assertEquals(RangeSource.UNAVAILABLE, estimate.source)
        assertNull(estimate.rangeKm)
        assertNull(estimate.averageKwhPer100Km)
        assertEquals(RangeFallbackReason.REMAINING_ENERGY_INVALID, estimate.fallbackReason)
    }

    @Test
    fun nonfiniteCalculatedRangeFallsThroughToOemWithoutClamping() {
        val estimate = RemainingRangeResolver.resolve(1e-308, 1.0, 100.0, Double.MAX_VALUE, 12.0)

        assertEquals(RangeSource.OEM, estimate.source)
        assertEquals(12.0, estimate.rangeKm)
        assertEquals(RangeFallbackReason.CALCULATED_RANGE_INVALID, estimate.fallbackReason)
    }
}
