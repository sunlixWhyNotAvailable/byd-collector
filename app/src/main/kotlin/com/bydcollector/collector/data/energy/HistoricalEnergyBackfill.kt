package com.bydcollector.collector.data.energy

import com.bydcollector.collector.data.local.HistoricalEnergyPoll
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.input.BusinessInputDelta
import com.bydcollector.collector.data.input.BusinessInputKind
import com.bydcollector.collector.data.input.BusinessInputProjection
import com.bydcollector.collector.data.input.BusinessInputReducer
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.data.trips.TripSession
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal sealed class HistoricalEnergyResult {
    data class Complete(val snapshot: EnergySnapshot) : HistoricalEnergyResult()
    data class Rejected(val reason: String) : HistoricalEnergyResult()
}

/** Makes cancellation and the short terminal commit decision mutually exclusive. */
internal class HistoricalBackfillFinalizationGate {
    private val lock = ReentrantReadWriteLock(true)
    private var enabled = false
    private var generation = 0L

    fun enable(): Long = lock.write {
        enabled = true
        generation += 1L
        generation
    }

    fun cancel() = lock.write {
        enabled = false
        generation++
    }

    fun isCurrent(expectedGeneration: Long): Boolean = lock.read {
        enabled && generation == expectedGeneration
    }

    fun <T> finalizeIfCurrent(expectedGeneration: Long, action: () -> T): T? = lock.read {
        if (enabled && generation == expectedGeneration) action() else null
    }
}

