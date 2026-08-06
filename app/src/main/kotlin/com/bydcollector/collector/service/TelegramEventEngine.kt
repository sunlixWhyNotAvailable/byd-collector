package com.bydcollector.collector.service

import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.telegram.TelegramEventType
import org.json.JSONObject
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

data class TelegramEventConfig(
    val enabledEvents: Set<TelegramEventType>,
    val chargeStepPercent: Int,
    val lowVoltageThreshold: Double,
    val unavailableDelayMs: Long,
    val tripEndDelayMs: Long
)

data class TelegramDetectedEvent(
    val type: TelegramEventType,
    val dedupeKey: String,
    val variables: Map<String, String>
)

data class TelegramEventResult(
    val state: TelegramEventState,
    val events: List<TelegramDetectedEvent>,
    val shouldPersist: Boolean,
    val nextWakeAtMs: Long?
)

data class TelegramEventState(
    val initialized: Boolean = false,
    val charging: String? = null,
    val chargingCandidate: String? = null,
    val chargingCandidateCount: Int = 0,
    val chargingActive: Boolean? = null,
    val chargingActiveCandidate: Boolean? = null,
    val chargingActiveCandidateCount: Int = 0,
    val chargingEvidenceSource: String? = null,
    val chargingLowPowerSinceMs: Long? = null,
    val chargeGunConnected: Boolean? = null,
    val chargeGunCandidate: Boolean? = null,
    val chargeGunCandidateCount: Int = 0,
    val gear: String? = null,
    val gearCandidate: String? = null,
    val gearCandidateCount: Int = 0,
    val chargingSessionId: String? = null,
    val chargingStartedAtMs: Long? = null,
    val chargingStartSoc: Double? = null,
    val chargingStartEnergyKwh: Double? = null,
    val lastProgressThreshold: Int? = null,
    val fullCandidateCount: Int = 0,
    val fullSent: Boolean = false,
    val lowVoltageSinceMs: Long? = null,
    val lowVoltageSent: Boolean = false,
    val lastSuccessfulPollAtMs: Long? = null,
    val telemetryExpectedSinceMs: Long? = null,
    val telemetryOutageSent: Boolean = false,
    val tripId: String? = null,
    val tripStartedAtMs: Long? = null,
    val tripStartOdometerKm: Double? = null,
    val tripStartSoc: Double? = null,
    val tripParkedSinceMs: Long? = null,
    val tripEndOdometerKm: Double? = null,
    val tripEndSoc: Double? = null,
    val tripEndEnergyKwh: Double? = null,
    val lastPersistedAtMs: Long = 0L
) {
    fun hasDeferredStorageWork(): Boolean {
        return tripId != null ||
            tripParkedSinceMs != null ||
            chargingSessionId != null ||
            chargingActive == true ||
            chargingLowPowerSinceMs != null ||
            (chargingCandidate != null && chargingCandidateCount > 0) ||
            (chargingActiveCandidate != null && chargingActiveCandidateCount > 0) ||
            (chargeGunCandidate != null && chargeGunCandidateCount > 0) ||
            (gearCandidate != null && gearCandidateCount > 0) ||
            fullCandidateCount > 0 ||
            fullSent ||
            lowVoltageSinceMs != null ||
            lowVoltageSent ||
            telemetryOutageSent
    }

    fun toJson(): String = JSONObject().apply {
        put("initialized", initialized)
        putNullable("charging", charging)
        putNullable("chargingCandidate", chargingCandidate)
        put("chargingCandidateCount", chargingCandidateCount)
        putNullable("chargingActive", chargingActive)
        putNullable("chargingActiveCandidate", chargingActiveCandidate)
        put("chargingActiveCandidateCount", chargingActiveCandidateCount)
        putNullable("chargingEvidenceSource", chargingEvidenceSource)
        putNullable("chargingLowPowerSinceMs", chargingLowPowerSinceMs)
        putNullable("chargeGunConnected", chargeGunConnected)
        putNullable("chargeGunCandidate", chargeGunCandidate)
        put("chargeGunCandidateCount", chargeGunCandidateCount)
        putNullable("gear", gear)
        putNullable("gearCandidate", gearCandidate)
        put("gearCandidateCount", gearCandidateCount)
        putNullable("chargingSessionId", chargingSessionId)
        putNullable("chargingStartedAtMs", chargingStartedAtMs)
        putNullable("chargingStartSoc", chargingStartSoc)
        putNullable("chargingStartEnergyKwh", chargingStartEnergyKwh)
        putNullable("lastProgressThreshold", lastProgressThreshold)
        put("fullCandidateCount", fullCandidateCount)
        put("fullSent", fullSent)
        putNullable("lowVoltageSinceMs", lowVoltageSinceMs)
        put("lowVoltageSent", lowVoltageSent)
        putNullable("lastSuccessfulPollAtMs", lastSuccessfulPollAtMs)
        putNullable("telemetryExpectedSinceMs", telemetryExpectedSinceMs)
        put("telemetryOutageSent", telemetryOutageSent)
        putNullable("tripId", tripId)
        putNullable("tripStartedAtMs", tripStartedAtMs)
        putNullable("tripStartOdometerKm", tripStartOdometerKm)
        putNullable("tripStartSoc", tripStartSoc)
        putNullable("tripParkedSinceMs", tripParkedSinceMs)
        putNullable("tripEndOdometerKm", tripEndOdometerKm)
        putNullable("tripEndSoc", tripEndSoc)
        putNullable("tripEndEnergyKwh", tripEndEnergyKwh)
        put("lastPersistedAtMs", lastPersistedAtMs)
    }.toString()

    companion object {
        fun fromJson(value: String?): TelegramEventState {
            return fromJsonOrNull(value) ?: TelegramEventState()
        }

        fun fromJsonOrNull(value: String?): TelegramEventState? {
            if (value.isNullOrBlank()) return TelegramEventState()
            return runCatching {
                val json = JSONObject(value)
                TelegramEventState(
                    initialized = json.optBoolean("initialized", false),
                    charging = json.optStringOrNull("charging"),
                    chargingCandidate = json.optStringOrNull("chargingCandidate"),
                    chargingCandidateCount = json.optInt("chargingCandidateCount"),
                    chargingActive = json.optBooleanOrNull("chargingActive"),
                    chargingActiveCandidate = json.optBooleanOrNull("chargingActiveCandidate"),
                    chargingActiveCandidateCount = json.optInt("chargingActiveCandidateCount"),
                    chargingEvidenceSource = json.optStringOrNull("chargingEvidenceSource"),
                    chargingLowPowerSinceMs = json.optLongOrNull("chargingLowPowerSinceMs"),
                    chargeGunConnected = json.optBooleanOrNull("chargeGunConnected"),
                    chargeGunCandidate = json.optBooleanOrNull("chargeGunCandidate"),
                    chargeGunCandidateCount = json.optInt("chargeGunCandidateCount"),
                    gear = json.optStringOrNull("gear"),
                    gearCandidate = json.optStringOrNull("gearCandidate"),
                    gearCandidateCount = json.optInt("gearCandidateCount"),
                    chargingSessionId = json.optStringOrNull("chargingSessionId"),
                    chargingStartedAtMs = json.optLongOrNull("chargingStartedAtMs"),
                    chargingStartSoc = json.optDoubleOrNull("chargingStartSoc"),
                    chargingStartEnergyKwh = json.optDoubleOrNull("chargingStartEnergyKwh"),
                    lastProgressThreshold = json.optIntOrNull("lastProgressThreshold"),
                    fullCandidateCount = json.optInt("fullCandidateCount"),
                    fullSent = json.optBoolean("fullSent"),
                    lowVoltageSinceMs = json.optLongOrNull("lowVoltageSinceMs"),
                    lowVoltageSent = json.optBoolean("lowVoltageSent"),
                    lastSuccessfulPollAtMs = json.optLongOrNull("lastSuccessfulPollAtMs"),
                    telemetryExpectedSinceMs = json.optLongOrNull("telemetryExpectedSinceMs"),
                    telemetryOutageSent = json.optBoolean("telemetryOutageSent"),
                    tripId = json.optStringOrNull("tripId"),
                    tripStartedAtMs = json.optLongOrNull("tripStartedAtMs"),
                    tripStartOdometerKm = json.optDoubleOrNull("tripStartOdometerKm"),
                    tripStartSoc = json.optDoubleOrNull("tripStartSoc"),
                    tripParkedSinceMs = json.optLongOrNull("tripParkedSinceMs"),
                    tripEndOdometerKm = json.optDoubleOrNull("tripEndOdometerKm"),
                    tripEndSoc = json.optDoubleOrNull("tripEndSoc"),
                    tripEndEnergyKwh = json.optDoubleOrNull("tripEndEnergyKwh"),
                    lastPersistedAtMs = json.optLong("lastPersistedAtMs")
                )
            }.getOrNull()
        }
    }
}

