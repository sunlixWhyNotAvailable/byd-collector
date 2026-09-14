package com.bydcollector.collector.data.energy

data class EnergyAnchor(
    val bootId: String,
    val elapsedMs: Long,
    val powerKw: Double
)

data class EnergyTotals(
    val dischargedKwh: Double = 0.0,
    val regeneratedKwh: Double = 0.0,
    val coveredMs: Long = 0L,
    val uncoveredMs: Long = 0L,
    val partial: Boolean = false
) {
    val netKwh: Double
        get() = dischargedKwh - regeneratedKwh

    init {
        require(dischargedKwh.isFinite() && dischargedKwh >= 0.0)
        require(regeneratedKwh.isFinite() && regeneratedKwh >= 0.0)
        require(coveredMs >= 0L)
        require(uncoveredMs >= 0L)
    }
}

data class EnergyInput(
    val bootId: String,
    val elapsedMs: Long,
    val voltage: Double?,
    val current: Double?,
    val powerOn: Boolean?,
    val gunDisconnected: Boolean?,
    val externalCharging: Boolean?
)

enum class EnergyIntegrationQuality {
    COVERED,
    ANCHOR_ONLY,
    EXCLUDED,
    PARTIAL
}

enum class EnergyIntegrationReason {
    INTEGRATED,
    INITIAL_ANCHOR,
    INVALID_SAMPLE,
    POWER_NOT_CONFIRMED,
    POWER_OFF,
    CHARGE_GUN_NOT_CONFIRMED,
    CHARGE_GUN_CONNECTED,
    EXTERNAL_CHARGING,
    BOOT_CHANGED,
    NON_MONOTONIC_TIME,
    GAP_EXCEEDED
}

data class EnergyIntegrationResult(
    val totals: EnergyTotals,
    val anchor: EnergyAnchor?,
    val quality: EnergyIntegrationQuality,
    val reason: EnergyIntegrationReason
)

object BatteryEnergyIntegrator {
    const val MAX_INTERVAL_MS = 2_000L
    private const val KILOWATT_MILLISECONDS_PER_KILOWATT_HOUR = 3_600_000.0

    fun integrate(
        previousAnchor: EnergyAnchor?,
        totals: EnergyTotals,
        input: EnergyInput
    ): EnergyIntegrationResult {
        if (input.bootId.isBlank() || input.elapsedMs < 0L) {
            return partial(previousAnchor, totals, input, EnergyIntegrationReason.INVALID_SAMPLE)
        }
        if (input.powerOn == false) {
            return excluded(totals, EnergyIntegrationReason.POWER_OFF)
        }
        if (input.externalCharging == true) {
            return excluded(totals, EnergyIntegrationReason.EXTERNAL_CHARGING)
        }
        if (input.gunDisconnected == false) {
            return excluded(totals, EnergyIntegrationReason.CHARGE_GUN_CONNECTED)
        }
        if (input.powerOn != true) {
            return partial(previousAnchor, totals, input, EnergyIntegrationReason.POWER_NOT_CONFIRMED)
        }
        if (input.gunDisconnected != true) {
            return partial(previousAnchor, totals, input, EnergyIntegrationReason.CHARGE_GUN_NOT_CONFIRMED)
        }

        val voltage = input.voltage
        val current = input.current
        if (voltage == null || current == null || !voltage.isFinite() || voltage <= 0.0 || !current.isFinite()) {
            return partial(previousAnchor, totals, input, EnergyIntegrationReason.INVALID_SAMPLE)
        }
        val powerKw = voltage * current / 1_000.0
        if (!powerKw.isFinite()) {
            return partial(previousAnchor, totals, input, EnergyIntegrationReason.INVALID_SAMPLE)
        }
        val anchor = EnergyAnchor(input.bootId, input.elapsedMs, powerKw)
        if (previousAnchor == null) {
            return EnergyIntegrationResult(
                totals = totals,
                anchor = anchor,
                quality = EnergyIntegrationQuality.ANCHOR_ONLY,
                reason = EnergyIntegrationReason.INITIAL_ANCHOR
            )
        }
        if (
            previousAnchor.bootId.isBlank() ||
            previousAnchor.elapsedMs < 0L ||
            !previousAnchor.powerKw.isFinite()
        ) {
            return EnergyIntegrationResult(
                totals = totals.copy(partial = true),
                anchor = anchor,
                quality = EnergyIntegrationQuality.PARTIAL,
                reason = EnergyIntegrationReason.INVALID_SAMPLE
            )
        }
        if (previousAnchor.bootId != input.bootId) {
            return EnergyIntegrationResult(
                totals = totals.copy(partial = true),
                anchor = anchor,
                quality = EnergyIntegrationQuality.PARTIAL,
                reason = EnergyIntegrationReason.BOOT_CHANGED
            )
        }

        val intervalMs = input.elapsedMs - previousAnchor.elapsedMs
        if (intervalMs <= 0L) {
            return EnergyIntegrationResult(
                totals = totals.copy(partial = true),
                anchor = anchor,
                quality = EnergyIntegrationQuality.PARTIAL,
                reason = EnergyIntegrationReason.NON_MONOTONIC_TIME
            )
        }
        if (intervalMs > MAX_INTERVAL_MS) {
            return EnergyIntegrationResult(
                totals = totals.copy(
                    uncoveredMs = addDuration(totals.uncoveredMs, intervalMs),
                    partial = true
                ),
                anchor = anchor,
                quality = EnergyIntegrationQuality.PARTIAL,
                reason = EnergyIntegrationReason.GAP_EXCEEDED
            )
        }

        val energy = splitTrapezoid(previousAnchor.powerKw, powerKw, intervalMs)
        return EnergyIntegrationResult(
            totals = totals.copy(
                dischargedKwh = totals.dischargedKwh + energy.dischargedKwh,
                regeneratedKwh = totals.regeneratedKwh + energy.regeneratedKwh,
                coveredMs = addDuration(totals.coveredMs, intervalMs)
            ),
            anchor = anchor,
            quality = EnergyIntegrationQuality.COVERED,
            reason = EnergyIntegrationReason.INTEGRATED
        )
    }

