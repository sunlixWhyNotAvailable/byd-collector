package com.bydcollector.collector.service

import com.bydcollector.collector.data.energy.EnergySnapshot
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.trips.TripMetrics
import com.bydcollector.collector.telegram.TelegramEventType
import com.bydcollector.collector.telegram.TelegramNavigatorMask
import com.bydcollector.collector.telegram.TelegramTemplateLanguage
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

data class TelegramEventConfig(
    val enabledEvents: Set<TelegramEventType>,
    val chargeStepPercent: Int,
    val lowVoltageThreshold: Double,
    val unavailableDelayMs: Long,
    val tripEndDelayMs: Long,
    val sendLocation: Boolean = false,
    val language: TelegramTemplateLanguage = TelegramTemplateLanguage.UK,
    val navigatorMask: Int = TelegramNavigatorMask.NONE
)

data class TelegramLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val capturedAt: String,
    val ageSeconds: Long,
    val osmUrl: String,
    val googleUrl: String,
    val appleUrl: String,
    val wazeUrl: String = ""
)

data class TelegramPowerOffSnapshot(
    val odometerKm: Double? = null,
    val soc: Double? = null,
    val tripEnergyKwh: Double? = null,
    val energySnapshot: EnergySnapshot? = null
)

data class TelegramDetectedEvent(
    val type: TelegramEventType,
    val dedupeKey: String,
    val variables: Map<String, String>,
    val textSuffix: String? = null,
    val omitOverall: Boolean = false,
    val locationOnly: Boolean = false,
    val waitsForSummaryKey: String? = null
)

data class TelegramEventResult(
    val state: TelegramEventState,
    val events: List<TelegramDetectedEvent>,
    val shouldPersist: Boolean,
    val nextWakeAtMs: Long?,
    val locationEligibilityReason: String? = null
)

data class TelegramEventState(
    val initialized: Boolean = false,
    val charging: String? = null,
    val chargingCandidate: String? = null,
    val chargingCandidateCount: Int = 0,
    val chargingActive: Boolean? = null,
    val chargingActiveCandidate: Boolean? = null,
    val chargingActiveCandidateCount: Int = 0,
    val chargingActiveCandidateSource: String? = null,
    val chargingEvidenceSource: String? = null,
    val bmsFinishHighPowerConflictActive: Boolean = false,
    val chargingLowPowerSinceMs: Long? = null,
    val chargeGunConnected: Boolean? = null,
    val chargeGunCandidate: Boolean? = null,
    val chargeGunCandidateCount: Int = 0,
    val gear: String? = null,
    val gearCandidate: String? = null,
    val gearCandidateCount: Int = 0,
    val awaitingInitialTripGear: Boolean = false,
    val chargingSessionId: String? = null,
    val chargingStartedAtMs: Long? = null,
    val chargingStartSoc: Double? = null,
    val chargingStartEnergyKwh: Double? = null,
    val chargingProgressBaselineSoc: Double? = null,
    val chargingProgressBaselineEnergyKwh: Double? = null,
    val chargingStepStartedAtMs: Long? = null,
    val lastProgressThreshold: Int? = null,
    val fullCandidateCount: Int = 0,
    val fullSent: Boolean = false,
    val lowVoltageSinceMs: Long? = null,
    val lowVoltageSent: Boolean = false,
    val lastSuccessfulPollAtMs: Long? = null,
    val telemetryExpectedSinceMs: Long? = null,
    val telemetryOutageSent: Boolean = false,
    val tripId: String? = null,
    val tripPowerSessionId: String? = null,
    val tripStartedAtMs: Long? = null,
    val tripStartOdometerKm: Double? = null,
    val tripStartSoc: Double? = null,
    val tripStartEnergyKwh: Double? = null,
    val tripParkedSinceMs: Long? = null,
    val tripEndOdometerKm: Double? = null,
    val tripEndSoc: Double? = null,
    val tripEndEnergyKwh: Double? = null,
    val tripAccumulatedEnergyKwh: Double? = null,
    val powerEnergyPoint: TelegramEnergyPoint? = null,
    val tripStartEnergyPoint: TelegramEnergyPoint? = null,
    val tripEndEnergyPoint: TelegramEnergyPoint? = null,
    val pendingPowerOffLocationTripId: String? = null,
    val pendingPowerOffLocationPowerSessionId: String? = null,
    val pendingPowerOffLocationSummaryDelivered: Boolean = false,
    val bootStartSoc: Double? = null,
    val bootEndSoc: Double? = null,
    val bootTotalDistanceKm: Double = 0.0,
    val bootTotalEnergyKwh: Double = 0.0,
    val bootTotalDurationMs: Long = 0L,
    val lastTripEnergyCounterKwh: Double? = null,
    val lastPersistedAtMs: Long = 0L
) {
    fun hasDeferredStorageWork(): Boolean {
        return tripId != null ||
            tripParkedSinceMs != null ||
            pendingPowerOffLocationTripId != null ||
            chargingSessionId != null ||
            chargingActive == true ||
            chargingLowPowerSinceMs != null ||
            (chargingActiveCandidate != null && chargingActiveCandidateCount > 0) ||
            bmsFinishHighPowerConflictActive ||
            (chargeGunCandidate != null && chargeGunCandidateCount > 0) ||
            (gearCandidate != null && gearCandidateCount > 0) ||
            awaitingInitialTripGear ||
            fullCandidateCount > 0 ||
            fullSent ||
            lowVoltageSinceMs != null ||
            lowVoltageSent ||
            telemetryOutageSent ||
            powerEnergyPoint != null ||
            tripStartEnergyPoint != null ||
            tripEndEnergyPoint != null ||
            bootStartSoc != null ||
            bootEndSoc != null ||
            bootTotalDistanceKm > 0.0 ||
            bootTotalEnergyKwh > 0.0 ||
            bootTotalDurationMs > 0L
    }

    fun toJson(): String = JSONObject().apply {
        put("initialized", initialized)
        putNullable("charging", charging)
        putNullable("chargingCandidate", chargingCandidate)
        put("chargingCandidateCount", chargingCandidateCount)
        putNullable("chargingActive", chargingActive)
        putNullable("chargingActiveCandidate", chargingActiveCandidate)
        put("chargingActiveCandidateCount", chargingActiveCandidateCount)
        putNullable("chargingActiveCandidateSource", chargingActiveCandidateSource)
        putNullable("chargingEvidenceSource", chargingEvidenceSource)
        put("bmsFinishHighPowerConflictActive", bmsFinishHighPowerConflictActive)
        putNullable("chargingLowPowerSinceMs", chargingLowPowerSinceMs)
        putNullable("chargeGunConnected", chargeGunConnected)
        putNullable("chargeGunCandidate", chargeGunCandidate)
        put("chargeGunCandidateCount", chargeGunCandidateCount)
        putNullable("gear", gear)
        putNullable("gearCandidate", gearCandidate)
        put("gearCandidateCount", gearCandidateCount)
        put("awaitingInitialTripGear", awaitingInitialTripGear)
        putNullable("chargingSessionId", chargingSessionId)
        putNullable("chargingStartedAtMs", chargingStartedAtMs)
        putNullable("chargingStartSoc", chargingStartSoc)
        putNullable("chargingStartEnergyKwh", chargingStartEnergyKwh)
        putNullable("chargingProgressBaselineSoc", chargingProgressBaselineSoc)
        putNullable("chargingProgressBaselineEnergyKwh", chargingProgressBaselineEnergyKwh)
        putNullable("chargingStepStartedAtMs", chargingStepStartedAtMs)
        putNullable("lastProgressThreshold", lastProgressThreshold)
        put("fullCandidateCount", fullCandidateCount)
        put("fullSent", fullSent)
        putNullable("lowVoltageSinceMs", lowVoltageSinceMs)
        put("lowVoltageSent", lowVoltageSent)
        putNullable("lastSuccessfulPollAtMs", lastSuccessfulPollAtMs)
        putNullable("telemetryExpectedSinceMs", telemetryExpectedSinceMs)
        put("telemetryOutageSent", telemetryOutageSent)
        putNullable("tripId", tripId)
        putNullable("tripPowerSessionId", tripPowerSessionId)
        putNullable("tripStartedAtMs", tripStartedAtMs)
        putNullable("tripStartOdometerKm", tripStartOdometerKm)
        putNullable("tripStartSoc", tripStartSoc)
        putNullable("tripStartEnergyKwh", tripStartEnergyKwh)
        putNullable("tripParkedSinceMs", tripParkedSinceMs)
        putNullable("tripEndOdometerKm", tripEndOdometerKm)
        putNullable("tripEndSoc", tripEndSoc)
        putNullable("tripEndEnergyKwh", tripEndEnergyKwh)
        putNullable("tripAccumulatedEnergyKwh", tripAccumulatedEnergyKwh)
        putNullable("powerEnergyPoint", powerEnergyPoint?.toJson())
        putNullable("tripStartEnergyPoint", tripStartEnergyPoint?.toJson())
        putNullable("tripEndEnergyPoint", tripEndEnergyPoint?.toJson())
        putNullable("pendingPowerOffLocationTripId", pendingPowerOffLocationTripId)
        putNullable("pendingPowerOffLocationPowerSessionId", pendingPowerOffLocationPowerSessionId)
        put("pendingPowerOffLocationSummaryDelivered", pendingPowerOffLocationSummaryDelivered)
        putNullable("bootStartSoc", bootStartSoc)
        putNullable("bootEndSoc", bootEndSoc)
        put("bootTotalDistanceKm", bootTotalDistanceKm)
        put("bootTotalEnergyKwh", bootTotalEnergyKwh)
        put("bootTotalDurationMs", bootTotalDurationMs)
        putNullable("lastTripEnergyCounterKwh", lastTripEnergyCounterKwh)
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
                    //legacy semantic charging fields are accepted but intentionally discarded
                    charging = null,
                    chargingCandidate = null,
                    chargingCandidateCount = 0,
                    chargingActive = json.optBooleanOrNull("chargingActive"),
                    chargingActiveCandidate = json.optBooleanOrNull("chargingActiveCandidate"),
                    chargingActiveCandidateCount = json.optInt("chargingActiveCandidateCount"),
                    chargingActiveCandidateSource = json.optStringOrNull("chargingActiveCandidateSource"),
                    chargingEvidenceSource = json.optStringOrNull("chargingEvidenceSource"),
                    bmsFinishHighPowerConflictActive =
                        json.optBoolean("bmsFinishHighPowerConflictActive", false),
                    chargingLowPowerSinceMs = json.optLongOrNull("chargingLowPowerSinceMs"),
                    chargeGunConnected = json.optBooleanOrNull("chargeGunConnected"),
                    chargeGunCandidate = json.optBooleanOrNull("chargeGunCandidate"),
                    chargeGunCandidateCount = json.optInt("chargeGunCandidateCount"),
                    gear = json.optStringOrNull("gear"),
                    gearCandidate = json.optStringOrNull("gearCandidate"),
                    gearCandidateCount = json.optInt("gearCandidateCount"),
                    awaitingInitialTripGear = json.optBoolean("awaitingInitialTripGear", false),
                    chargingSessionId = json.optStringOrNull("chargingSessionId"),
                    chargingStartedAtMs = json.optLongOrNull("chargingStartedAtMs"),
                    chargingStartSoc = json.optDoubleOrNull("chargingStartSoc"),
                    chargingStartEnergyKwh = json.optDoubleOrNull("chargingStartEnergyKwh"),
                    chargingProgressBaselineSoc = json.optDoubleOrNull("chargingProgressBaselineSoc"),
                    chargingProgressBaselineEnergyKwh = json.optDoubleOrNull("chargingProgressBaselineEnergyKwh"),
                    chargingStepStartedAtMs = json.optLongOrNull("chargingStepStartedAtMs")
                        ?: json.optLongOrNull("chargingProgressStartedAtMs")
                        ?: json.optLongOrNull("chargingStartedAtMs"),
                    lastProgressThreshold = json.optIntOrNull("lastProgressThreshold"),
                    fullCandidateCount = json.optInt("fullCandidateCount"),
                    fullSent = json.optBoolean("fullSent"),
                    lowVoltageSinceMs = json.optLongOrNull("lowVoltageSinceMs"),
                    lowVoltageSent = json.optBoolean("lowVoltageSent"),
                    lastSuccessfulPollAtMs = json.optLongOrNull("lastSuccessfulPollAtMs"),
                    telemetryExpectedSinceMs = json.optLongOrNull("telemetryExpectedSinceMs"),
                    telemetryOutageSent = json.optBoolean("telemetryOutageSent"),
                    tripId = json.optStringOrNull("tripId"),
                    tripPowerSessionId = json.optStringOrNull("tripPowerSessionId"),
                    tripStartedAtMs = json.optLongOrNull("tripStartedAtMs"),
                    tripStartOdometerKm = json.optDoubleOrNull("tripStartOdometerKm"),
                    tripStartSoc = json.optDoubleOrNull("tripStartSoc"),
                    tripStartEnergyKwh = json.optDoubleOrNull("tripStartEnergyKwh"),
                    tripParkedSinceMs = json.optLongOrNull("tripParkedSinceMs"),
                    tripEndOdometerKm = json.optDoubleOrNull("tripEndOdometerKm"),
                    tripEndSoc = json.optDoubleOrNull("tripEndSoc"),
                    tripEndEnergyKwh = json.optDoubleOrNull("tripEndEnergyKwh"),
                    tripAccumulatedEnergyKwh = json.optDoubleOrNull("tripAccumulatedEnergyKwh")
                        ?.takeIf { it.isFinite() && it >= 0.0 },
                    powerEnergyPoint = TelegramEnergyPoint.fromJsonOrNull(json.optJSONObject("powerEnergyPoint")),
                    tripStartEnergyPoint = TelegramEnergyPoint.fromJsonOrNull(json.optJSONObject("tripStartEnergyPoint")),
                    tripEndEnergyPoint = TelegramEnergyPoint.fromJsonOrNull(json.optJSONObject("tripEndEnergyPoint")),
                    pendingPowerOffLocationTripId = json.optStringOrNull("pendingPowerOffLocationTripId"),
                    pendingPowerOffLocationPowerSessionId =
                        json.optStringOrNull("pendingPowerOffLocationPowerSessionId"),
                    pendingPowerOffLocationSummaryDelivered =
                        json.optBoolean("pendingPowerOffLocationSummaryDelivered", false),
                    bootStartSoc = json.optDoubleOrNull("bootStartSoc"),
                    bootEndSoc = json.optDoubleOrNull("bootEndSoc"),
                    bootTotalDistanceKm = json.optDoubleOrNull("bootTotalDistanceKm")
                        ?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                    bootTotalEnergyKwh = json.optDoubleOrNull("bootTotalEnergyKwh")
                        ?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                    bootTotalDurationMs = (json.optLongOrNull("bootTotalDurationMs") ?: 0L).coerceAtLeast(0L),
                    lastTripEnergyCounterKwh = json.optDoubleOrNull("lastTripEnergyCounterKwh")
                        ?.takeIf { it.isFinite() && it >= 0.0 },
                    lastPersistedAtMs = json.optLong("lastPersistedAtMs")
                )
            }.getOrNull()
        }
    }
}