class TelegramEventEngine(initialState: TelegramEventState = TelegramEventState()) {
    private val resumePendingChargingTransition =
        initialState.chargingActiveCandidate != null && initialState.chargingActiveCandidateCount > 0
    private var tripRecoveryNeedsFreshGear = initialState.tripId != null && initialState.tripParkedSinceMs != null
    private var tripRecoveryTelemetrySeen = false
    private var tripRecoveryGraceStartedAtMs: Long? = null
    var state: TelegramEventState = initialState.copy(
        chargingActive = initialState.chargingActive.takeIf { resumePendingChargingTransition },
        chargingActiveCandidate = initialState.chargingActiveCandidate.takeIf { resumePendingChargingTransition },
        chargingActiveCandidateCount = initialState.chargingActiveCandidateCount.takeIf { resumePendingChargingTransition } ?: 0,
        chargingEvidenceSource = null,
        chargingLowPowerSinceMs = null,
        fullCandidateCount = 0
    )
        private set

    fun reset(): TelegramEventState {
        tripRecoveryNeedsFreshGear = false
        tripRecoveryTelemetrySeen = false
        tripRecoveryGraceStartedAtMs = null
        state = TelegramEventState()
        return state
    }

    fun onSuccessfulPoll(
        observations: List<NormalizedObservation>,
        config: TelegramEventConfig,
        nowMs: Long
    ): TelegramEventResult {
        val values = observations.asSequence()
            .filter { it.quality == NormalizedQuality.OK }
            .associateBy { it.field.fieldKey }
        val soc = values.number("soc")
        val remainingEnergy = values.number("battery_remaining_energy_kwh")
        val batteryChargePower = values.number("battery_charge_power_kw")
        val auxVoltage = values.number("aux_voltage_v")
        val odometer = values.number("odometer_km")
        val tripEnergy = values.number("trip_energy_kwh")
        val range = values.number("remaining_range_km")
        val rawCharging = values.text("charging_state")
        val rawGun = values.bool("charge_gun_connected_raw")
        val rawGear = values.text("gear_auto_mode_raw")
        val events = mutableListOf<TelegramDetectedEvent>()
        val original = state
        val firstPoll = !state.initialized

        if (tripRecoveryNeedsFreshGear) {
            tripRecoveryTelemetrySeen = true
            tripRecoveryGraceStartedAtMs = null
        }

        if (firstPoll) {
            state = state.copy(
                initialized = true,
                charging = rawCharging,
                chargeGunConnected = rawGun,
                gear = rawGear,
                lastSuccessfulPollAtMs = nowMs,
                telemetryExpectedSinceMs = state.telemetryExpectedSinceMs ?: nowMs,
                telemetryOutageSent = false
            )
            if (rawGear != null && rawGear != PARK) startTrip(nowMs, odometer, soc)
        } else {
            state = state.copy(
                lastSuccessfulPollAtMs = nowMs,
                telemetryExpectedSinceMs = state.telemetryExpectedSinceMs ?: nowMs,
                telemetryOutageSent = false
            )
            trackChargingSemantic(rawCharging)
            confirmChargeGun(rawGun, nowMs, soc, config, events)
            confirmGear(rawGear, nowMs, odometer, soc, tripEnergy, config)
        }

        val evidence = chargingEvidence(rawGun, batteryChargePower, rawCharging)
        val stopCharging = confirmChargingEvidence(
            evidence = evidence,
            nowMs = nowMs,
            soc = soc,
            remainingEnergy = remainingEnergy,
            batteryChargePower = batteryChargePower,
            config = config,
            events = events
        )
        if (evidence.active != null && (state.chargingActive == true || stopCharging)) {
            evaluateChargingProgress(nowMs, soc, remainingEnergy, batteryChargePower, range, config, events)
        }
        if (stopCharging && state.chargingSessionId != null) {
            stopChargingSession(nowMs, soc, remainingEnergy, batteryChargePower, config, events)
        } else if (stopCharging) {
            state = state.copy(fullSent = false)
        }
        evaluateLowVoltage(nowMs, auxVoltage, config, events)

        val changedBeyondHeartbeat = state.copy(lastSuccessfulPollAtMs = original.lastSuccessfulPollAtMs) != original
        return persistedResult(
            events = events,
            nowMs = nowMs,
            force = firstPoll || changedBeyondHeartbeat || nowMs - state.lastPersistedAtMs >= STATE_HEARTBEAT_MS,
            config = config
        )
    }

