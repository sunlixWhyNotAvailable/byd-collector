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
    val shouldPersist: Boolean
)

data class TelegramEventState(
    val initialized: Boolean = false,
    val charging: String? = null,
    val chargingCandidate: String? = null,
    val chargingCandidateCount: Int = 0,
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
    val lastPersistedAtMs: Long = 0L
) {
    fun toJson(): String = JSONObject().apply {
        put("initialized", initialized)
        putNullable("charging", charging)
        putNullable("chargingCandidate", chargingCandidate)
        put("chargingCandidateCount", chargingCandidateCount)
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
        put("lastPersistedAtMs", lastPersistedAtMs)
    }.toString()

    companion object {
        fun fromJson(value: String?): TelegramEventState {
            if (value.isNullOrBlank()) return TelegramEventState()
            return runCatching {
                val json = JSONObject(value)
                TelegramEventState(
                    initialized = json.optBoolean("initialized", false),
                    charging = json.optStringOrNull("charging"),
                    chargingCandidate = json.optStringOrNull("chargingCandidate"),
                    chargingCandidateCount = json.optInt("chargingCandidateCount"),
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
                    lastPersistedAtMs = json.optLong("lastPersistedAtMs")
                )
            }.getOrDefault(TelegramEventState())
        }
    }
}

class TelegramEventEngine(initialState: TelegramEventState = TelegramEventState()) {
    var state: TelegramEventState = initialState
        private set

    fun reset(): TelegramEventState {
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
        val batteryPower = values.number("battery_power_kw")
        val auxVoltage = values.number("aux_voltage_v")
        val odometer = values.number("odometer_km")
        val tripEnergy = values.number("trip_energy_kwh")
        val range = values.number("remaining_range_km")
        val rawCharging = values.text("charging_state")
        val rawGun = values.bool("charge_gun_connected_raw")
        val rawGear = values.text("gear_auto_mode_raw")
        val events = mutableListOf<TelegramDetectedEvent>()
        val original = state

        if (!state.initialized) {
            state = state.copy(
                initialized = true,
                charging = rawCharging,
                chargeGunConnected = rawGun,
                gear = rawGear,
                lastSuccessfulPollAtMs = nowMs,
                telemetryExpectedSinceMs = state.telemetryExpectedSinceMs ?: nowMs,
                telemetryOutageSent = false
            )
            if (rawCharging == CHARGING) startChargingSession(nowMs, soc, remainingEnergy, config.chargeStepPercent)
            if (rawGear != null && rawGear != PARK) startTrip(nowMs, odometer, soc)
            return persistedResult(events, nowMs, force = true)
        }

        state = state.copy(
            lastSuccessfulPollAtMs = nowMs,
            telemetryExpectedSinceMs = state.telemetryExpectedSinceMs ?: nowMs,
            telemetryOutageSent = false
        )
        confirmCharging(rawCharging, nowMs, soc, remainingEnergy, batteryPower, config, events)
        confirmChargeGun(rawGun, nowMs, soc, config, events)
        confirmGear(rawGear, nowMs, odometer, soc, tripEnergy, config, events)
        evaluateChargingProgress(nowMs, soc, remainingEnergy, batteryPower, range, config, events)
        evaluateLowVoltage(nowMs, auxVoltage, config, events)

        val changedBeyondHeartbeat = state.copy(lastSuccessfulPollAtMs = original.lastSuccessfulPollAtMs) != original
        return persistedResult(
            events = events,
            nowMs = nowMs,
            force = changedBeyondHeartbeat || nowMs - state.lastPersistedAtMs >= STATE_HEARTBEAT_MS
        )
    }

