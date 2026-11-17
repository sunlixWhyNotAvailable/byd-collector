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

/** Null proof means an observed callback interval whose continuity is not established. */
data class EnergyIntervalContinuity(val previousValidSinceMs: Long?)

data class EnergyInput(
    val bootId: String,
    val elapsedMs: Long,
    val voltage: Double?,
    val current: Double?,
    val powerOn: Boolean?,
    val gunDisconnected: Boolean?,
    val externalCharging: Boolean?,
    val continuity: EnergyIntervalContinuity? = null
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
    GAP_EXCEEDED,
    ZERO_INTERVAL,
    LATE_SOURCE_REANCHORED,
    SOURCE_CONTINUITY_UNKNOWN
}

data class EnergyIntegrationResult(
    val totals: EnergyTotals,
    val anchor: EnergyAnchor?,
    val quality: EnergyIntegrationQuality,
    val reason: EnergyIntegrationReason,
    val heldInterval: Boolean = false
)

object BatteryEnergyIntegrator {
    const val MAX_INTERVAL_MS = 2_000L
    private const val KILOWATT_MILLISECONDS_PER_KILOWATT_HOUR = 3_600_000.0

    fun integrate(
        previousAnchor: EnergyAnchor?,
        totals: EnergyTotals,
        input: EnergyInput,
        previousEvaluationElapsedMs: Long? = null
    ): EnergyIntegrationResult {
        if (input.bootId.isBlank() || input.elapsedMs < 0L) {
            return partial(previousAnchor, totals, input, EnergyIntegrationReason.INVALID_SAMPLE)
        }
        if (input.continuity != null) {
            return integrateCallback(previousAnchor, totals, input, previousEvaluationElapsedMs)
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
        if (intervalMs < 0L) {
            return EnergyIntegrationResult(
                totals = totals.copy(partial = true),
                anchor = anchor,
                quality = EnergyIntegrationQuality.PARTIAL,
                reason = EnergyIntegrationReason.NON_MONOTONIC_TIME
            )
        }
        if (intervalMs == 0L) {
            return EnergyIntegrationResult(
                totals = totals,
                anchor = anchor,
                quality = EnergyIntegrationQuality.ANCHOR_ONLY,
                reason = EnergyIntegrationReason.ZERO_INTERVAL
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

    private fun integrateCallback(previous: EnergyAnchor?, totals: EnergyTotals, input: EnergyInput,
        previousEvaluationElapsedMs: Long?): EnergyIntegrationResult {
        val since = input.continuity?.previousValidSinceMs
        val duration = previous?.takeIf { it.bootId == input.bootId }?.let { input.elapsedMs - it.elapsedMs }
        val covered = previous != null && duration != null && duration > 0L &&
            previous.elapsedMs >= 0L && previous.powerKw.isFinite() && since != null &&
            since >= 0L && since <= previous.elapsedMs
        val missing = if (duration != null && duration > 0L && !covered) duration else
            if (previous == null && totals.partial && previousEvaluationElapsedMs != null)
                (input.elapsedMs - previousEvaluationElapsedMs).coerceAtLeast(0L) else 0L
        val boundaryInvalid = previous != null && (previous.bootId != input.bootId || duration == null || duration < 0L)
        val advanced = if (covered) integrateCoveredInterval(totals, previous!!.powerKw, previous.powerKw,
            requireNotNull(duration), held = true) else totals.copy(
            uncoveredMs = addDuration(totals.uncoveredMs, missing),
            partial = totals.partial || missing > 0L || boundaryInvalid)
        // The new value governs only the next interval, even when this event is a loss or OFF.
        val next = integrate(null, advanced, input.copy(continuity = null))
        return when {
            covered -> next.copy(quality = if (next.quality == EnergyIntegrationQuality.PARTIAL) next.quality
                else EnergyIntegrationQuality.COVERED,
                reason = if (next.quality == EnergyIntegrationQuality.ANCHOR_ONLY) EnergyIntegrationReason.INTEGRATED else next.reason,
                heldInterval = true)
            missing > 0L || boundaryInvalid -> next.copy(quality = EnergyIntegrationQuality.PARTIAL,
                reason = if (next.quality == EnergyIntegrationQuality.ANCHOR_ONLY) EnergyIntegrationReason.SOURCE_CONTINUITY_UNKNOWN else next.reason)
            else -> next
        }
    }

    fun reanchorAfterLateSource(
        previousAnchor: EnergyAnchor?,
        totals: EnergyTotals,
        input: EnergyInput
    ): EnergyIntegrationResult {
        val elapsedMs = previousAnchor
            ?.takeIf { it.bootId == input.bootId && input.elapsedMs > it.elapsedMs }
            ?.let { input.elapsedMs - it.elapsedMs }
            ?: 0L
        val anchor = if (
            input.powerOn == true && input.externalCharging != true &&
            input.gunDisconnected == true && validPower(input)
        ) {
            val powerKw = requireNotNull(input.voltage) * requireNotNull(input.current) / 1_000.0
            EnergyAnchor(input.bootId, input.elapsedMs, powerKw)
        } else {
            null
        }
        return EnergyIntegrationResult(
            totals = totals.copy(
                uncoveredMs = addDuration(totals.uncoveredMs, elapsedMs),
                partial = true
            ),
            anchor = anchor,
            quality = EnergyIntegrationQuality.PARTIAL,
            reason = EnergyIntegrationReason.LATE_SOURCE_REANCHORED
        )
    }

    fun finishSession(
        previousAnchor: EnergyAnchor?,
        totals: EnergyTotals,
        input: EnergyInput,
        previousEvaluationElapsedMs: Long? = null
    ): EnergyIntegrationResult {
        if (input.continuity != null && input.powerOn == false) {
            return integrate(previousAnchor, totals, input, previousEvaluationElapsedMs)
        }
        require(input.powerOn == false) { "Energy session can finish only on confirmed power off" }
        val intervalMs = previousAnchor
            ?.takeIf { it.bootId == input.bootId }
            ?.let { input.elapsedMs - it.elapsedMs }
        val invalidReason = when {
            previousAnchor == null -> EnergyIntegrationReason.INVALID_SAMPLE
            input.externalCharging == true -> EnergyIntegrationReason.EXTERNAL_CHARGING
            input.gunDisconnected == false -> EnergyIntegrationReason.CHARGE_GUN_CONNECTED
            input.gunDisconnected != true -> EnergyIntegrationReason.CHARGE_GUN_NOT_CONFIRMED
            input.bootId.isBlank() || input.elapsedMs < 0L -> EnergyIntegrationReason.INVALID_SAMPLE
            previousAnchor.bootId.isBlank() || previousAnchor.elapsedMs < 0L || !previousAnchor.powerKw.isFinite() ->
                EnergyIntegrationReason.INVALID_SAMPLE
            previousAnchor.bootId != input.bootId -> EnergyIntegrationReason.BOOT_CHANGED
            intervalMs == null || intervalMs < 0L -> EnergyIntegrationReason.NON_MONOTONIC_TIME
            intervalMs > MAX_INTERVAL_MS -> EnergyIntegrationReason.GAP_EXCEEDED
            !validPower(input) -> EnergyIntegrationReason.INVALID_SAMPLE
            else -> null
        }
        if (invalidReason != null) {
            val uncoveredMs = intervalMs?.takeIf { it > 0L } ?: 0L
            return EnergyIntegrationResult(
                totals = totals.copy(
                    uncoveredMs = addDuration(totals.uncoveredMs, uncoveredMs),
                    partial = true
                ),
                anchor = null,
                quality = EnergyIntegrationQuality.PARTIAL,
                reason = invalidReason
            )
        }

        val closingAnchor = requireNotNull(previousAnchor)
        val durationMs = requireNotNull(intervalMs)
        if (durationMs == 0L) {
            return EnergyIntegrationResult(
                totals = totals,
                anchor = null,
                quality = EnergyIntegrationQuality.ANCHOR_ONLY,
                reason = EnergyIntegrationReason.ZERO_INTERVAL
            )
        }
        val powerKw = requireNotNull(input.voltage) * requireNotNull(input.current) / 1_000.0
        val energy = splitTrapezoid(closingAnchor.powerKw, powerKw, durationMs)
        return EnergyIntegrationResult(
            totals = totals.copy(
                dischargedKwh = totals.dischargedKwh + energy.dischargedKwh,
                regeneratedKwh = totals.regeneratedKwh + energy.regeneratedKwh,
                coveredMs = addDuration(totals.coveredMs, durationMs)
            ),
            anchor = null,
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

    private fun validPower(input: EnergyInput): Boolean {
        val voltage = input.voltage
        val current = input.current
        return voltage != null && voltage.isFinite() && voltage > 0.0 &&
            current != null && current.isFinite() && (voltage * current / 1_000.0).isFinite()
    }

    fun integrateCoveredInterval(totals: EnergyTotals, startKw: Double, endKw: Double, intervalMs: Long,
        held: Boolean = false): EnergyTotals {
        require(startKw.isFinite() && endKw.isFinite() && intervalMs >= 0L &&
            (if (held) startKw == endKw else intervalMs <= MAX_INTERVAL_MS))
        val energy = splitTrapezoid(startKw, endKw, intervalMs)
        return totals.copy(dischargedKwh = totals.dischargedKwh + energy.dischargedKwh,
            regeneratedKwh = totals.regeneratedKwh + energy.regeneratedKwh,
            coveredMs = addDuration(totals.coveredMs, intervalMs))
    }

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