    private fun partial(
        previousAnchor: EnergyAnchor?,
        totals: EnergyTotals,
        input: EnergyInput,
        reason: EnergyIntegrationReason
    ): EnergyIntegrationResult {
        val knownUncoveredMs = previousAnchor
            ?.takeIf { it.bootId == input.bootId && input.elapsedMs > it.elapsedMs }
            ?.let { input.elapsedMs - it.elapsedMs }
            ?: 0L
        return EnergyIntegrationResult(
            totals = totals.copy(
                uncoveredMs = addDuration(totals.uncoveredMs, knownUncoveredMs),
                partial = true
            ),
            anchor = null,
            quality = EnergyIntegrationQuality.PARTIAL,
            reason = reason
        )
    }

    private fun excluded(
        totals: EnergyTotals,
        reason: EnergyIntegrationReason
    ) = EnergyIntegrationResult(
        totals = totals,
        anchor = null,
        quality = EnergyIntegrationQuality.EXCLUDED,
        reason = reason
    )

    private fun splitTrapezoid(startKw: Double, endKw: Double, intervalMs: Long): EnergySlice {
        val duration = intervalMs.toDouble()
        if (startKw >= 0.0 && endKw >= 0.0) {
            return EnergySlice((startKw + endKw) * duration / 2.0 / KILOWATT_MILLISECONDS_PER_KILOWATT_HOUR, 0.0)
        }
        if (startKw <= 0.0 && endKw <= 0.0) {
            return EnergySlice(0.0, -(startKw + endKw) * duration / 2.0 / KILOWATT_MILLISECONDS_PER_KILOWATT_HOUR)
        }

        val zeroFraction = startKw / (startKw - endKw)
        val beforeZeroMs = duration * zeroFraction
        val afterZeroMs = duration - beforeZeroMs
        return if (startKw > 0.0) {
            EnergySlice(
                dischargedKwh = startKw * beforeZeroMs / 2.0 / KILOWATT_MILLISECONDS_PER_KILOWATT_HOUR,
                regeneratedKwh = -endKw * afterZeroMs / 2.0 / KILOWATT_MILLISECONDS_PER_KILOWATT_HOUR
            )
        } else {
            EnergySlice(
                dischargedKwh = endKw * afterZeroMs / 2.0 / KILOWATT_MILLISECONDS_PER_KILOWATT_HOUR,
                regeneratedKwh = -startKw * beforeZeroMs / 2.0 / KILOWATT_MILLISECONDS_PER_KILOWATT_HOUR
            )
        }
    }

    private fun addDuration(total: Long, interval: Long): Long =
        if (Long.MAX_VALUE - total < interval) Long.MAX_VALUE else total + interval

    private data class EnergySlice(
        val dischargedKwh: Double,
        val regeneratedKwh: Double
    )
}