    fun onTick(config: TelegramEventConfig, mainCollectionExpected: Boolean, lastError: String?, nowMs: Long): TelegramEventResult {
        val events = mutableListOf<TelegramDetectedEvent>()
        val original = state
        if (!mainCollectionExpected) {
            state = state.copy(telemetryExpectedSinceMs = null, telemetryOutageSent = false)
            return persistedResult(events, nowMs, force = state != original)
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
        return persistedResult(events, nowMs, force = state != original || events.isNotEmpty())
    }

    private fun confirmCharging(
        raw: String?,
        nowMs: Long,
        soc: Double?,
        remainingEnergy: Double?,
        batteryPower: Double?,
        config: TelegramEventConfig,
        events: MutableList<TelegramDetectedEvent>
    ) {
        if (raw == null || raw == state.charging) {
            state = state.copy(chargingCandidate = null, chargingCandidateCount = 0)
            return
        }
        val count = if (state.chargingCandidate == raw) state.chargingCandidateCount + 1 else 1
        state = state.copy(chargingCandidate = raw, chargingCandidateCount = count)
        if (count < CONFIRMATION_SAMPLES) return
        val previous = state.charging
        state = state.copy(charging = raw, chargingCandidate = null, chargingCandidateCount = 0)
        if (raw == CHARGING && previous != CHARGING) {
            startChargingSession(nowMs, soc, remainingEnergy, config.chargeStepPercent)
            addIfEnabled(
                events, config, TelegramEventType.CHARGING_STARTED,
                "${state.chargingSessionId}:started",
                chargingVariables(nowMs, soc, remainingEnergy, batteryPower)
            )
        } else if (previous == CHARGING && raw != CHARGING && state.chargingSessionId != null) {
            val sessionId = state.chargingSessionId.orEmpty()
            addIfEnabled(
                events, config, TelegramEventType.CHARGING_STOPPED,
                "$sessionId:stopped",
                chargingVariables(nowMs, soc, remainingEnergy, batteryPower)
            )
            state = state.copy(
                chargingSessionId = null,
                chargingStartedAtMs = null,
                chargingStartSoc = null,
                chargingStartEnergyKwh = null,
                lastProgressThreshold = null,
                fullCandidateCount = 0,
                fullSent = false
            )
        }
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
        config: TelegramEventConfig,
        events: MutableList<TelegramDetectedEvent>
    ) {
        if (raw != null && raw != state.gear) {
            val count = if (state.gearCandidate == raw) state.gearCandidateCount + 1 else 1
            state = state.copy(gearCandidate = raw, gearCandidateCount = count)
            if (count >= CONFIRMATION_SAMPLES) {
                val previous = state.gear
                state = state.copy(gear = raw, gearCandidate = null, gearCandidateCount = 0)
                if (previous == PARK && raw != PARK && state.tripId == null) startTrip(nowMs, odometer, soc)
                if (raw == PARK && state.tripId != null) state = state.copy(tripParkedSinceMs = nowMs)
                if (raw != PARK) state = state.copy(tripParkedSinceMs = null)
            }
        } else {
            state = state.copy(gearCandidate = null, gearCandidateCount = 0)
        }
        val parkedSince = state.tripParkedSinceMs ?: return
        val tripId = state.tripId ?: return
        if (state.gear != PARK || nowMs - parkedSince < config.tripEndDelayMs) return
        val tripStartOdometer = state.tripStartOdometerKm
        val distance = if (odometer != null && tripStartOdometer != null) {
            (odometer - tripStartOdometer).takeIf { it >= 0.0 && it <= MAX_TRIP_DISTANCE_KM }
        } else null
        addIfEnabled(
            events, config, TelegramEventType.TRIP_SUMMARY, "$tripId:summary",
            mapOf(
                "trip_distance_km" to formatNumber(distance),
                "trip_energy_kwh" to formatNumber(tripEnergy),
                "trip_duration" to formatDuration(nowMs - (state.tripStartedAtMs ?: nowMs)),
                "soc_start" to formatNumber(state.tripStartSoc),
                "soc_end" to formatNumber(soc),
                "time" to formatTime(nowMs)
            )
        )
        state = state.copy(
            tripId = null,
            tripStartedAtMs = null,
            tripStartOdometerKm = null,
            tripStartSoc = null,
            tripParkedSinceMs = null
        )
    }

    private fun evaluateChargingProgress(
        nowMs: Long,
        soc: Double?,
        remainingEnergy: Double?,
        batteryPower: Double?,
        range: Double?,
        config: TelegramEventConfig,
        events: MutableList<TelegramDetectedEvent>
    ) {
        val sessionId = state.chargingSessionId ?: return
        val currentSoc = soc ?: return
        val fullNow = currentSoc >= FULL_SOC_THRESHOLD
        val fullCount = if (fullNow) state.fullCandidateCount + 1 else 0
        state = state.copy(fullCandidateCount = fullCount)
        if (fullCount >= CONFIRMATION_SAMPLES && !state.fullSent) {
            addIfEnabled(
                events, config, TelegramEventType.CHARGED_TO_100, "$sessionId:full",
                chargingVariables(nowMs, soc, remainingEnergy, batteryPower) +
                    mapOf("remaining_energy_kwh" to formatNumber(remainingEnergy), "range_km" to formatNumber(range))
            )
            state = state.copy(fullSent = true)
        }
        val step = config.chargeStepPercent.coerceIn(1, 99)
        var threshold = if (fullCount >= CONFIRMATION_SAMPLES && TelegramEventType.CHARGED_TO_100 !in config.enabledEvents) {
            100
        } else {
            (floor(currentSoc / step) * step).toInt()
        }
        if (threshold >= 100 && TelegramEventType.CHARGED_TO_100 in config.enabledEvents) threshold = 100 - step
        val previousThreshold = state.lastProgressThreshold ?: return
        if (threshold <= previousThreshold) return
        state = state.copy(lastProgressThreshold = threshold)
        addIfEnabled(
            events, config, TelegramEventType.CHARGING_PROGRESS, "$sessionId:progress:$threshold",
            chargingVariables(nowMs, soc, remainingEnergy, batteryPower)
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

    private fun startChargingSession(nowMs: Long, soc: Double?, remainingEnergy: Double?, chargeStepPercent: Int) {
        val step = chargeStepPercent.coerceIn(1, 99)
        state = state.copy(
            chargingSessionId = UUID.randomUUID().toString(),
            chargingStartedAtMs = nowMs,
            chargingStartSoc = soc,
            chargingStartEnergyKwh = remainingEnergy,
            lastProgressThreshold = soc?.let { (floor(it / step) * step).toInt() },
            fullCandidateCount = 0,
            fullSent = false
        )
    }

    private fun startTrip(nowMs: Long, odometer: Double?, soc: Double?) {
        state = state.copy(
            tripId = UUID.randomUUID().toString(),
            tripStartedAtMs = nowMs,
            tripStartOdometerKm = odometer,
            tripStartSoc = soc,
            tripParkedSinceMs = null
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

    private fun persistedResult(events: List<TelegramDetectedEvent>, nowMs: Long, force: Boolean): TelegramEventResult {
        val persist = force || events.isNotEmpty()
        if (persist) state = state.copy(lastPersistedAtMs = nowMs)
        return TelegramEventResult(state, events, persist)
    }

    private fun Map<String, NormalizedObservation>.number(key: String): Double? = get(key)?.value?.number
    private fun Map<String, NormalizedObservation>.text(key: String): String? = get(key)?.value?.text
    private fun Map<String, NormalizedObservation>.bool(key: String): Boolean? = get(key)?.value?.bool

    companion object {
        private const val CHARGING = "charging"
        private const val PARK = "P"
        private const val CONFIRMATION_SAMPLES = 2
        private const val FULL_SOC_THRESHOLD = 99.5
        private const val LOW_VOLTAGE_CONFIRM_MS = 60_000L
        private const val LOW_VOLTAGE_HYSTERESIS = 0.3
        private const val STATE_HEARTBEAT_MS = 30_000L
        private const val MAX_TRIP_DISTANCE_KM = 2_000.0
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