    fun onTick(config: TelegramEventConfig, mainCollectionExpected: Boolean, lastError: String?, nowMs: Long): TelegramEventResult {
        val events = mutableListOf<TelegramDetectedEvent>()
        val original = state
        finalizePendingTrip(config, nowMs, events)
        if (!mainCollectionExpected) {
            state = state.copy(telemetryExpectedSinceMs = null, telemetryOutageSent = false)
            return persistedResult(events, nowMs, force = state != original || events.isNotEmpty(), config = config)
        }
        val expectedSince = state.telemetryExpectedSinceMs ?: nowMs
        state = state.copy(telemetryExpectedSinceMs = expectedSince)
        val outageBaseline = maxOf(state.lastSuccessfulPollAtMs ?: Long.MIN_VALUE, expectedSince)
        if (
            !state.telemetryOutageSent &&
            nowMs - outageBaseline >= config.unavailableDelayMs
        ) {
            state = state.copy(telemetryOutageSent = true)
            addIfEnabled(
                events,
                config,
                TelegramEventType.TELEMETRY_UNAVAILABLE,
                "telemetry-unavailable:$outageBaseline",
                mapOf(
                    "last_data_time" to state.lastSuccessfulPollAtMs?.let(::formatTime).orEmpty().ifBlank { "n/a" },
                    "error" to (lastError?.take(300) ?: "unknown"),
                    "time" to formatTime(nowMs)
                )
            )
        }
        return persistedResult(events, nowMs, force = state != original || events.isNotEmpty(), config = config)
    }

