package com.bydcollector.collector.telegram

import kotlin.math.ceil

/** A boot/session boundary for every source-time history owned by the calculator. */
data class ChargingEtaSessionId(val bootId: String, val sessionId: String) {
    init {
        require(bootId.isNotBlank())
        require(sessionId.isNotBlank())
    }
}

enum class ChargingType { AC, DC, UNKNOWN, CONFLICT }
enum class ChargingEtaSelection { CALCULATED, OEM_ONLY }
enum class ChargingEtaSource { CALCULATED_AC, CALCULATED_DC, OEM, NONE }

enum class ChargingEtaUnavailableReason {
    NO_SESSION,
    SESSION_MISMATCH,
    OUT_OF_ORDER_EVALUATION,
    CHARGING_INACTIVE,
    CHARGING_PAUSED,
    UNKNOWN_CHARGE_TYPE,
    INVALID_SOC,
    INVALID_REMAINING_ENERGY,
    INSUFFICIENT_POWER_WINDOW,
    INVALID_POWER,
    DC_PROFILE_GAP,
    OEM_NOT_STABLE,
    INVALID_MEASUREMENT_TIME
}

data class ChargingEtaInput(
    val session: ChargingEtaSessionId,
    /** Current monotonic elapsed-realtime, in the same boot as the session. */
    val nowElapsedMs: Long,
    /** Monotonic source time of the triggering SOC/event observation. */
    val measuredAtElapsedMs: Long,
    /** Wall-clock timestamp of this source event; finish time is anchored here. */
    val measuredAtWallMs: Long,
    val socPct: Double?,
    val remainingEnergyKwh: Double?,
    /** Conservative source time for AC energy; ignored by DC and OEM-only paths. */
    val remainingEnergySourceElapsedMs: Long? = measuredAtElapsedMs,
    val chargeType: ChargingType,
    val selection: ChargingEtaSelection,
    val chargingActive: Boolean,
    val paused: Boolean
)

data class ChargingEtaResult(
    val source: ChargingEtaSource,
    val seconds: Double?,
    val roundedUpMinutes: Long?,
    val finishAtWallMs: Long?,
    val measuredAtWallMs: Long,
    /** Original source wall time; this is not refreshed when a cached OEM value is held. */
    val sourceObservedAtWallMs: Long?,
    val profileVersion: String?,
    val fallbackReason: ChargingEtaUnavailableReason?,
    val unavailableReason: ChargingEtaUnavailableReason?,
    val calculationPowerKw: Double? = null
) {
    val available: Boolean get() = source != ChargingEtaSource.NONE && seconds != null
}

/**
 * Pure AC/DC estimator and causal OEM fallback stabilizer. The owner must call
 * [addPowerSample] only for a genuinely fresh normalized power source stamp and
 * may call [observeOem] on each poll while retaining original source stamps.
 */