class TelegramEventEngine(initialState: TelegramEventState = TelegramEventState()) {
    private val resumePendingChargingTransition =
        initialState.chargingActiveCandidate != null &&
            initialState.chargingActiveCandidateCount > 0 &&
            initialState.chargingActiveCandidateSource != null
    private val recoverableFirstTripSoc = initialState.tripStartSoc
        ?.takeIf { initialState.tripId != null }
        ?.takeIf {
            initialState.bootTotalDistanceKm == 0.0 &&
                initialState.bootTotalEnergyKwh == 0.0 &&
                initialState.bootTotalDurationMs == 0L
        }
    var state: TelegramEventState = initialState.copy(
        charging = null,
        chargingCandidate = null,
        chargingCandidateCount = 0,
        chargingActive = initialState.chargingActive.takeIf { resumePendingChargingTransition },
        chargingActiveCandidate = initialState.chargingActiveCandidate.takeIf { resumePendingChargingTransition },
        chargingActiveCandidateCount = initialState.chargingActiveCandidateCount.takeIf { resumePendingChargingTransition } ?: 0,
        chargingActiveCandidateSource = initialState.chargingActiveCandidateSource.takeIf { resumePendingChargingTransition },
        chargingEvidenceSource = null,
        chargingLowPowerSinceMs = null,
        fullCandidateCount = 0,
        bootStartSoc = validSoc(initialState.bootStartSoc) ?: validSoc(recoverableFirstTripSoc),
        bootEndSoc = validSoc(initialState.bootEndSoc)
            ?: validSoc(initialState.tripEndSoc)
            ?: validSoc(recoverableFirstTripSoc)
    )
        private set

    fun reset(): TelegramEventState {
        state = TelegramEventState()
        return state
    }