    private fun trackChargingSemantic(raw: String?) {
        if (raw == null || raw == state.charging) {
            state = state.copy(chargingCandidate = null, chargingCandidateCount = 0)
            return
        }
        val count = if (state.chargingCandidate == raw) state.chargingCandidateCount + 1 else 1
        state = state.copy(chargingCandidate = raw, chargingCandidateCount = count)
        if (count >= CONFIRMATION_SAMPLES) {
            state = state.copy(charging = raw, chargingCandidate = null, chargingCandidateCount = 0)
        }
    }

    private fun confirmChargingEvidence(
        evidence: ChargingEvidence,
        nowMs: Long,
        soc: Double?,
        remainingEnergy: Double?,
        batteryChargePower: Double?,
        config: TelegramEventConfig,
        events: MutableList<TelegramDetectedEvent>
    ): Boolean {
        if (evidence.active == null) {
            state = state.copy(
                chargingActiveCandidate = null,
                chargingActiveCandidateCount = 0,
                chargingLowPowerSinceMs = null,
                fullCandidateCount = 0
            )
            return false
        }

        if (
            evidence.source == ChargingEvidenceSource.PRIMARY_LOW_POWER &&
            (state.chargingActive == true || state.chargingSessionId != null)
        ) {
            val lowPowerSince = state.chargingLowPowerSinceMs ?: nowMs
            state = state.copy(
                chargingActiveCandidate = null,
                chargingActiveCandidateCount = 0,
                chargingEvidenceSource = evidence.source.key,
                chargingLowPowerSinceMs = lowPowerSince
            )
            if (nowMs - lowPowerSince < CHARGING_LOW_POWER_CONFIRM_MS) return false
            state = state.copy(chargingActive = false, chargingLowPowerSinceMs = null)
            return true
        }

        state = state.copy(chargingLowPowerSinceMs = null)
        if (evidence.active == state.chargingActive) {
            state = state.copy(
                chargingActiveCandidate = null,
                chargingActiveCandidateCount = 0,
                chargingEvidenceSource = evidence.source.key
            )
            return false
        }
        val count = if (state.chargingActiveCandidate == evidence.active) {
            state.chargingActiveCandidateCount + 1
        } else {
            1
        }
        state = state.copy(chargingActiveCandidate = evidence.active, chargingActiveCandidateCount = count)
        if (count < CONFIRMATION_SAMPLES) return false
        val previous = state.chargingActive
        state = state.copy(
            chargingActive = evidence.active,
            chargingActiveCandidate = null,
            chargingActiveCandidateCount = 0,
            chargingEvidenceSource = evidence.source.key
        )
        if (evidence.active) {
            if (state.chargingSessionId == null) {
                startChargingSession(
                    nowMs,
                    soc,
                    remainingEnergy,
                    config.chargeStepPercent,
                    fullAlreadySent = previous == null && state.fullSent && soc?.let { it >= FULL_SOC_THRESHOLD } == true
                )
            }
            if (previous == false) {
                addIfEnabled(
                    events, config, TelegramEventType.CHARGING_STARTED,
                    "${state.chargingSessionId}:started",
                    chargingVariables(nowMs, soc, remainingEnergy, batteryChargePower)
                )
            }
            return false
        }
        if (previous == null && state.chargingSessionId != null) {
            finishChargingSession(full = false)
        }
        return previous == true
    }

