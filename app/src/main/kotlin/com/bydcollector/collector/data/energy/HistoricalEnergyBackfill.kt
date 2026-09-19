package com.bydcollector.collector.data.energy

import com.bydcollector.collector.data.local.HistoricalEnergyPoll
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedQuality
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