    /** Binds a Trips power-session parent; persistence runs before assignment and may abort the bind. */
    fun bindTripPowerSession(
        legId: String,
        powerSessionId: String,
        persist: (TelegramEventState) -> Unit = {}
    ): TelegramEventState? {
        val normalizedLegId = legId.trim()
        val normalizedPowerSessionId = powerSessionId.trim()
        if (normalizedLegId.isEmpty() || normalizedPowerSessionId.isEmpty()) return null

        val next = when {
            state.tripId == normalizedLegId -> {
                if (!state.tripPowerSessionId.isNullOrBlank()) return null
                state.copy(tripPowerSessionId = normalizedPowerSessionId)
            }
            state.pendingPowerOffLocationTripId == normalizedLegId -> {
                if (!state.pendingPowerOffLocationPowerSessionId.isNullOrBlank()) return null
                state.copy(pendingPowerOffLocationPowerSessionId = normalizedPowerSessionId)
            }
            else -> return null
        }
        persist(next)
        state = next
        return next
    }

    /** Persists proof only after the matching delayed-P summary leaves the durable outbox. */
    fun markTripSummaryDelivered(dedupeKey: String, nowMs: Long): TelegramEventState? {
        val tripId = state.pendingPowerOffLocationTripId ?: return null
        if (dedupeKey != "$tripId:summary" || state.pendingPowerOffLocationSummaryDelivered) return null
        state = state.copy(
            pendingPowerOffLocationSummaryDelivered = true,
            lastPersistedAtMs = nowMs
        )
        return state
    }

    /** Finalizes a parked trip restored from durable state before fresh telemetry can replace it. */
    fun recoverPendingTrip(
        config: TelegramEventConfig,
        nowMs: Long,
        energySnapshot: EnergySnapshot? = null
    ): TelegramEventResult {
        val events = mutableListOf<TelegramDetectedEvent>()
        val original = state
        observeEnergySnapshot(energySnapshot, allowInactive = false)
        if (state.tripId != null && state.tripParkedSinceMs != null) {
            finalizePendingTrip(config, nowMs, events)
        }
        return persistedResult(events, nowMs, force = state != original || events.isNotEmpty(), config = config)
    }

    fun onSuccessfulPoll(
        observations: List<NormalizedObservation>,
        config: TelegramEventConfig,
        nowMs: Long,
        energySnapshot: EnergySnapshot? = null
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
        val rawGun = values.bool("charge_gun_connected_raw")
        val bmsState = values.text("charging_battery_device_state")
        val rawGear = values.text("gear_auto_mode_raw")
        val events = mutableListOf<TelegramDetectedEvent>()
        val original = state
        val currentEnergyPoint = observeEnergySnapshot(energySnapshot, allowInactive = false)
        val tripCounterResetObserved = observeTripEnergyCounter(tripEnergy)
        val firstPoll = !state.initialized
        updatePowerSessionSoc(soc)
        finalizePendingTrip(config, nowMs, events)

        if (firstPoll) {
            state = state.copy(
                initialized = true,
                chargeGunConnected = rawGun,
                gear = rawGear,
                awaitingInitialTripGear = rawGear == null && state.tripId == null,
                lastSuccessfulPollAtMs = nowMs,
                telemetryExpectedSinceMs = state.telemetryExpectedSinceMs ?: nowMs,
                telemetryOutageSent = false
            )
            if (state.tripId == null && rawGear != null && rawGear != PARK) {
                startTrip(nowMs, odometer, soc, tripEnergy, currentEnergyPoint)
            }
        } else {
            state = state.copy(
                lastSuccessfulPollAtMs = nowMs,
                telemetryExpectedSinceMs = state.telemetryExpectedSinceMs ?: nowMs,
                telemetryOutageSent = false
            )
            confirmChargeGun(rawGun, nowMs, soc, config, events)
            confirmGear(rawGear, nowMs, odometer, soc, tripEnergy, currentEnergyPoint, config)
        }
        updatePowerSessionSoc(soc)

        val bmsFinishHighPowerConflict = rawGun != false &&
            bmsState == BMS_FINISHED &&
            batteryChargePower?.let { it.isFinite() && it >= CHARGING_POWER_THRESHOLD_KW } == true
        state = state.copy(bmsFinishHighPowerConflictActive = bmsFinishHighPowerConflict)
        val evidence = chargingEvidence(rawGun, batteryChargePower, bmsState)
        val stopCharging = confirmChargingEvidence(
            evidence = evidence,
            nowMs = nowMs,
            soc = soc,
            remainingEnergy = remainingEnergy,
            batteryChargePower = batteryChargePower,
            config = config,
            events = events
        )
        val fullFinished = evidence.active != null && (state.chargingSessionId != null || stopCharging) &&
            evaluateChargingProgress(nowMs, soc, remainingEnergy, batteryChargePower, range, config, events)
        if (stopCharging && !fullFinished && state.chargingSessionId != null) {
            stopChargingSession(nowMs, soc, remainingEnergy, batteryChargePower, config, events)
        } else if (stopCharging && !fullFinished) {
            state = state.copy(fullSent = false)
        }
        evaluateLowVoltage(nowMs, auxVoltage, config, events)

        val powerEnergySessionChanged =
            state.powerEnergyPoint?.powerSessionId != original.powerEnergyPoint?.powerSessionId
        val changedBeyondHeartbeat = state.copy(
            lastSuccessfulPollAtMs = original.lastSuccessfulPollAtMs,
            lastTripEnergyCounterKwh = original.lastTripEnergyCounterKwh,
            powerEnergyPoint = original.powerEnergyPoint
        ) != original
        return persistedResult(
            events = events,
            nowMs = nowMs,
            force = firstPoll || tripCounterResetObserved || powerEnergySessionChanged || changedBeyondHeartbeat ||
                nowMs - state.lastPersistedAtMs >= STATE_HEARTBEAT_MS,
            config = config
        )
    }