    private fun confirmChargeGun(
        raw: Boolean?,
        nowMs: Long,
        soc: Double?,
        config: TelegramEventConfig,
        events: MutableList<TelegramDetectedEvent>
    ) {
        if (raw == null || raw == state.chargeGunConnected) {
            state = state.copy(chargeGunCandidate = null, chargeGunCandidateCount = 0)
            return
        }
        val count = if (state.chargeGunCandidate == raw) state.chargeGunCandidateCount + 1 else 1
        state = state.copy(chargeGunCandidate = raw, chargeGunCandidateCount = count)
        if (count < CONFIRMATION_SAMPLES) return
        state = state.copy(chargeGunConnected = raw, chargeGunCandidate = null, chargeGunCandidateCount = 0)
        val type = if (raw) TelegramEventType.CHARGE_GUN_CONNECTED else TelegramEventType.CHARGE_GUN_DISCONNECTED
        addIfEnabled(
            events, config, type, "charge-gun:$nowMs:${if (raw) 1 else 0}",
            mapOf("soc" to formatNumber(soc), "time" to formatTime(nowMs))
        )
    }

    private fun confirmGear(
        raw: String?,
        nowMs: Long,
        odometer: Double?,
        soc: Double?,
        tripEnergy: Double?,
        config: TelegramEventConfig
    ) {
        if (raw != null && (tripRecoveryNeedsFreshGear || raw != state.gear)) {
            val count = if (state.gearCandidate == raw) state.gearCandidateCount + 1 else 1
            state = state.copy(gearCandidate = raw, gearCandidateCount = count)
            if (count >= CONFIRMATION_SAMPLES) {
                val previous = state.gear
                state = state.copy(gear = raw, gearCandidate = null, gearCandidateCount = 0)
                tripRecoveryNeedsFreshGear = false
                tripRecoveryTelemetrySeen = false
                tripRecoveryGraceStartedAtMs = null
                if (previous == PARK && raw != PARK) {
                    val parkedLongEnough = state.tripParkedSinceMs?.let {
                        nowMs - it >= config.tripEndDelayMs
                    } == true
                    if (state.tripId == null || parkedLongEnough) {
                        startTrip(nowMs, odometer, soc)
                    } else {
                        clearPendingTrip()
                    }
                }
                if (raw == PARK && previous != PARK && state.tripId != null) {
                    state = state.copy(tripParkedSinceMs = nowMs)
                    updatePendingTripSnapshot(odometer, soc, tripEnergy)
                }
                if (raw != PARK && previous != PARK) clearPendingTrip()
            }
        } else {
            state = state.copy(gearCandidate = null, gearCandidateCount = 0)
        }
        if (state.gear == PARK && state.tripParkedSinceMs != null && state.tripId != null) {
            updatePendingTripSnapshot(odometer, soc, tripEnergy)
        }
    }