class ChargingEtaCalculator internal constructor(
    private val dcProfile: DcChargingProfile = DcChargingProfile.SL07_FROZEN
) {
    private var currentSession: ChargingEtaSessionId? = null
    private var lastEstimateSourceElapsedMs: Long? = null
    private val powerWindow = ChargingPowerWindow()
    private val oemStabilizer = OemEtaStabilizer()

    /** Starts a new identity boundary and discards all in-memory histories. */
    fun beginSession(session: ChargingEtaSessionId) {
        if (currentSession == session) return
        currentSession = session
        lastEstimateSourceElapsedMs = null
        powerWindow.reset(session)
        oemStabilizer.reset(session)
    }

    /** Add only new/fresh power observations; cached callback values are not new samples. */
    fun addPowerSample(
        session: ChargingEtaSessionId,
        sourceElapsedMs: Long,
        powerKw: Double?
    ): Boolean {
        if (currentSession != session) return false
        return powerWindow.add(session, sourceElapsedMs, powerKw)
    }

    /**
     * Record the normalized OEM hours/minutes at the current evaluation time.
     * Pass the minute FID's original source time and the current poll/cycle
     * source time separately. A valid cached hour may remain unchanged for the
     * session; it is not incorrectly expired by the composite's oldest stamp.
     * Re-observing a cached value is allowed without refreshing minute time.
     */
    fun observeOem(
        session: ChargingEtaSessionId,
        evaluatedElapsedMs: Long,
        minuteSourceElapsedMs: Long,
        cycleSourceElapsedMs: Long,
        sourceWallMs: Long,
        hours: Int,
        minutes: Int
    ): Boolean {
        if (currentSession != session) return false
        return oemStabilizer.observe(
            session = session,
            evaluatedElapsedMs = evaluatedElapsedMs,
            minuteSourceElapsedMs = minuteSourceElapsedMs,
            cycleSourceElapsedMs = cycleSourceElapsedMs,
            sourceWallMs = sourceWallMs,
            hours = hours,
            minutes = minutes
        )
    }

    fun estimate(input: ChargingEtaInput): ChargingEtaResult {
        if (currentSession == null) return unavailable(input.measuredAtWallMs, ChargingEtaUnavailableReason.NO_SESSION)
        if (currentSession != input.session) return unavailable(input.measuredAtWallMs, ChargingEtaUnavailableReason.SESSION_MISMATCH)
        if (input.measuredAtWallMs <= 0L || input.nowElapsedMs < 0L || input.measuredAtElapsedMs < 0L) {
            return unavailable(input.measuredAtWallMs, ChargingEtaUnavailableReason.INVALID_MEASUREMENT_TIME)
        }
        if (input.measuredAtElapsedMs > input.nowElapsedMs ||
            input.nowElapsedMs - input.measuredAtElapsedMs > MAX_STATE_AGE_MS
        ) {
            return unavailable(input.measuredAtWallMs, ChargingEtaUnavailableReason.INVALID_MEASUREMENT_TIME)
        }
        val priorEvaluation = lastEstimateSourceElapsedMs
        if (priorEvaluation != null && input.measuredAtElapsedMs < priorEvaluation) {
            return unavailable(input.measuredAtWallMs, ChargingEtaUnavailableReason.OUT_OF_ORDER_EVALUATION)
        }
        lastEstimateSourceElapsedMs = input.measuredAtElapsedMs

        if (!input.chargingActive) return unavailable(input.measuredAtWallMs, ChargingEtaUnavailableReason.CHARGING_INACTIVE)
        if (input.paused) return unavailable(input.measuredAtWallMs, ChargingEtaUnavailableReason.CHARGING_PAUSED)
        if (input.chargeType == ChargingType.UNKNOWN || input.chargeType == ChargingType.CONFLICT) {
            return unavailable(input.measuredAtWallMs, ChargingEtaUnavailableReason.UNKNOWN_CHARGE_TYPE)
        }

        if (input.selection == ChargingEtaSelection.CALCULATED) {
            val own = calculateOwn(input)
            if (own.seconds != null) {
                return result(
                    input = input,
                    source = own.source,
                    calculationPowerKw = own.powerKw,
                    seconds = own.seconds,
                    sourceObservedAtWallMs = null,
                    profileVersion = if (own.source == ChargingEtaSource.CALCULATED_DC) dcProfile.version else null,
                    fallbackReason = null,
                    unavailableReason = null
                )
            }
            val oem = oemStabilizer.stable(
                session = input.session,
                asOfElapsedMs = input.measuredAtElapsedMs,
                actualNowElapsedMs = input.nowElapsedMs
            )
            if (oem != null) {
                return result(
                    input = input,
                    source = ChargingEtaSource.OEM,
                    seconds = oem.seconds,
                    sourceObservedAtWallMs = oem.sourceWallMs,
                    profileVersion = null,
                    fallbackReason = own.reason,
                    unavailableReason = null
                )
            }
            return unavailable(input.measuredAtWallMs, own.reason ?: ChargingEtaUnavailableReason.OEM_NOT_STABLE)
        }

        val oem = oemStabilizer.stable(
            session = input.session,
            asOfElapsedMs = input.measuredAtElapsedMs,
            actualNowElapsedMs = input.nowElapsedMs
        )
            ?: return unavailable(input.measuredAtWallMs, ChargingEtaUnavailableReason.OEM_NOT_STABLE)
        return result(
            input = input,
            source = ChargingEtaSource.OEM,
            seconds = oem.seconds,
            sourceObservedAtWallMs = oem.sourceWallMs,
            profileVersion = null,
            fallbackReason = null,
            unavailableReason = null
        )
    }

    private fun calculateOwn(input: ChargingEtaInput): OwnCalculation {
        val soc = input.socPct
        if (soc == null || !soc.isFinite() || soc < 0.0 || soc >= 100.0 || (soc == 0.0 && input.chargeType == ChargingType.AC)) {
            return OwnCalculation(null, ChargingEtaUnavailableReason.INVALID_SOC)
        }
        val power = powerWindow.weightedMedianKw(
            session = input.session,
            asOfElapsedMs = input.measuredAtElapsedMs,
            actualNowElapsedMs = input.nowElapsedMs
        )?.powerKw
            ?: return OwnCalculation(null, ChargingEtaUnavailableReason.INSUFFICIENT_POWER_WINDOW)
        if (power <= 1.0 || !power.isFinite()) return OwnCalculation(null, ChargingEtaUnavailableReason.INVALID_POWER)

        return when (input.chargeType) {
            ChargingType.AC -> {
                val energy = input.remainingEnergyKwh
                val energySourceElapsedMs = input.remainingEnergySourceElapsedMs
                if (energy == null || !energy.isFinite() || energy <= 0.0) {
                    OwnCalculation(null, ChargingEtaUnavailableReason.INVALID_REMAINING_ENERGY)
                } else if (energySourceElapsedMs == null || energySourceElapsedMs < 0L ||
                    energySourceElapsedMs > input.measuredAtElapsedMs ||
                    energySourceElapsedMs > input.nowElapsedMs ||
                    input.nowElapsedMs - energySourceElapsedMs > MAX_STATE_AGE_MS
                ) {
                    OwnCalculation(null, ChargingEtaUnavailableReason.INVALID_REMAINING_ENERGY)
                } else {
                    val remainingEnergy = energy * (100.0 - soc) / soc
                    val seconds = remainingEnergy * 3_600.0 / power
                    OwnCalculation(seconds.takeIf { it.isFinite() && it > 0.0 }, ChargingEtaUnavailableReason.INVALID_REMAINING_ENERGY, ChargingEtaSource.CALCULATED_AC, power)
                }
            }
            ChargingType.DC -> {
                val seconds = dcProfile.estimateSeconds(soc, 100.0, power)
                OwnCalculation(
                    seconds = seconds,
                    reason = if (seconds == null) ChargingEtaUnavailableReason.DC_PROFILE_GAP else null,
                    source = ChargingEtaSource.CALCULATED_DC,
                    powerKw = power
                )
            }
            ChargingType.UNKNOWN, ChargingType.CONFLICT -> OwnCalculation(null, ChargingEtaUnavailableReason.UNKNOWN_CHARGE_TYPE)
        }
    }

    private fun result(
        input: ChargingEtaInput,
        source: ChargingEtaSource,
        seconds: Double,
        sourceObservedAtWallMs: Long?,
        profileVersion: String?,
        fallbackReason: ChargingEtaUnavailableReason?,
        unavailableReason: ChargingEtaUnavailableReason?,
        calculationPowerKw: Double? = null
    ): ChargingEtaResult {
        if (!seconds.isFinite() || seconds <= 0.0) {
            return unavailable(input.measuredAtWallMs, ChargingEtaUnavailableReason.INVALID_POWER)
        }
        val minutes = ceil(seconds / 60.0).toLong()
        val finishAt = if (minutes <= (Long.MAX_VALUE - input.measuredAtWallMs) / 60_000L) {
            input.measuredAtWallMs + minutes * 60_000L
        } else {
            null
        }
        return ChargingEtaResult(
            source = source,
            seconds = seconds,
            roundedUpMinutes = minutes,
            finishAtWallMs = finishAt,
            measuredAtWallMs = input.measuredAtWallMs,
            sourceObservedAtWallMs = sourceObservedAtWallMs,
            profileVersion = profileVersion,
            fallbackReason = fallbackReason,
            unavailableReason = unavailableReason,
            calculationPowerKw = calculationPowerKw
        )
    }

    private fun unavailable(
        measuredAtWallMs: Long,
        reason: ChargingEtaUnavailableReason
    ) = ChargingEtaResult(
        source = ChargingEtaSource.NONE,
        seconds = null,
        roundedUpMinutes = null,
        finishAtWallMs = null,
        measuredAtWallMs = measuredAtWallMs,
        sourceObservedAtWallMs = null,
        profileVersion = null,
        fallbackReason = null,
        unavailableReason = reason
    )

    private data class OwnCalculation(
        val seconds: Double?,
        val reason: ChargingEtaUnavailableReason?,
        val source: ChargingEtaSource = ChargingEtaSource.NONE,
        val powerKw: Double? = null
    )

    companion object {
        private const val MAX_STATE_AGE_MS = 120_000L
    }
}