/** Streaming reconstruction. Epoch time is deliberately synthetic input, never claimed as device uptime. */
internal class HistoricalEnergyAccumulator(
    private val trip: TripSession,
    private val firstPollId: Long,
    private val lastPollId: Long,
    private val expectedSessionId: Long,
    private val normalizer: VehicleStateNormalizer = VehicleStateNormalizer()
) {
    private val started = parse(trip.startedAt)
    private val ended = trip.endedAt?.let(::parse)
    private var previousInstant: Instant? = null
    private var previousPollId: Long? = null
    private var totals = EnergyTotals()
    private var anchor: EnergyAnchor? = null
    private var count = 0L
    private var failure: String? = if (started == null || ended == null || !ended.isAfter(started)) "malformed_trip_bounds" else null

    fun accept(poll: HistoricalEnergyPoll) {
        if (failure != null) return
        val instant = parse(poll.timestamp) ?: return reject("malformed_poll_time")
        if (count == 0L && (poll.pollId != firstPollId || instant != started)) return reject("missing_start_bookend")
        if (poll.sessionId != expectedSessionId) return reject("collection_session_changed")
        previousPollId?.let {
            if (poll.pollId <= it) return reject("non_monotonic_poll_id")
            if (it == Long.MAX_VALUE || poll.pollId != it + 1L) return reject("missing_poll_row")
        }
        previousInstant?.let { prior ->
            val interval = instant.toEpochMilli() - prior.toEpochMilli()
            if (interval <= 0L) return reject("non_monotonic_time")
            if (interval > BatteryEnergyIntegrator.MAX_INTERVAL_MS) return reject("gap_exceeded")
        }
        if (!poll.ok) return reject("failed_poll")

        val observations = normalizer.normalize(poll.pollId, poll.timestamp, poll.readings)
        if (invalidPresentOptionalVeto(poll.readings, observations.associateBy { it.field.fieldKey })) {
            return reject("invalid_charge_veto")
        }
        val sourceLabel = "historical-main-session:${poll.sessionId}:epoch-ms-not-uptime"
        val receipt = EnergyTelemetryProjection.receipt(
            PollSampleSource(sourceLabel, sourceLabel, instant.toEpochMilli()),
            poll.timestamp,
            poll.readings,
            observations
        )
        val input = receipt.input
        val finalOff = poll.pollId == lastPollId && input.powerOn == false
        val integrated = if (finalOff) {
            BatteryEnergyIntegrator.finishSession(anchor, totals, input)
        } else {
            if (input.externalCharging == true || input.gunDisconnected == false) return reject("charging_veto")
            if (input.voltage == null || input.current == null ||
                input.powerOn != true || input.gunDisconnected != true) {
                return reject("missing_or_invalid_required")
            }
            BatteryEnergyIntegrator.integrate(anchor, totals, input)
        }
        if (
            integrated.reason != EnergyIntegrationReason.INITIAL_ANCHOR &&
            integrated.reason != EnergyIntegrationReason.INTEGRATED
        ) {
            return reject(integrated.reason.name.lowercase())
        }
        totals = integrated.totals
        anchor = integrated.anchor
        previousInstant = instant
        previousPollId = poll.pollId
        count++
    }

    fun finish(): HistoricalEnergyResult {
        failure?.let { return HistoricalEnergyResult.Rejected(it) }
        if (count == 0L || previousPollId != lastPollId || previousInstant != ended) {
            return HistoricalEnergyResult.Rejected("missing_end_bookend")
        }
        if (totals.coveredMs <= 0L || totals.partial || totals.uncoveredMs != 0L) {
            return HistoricalEnergyResult.Rejected("zero_or_partial_coverage")
        }
        val observedAt = requireNotNull(trip.endedAt)
        val sourceLabel = "historical-main-session:$expectedSessionId:epoch-ms-not-uptime"
        return HistoricalEnergyResult.Complete(
            EnergySnapshot(
                snapshotId = "historical-energy:${trip.tripId}",
                sourceIdentity = sourceLabel,
                powerSessionId = trip.tripId,
                startedAt = trip.startedAt,
                observedAt = observedAt,
                sourceBootId = sourceLabel,
                sourceElapsedMs = requireNotNull(ended).toEpochMilli(),
                active = false,
                dischargedKwh = totals.dischargedKwh,
                regeneratedKwh = totals.regeneratedKwh,
                netKwh = totals.netKwh,
                energyCoveredMs = totals.coveredMs,
                energyUncoveredMs = 0L,
                energyPartial = false,
                integrationQuality = EnergyIntegrationQuality.COVERED,
                reason = "historical_complete"
            )
        )
    }

    private fun reject(reason: String) {
        failure = reason
    }

    private fun invalidPresentOptionalVeto(
        readings: List<PollReading>,
        observations: Map<String, com.bydcollector.collector.data.normalized.NormalizedObservation>
    ): Boolean = OPTIONAL_VETO_FIELDS.any { (rawKey, fieldKey) ->
        readings.firstOrNull { it.rawKey == rawKey }
            ?.let { it.rawValue != null || it.descValue != null } == true &&
            observations[fieldKey]?.quality != NormalizedQuality.OK
    }

    companion object {
        private val OPTIONAL_VETO_FIELDS = mapOf(
            "charging_1009_89128973_5" to "charger_connected_raw",
            "charging_charging_vtov_discharge_status" to "v2l_discharge_active_raw",
            "charging_1009_876609560_5" to "charging_battery_device_state"
        )

        internal fun overlaps(candidate: TripSession, other: TripSession): Boolean {
            if (candidate.tripId == other.tripId) return false
            val candidateStart = parse(candidate.startedAt) ?: return true
            val candidateEnd = candidate.endedAt?.let(::parse) ?: return true
            val otherStart = parse(other.startedAt) ?: return false
            val otherEnd = other.endedAt?.let(::parse) ?: return false
            return candidateStart < otherEnd && otherStart < candidateEnd
        }

        private fun parse(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
    }
}

/** Reconstructs an event-sourced trip using its real same-boot elapsed-time bounds. */
internal class HistoricalBusinessEnergyAccumulator(
    private val trip: TripSession,
    private val businessGeneration: String,
    initialSeed: Map<String, NormalizedSourceInput>,
    private val normalizer: VehicleStateNormalizer = VehicleStateNormalizer()
) {
    private val bootId = trip.startBootId
    private val startedElapsedMs = trip.startElapsedMs
    private val endedElapsedMs = trip.endElapsedMs
    private val started = parse(trip.startedAt)
    private val ended = trip.endedAt?.let(::parse)
    private var inputs = initialSeed.filter { (_, value) ->
        value.stamp.bootId == bootId &&
            (startedElapsedMs == null || value.stamp.elapsedMs <= startedElapsedMs)
    }
    private var continuity: Map<String, com.bydcollector.collector.data.input.BusinessInputContinuity> = emptyMap()
    private var failure: String? = when {
        businessGeneration.isBlank() -> "missing_business_generation"
        started == null || ended == null || !ended.isAfter(started) -> "malformed_trip_bounds"
        bootId.isNullOrBlank() || bootId != trip.endBootId -> "trip_boot_changed"
        startedElapsedMs == null || endedElapsedMs == null || startedElapsedMs < 0L || endedElapsedMs < startedElapsedMs ->
            "malformed_trip_elapsed_bounds"
        else -> null
    }
    private var previousEvaluationElapsedMs: Long? = null
    private var totals = EnergyTotals()
    private var anchor: EnergyAnchor? = null
    private var startedAtBoundary = false
    private var endedAtBoundary = false
    private var lastBoundaryReceipt: EnergyReceipt? = null
    private var lastIntegrationQuality = EnergyIntegrationQuality.ANCHOR_ONLY

    fun accept(ref: com.bydcollector.collector.data.input.BusinessInputRef, delta: BusinessInputDelta) {
        if (failure != null || endedAtBoundary) return
        val tripBootId = checkNotNull(bootId)
        if (delta.ref != ref) return reject("business_input_reference_mismatch")
        if (ref.bootId != tripBootId) return reject("business_input_boot_mismatch")
        val sourceElapsed = ref.acquiredElapsedMs ?: return reject("business_input_elapsed_missing")
        val tripStart = requireNotNull(startedElapsedMs)
        val tripEnd = requireNotNull(endedElapsedMs)
        if (sourceElapsed > tripEnd) return

        val incoming = BusinessInputProjection.incoming(delta)
        val previousEvaluation = previousEvaluationElapsedMs
        val merged = BusinessInputReducer.merge(inputs, incoming, tripBootId)
        val mergedContinuity = BusinessInputReducer.mergeContinuity(continuity, delta.continuityUpdates, tripBootId, merged.inputs)
        val previousInputs = inputs
        val previousContinuity = continuity
        val energyUpdated = merged.updates.keys.any { it in BusinessInputProjection.energyKeys }
        inputs = merged.inputs
        continuity = mergedContinuity.continuity
        if (sourceElapsed < tripStart) return

        val continuityEnergyUpdated = mergedContinuity.updates.keys.any { it in BusinessInputProjection.energyKeys }
        val energyDue = ref.kind == BusinessInputKind.TIMER || energyUpdated || continuityEnergyUpdated
        if (!energyDue) return

        val evaluationElapsed = maxOf(previousEvaluationElapsedMs ?: sourceElapsed, sourceElapsed)
        val source = BusinessInputProjection.source(ref)
        val observedAt = BusinessInputProjection.timestamp(ref)
        val previousFresh = BusinessInputProjection.freshInputs(previousInputs, tripBootId, evaluationElapsed, previousContinuity)
        val fresh = BusinessInputProjection.freshInputs(inputs, tripBootId, evaluationElapsed, continuity)
        val changedEnergy = (previousFresh.keys + fresh.keys).any { key ->
            if (key !in BusinessInputProjection.energyKeys) false else {
                previousFresh[key]?.reading?.let { it.rawValue to it.descValue } !=
                    fresh[key]?.reading?.let { it.rawValue to it.descValue }
            }
        }
        val observations = BusinessInputProjection.observations(normalizer, fresh)
        val readings = fresh.values.map { it.reading }
        if (invalidPresentOptionalVeto(readings, observations.associateBy { it.field.fieldKey })) {
            return reject("invalid_charge_veto")
        }
        val sourceIdentity = EnergyReceipt.businessIdentity(businessGeneration, ref.id)
        val receipt = EnergyTelemetryProjection.receipt(
            source.copy(identity = sourceIdentity), observedAt, readings, observations
        ).copy(
            businessGeneration = businessGeneration,
            businessInputId = ref.id,
            evaluationBootId = tripBootId,
            evaluationElapsedMs = evaluationElapsed,
            lateRelevantSource = changedEnergy && previousEvaluation?.let { sourceElapsed < it } == true
        )
        val evaluationInput = receipt.input.copy(bootId = tripBootId, elapsedMs = evaluationElapsed)

        if (!startedAtBoundary) {
            if (sourceElapsed != tripStart) return reject("missing_start_bookend")
            val powerInput = inputs[POWER_KEY]
            if (evaluationInput.powerOn != true || powerInput?.stamp?.elapsedMs != tripStart) return
            if (evaluationInput.externalCharging == true || evaluationInput.gunDisconnected != true ||
                evaluationInput.voltage == null || evaluationInput.current == null
            ) return
            val integration = BatteryEnergyIntegrator.integrate(null, totals, evaluationInput)
            if (integration.reason != EnergyIntegrationReason.INITIAL_ANCHOR) return
            totals = integration.totals
            anchor = integration.anchor
            lastIntegrationQuality = integration.quality
            startedAtBoundary = true
        } else if (evaluationInput.powerOn == false) {
            val powerInput = inputs[POWER_KEY]
            if (sourceElapsed != tripEnd || powerInput?.stamp?.elapsedMs != tripEnd || powerInput.reading.descValue
                    ?.trim()?.toIntOrNull()?.let { it > 0 } != false
            ) return reject("missing_end_bookend")
            val integration = if (receipt.lateRelevantSource) {
                BatteryEnergyIntegrator.reanchorAfterLateSource(anchor, totals, evaluationInput)
            } else {
                BatteryEnergyIntegrator.finishSession(anchor, totals, evaluationInput)
            }
            totals = integration.totals
            anchor = integration.anchor
            lastIntegrationQuality = integration.quality
            previousEvaluationElapsedMs = evaluationElapsed
            lastBoundaryReceipt = receipt
            endedAtBoundary = true
            return
        } else {
            val integration = if (receipt.lateRelevantSource) {
                BatteryEnergyIntegrator.reanchorAfterLateSource(anchor, totals, evaluationInput)
            } else {
                BatteryEnergyIntegrator.integrate(anchor, totals, evaluationInput)
            }
            totals = integration.totals
            anchor = integration.anchor
            lastIntegrationQuality = integration.quality
        }
        previousEvaluationElapsedMs = evaluationElapsed
    }

    fun finish(): HistoricalEnergyResult {
        failure?.let { return HistoricalEnergyResult.Rejected(it) }
        if (!startedAtBoundary) return HistoricalEnergyResult.Rejected("missing_start_bookend")
        if (!endedAtBoundary) return HistoricalEnergyResult.Rejected("missing_end_bookend")
        if (totals.coveredMs <= 0L || totals.partial || totals.uncoveredMs != 0L) {
            return HistoricalEnergyResult.Rejected("zero_or_partial_coverage")
        }
        val receipt = lastBoundaryReceipt ?: return HistoricalEnergyResult.Rejected("missing_end_bookend")
        return HistoricalEnergyResult.Complete(
            EnergySnapshot(
                snapshotId = "historical-energy:${trip.tripId}",
                sourceIdentity = receipt.sourceIdentity,
                powerSessionId = trip.tripId,
                startedAt = trip.startedAt,
                observedAt = requireNotNull(trip.endedAt),
                sourceBootId = requireNotNull(bootId),
                sourceElapsedMs = requireNotNull(previousEvaluationElapsedMs),
                active = false,
                dischargedKwh = totals.dischargedKwh,
                regeneratedKwh = totals.regeneratedKwh,
                netKwh = totals.netKwh,
                energyCoveredMs = totals.coveredMs,
                energyUncoveredMs = totals.uncoveredMs,
                energyPartial = false,
                integrationQuality = lastIntegrationQuality,
                reason = "historical_event_complete"
            )
        )
    }

    private fun reject(reason: String) {
        failure = reason
    }

    private fun invalidPresentOptionalVeto(
        readings: List<PollReading>,
        observations: Map<String, com.bydcollector.collector.data.normalized.NormalizedObservation>
    ): Boolean = OPTIONAL_VETO_FIELDS.any { (rawKey, fieldKey) ->
        readings.firstOrNull { it.rawKey == rawKey }
            ?.let { it.rawValue != null || it.descValue != null } == true &&
            observations[fieldKey]?.quality != NormalizedQuality.OK
    }

    companion object {
        private const val POWER_KEY = "bodywork_power_level"
        private val OPTIONAL_VETO_FIELDS = mapOf(
            "charging_1009_89128973_5" to "charger_connected_raw",
            "charging_charging_vtov_discharge_status" to "v2l_discharge_active_raw",
            "charging_1009_876609560_5" to "charging_battery_device_state"
        )

        private fun parse(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
    }
}