    private fun evaluateChargingProgress(
        nowMs: Long,
        soc: Double?,
        remainingEnergy: Double?,
        batteryChargePower: Double?,
        range: Double?,
        config: TelegramEventConfig,
        events: MutableList<TelegramDetectedEvent>
    ) {
        val sessionId = state.chargingSessionId ?: return
        val currentSoc = soc ?: return
        val fullNow = currentSoc >= FULL_SOC_THRESHOLD
        val fullCount = if (fullNow) state.fullCandidateCount + 1 else 0
        state = state.copy(fullCandidateCount = fullCount)
        if (fullCount >= CONFIRMATION_SAMPLES) {
            if (!state.fullSent) {
                if (TelegramEventType.CHARGED_TO_100 in config.enabledEvents) {
                    addIfEnabled(
                        events, config, TelegramEventType.CHARGED_TO_100, "$sessionId:full",
                        chargingVariables(nowMs, soc, remainingEnergy, batteryChargePower) +
                            mapOf("remaining_energy_kwh" to formatNumber(remainingEnergy), "range_km" to formatNumber(range))
                    )
                } else {
                    addIfEnabled(
                        events, config, TelegramEventType.CHARGING_PROGRESS, "$sessionId:progress:100",
                        chargingVariables(nowMs, soc, remainingEnergy, batteryChargePower)
                    )
                }
            }
            finishChargingSession(full = true)
            return
        }
        val step = config.chargeStepPercent.coerceIn(1, 99)
        var threshold = (floor(currentSoc / step) * step).toInt()
        if (threshold >= 100 && TelegramEventType.CHARGED_TO_100 in config.enabledEvents) threshold = 100 - step
        val previousThreshold = state.lastProgressThreshold ?: return
        if (threshold <= previousThreshold) return
        state = state.copy(lastProgressThreshold = threshold)
        addIfEnabled(
            events, config, TelegramEventType.CHARGING_PROGRESS, "$sessionId:progress:$threshold",
            chargingVariables(nowMs, soc, remainingEnergy, batteryChargePower)
        )
    }

    private fun evaluateLowVoltage(
        nowMs: Long,
        voltage: Double?,
        config: TelegramEventConfig,
        events: MutableList<TelegramDetectedEvent>
    ) {
        val current = voltage ?: return
        if (current < config.lowVoltageThreshold) {
            val since = state.lowVoltageSinceMs ?: nowMs
            state = state.copy(lowVoltageSinceMs = since)
            if (!state.lowVoltageSent && nowMs - since >= LOW_VOLTAGE_CONFIRM_MS) {
                addIfEnabled(
                    events, config, TelegramEventType.LOW_12V_VOLTAGE, "low-12v:$since",
                    mapOf("battery_12v" to formatNumber(current), "time" to formatTime(nowMs))
                )
                state = state.copy(lowVoltageSent = true)
            }
        } else if (current >= config.lowVoltageThreshold + LOW_VOLTAGE_HYSTERESIS) {
            state = state.copy(lowVoltageSinceMs = null, lowVoltageSent = false)
        }
    }

    private fun chargingEvidence(
        chargeGunConnected: Boolean?,
        batteryChargePower: Double?,
        semanticState: String?
    ): ChargingEvidence {
        if (chargeGunConnected == false) {
            return ChargingEvidence(false, ChargingEvidenceSource.PRIMARY_DISCONNECTED)
        }
        if (chargeGunConnected == true && batteryChargePower != null) {
            return if (batteryChargePower >= CHARGING_POWER_THRESHOLD_KW) {
                ChargingEvidence(true, ChargingEvidenceSource.PRIMARY_POWER)
            } else {
                ChargingEvidence(false, ChargingEvidenceSource.PRIMARY_LOW_POWER)
            }
        }
        if (semanticState == CHARGING) {
            return ChargingEvidence(true, ChargingEvidenceSource.SEMANTIC_FALLBACK)
        }
        if (semanticState != null) {
            return ChargingEvidence(false, ChargingEvidenceSource.SEMANTIC_FALLBACK)
        }
        return ChargingEvidence(null, ChargingEvidenceSource.UNKNOWN)
    }

    private fun stopChargingSession(
        nowMs: Long,
        soc: Double?,
        remainingEnergy: Double?,
        batteryChargePower: Double?,
        config: TelegramEventConfig,
        events: MutableList<TelegramDetectedEvent>
    ) {
        val sessionId = state.chargingSessionId ?: return
        addIfEnabled(
            events, config, TelegramEventType.CHARGING_STOPPED, "$sessionId:stopped",
            chargingVariables(nowMs, soc, remainingEnergy, batteryChargePower)
        )
        finishChargingSession(full = false)
    }

