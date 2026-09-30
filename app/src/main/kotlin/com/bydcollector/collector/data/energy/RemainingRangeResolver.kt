package com.bydcollector.collector.data.energy

internal enum class RangeSource { EC, LIFETIME_COUNTERS, OEM, UNAVAILABLE }

internal enum class RangeFallbackReason {
    EC_UNAVAILABLE,
    EC_INVALID,
    REMAINING_ENERGY_INVALID,
    LIFETIME_COUNTERS_INVALID,
    CALCULATED_RANGE_INVALID,
    OEM_RANGE_INVALID,
    NO_USABLE_RANGE
}

internal data class RemainingRangeEstimate(
    val rangeKm: Double?,
    val averageKwhPer100Km: Double?,
    val source: RangeSource,
    val fallbackReason: RangeFallbackReason?
)

internal object RemainingRangeResolver {
    fun resolve(
        ecMeanKwhPer100Km: Double?,
        cumulativeEnergyKwh: Double?,
        odometerKm: Double?,
        remainingEnergyKwh: Double?,
        oemRangeKm: Double?
    ): RemainingRangeEstimate {
        val remainingEnergyValid = remainingEnergyKwh.isValidRemainingEnergy()
        val ecMeanValid = ecMeanKwhPer100Km.isValidMean()
        var ecFailure = when {
            !remainingEnergyValid -> RangeFallbackReason.REMAINING_ENERGY_INVALID
            ecMeanKwhPer100Km == null -> RangeFallbackReason.EC_UNAVAILABLE
            !ecMeanValid -> RangeFallbackReason.EC_INVALID
            else -> null
        }

        if (ecMeanValid && remainingEnergyValid) {
            calculatedRange(remainingEnergyKwh!!, ecMeanKwhPer100Km!!)?.let { range ->
                return RemainingRangeEstimate(range, ecMeanKwhPer100Km, RangeSource.EC, null)
            }
            ecFailure = RangeFallbackReason.CALCULATED_RANGE_INVALID
        }

        val lifetimeMean = lifetimeMean(cumulativeEnergyKwh, odometerKm)
        if (lifetimeMean != null && remainingEnergyValid) {
            calculatedRange(remainingEnergyKwh!!, lifetimeMean)?.let { range ->
                return RemainingRangeEstimate(
                    range,
                    lifetimeMean,
                    RangeSource.LIFETIME_COUNTERS,
                    ecFailure ?: RangeFallbackReason.EC_UNAVAILABLE
                )
            }
        }

        if (oemRangeKm.isValidRemainingEnergy()) {
            return RemainingRangeEstimate(
                rangeKm = oemRangeKm,
                averageKwhPer100Km = null,
                source = RangeSource.OEM,
                fallbackReason = when {
                    !remainingEnergyValid -> RangeFallbackReason.REMAINING_ENERGY_INVALID
                    lifetimeMean == null -> RangeFallbackReason.LIFETIME_COUNTERS_INVALID
                    else -> RangeFallbackReason.CALCULATED_RANGE_INVALID
                }
            )
        }

        return RemainingRangeEstimate(
            rangeKm = null,
            averageKwhPer100Km = null,
            source = RangeSource.UNAVAILABLE,
            fallbackReason = when {
                !remainingEnergyValid -> RangeFallbackReason.REMAINING_ENERGY_INVALID
                lifetimeMean == null && !ecMeanValid -> RangeFallbackReason.NO_USABLE_RANGE
                lifetimeMean == null -> RangeFallbackReason.LIFETIME_COUNTERS_INVALID
                else -> RangeFallbackReason.OEM_RANGE_INVALID
            }
        )
    }

    private fun lifetimeMean(cumulativeEnergyKwh: Double?, odometerKm: Double?): Double? {
        if (cumulativeEnergyKwh == null || !cumulativeEnergyKwh.isFinite() ||
            odometerKm == null || !odometerKm.isFinite() || odometerKm <= 0.0
        ) return null

        return (cumulativeEnergyKwh / odometerKm * 100.0).takeIf { it.isFinite() && it > 0.0 }
    }

    private fun calculatedRange(remainingEnergyKwh: Double, meanKwhPer100Km: Double): Double? =
        (remainingEnergyKwh / meanKwhPer100Km * 100.0).takeIf { it.isFinite() && it >= 0.0 }

    private fun Double?.isValidMean(): Boolean = this != null && isFinite() && this > 0.0

    private fun Double?.isValidRemainingEnergy(): Boolean = this != null && isFinite() && this >= 0.0
}