    fun onTick(
        config: TelegramEventConfig,
        mainCollectionExpected: Boolean,
        lastError: String?,
        nowMs: Long,
        runtimeStartedAtMs: Long? = null
    ): TelegramEventResult {
        val events = mutableListOf<TelegramDetectedEvent>()
        val original = state
        finalizePendingTrip(config, nowMs, events)
        if (!mainCollectionExpected) {
            state = state.copy(telemetryExpectedSinceMs = null, telemetryOutageSent = false)
            return persistedResult(events, nowMs, force = state != original || events.isNotEmpty(), config = config)
        }
        val expectedSince = maxOf(
            state.telemetryExpectedSinceMs ?: nowMs,
            runtimeStartedAtMs ?: Long.MIN_VALUE
        )
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

    /** Finalizes the pending trip immediately after a confirmed vehicle power-off. */
    fun onPowerOffConfirmed(
        config: TelegramEventConfig,
        snapshot: TelegramPowerOffSnapshot = TelegramPowerOffSnapshot(),
        location: TelegramLocationSnapshot? = null,
        nowMs: Long
    ): TelegramEventResult {
        val events = mutableListOf<TelegramDetectedEvent>()
        val original = state
        val finalEnergyPoint = observeEnergySnapshot(snapshot.energySnapshot, allowInactive = true)
        val counterResetObserved = observeTripEnergyCounter(snapshot.tripEnergyKwh)
        val tripId = state.tripId
        if (tripId == null) {
            val locationEligibilityReason = appendPendingPowerOffLocation(config, location, events)
            clearPowerSessionTotals()
            return persistedResult(
                events,
                nowMs,
                force = counterResetObserved || state != original,
                config = config,
                locationEligibilityReason = locationEligibilityReason
            )
        }
        state = state.copy(
            tripEndOdometerKm = snapshot.odometerKm ?: state.tripEndOdometerKm,
            tripEndSoc = if (state.tripParkedSinceMs == null) {
                validSoc(snapshot.soc) ?: state.tripEndSoc
            } else {
                state.tripEndSoc ?: validSoc(snapshot.soc)
            },
            tripEndEnergyKwh = snapshot.tripEnergyKwh?.takeIf { it.isFinite() && it >= 0.0 }
                ?: state.tripEndEnergyKwh,
            bootEndSoc = validSoc(snapshot.soc) ?: state.bootEndSoc
        )
        if (state.tripParkedSinceMs == null && state.tripEndEnergyPoint == null) {
            state = state.copy(
                tripEndEnergyPoint = (finalEnergyPoint ?: state.powerEnergyPoint).compatibleWithActiveLeg()
            )
        }
        val distance = nonNegativeDelta(state.tripEndOdometerKm, state.tripStartOdometerKm)
            ?.takeIf { it <= MAX_TRIP_DISTANCE_KM }
        val energy = currentTripEnergy()
        val tripEnergyMetrics = energyLegMetrics(state.tripStartEnergyPoint, state.tripEndEnergyPoint)
        val totalEnergyMetrics = energyTotalMetrics(
            state.powerEnergyPoint,
            state.tripStartEnergyPoint?.powerSessionId ?: state.tripPowerSessionId
        )
        val durationMs = (nowMs - (state.tripStartedAtMs ?: nowMs)).coerceAtLeast(0L)
        val totalDistance = state.bootTotalDistanceKm + (distance ?: 0.0)
        val totalEnergy = state.bootTotalEnergyKwh + (energy ?: 0.0)
        val totalDurationMs = state.bootTotalDurationMs + durationMs
        val socDelta = nonNegativeMagnitude(state.tripEndSoc, state.tripStartSoc)
        val qualifies = socDelta?.let { meetsThreshold(it, MIN_TRIP_SOC_DELTA_PERCENT) } == true ||
            distance?.let { exceedsThreshold(it, MIN_TRIP_DISTANCE_KM) } == true ||
            energy?.let { exceedsThreshold(it, MIN_TRIP_ENERGY_KWH) } == true
        if (qualifies) {
            addIfEnabled(
                events,
                config,
                TelegramEventType.TRIP_SUMMARY,
                "$tripId:summary",
                tripSummaryVariables(
                    distance = distance,
                    energy = energy,
                    durationMs = durationMs,
                    totalDistance = totalDistance,
                    totalEnergy = totalEnergy,
                    totalDurationMs = totalDurationMs,
                    tripStartSoc = state.tripStartSoc,
                    tripEndSoc = state.tripEndSoc,
                    totalStartSoc = state.bootStartSoc,
                    totalEndSoc = state.bootEndSoc,
                    tripEnergyMetrics = tripEnergyMetrics,
                    totalEnergyMetrics = totalEnergyMetrics,
                    language = config.language,
                    nowMs = nowMs
                ),
                textSuffix = location
                    ?.takeIf(::isActualLocation)
                    ?.takeIf { config.sendLocation }
                    ?.let { formatLocation(it, config.navigatorMask) }
                    ?.takeIf(String::isNotEmpty),
                omitOverall = displayedTripTotalsMatch(
                    distance = distance,
                    energy = energy,
                    durationMs = durationMs,
                    totalDistance = totalDistance,
                    totalEnergy = totalEnergy,
                    totalDurationMs = totalDurationMs,
                    tripStartSoc = state.tripStartSoc,
                    tripEndSoc = state.tripEndSoc,
                    totalStartSoc = state.bootStartSoc,
                    totalEndSoc = state.bootEndSoc,
                    tripEnergyMetrics = tripEnergyMetrics,
                    totalEnergyMetrics = totalEnergyMetrics,
                    language = config.language
                )
            )
        }
        // A previous parked summary may still be waiting for its location when
        // a short replacement leg starts in the same power session. Finalize
        // the active leg first, then settle that older location obligation in
        // the same durable result so it cannot leak into the next session. A
        // qualifying final summary carries the current location itself, so the
        // older marker is cleared without a duplicate links-only event.
        val summaryCarriesLocation = qualifies &&
            TelegramEventType.TRIP_SUMMARY in config.enabledEvents &&
            config.sendLocation &&
            location?.takeIf(::isActualLocation)
                ?.let { selectedLocationLinks(it, config.navigatorMask).isNotEmpty() } == true
        val locationEligibilityReason = if (summaryCarriesLocation) {
            clearPendingPowerOffLocation()
            null
        } else {
            appendPendingPowerOffLocation(config, location, events)
        }
        clearTrip()
        clearPowerSessionTotals()
        return persistedResult(
            events,
            nowMs,
            force = true,
            config = config,
            locationEligibilityReason = locationEligibilityReason
        )
    }

    /** Enqueues and atomically clears the deferred location obligation. */
    private fun appendPendingPowerOffLocation(
        config: TelegramEventConfig,
        location: TelegramLocationSnapshot?,
        events: MutableList<TelegramDetectedEvent>
    ): String? {
        val pendingLocationTripId = state.pendingPowerOffLocationTripId ?: return null
        val locationEligibilityReason = when {
            TelegramEventType.TRIP_SUMMARY !in config.enabledEvents -> "summary_disabled"
            !config.sendLocation -> "disabled"
            TelegramNavigatorMask.sanitize(config.navigatorMask) == TelegramNavigatorMask.NONE -> "no_navigator"
            location == null -> "no_fix"
            !isActualLocation(location) -> "invalid_fix"
            selectedLocationLinks(location, config.navigatorMask).isEmpty() -> "no_links"
            state.pendingPowerOffLocationSummaryDelivered -> "ready"
            else -> "waiting_summary"
        }
        val locationLinks = location
            ?.takeIf(::isActualLocation)
            ?.let { formatLocationOnly(it, config.navigatorMask) }
            ?.takeIf(String::isNotEmpty)
            ?.takeIf { TelegramEventType.TRIP_SUMMARY in config.enabledEvents }
            ?.takeIf { config.sendLocation }
            ?.takeIf { TelegramNavigatorMask.sanitize(config.navigatorMask) != TelegramNavigatorMask.NONE }
        if (locationLinks != null) {
            addIfEnabled(
                events,
                config,
                TelegramEventType.TRIP_SUMMARY,
                "$pendingLocationTripId:location",
                emptyMap(),
                textSuffix = locationLinks,
                locationOnly = true,
                waitsForSummaryKey = pendingLocationTripId
                    .takeUnless { state.pendingPowerOffLocationSummaryDelivered }
                    ?.let { "$it:summary" }
            )
        }
        clearPendingPowerOffLocation()
        return locationEligibilityReason
    }

    private fun clearPendingPowerOffLocation() {
        if (state.pendingPowerOffLocationTripId == null) return
        state = state.copy(
            pendingPowerOffLocationTripId = null,
            pendingPowerOffLocationPowerSessionId = null,
            pendingPowerOffLocationSummaryDelivered = false
        )
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
            clearTentativeChargingBaseline()
            state = state.copy(
                chargingActiveCandidate = null,
                chargingActiveCandidateCount = 0,
                chargingActiveCandidateSource = null,
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
                chargingActiveCandidateSource = null,
                chargingEvidenceSource = evidence.source.key,
                chargingLowPowerSinceMs = lowPowerSince
            )
            if (nowMs - lowPowerSince < CHARGING_LOW_POWER_CONFIRM_MS) return false
            state = state.copy(chargingActive = false, chargingLowPowerSinceMs = null)
            return true
        }

        state = state.copy(chargingLowPowerSinceMs = null)
        if (evidence.active == state.chargingActive) {
            if (evidence.active == false) clearTentativeChargingBaseline()
            state = state.copy(
                chargingActiveCandidate = null,
                chargingActiveCandidateCount = 0,
                chargingActiveCandidateSource = null,
                chargingEvidenceSource = evidence.source.key
            )
            return false
        }
        if (
            evidence.active &&
            state.chargingSessionId == null &&
            (state.chargingActiveCandidate != true || state.chargingActiveCandidateSource != evidence.source.key)
        ) {
            val baselineSoc = soc?.takeIf { it.isFinite() && it >= 0.0 }
            val baselineEnergy = remainingEnergy?.takeIf { it.isFinite() && it >= 0.0 }
            state = state.copy(
                chargingStartedAtMs = nowMs,
                chargingStartSoc = baselineSoc,
                chargingStartEnergyKwh = baselineEnergy,
                chargingProgressBaselineSoc = baselineSoc,
                chargingProgressBaselineEnergyKwh = baselineEnergy,
                chargingStepStartedAtMs = nowMs
            )
        } else if (!evidence.active) {
            clearTentativeChargingBaseline()
        }
        val count = if (
            state.chargingActiveCandidate == evidence.active &&
            state.chargingActiveCandidateSource == evidence.source.key
        ) {
            state.chargingActiveCandidateCount + 1
        } else {
            1
        }
        state = state.copy(
            chargingActiveCandidate = evidence.active,
            chargingActiveCandidateCount = count,
            chargingActiveCandidateSource = evidence.source.key
        )
        if (count < CONFIRMATION_SAMPLES) return false
        val previous = state.chargingActive
        state = state.copy(
            chargingActive = evidence.active,
            chargingActiveCandidate = null,
            chargingActiveCandidateCount = 0,
            chargingActiveCandidateSource = null,
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
            } else if (state.chargingStartedAtMs == null) {
                // A legacy/persisted session without a timestamp is a cold attach: baseline only what was observed now.
                val baselineSoc = soc?.takeIf { it.isFinite() && it >= 0.0 }
                val baselineEnergy = remainingEnergy?.takeIf { it.isFinite() && it >= 0.0 }
                state = state.copy(
                    chargingStartedAtMs = nowMs,
                    chargingStartSoc = state.chargingStartSoc ?: baselineSoc,
                    chargingStartEnergyKwh = state.chargingStartEnergyKwh ?: baselineEnergy,
                    chargingProgressBaselineSoc = state.chargingProgressBaselineSoc ?: baselineSoc,
                    chargingProgressBaselineEnergyKwh = state.chargingProgressBaselineEnergyKwh ?: baselineEnergy,
                    chargingStepStartedAtMs = state.chargingStepStartedAtMs ?: nowMs
                )
            }
            if (previous == false) {
                addIfEnabled(
                    events, config, TelegramEventType.CHARGING_STARTED,
                    "${state.chargingSessionId}:started",
                    chargingVariables(nowMs, soc, remainingEnergy, batteryChargePower, config.language)
                )
            }
            return false
        }
        if (
            previous == null &&
            state.chargingSessionId != null &&
            evidence.source == ChargingEvidenceSource.BMS_FINISHED
        ) {
            return true
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
        energyPoint: TelegramEnergyPoint?,
        config: TelegramEventConfig
    ) {
        if (raw != null && raw != state.gear) {
            val count = if (state.gearCandidate == raw) state.gearCandidateCount + 1 else 1
            state = state.copy(gearCandidate = raw, gearCandidateCount = count)
            if (count >= CONFIRMATION_SAMPLES) {
                val previous = state.gear
                val initialGearRecovered = previous == null && state.awaitingInitialTripGear && state.tripId == null
                state = state.copy(
                    gear = raw, gearCandidate = null, gearCandidateCount = 0,
                    awaitingInitialTripGear = false
                )
                if (initialGearRecovered && raw != PARK) {
                    startTrip(nowMs, odometer, soc, tripEnergy, energyPoint)
                } else if (previous == PARK && raw != PARK) {
                    val parkedLongEnough = state.tripParkedSinceMs?.let {
                        nowMs - it >= config.tripEndDelayMs
                    } == true
                    if (state.tripId == null || parkedLongEnough) {
                        startTrip(nowMs, odometer, soc, tripEnergy, energyPoint)
                    } else {
                        clearPendingTrip()
                    }
                }
                if (raw == PARK && previous != PARK && state.tripId != null) {
                    state = state.copy(tripParkedSinceMs = nowMs)
                    updatePendingTripSnapshot(odometer, soc, tripEnergy, energyPoint)
                }
                if (raw != PARK && previous != PARK) clearPendingTrip()
            }
        } else {
            state = state.copy(gearCandidate = null, gearCandidateCount = 0)
        }
        if (
            state.gear == PARK &&
            state.tripParkedSinceMs != null &&
            state.tripId != null
        ) {
            updatePendingTripSnapshot(odometer, soc, tripEnergy, energyPoint)
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
    ): Boolean {
        val sessionId = state.chargingSessionId ?: return false
        val currentSoc = soc ?: return false
        val fullNow = currentSoc >= FULL_SOC_THRESHOLD
        val fullCount = if (fullNow) state.fullCandidateCount + 1 else 0
        state = state.copy(fullCandidateCount = fullCount)
        if (fullCount >= CONFIRMATION_SAMPLES) {
            if (!state.fullSent) {
                if (TelegramEventType.CHARGED_TO_100 in config.enabledEvents) {
                    addIfEnabled(
                        events, config, TelegramEventType.CHARGED_TO_100, "$sessionId:full",
                        chargingVariables(nowMs, soc, remainingEnergy, batteryChargePower, config.language) +
                            mapOf("remaining_energy_kwh" to formatNumber(remainingEnergy), "range_km" to formatNumber(range))
                    )
                } else {
                    val variables = chargingProgressVariables(nowMs, soc, remainingEnergy, batteryChargePower, config.language)
                    advanceChargingProgressBaseline(nowMs, soc, remainingEnergy)
                    addIfEnabled(
                        events, config, TelegramEventType.CHARGING_PROGRESS, "$sessionId:progress:100",
                        variables
                    )
                }
            }
            finishChargingSession(full = true)
            return true
        }
        val step = config.chargeStepPercent.coerceIn(1, 99)
        var threshold = (floor(currentSoc / step) * step).toInt()
        if (threshold >= 100 && TelegramEventType.CHARGED_TO_100 in config.enabledEvents) threshold = 100 - step
        val previousThreshold = state.lastProgressThreshold ?: return false
        if (threshold <= previousThreshold) return false
        state = state.copy(lastProgressThreshold = threshold)
        val variables = chargingProgressVariables(nowMs, soc, remainingEnergy, batteryChargePower, config.language)
        advanceChargingProgressBaseline(nowMs, soc, remainingEnergy)
        addIfEnabled(
            events, config, TelegramEventType.CHARGING_PROGRESS, "$sessionId:progress:$threshold",
            variables
        )
        return false
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
        bmsState: String?
    ): ChargingEvidence {
        if (chargeGunConnected == false) {
            return ChargingEvidence(false, ChargingEvidenceSource.PRIMARY_DISCONNECTED)
        }
        if (chargeGunConnected == true && bmsState == BMS_CHARGING) {
            return ChargingEvidence(true, ChargingEvidenceSource.BMS_CHARGING)
        }
        if (
            bmsState == BMS_FINISHED &&
            batteryChargePower?.let { it.isFinite() && it < CHARGING_POWER_THRESHOLD_KW } == true
        ) {
            return ChargingEvidence(false, ChargingEvidenceSource.BMS_FINISHED)
        }
        if (chargeGunConnected == true && batteryChargePower?.isFinite() == true) {
            return if (batteryChargePower >= CHARGING_POWER_THRESHOLD_KW) {
                ChargingEvidence(true, ChargingEvidenceSource.PRIMARY_POWER)
            } else {
                ChargingEvidence(false, ChargingEvidenceSource.PRIMARY_LOW_POWER)
            }
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
            chargingVariables(nowMs, soc, remainingEnergy, batteryChargePower, config.language)
        )
        finishChargingSession(full = false)
    }

    private fun finishChargingSession(full: Boolean) {
        state = state.copy(
            chargingSessionId = null,
            chargingStartedAtMs = null,
            chargingStartSoc = null,
            chargingStartEnergyKwh = null,
            chargingProgressBaselineSoc = null,
            chargingProgressBaselineEnergyKwh = null,
            chargingStepStartedAtMs = null,
            lastProgressThreshold = null,
            fullCandidateCount = 0,
            fullSent = full
        )
    }

    private fun finalizePendingTrip(
        config: TelegramEventConfig,
        nowMs: Long,
        events: MutableList<TelegramDetectedEvent>,
        ignoreDeadline: Boolean = false
    ) {
        val parkedSince = state.tripParkedSinceMs ?: return
        val tripId = state.tripId ?: return
        if (!ignoreDeadline && (state.gear != PARK || nowMs < parkedSince + config.tripEndDelayMs)) return
        val tripStartOdometer = state.tripStartOdometerKm
        val tripEndOdometer = state.tripEndOdometerKm
        val distance = if (tripEndOdometer != null && tripStartOdometer != null) {
            (tripEndOdometer - tripStartOdometer)
                .takeIf { it >= 0.0 && it <= MAX_TRIP_DISTANCE_KM }
        } else {
            null
        }
        val energy = currentTripEnergy()
        val tripEnergyMetrics = energyLegMetrics(state.tripStartEnergyPoint, state.tripEndEnergyPoint)
        val totalEnergyMetrics = energyTotalMetrics(
            state.powerEnergyPoint,
            state.tripStartEnergyPoint?.powerSessionId ?: state.tripPowerSessionId
        )
        val durationMs = parkedSince - (state.tripStartedAtMs ?: parkedSince)
        val totalDistance = state.bootTotalDistanceKm + (distance ?: 0.0)
        val totalEnergy = state.bootTotalEnergyKwh + (energy ?: 0.0)
        val totalDurationMs = state.bootTotalDurationMs + durationMs.coerceAtLeast(0L)
        state = state.copy(
            bootTotalDistanceKm = totalDistance,
            bootTotalEnergyKwh = totalEnergy,
            bootTotalDurationMs = totalDurationMs
        )
        val socDelta = nonNegativeMagnitude(state.tripEndSoc, state.tripStartSoc)
        if (socDelta?.let { meetsThreshold(it, MIN_TRIP_SOC_DELTA_PERCENT) } == true ||
            distance?.let { exceedsThreshold(it, MIN_TRIP_DISTANCE_KM) } == true ||
            energy?.let { exceedsThreshold(it, MIN_TRIP_ENERGY_KWH) } == true
        ) {
            addIfEnabled(
                events, config, TelegramEventType.TRIP_SUMMARY, "$tripId:summary",
                tripSummaryVariables(
                    distance = distance,
                    energy = energy,
                    durationMs = durationMs,
                    totalDistance = totalDistance,
                    totalEnergy = totalEnergy,
                    totalDurationMs = totalDurationMs,
                    tripStartSoc = state.tripStartSoc,
                    tripEndSoc = state.tripEndSoc,
                    totalStartSoc = state.bootStartSoc,
                    totalEndSoc = state.bootEndSoc,
                    tripEnergyMetrics = tripEnergyMetrics,
                    totalEnergyMetrics = totalEnergyMetrics,
                    language = config.language,
                    nowMs = parkedSince
                ),
                omitOverall = displayedTripTotalsMatch(
                    distance = distance,
                    energy = energy,
                    durationMs = durationMs,
                    totalDistance = totalDistance,
                    totalEnergy = totalEnergy,
                    totalDurationMs = totalDurationMs,
                    tripStartSoc = state.tripStartSoc,
                    tripEndSoc = state.tripEndSoc,
                    totalStartSoc = state.bootStartSoc,
                    totalEndSoc = state.bootEndSoc,
                    tripEnergyMetrics = tripEnergyMetrics,
                    totalEnergyMetrics = totalEnergyMetrics,
                    language = config.language
                )
            )
            if (TelegramEventType.TRIP_SUMMARY in config.enabledEvents) {
                state = state.copy(
                    pendingPowerOffLocationTripId = tripId,
                    pendingPowerOffLocationPowerSessionId = state.tripPowerSessionId,
                    pendingPowerOffLocationSummaryDelivered = false
                )
            }
        }
        clearTrip()
    }

    private fun updatePendingTripSnapshot(
        odometer: Double?,
        soc: Double?,
        tripEnergy: Double?,
        energyPoint: TelegramEnergyPoint?
    ) {
        state = state.copy(
            tripEndOdometerKm = odometer ?: state.tripEndOdometerKm,
            tripEndSoc = state.tripEndSoc ?: validSoc(soc),
            tripEndEnergyKwh = tripEnergy?.takeIf { it.isFinite() && it >= 0.0 }
                ?: state.tripEndEnergyKwh,
            tripEndEnergyPoint = state.tripEndEnergyPoint ?: energyPoint.compatibleWithActiveLeg()
        )
    }

    private fun updatePowerSessionSoc(soc: Double?) {
        if (state.tripId == null && state.bootStartSoc == null &&
            state.bootTotalDistanceKm == 0.0 && state.bootTotalEnergyKwh == 0.0 &&
            state.bootTotalDurationMs == 0L
        ) return
        val current = validSoc(soc) ?: return
        val canBackfillStart = state.tripId != null &&
            state.gear != PARK &&
            state.bootStartSoc == null &&
            state.bootTotalDistanceKm == 0.0 &&
            state.bootTotalEnergyKwh == 0.0 &&
            state.bootTotalDurationMs == 0L
        state = state.copy(
            bootStartSoc = if (canBackfillStart) current else state.bootStartSoc,
            bootEndSoc = current
        )
    }

    private fun clearPendingTrip() {
        state = state.copy(
            tripParkedSinceMs = null,
            tripEndOdometerKm = null,
            tripEndSoc = null,
            tripEndEnergyKwh = null,
            tripEndEnergyPoint = null
        )
    }

    private fun clearTrip() {
        state = state.copy(
            tripId = null,
            tripPowerSessionId = null,
            tripStartedAtMs = null,
            tripStartOdometerKm = null,
            tripStartSoc = null,
            tripStartEnergyKwh = null,
            tripParkedSinceMs = null,
            tripEndOdometerKm = null,
            tripEndSoc = null,
            tripEndEnergyKwh = null,
            tripAccumulatedEnergyKwh = null,
            tripStartEnergyPoint = null,
            tripEndEnergyPoint = null
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
        val startedAtMs = state.chargingStartedAtMs ?: nowMs
        val startSoc = state.chargingStartSoc ?: soc?.takeIf { it.isFinite() && it >= 0.0 }
        val startEnergy = state.chargingStartEnergyKwh ?: remainingEnergy?.takeIf { it.isFinite() && it >= 0.0 }
        state = state.copy(
            chargingSessionId = UUID.randomUUID().toString(),
            chargingStartedAtMs = startedAtMs,
            chargingStartSoc = startSoc,
            chargingStartEnergyKwh = startEnergy,
            chargingProgressBaselineSoc = state.chargingProgressBaselineSoc ?: startSoc,
            chargingProgressBaselineEnergyKwh = state.chargingProgressBaselineEnergyKwh ?: startEnergy,
            chargingStepStartedAtMs = state.chargingStepStartedAtMs ?: startedAtMs,
            lastProgressThreshold = soc?.let { (floor(it / step) * step).toInt() },
            fullCandidateCount = 0,
            fullSent = fullAlreadySent
        )
    }

    private fun clearTentativeChargingBaseline() {
        if (state.chargingSessionId != null) return
        state = state.copy(
            chargingStartedAtMs = null,
            chargingStartSoc = null,
            chargingStartEnergyKwh = null,
            chargingProgressBaselineSoc = null,
            chargingProgressBaselineEnergyKwh = null,
            chargingStepStartedAtMs = null
        )
    }

    private fun startTrip(
        nowMs: Long,
        odometer: Double?,
        soc: Double?,
        tripEnergy: Double?,
        energyPoint: TelegramEnergyPoint?
    ) {
        val startEnergy = tripEnergy?.takeIf { it.isFinite() && it >= 0.0 }
        val startSoc = validSoc(soc)
        val canAnchorPowerSession = state.bootTotalDistanceKm == 0.0 &&
            state.bootTotalEnergyKwh == 0.0 && state.bootTotalDurationMs == 0L
        state = state.copy(
            tripId = UUID.randomUUID().toString(),
            tripPowerSessionId = energyPoint?.powerSessionId,
            tripStartedAtMs = nowMs,
            tripStartOdometerKm = odometer,
            tripStartSoc = startSoc,
            tripStartEnergyKwh = startEnergy,
            tripParkedSinceMs = null,
            tripEndOdometerKm = null,
            tripEndSoc = null,
            tripEndEnergyKwh = null,
            tripStartEnergyPoint = energyPoint,
            tripEndEnergyPoint = null,
            bootStartSoc = state.bootStartSoc ?: startSoc?.takeIf { canAnchorPowerSession },
            bootEndSoc = startSoc ?: state.bootEndSoc,
            tripAccumulatedEnergyKwh = startEnergy?.let { 0.0 },
            lastTripEnergyCounterKwh = startEnergy ?: state.lastTripEnergyCounterKwh
        )
    }

    private fun chargingVariables(
        nowMs: Long,
        soc: Double?,
        remainingEnergy: Double?,
        batteryPower: Double?,
        language: TelegramTemplateLanguage
    ): Map<String, String> {
        val startSoc = state.chargingStartSoc
        val startEnergy = state.chargingStartEnergyKwh
        val addedPercent = nonNegativeDelta(soc, startSoc)
        val addedEnergy = nonNegativeDelta(remainingEnergy, startEnergy)
        return mapOf(
            "soc" to formatNumber(soc),
            "battery_power_kw" to formatNumber(batteryPower),
            "charge_added_percent" to formatNumber(addedPercent),
            "charge_added_kwh" to formatNumber(addedEnergy),
            "charge_duration" to formatChargeDuration(nowMs - (state.chargingStartedAtMs ?: nowMs), language),
            "charge_start_time" to formatLocalTime(state.chargingStartedAtMs),
            "charge_end_time" to formatLocalTime(nowMs),
            "charge_duration_hhmm" to formatChargeDurationHhmm(nowMs - (state.chargingStartedAtMs ?: nowMs)),
            "time" to formatTime(nowMs)
        )
    }

    private fun chargingProgressVariables(
        nowMs: Long,
        soc: Double?,
        remainingEnergy: Double?,
        batteryPower: Double?,
        language: TelegramTemplateLanguage
    ): Map<String, String> {
        return chargingVariables(nowMs, soc, remainingEnergy, batteryPower, language) + mapOf(
            "charge_step_added_percent" to formatNumber(
                nonNegativeDelta(soc, state.chargingProgressBaselineSoc)
            ),
            "charge_step_added_kwh" to formatNumber(
                nonNegativeDelta(remainingEnergy, state.chargingProgressBaselineEnergyKwh)
            ),
            "charge_step_duration" to formatChargeDuration(
                nowMs - (state.chargingStepStartedAtMs ?: state.chargingStartedAtMs ?: nowMs),
                language
            )
        )
    }

    private fun advanceChargingProgressBaseline(nowMs: Long, soc: Double?, remainingEnergy: Double?) {
        state = state.copy(
            chargingProgressBaselineSoc = soc?.takeIf { it.isFinite() && it >= 0.0 },
            chargingProgressBaselineEnergyKwh = remainingEnergy?.takeIf { it.isFinite() && it >= 0.0 },
            chargingStepStartedAtMs = nowMs
        )
    }

    private fun observeTripEnergyCounter(value: Double?): Boolean {
        val current = value?.takeIf { it.isFinite() && it >= 0.0 } ?: return false
        val previous = state.lastTripEnergyCounterKwh
            ?: state.tripEndEnergyKwh
            ?: state.tripStartEnergyKwh
        val accumulated = state.tripAccumulatedEnergyKwh
            ?: state.tripId?.let { nonNegativeDelta(previous, state.tripStartEnergyKwh) }
        val update = TripMetrics.advanceEnergyCounter(
            accumulatedKwh = accumulated,
            lastCounterKwh = previous,
            currentCounterKwh = current,
            resetToleranceKwh = TRIP_ENERGY_RESET_TOLERANCE_KWH
        )
        state = state.copy(
            tripAccumulatedEnergyKwh = update.accumulatedKwh.takeIf { state.tripId != null },
            lastTripEnergyCounterKwh = update.lastCounterKwh
        )
        return update.resetObserved
    }

    private fun currentTripEnergy(): Double? = state.tripAccumulatedEnergyKwh
        ?: nonNegativeDelta(state.tripEndEnergyKwh, state.tripStartEnergyKwh)

    private fun observeEnergySnapshot(
        snapshot: EnergySnapshot?,
        allowInactive: Boolean
    ): TelegramEnergyPoint? {
        val point = TelegramEnergyPoint.fromSnapshot(snapshot) ?: return null
        val activeLegSession = state.tripPowerSessionId
            ?: state.tripStartEnergyPoint?.powerSessionId
            ?: state.powerEnergyPoint?.powerSessionId
        val belongsToActiveLeg = state.tripId != null && activeLegSession == point.powerSessionId
        if (snapshot?.active == false && state.tripId != null && activeLegSession != null && !belongsToActiveLeg) {
            return null
        }
        if (!allowInactive && snapshot?.active != true && !belongsToActiveLeg) return null

        val previous = state.powerEnergyPoint
        if (previous != null && previous.powerSessionId == point.powerSessionId) {
            if (previous.snapshotId == point.snapshotId) return previous
            if (previous.sourceBootId == point.sourceBootId && point.sourceElapsedMs <= previous.sourceElapsedMs) {
                return null
            }
        }

        state = if (previous != null && previous.powerSessionId != point.powerSessionId && state.tripId != null) {
            state.copy(
                powerEnergyPoint = point,
                tripStartEnergyPoint = null,
                tripEndEnergyPoint = null
            )
        } else {
            state.copy(powerEnergyPoint = point)
        }
        return point
    }

    private fun TelegramEnergyPoint?.compatibleWithActiveLeg(): TelegramEnergyPoint? {
        val point = this ?: return null
        val expectedSession = state.tripStartEnergyPoint?.powerSessionId
            ?: state.tripPowerSessionId
            ?: return null
        return point.takeIf { it.powerSessionId == expectedSession }
    }

    private fun clearPowerSessionTotals() {
        state = state.copy(
            awaitingInitialTripGear = false,
            bootStartSoc = null,
            bootEndSoc = null,
            bootTotalDistanceKm = 0.0,
            bootTotalEnergyKwh = 0.0,
            bootTotalDurationMs = 0L,
            lastTripEnergyCounterKwh = null,
            powerEnergyPoint = null,
            tripStartEnergyPoint = null,
            tripEndEnergyPoint = null
        )
    }

    private fun addIfEnabled(
        events: MutableList<TelegramDetectedEvent>,
        config: TelegramEventConfig,
        type: TelegramEventType,
        dedupeKey: String,
        variables: Map<String, String>,
        textSuffix: String? = null,
        omitOverall: Boolean = false,
        locationOnly: Boolean = false,
        waitsForSummaryKey: String? = null
    ) {
        if (type in config.enabledEvents) {
            events += TelegramDetectedEvent(
                type = type,
                dedupeKey = dedupeKey,
                variables = variables,
                textSuffix = textSuffix,
                omitOverall = omitOverall,
                locationOnly = locationOnly,
                waitsForSummaryKey = waitsForSummaryKey
            )
        }
    }

    private fun persistedResult(
        events: List<TelegramDetectedEvent>,
        nowMs: Long,
        force: Boolean,
        config: TelegramEventConfig,
        locationEligibilityReason: String? = null
    ): TelegramEventResult {
        val persist = force || events.isNotEmpty()
        if (persist) state = state.copy(lastPersistedAtMs = nowMs)
        return TelegramEventResult(
            state = state,
            events = events,
            shouldPersist = persist,
            nextWakeAtMs = pendingTripDeadline(config),
            locationEligibilityReason = locationEligibilityReason
        )
    }

    private fun pendingTripDeadline(config: TelegramEventConfig): Long? {
        if (state.tripId == null || state.gear != PARK) return null
        return state.tripParkedSinceMs?.plus(config.tripEndDelayMs)
    }

    private fun Map<String, NormalizedObservation>.number(key: String): Double? = get(key)?.value?.number
    private fun Map<String, NormalizedObservation>.text(key: String): String? = get(key)?.value?.text
    private fun Map<String, NormalizedObservation>.bool(key: String): Boolean? = get(key)?.value?.bool

    companion object {
        private const val PARK = "P"
        private const val CONFIRMATION_SAMPLES = 2
        private const val FULL_SOC_THRESHOLD = 99.5
        private const val CHARGING_POWER_THRESHOLD_KW = 0.5
        private const val CHARGING_LOW_POWER_CONFIRM_MS = 60_000L
        private const val BMS_CHARGING = "charging"
        private const val BMS_FINISHED = "finished"
        private const val LOW_VOLTAGE_CONFIRM_MS = 60_000L
        private const val LOW_VOLTAGE_HYSTERESIS = 0.3
        private const val STATE_HEARTBEAT_MS = 30_000L
        private const val MAX_TRIP_DISTANCE_KM = 2_000.0
        private const val MIN_TRIP_SOC_DELTA_PERCENT = 1.0
        private const val MIN_TRIP_DISTANCE_KM = 0.1
        private const val MIN_TRIP_ENERGY_KWH = 0.1
        private const val TRIP_ENERGY_RESET_TOLERANCE_KWH = 0.1
    }

    private data class ChargingEvidence(
        val active: Boolean?,
        val source: ChargingEvidenceSource
    )

    private enum class ChargingEvidenceSource(val key: String) {
        BMS_CHARGING("bms_charging"),
        BMS_FINISHED("bms_finished"),
        PRIMARY_POWER("primary_power"),
        PRIMARY_LOW_POWER("primary_low_power"),
        PRIMARY_DISCONNECTED("primary_disconnected"),
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
    if (value == null || !value.isFinite()) return "n/a"
    val rounded = (value * 100.0).roundToInt() / 100.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

private fun nonNegativeDelta(end: Double?, start: Double?): Double? {
    if (end == null || start == null || !end.isFinite() || !start.isFinite() || end < 0.0 || start < 0.0) return null
    return (end - start).takeIf { it >= 0.0 }
}

private fun nonNegativeMagnitude(end: Double?, start: Double?): Double? {
    if (end == null || start == null || !end.isFinite() || !start.isFinite()) return null
    return abs(end - start)
}

private fun meetsThreshold(value: Double, threshold: Double): Boolean =
    value + THRESHOLD_EPSILON >= threshold

private fun exceedsThreshold(value: Double, threshold: Double): Boolean =
    value - threshold > THRESHOLD_EPSILON

private const val THRESHOLD_EPSILON = 1e-9

private fun formatDuration(durationMs: Long): String {
    val totalMinutes = (durationMs.coerceAtLeast(0L) / 60_000L)
    return "%d:%02d".format(totalMinutes / 60L, totalMinutes % 60L)
}

private fun formatChargeDuration(durationMs: Long, language: TelegramTemplateLanguage): String {
    val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when (language) {
        TelegramTemplateLanguage.UK -> buildString {
            if (hours > 0L) append("${hours}год")
            if (hours > 0L && minutes > 0L) append(' ')
            if (minutes > 0L || hours == 0L) append("${minutes}хв")
        }
        TelegramTemplateLanguage.EN -> buildString {
            if (hours > 0L) append("${hours}h")
            if (hours > 0L && minutes > 0L) append(' ')
            if (minutes > 0L || hours == 0L) append("${minutes}m")
        }
    }
}

private fun formatChargeDurationHhmm(durationMs: Long): String {
    val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
    return "%02d:%02d".format(totalMinutes / 60L, totalMinutes % 60L)
}

private fun formatLocalTime(timeMs: Long?): String {
    return timeMs?.let {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
    } ?: "n/a"
}

private fun formatTime(timeMs: Long): String {
    return java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timeMs))
}

private fun tripSummaryVariables(
    distance: Double?,
    energy: Double?,
    durationMs: Long,
    totalDistance: Double,
    totalEnergy: Double,
    totalDurationMs: Long,
    tripStartSoc: Double?,
    tripEndSoc: Double?,
    totalStartSoc: Double?,
    totalEndSoc: Double?,
    tripEnergyMetrics: TelegramEnergyMetrics,
    totalEnergyMetrics: TelegramEnergyMetrics,
    language: TelegramTemplateLanguage,
    nowMs: Long
): Map<String, String> = mapOf(
    "trip_distance_km" to formatNumber(distance),
    "trip_energy_kwh" to formatNumber(energy),
    "trip_avg_kwh_per_100km" to formatNumber(TripMetrics.averageConsumptionKwhPer100Km(energy, distance)),
    "trip_duration" to formatDuration(durationMs),
    "soc_start" to formatNumber(tripStartSoc),
    "soc_end" to formatNumber(tripEndSoc),
    "total_soc_start" to formatNumber(totalStartSoc),
    "total_soc_end" to formatNumber(totalEndSoc),
    "total_distance_km" to formatNumber(totalDistance),
    "total_energy_kwh" to formatNumber(totalEnergy),
    "total_avg_kwh_per_100km" to formatNumber(
        TripMetrics.averageConsumptionKwhPer100Km(totalEnergy, totalDistance)
    ),
    "total_duration" to formatDuration(totalDurationMs),
    "trip_discharged_kwh" to formatNumber(tripEnergyMetrics.dischargedKwh),
    "trip_regenerated_kwh" to formatNumber(tripEnergyMetrics.regeneratedKwh),
    "trip_net_kwh" to formatNumber(tripEnergyMetrics.netKwh),
    "trip_net_kwh_per_100km" to formatNumber(
        TripMetrics.averageConsumptionKwhPer100Km(tripEnergyMetrics.netKwh, distance)
    ),
    "total_discharged_kwh" to formatNumber(totalEnergyMetrics.dischargedKwh),
    "total_regenerated_kwh" to formatNumber(totalEnergyMetrics.regeneratedKwh),
    "total_net_kwh" to formatNumber(totalEnergyMetrics.netKwh),
    "total_net_kwh_per_100km" to formatNumber(
        TripMetrics.averageConsumptionKwhPer100Km(totalEnergyMetrics.netKwh, totalDistance)
    ),
    "time" to formatTime(nowMs)
)

private fun displayedTripTotalsMatch(
    distance: Double?,
    energy: Double?,
    durationMs: Long,
    totalDistance: Double,
    totalEnergy: Double,
    totalDurationMs: Long,
    tripStartSoc: Double?,
    tripEndSoc: Double?,
    totalStartSoc: Double?,
    totalEndSoc: Double?,
    tripEnergyMetrics: TelegramEnergyMetrics,
    totalEnergyMetrics: TelegramEnergyMetrics,
    language: TelegramTemplateLanguage
): Boolean {
    val newEnergyAvailable = tripEnergyMetrics.netKwh != null || totalEnergyMetrics.netKwh != null
    val displayedEnergyMatches = if (newEnergyAvailable) {
        formatNumber(tripEnergyMetrics.dischargedKwh) ==
            formatNumber(totalEnergyMetrics.dischargedKwh) &&
            formatNumber(tripEnergyMetrics.regeneratedKwh) ==
            formatNumber(totalEnergyMetrics.regeneratedKwh) &&
            formatNumber(tripEnergyMetrics.netKwh) ==
            formatNumber(totalEnergyMetrics.netKwh) &&
            formatNumber(
                TripMetrics.averageConsumptionKwhPer100Km(tripEnergyMetrics.netKwh, distance)
            ) == formatNumber(
                TripMetrics.averageConsumptionKwhPer100Km(totalEnergyMetrics.netKwh, totalDistance)
            )
    } else {
        formatNumber(energy) == formatNumber(totalEnergy)
    }
    return formatNumber(distance) == formatNumber(totalDistance) &&
        displayedEnergyMatches &&
        formatDuration(durationMs) == formatDuration(totalDurationMs) &&
        formatNumber(tripStartSoc) == formatNumber(totalStartSoc) &&
        formatNumber(tripEndSoc) == formatNumber(totalEndSoc)
}

private data class TelegramEnergyMetrics(
    val dischargedKwh: Double? = null,
    val regeneratedKwh: Double? = null,
    val netKwh: Double? = null,
    val partial: Boolean = false
)

private fun energyTotalMetrics(
    point: TelegramEnergyPoint?,
    expectedPowerSessionId: String?
): TelegramEnergyMetrics {
    point ?: return TelegramEnergyMetrics()
    if (expectedPowerSessionId != null && point.powerSessionId != expectedPowerSessionId) {
        return TelegramEnergyMetrics()
    }
    val discharged = point.dischargedKwh ?: return TelegramEnergyMetrics()
    val regenerated = point.regeneratedKwh ?: return TelegramEnergyMetrics()
    return TelegramEnergyMetrics(
        dischargedKwh = discharged,
        regeneratedKwh = regenerated,
        netKwh = discharged - regenerated,
        partial = point.energyPartial
    )
}

private fun energyLegMetrics(
    start: TelegramEnergyPoint?,
    end: TelegramEnergyPoint?
): TelegramEnergyMetrics {
    if (start == null || end == null || start.powerSessionId != end.powerSessionId) {
        return TelegramEnergyMetrics()
    }
    val discharged = nonNegativeEnergyDelta(end.baselineDischargedKwh(), start.baselineDischargedKwh())
        ?: return TelegramEnergyMetrics()
    val regenerated = nonNegativeEnergyDelta(end.baselineRegeneratedKwh(), start.baselineRegeneratedKwh())
        ?: return TelegramEnergyMetrics()
    val elapsedDelta = if (start.sourceBootId == end.sourceBootId) {
        end.sourceElapsedMs - start.sourceElapsedMs
    } else {
        -1L
    }
    val coveredDelta = end.energyCoveredMs - start.energyCoveredMs
    val uncoveredDelta = end.energyUncoveredMs - start.energyUncoveredMs
    val complete = elapsedDelta >= 0L && coveredDelta == elapsedDelta && uncoveredDelta == 0L
    return TelegramEnergyMetrics(
        dischargedKwh = discharged,
        regeneratedKwh = regenerated,
        netKwh = discharged - regenerated,
        partial = !complete
    )
}

private fun nonNegativeEnergyDelta(end: Double?, start: Double?): Double? {
    if (end == null || start == null || !end.isFinite() || !start.isFinite()) return null
    val delta = end - start
    return when {
        delta >= 0.0 -> delta
        delta >= -ENERGY_COUNTER_EPSILON_KWH -> 0.0
        else -> null
    }
}

private const val ENERGY_COUNTER_EPSILON_KWH = 1e-9

private fun validSoc(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it in 0.0..100.0 }

private fun isActualLocation(location: TelegramLocationSnapshot): Boolean {
    return location.latitude.isFinite() && location.longitude.isFinite() &&
        location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0 &&
        location.ageSeconds >= 0L
}

private fun formatLocation(
    location: TelegramLocationSnapshot,
    navigatorMask: Int
): String {
    return selectedLocationLinks(location, navigatorMask).takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "\n", prefix = "\n")
        .orEmpty()
}

private fun formatLocationOnly(
    location: TelegramLocationSnapshot,
    navigatorMask: Int
): String = selectedLocationLinks(location, navigatorMask).joinToString("\n")

private fun selectedLocationLinks(
    location: TelegramLocationSnapshot,
    navigatorMask: Int
): List<String> {
    val mask = TelegramNavigatorMask.sanitize(navigatorMask)
    return buildList {
        if (mask and TelegramNavigatorMask.GOOGLE != 0 && location.googleUrl.isNotBlank()) {
            add("Google: ${location.googleUrl}")
        }
        if (mask and TelegramNavigatorMask.WAZE != 0 && location.wazeUrl.isNotBlank()) {
            add("Waze: ${location.wazeUrl}")
        }
        if (mask and TelegramNavigatorMask.APPLE != 0 && location.appleUrl.isNotBlank()) {
            add("Apple: ${location.appleUrl}")
        }
        if (mask and TelegramNavigatorMask.OSM != 0 && location.osmUrl.isNotBlank()) {
            add("OSM: ${location.osmUrl}")
        }
    }
}