    private fun finishChargingSession(full: Boolean) {
        state = state.copy(
            chargingSessionId = null,
            chargingStartedAtMs = null,
            chargingStartSoc = null,
            chargingStartEnergyKwh = null,
            lastProgressThreshold = null,
            fullCandidateCount = 0,
            fullSent = full
        )
    }

    private fun finalizePendingTrip(
        config: TelegramEventConfig,
        nowMs: Long,
        events: MutableList<TelegramDetectedEvent>
    ) {
        val parkedSince = state.tripParkedSinceMs ?: return
        val tripId = state.tripId ?: return
        if (tripRecoveryNeedsFreshGear) {
            if (tripRecoveryTelemetrySeen) return
            val graceStartedAt = tripRecoveryGraceStartedAtMs ?: nowMs.also {
                tripRecoveryGraceStartedAtMs = it
            }
            if (nowMs - graceStartedAt < TRIP_RECOVERY_GEAR_GRACE_MS) return
            tripRecoveryNeedsFreshGear = false
        }
        if (state.gear != PARK || nowMs < parkedSince + config.tripEndDelayMs) return
        val tripStartOdometer = state.tripStartOdometerKm
        val tripEndOdometer = state.tripEndOdometerKm
        val distance = if (tripEndOdometer != null && tripStartOdometer != null) {
            (tripEndOdometer - tripStartOdometer)
                .takeIf { it >= 0.0 && it <= MAX_TRIP_DISTANCE_KM }
        } else {
            null
        }
        addIfEnabled(
            events, config, TelegramEventType.TRIP_SUMMARY, "$tripId:summary",
            mapOf(
                "trip_distance_km" to formatNumber(distance),
                "trip_energy_kwh" to formatNumber(state.tripEndEnergyKwh),
                "trip_duration" to formatDuration(parkedSince - (state.tripStartedAtMs ?: parkedSince)),
                "soc_start" to formatNumber(state.tripStartSoc),
                "soc_end" to formatNumber(state.tripEndSoc),
                "time" to formatTime(parkedSince)
            )
        )
        clearTrip()
    }

    private fun updatePendingTripSnapshot(odometer: Double?, soc: Double?, tripEnergy: Double?) {
        state = state.copy(
            tripEndOdometerKm = odometer ?: state.tripEndOdometerKm,
            tripEndSoc = soc ?: state.tripEndSoc,
            tripEndEnergyKwh = tripEnergy ?: state.tripEndEnergyKwh
        )
    }

    private fun clearPendingTrip() {
        state = state.copy(
            tripParkedSinceMs = null,
            tripEndOdometerKm = null,
            tripEndSoc = null,
            tripEndEnergyKwh = null
        )
    }

    private fun clearTrip() {
        tripRecoveryNeedsFreshGear = false
        tripRecoveryTelemetrySeen = false
        tripRecoveryGraceStartedAtMs = null
        state = state.copy(
            tripId = null,
            tripStartedAtMs = null,
            tripStartOdometerKm = null,
            tripStartSoc = null,
            tripParkedSinceMs = null,
            tripEndOdometerKm = null,
            tripEndSoc = null,
            tripEndEnergyKwh = null
        )
    }

    private fun startChargingSession(
        nowMs: Long,
        soc: Double?,
        remainingEnergy: Double?,
        chargeStepPercent: Int,
        fullAlreadySent: Boolean = false
    ) {
        val step = chargeStepPercent.coerceIn(1, 99)
        state = state.copy(
            chargingSessionId = UUID.randomUUID().toString(),
            chargingStartedAtMs = nowMs,
            chargingStartSoc = soc,
            chargingStartEnergyKwh = remainingEnergy,
            lastProgressThreshold = soc?.let { (floor(it / step) * step).toInt() },
            fullCandidateCount = 0,
            fullSent = fullAlreadySent
        )
    }

    private fun startTrip(nowMs: Long, odometer: Double?, soc: Double?) {
        tripRecoveryNeedsFreshGear = false
        tripRecoveryTelemetrySeen = false
        tripRecoveryGraceStartedAtMs = null
        state = state.copy(
            tripId = UUID.randomUUID().toString(),
            tripStartedAtMs = nowMs,
            tripStartOdometerKm = odometer,
            tripStartSoc = soc,
            tripParkedSinceMs = null,
            tripEndOdometerKm = null,
            tripEndSoc = null,
            tripEndEnergyKwh = null
        )
    }

