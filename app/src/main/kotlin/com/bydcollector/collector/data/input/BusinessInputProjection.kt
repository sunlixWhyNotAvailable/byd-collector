package com.bydcollector.collector.data.input

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedCategory
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceOrder
import com.bydcollector.collector.data.normalized.NormalizedSourceOrdering
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.data.normalized.normalizedSourceMatchesSeed
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollSampleSource
import java.time.Instant

/** Retained values keep acquisition clocks; evaluation never refreshes a source. */
object BusinessInputProjection {
    val energyKeys = setOf(
        "charging_charge_battery_volt", "charging_charge_current", "bodywork_power_level",
        "charging_1009_876609586_5", "charging_1009_89128973_5",
        "charging_charging_vtov_discharge_status", "charging_1009_876609560_5"
    )
    private val freshnessByKey = NormalizedFieldCatalog.fields
        .flatMap { field -> field.sourceKeys.map { it to field.category.staleAfterMs } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, ages) -> ages.min() }

    val energyHistoryLookbackMs: Long
        get() = energyKeys.maxOfOrNull { freshnessByKey[it] ?: NormalizedCategory.BODY.staleAfterMs }
            ?: NormalizedCategory.BODY.staleAfterMs

    fun source(ref: BusinessInputRef): PollSampleSource = PollSampleSource(
        ref.sourceIdentity,
        checkNotNull(ref.bootId) { "Business input ${ref.id} has no acquisition boot ID" },
        checkNotNull(ref.acquiredElapsedMs) { "Business input ${ref.id} has no acquisition elapsed time" },
        ref.generation, ref.sequence
    )

    fun timestamp(ref: BusinessInputRef): String = Instant.ofEpochMilli(
        checkNotNull(ref.acquiredWallMs) { "Business input ${ref.id} has no acquisition wall time" }
    ).toString()

    fun incoming(delta: BusinessInputDelta): Map<String, NormalizedSourceInput> {
        // A failed sparse GET invalidates only its recorded attempted fields. Only a
        // transport failure without any attempted keys is truly unscoped.
        if (delta.ref.kind != BusinessInputKind.GET || delta.ref.ok || delta.ref.readingKeys.isNotEmpty()) {
            return delta.sourceInputs
        }
        val source = source(delta.ref)
        val stamp = NormalizedSourceStamp(NormalizedSourceKind.POLL, source.identity,
            source.bootId, source.generatorId, source.sequence, checkNotNull(delta.ref.acquiredWallMs),
            source.capturedElapsedMs)
        // An overall failed GET has no values; retain its failure frontier instead of stale successes.
        return BusinessInputCheckpointKeys.ALL.associateWith { key ->
            NormalizedSourceInput(PollReading(key, null, null), stamp, delta.ref.pollId)
        }
    }

    fun freshInputs(
        inputs: Map<String, NormalizedSourceInput>, bootId: String, evaluatedElapsedMs: Long,
        continuity: Map<String, BusinessInputContinuity> = emptyMap()
    ): Map<String, NormalizedSourceInput> = inputs.mapValues { (key, input) ->
        val age = evaluatedElapsedMs - input.stamp.elapsedMs
        val ttl = freshnessByKey[key] ?: NormalizedCategory.BODY.staleAfterMs
        val sourceState = continuity[key]
        val afterContinuityFrontier = sourceState != null && sourceAfter(input.stamp, sourceState.stamp, bootId)
        val explicitValid = sourceState?.state == BusinessContinuityState.AVAILABLE && sourceState.seedValid &&
            sourceState.stamp.bootId == bootId && input.stamp.bootId == bootId &&
            (normalizedSourceMatchesSeed(input.stamp, sourceState.seedKind, sourceState.seedIdentity, sourceState.seedRecordSequence) ||
                afterContinuityFrontier) ||
            sourceState?.state == BusinessContinuityState.LOST && afterContinuityFrontier
        val explicitlyLost = sourceState?.state == BusinessContinuityState.LOST && sourceState.stamp.bootId == bootId &&
            !afterContinuityFrontier
        if (input.stamp.bootId == bootId && !explicitlyLost && (explicitValid || sourceState == null && age in 0..ttl)) input
        else input.copy(reading = PollReading(key, null, null))
    }

    private fun sourceAfter(input: NormalizedSourceStamp, boundary: NormalizedSourceStamp, bootId: String): Boolean {
        if (input.bootId != bootId || boundary.bootId != bootId) return false
        if (input.kind == NormalizedSourceKind.CALLBACK) {
            return input.sourceEpoch != null &&
                NormalizedSourceOrdering.compare(input, boundary, bootId) == NormalizedSourceOrder.NEWER
        }
        // A GET after a known break only reopens a source when helper lineage was persisted.
        return input.sourceEpoch != null &&
            NormalizedSourceOrdering.compare(input, boundary, bootId) == NormalizedSourceOrder.NEWER
    }

    fun observations(
        normalizer: VehicleStateNormalizer, inputs: Map<String, NormalizedSourceInput>
    ): List<NormalizedObservation> = normalizer.normalizeSparse(inputs, inputs.keys)
}
