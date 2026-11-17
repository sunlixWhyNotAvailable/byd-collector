package com.bydcollector.collector.service

import com.bydcollector.collector.data.input.BusinessInputDelta
import com.bydcollector.collector.data.input.BusinessInputKind
import com.bydcollector.collector.data.input.BusinessInputProjection
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.normalized.normalizedPollSourceStamp
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.data.energy.EnergySnapshot
import com.bydcollector.collector.data.trips.TripTime

/** Replays retained inputs into a detached detector; never creates an outbox or advances a receipt. */
internal object TelegramHistoryRepair {
    data class Result(val state: TelegramEventState, val reason: String, val inputs: Int, val restoredKeys: Int)

    fun recover(current: TelegramEventState, config: TelegramEventConfig, normalizer: VehicleStateNormalizer,
        activeGroup: Boolean, history: Sequence<BusinessInputDelta>, energy: EnergySnapshot? = null): Result {
        val boot = current.detectorTiming.bootId ?: energy?.takeIf { it.active }?.sourceBootId
            ?: return Result(current.copy(initialSeedRecoveryVersion = 2), "no_source_boot", 0, 0)
        val through = if (current.detectorTiming.bootId == boot) current.detectorTiming.evaluatedElapsedMs
            else requireNotNull(energy).sourceElapsedMs
        val start = current.bootTotalStartedAtMs.takeIf { activeGroup }
        val silent = config.copy(enabledEvents = emptySet())
        var before = emptyMap<String, TelegramRawSourceCursor>()
        var restored = current.rawSourceInputs
        var replay: TelegramEventEngine? = null
        val power = energy?.takeIf { it.active && it.sourceBootId == boot &&
            (current.powerSession == null || current.powerSession.id == it.powerSessionId) &&
            (current.powerEnergyPoint == null || current.powerEnergyPoint.powerSessionId == it.powerSessionId) }
        val powerStart = power?.startedAt?.let { TripTime.instant(it)?.toEpochMilli() }
        var powerPrefix = emptyMap<String, TelegramRawSourceCursor>()
        var powerReplay: TelegramEventEngine? = null
        var count = 0
        history.forEach { delta ->
            val ref = delta.ref
            if (ref.bootId != boot || ref.acquiredElapsedMs == null || ref.acquiredWallMs == null ||
                ref.acquiredElapsedMs > through) return@forEach
            count++
            val source = BusinessInputProjection.source(ref)
            val timestamp = BusinessInputProjection.timestamp(ref)
            val stamp = normalizedPollSourceStamp(source, timestamp)
            val matchingOn = powerStart == stamp.wallMs && ref.ok && delta.readings.any {
                it.rawKey == "bodywork_power_level" &&
                    it.descValue?.trim()?.toIntOrNull()?.let { level -> level > 0 } == true
            }
            if (powerReplay == null && matchingOn) {
                powerReplay = TelegramEventEngine(TelegramEventState(rawSourceInputs = powerPrefix))
            }
            powerReplay?.let { detector ->
                if (ref.kind == BusinessInputKind.TIMER) {
                    detector.onDetectorTimer(silent, stamp.wallMs, boot, stamp.elapsedMs, normalizer)
                } else {
                    detector.onSourcePoll(ref.pollId ?: 0L, timestamp, source, delta.readings, PollOrigin.REPLAY,
                        boot, silent, stamp.wallMs, normalizer, nowElapsedMs = stamp.elapsedMs,
                        continuityUpdates = delta.continuityUpdates,
                        startedPowerSessionId = power?.powerSessionId?.takeIf { matchingOn })
                }
            }
            if (powerReplay == null && ref.kind != BusinessInputKind.TIMER) {
                powerPrefix = TelegramOrderedInput.merge(powerPrefix, delta.readings, stamp,
                    ref.pollId ?: 0L, boot, delta.continuityUpdates).cursors
            }
            if (ref.kind != BusinessInputKind.TIMER) {
                restored = TelegramOrderedInput.merge(restored, delta.readings, stamp,
                    ref.pollId ?: 0L, boot, delta.continuityUpdates).cursors
            }
            if (start == null) return@forEach
            if (ref.acquiredWallMs < start) {
                if (ref.kind != BusinessInputKind.TIMER) before = TelegramOrderedInput.merge(before,
                    delta.readings, stamp, ref.pollId ?: 0L, boot, delta.continuityUpdates).cursors
                return@forEach
            }
            val engine = replay ?: TelegramEventEngine(TelegramEventState(rawSourceInputs = before)).also { replay = it }
            if (ref.kind == BusinessInputKind.TIMER) {
                engine.onDetectorTimer(silent, ref.acquiredWallMs, boot, ref.acquiredElapsedMs, normalizer)
            } else {
                engine.onSourcePoll(ref.pollId ?: 0L, timestamp, source, delta.readings, PollOrigin.REPLAY,
                    boot, silent, ref.acquiredWallMs, normalizer, nowElapsedMs = ref.acquiredElapsedMs,
                    continuityUpdates = delta.continuityUpdates)
            }
        }
        val recovered = replay?.state
        // Different boundaries mean incomplete history or a different group; do not splice its totals.
        val sameGroup = recovered != null && start != null && recovered.bootTotalStartedAtMs == start &&
            recovered.bootTotalEndedAtMs == current.bootTotalEndedAtMs &&
            recovered.bootTotalDurationMs == current.bootTotalDurationMs &&
            recovered.tripStartedAtMs == current.tripStartedAtMs && recovered.tripEndedAtMs == current.tripEndedAtMs
        var result = current.copy(rawSourceInputs = restored, initialSeedRecoveryVersion = 2)
        if (sameGroup) {
            val r = checkNotNull(recovered)
            val distance = !current.bootTotalDistanceComplete && r.bootTotalDistanceComplete
            val energy = !current.bootTotalEnergyComplete && r.bootTotalEnergyComplete
            val legDistance = current.tripId != null && current.tripDistance.completeKm == null && r.tripDistance.completeKm != null
            val legEnergy = current.tripId != null && current.tripStartEnergyKwh == null &&
                r.tripStartEnergyKwh != null && r.tripEnergyContinuous
            result = result.copy(
                bootStartSoc = current.bootStartSoc ?: r.bootStartSoc,
                bootTotalDistanceKm = if (distance) r.bootTotalDistanceKm else current.bootTotalDistanceKm,
                bootTotalDistanceComplete = current.bootTotalDistanceComplete || distance,
                bootTotalEnergyKwh = if (energy) r.bootTotalEnergyKwh else current.bootTotalEnergyKwh,
                bootTotalEnergyComplete = current.bootTotalEnergyComplete || energy,
                tripDistance = if (legDistance) r.tripDistance else current.tripDistance,
                tripStartSoc = current.tripStartSoc ?: r.tripStartSoc,
                tripStartEnergyKwh = if (legEnergy) r.tripStartEnergyKwh else current.tripStartEnergyKwh,
                tripAccumulatedEnergyKwh = if (legEnergy) r.tripAccumulatedEnergyKwh else current.tripAccumulatedEnergyKwh
            )
        }
        powerReplay?.state?.takeIf { current.powerSession?.boundary == null }?.let { recoveredPower ->
            val session = recoveredPower.powerSession?.takeIf { it.id == power?.powerSessionId }
            if (session != null) result = result.copy(powerSession = session,
                bootTotalStartedAtMs = session.startedAtMs, bootStartSoc = session.startSoc,
                tripEndPowerSession = recoveredPower.tripEndPowerSession.takeIf {
                    current.tripStartedAtMs == recoveredPower.tripStartedAtMs &&
                        current.tripEndedAtMs == recoveredPower.tripEndedAtMs
                } ?: current.tripEndPowerSession)
        }
        return Result(result, when {
            count == 0 -> "history_unavailable"
            !activeGroup -> "source_state_only_no_active_group"
            sameGroup -> "matching_active_group"
            else -> "group_boundaries_not_reconstructable"
        }, count, restored.count { (key, value) -> value.valid && current.rawSourceInputs[key]?.valid != true })
    }
}