    private fun chargingVariables(
        nowMs: Long,
        soc: Double?,
        remainingEnergy: Double?,
        batteryPower: Double?
    ): Map<String, String> {
        val startSoc = state.chargingStartSoc
        val startEnergy = state.chargingStartEnergyKwh
        val addedPercent = if (soc != null && startSoc != null) soc - startSoc else null
        val addedEnergy = if (remainingEnergy != null && startEnergy != null) {
            remainingEnergy - startEnergy
        } else null
        return mapOf(
            "soc" to formatNumber(soc),
            "battery_power_kw" to formatNumber(batteryPower),
            "charge_added_percent" to formatNumber(addedPercent),
            "charge_added_kwh" to formatNumber(addedEnergy),
            "charge_duration" to formatDuration(nowMs - (state.chargingStartedAtMs ?: nowMs)),
            "time" to formatTime(nowMs)
        )
    }

    private fun addIfEnabled(
        events: MutableList<TelegramDetectedEvent>,
        config: TelegramEventConfig,
        type: TelegramEventType,
        dedupeKey: String,
        variables: Map<String, String>
    ) {
        if (type in config.enabledEvents) events += TelegramDetectedEvent(type, dedupeKey, variables)
    }

    private fun persistedResult(
        events: List<TelegramDetectedEvent>,
        nowMs: Long,
        force: Boolean,
        config: TelegramEventConfig
    ): TelegramEventResult {
        val persist = force || events.isNotEmpty()
        if (persist) state = state.copy(lastPersistedAtMs = nowMs)
        return TelegramEventResult(state, events, persist, pendingTripDeadline(config))
    }

    private fun pendingTripDeadline(config: TelegramEventConfig): Long? {
        if (state.tripId == null || state.gear != PARK) return null
        if (tripRecoveryNeedsFreshGear) {
            if (tripRecoveryTelemetrySeen) return null
            return tripRecoveryGraceStartedAtMs?.plus(TRIP_RECOVERY_GEAR_GRACE_MS)
        }
        return state.tripParkedSinceMs?.plus(config.tripEndDelayMs)
    }

    private fun Map<String, NormalizedObservation>.number(key: String): Double? = get(key)?.value?.number
    private fun Map<String, NormalizedObservation>.text(key: String): String? = get(key)?.value?.text
    private fun Map<String, NormalizedObservation>.bool(key: String): Boolean? = get(key)?.value?.bool

    companion object {
        private const val CHARGING = "charging"
        private const val PARK = "P"
        private const val CONFIRMATION_SAMPLES = 2
        private const val FULL_SOC_THRESHOLD = 99.5
        private const val CHARGING_POWER_THRESHOLD_KW = 0.5
        private const val CHARGING_LOW_POWER_CONFIRM_MS = 60_000L
        private const val LOW_VOLTAGE_CONFIRM_MS = 60_000L
        private const val LOW_VOLTAGE_HYSTERESIS = 0.3
        private const val STATE_HEARTBEAT_MS = 30_000L
        private const val TRIP_RECOVERY_GEAR_GRACE_MS = 30_000L
        private const val MAX_TRIP_DISTANCE_KM = 2_000.0
    }

    private data class ChargingEvidence(
        val active: Boolean?,
        val source: ChargingEvidenceSource
    )

    private enum class ChargingEvidenceSource(val key: String) {
        PRIMARY_POWER("primary_power"),
        PRIMARY_LOW_POWER("primary_low_power"),
        PRIMARY_DISCONNECTED("primary_disconnected"),
        SEMANTIC_FALLBACK("semantic_fallback"),
        UNKNOWN("unknown")
    }
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

private fun formatNumber(value: Double?): String {
    value ?: return "n/a"
    val rounded = (value * 100.0).roundToInt() / 100.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

private fun formatDuration(durationMs: Long): String {
    val totalMinutes = (durationMs.coerceAtLeast(0L) / 60_000L)
    return "%d:%02d".format(totalMinutes / 60L, totalMinutes % 60L)
}

private fun formatTime(timeMs: Long): String {
    return java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timeMs))
}
