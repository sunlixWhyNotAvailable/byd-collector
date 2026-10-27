package com.bydcollector.collector.data.input

import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.NormalizedSourceOrder
import com.bydcollector.collector.data.normalized.NormalizedSourceOrdering
import com.bydcollector.collector.data.normalized.normalizedHelperSourceGeneratorId
import com.bydcollector.collector.data.normalized.normalizedSourceMatchesSeed

data class BusinessInputMerge(
    val inputs: Map<String, NormalizedSourceInput>,
    val updates: Map<String, NormalizedSourceInput>,
    val olderCount: Int,
    val incomparableCount: Int
)

data class BusinessContinuityMerge(
    val continuity: Map<String, BusinessInputContinuity>,
    val updates: Map<String, BusinessInputContinuity>
)

/** The live consumer and historical replay apply the same per-field acquisition ordering. */
object BusinessInputReducer {
    private const val APP_SOURCE_LOSS_GENERATOR_PREFIX = "app-source-loss:"

    fun mergeContinuity(
        previous: Map<String, BusinessInputContinuity>,
        incoming: Map<String, BusinessInputContinuity>,
        currentBootId: String?,
        currentSourceInputs: Map<String, NormalizedSourceInput> = emptyMap()
    ): BusinessContinuityMerge {
        val next = previous.toMutableMap()
        val updates = linkedMapOf<String, BusinessInputContinuity>()
        incoming.forEach { (key, state) ->
            require(key.isNotBlank()) { "business continuity key is blank" }
            val last = next[key]
            val currentSource = currentSourceInputs[key]
            if (isAppObservedSourceLoss(state) &&
                !appObservedSourceLossTargetsCurrentSource(state, last, currentSource)
            ) return@forEach
            if (shouldAcceptCurrentSeedAfterAppObservedSourceLoss(last, state, currentSource)) {
                next[key] = state
                updates[key] = state
                return@forEach
            }
            when (last?.let { NormalizedSourceOrdering.compare(state.stamp, it.stamp, currentBootId) }
                ?: NormalizedSourceOrder.NEWER) {
                NormalizedSourceOrder.NEWER -> {
                    next[key] = state
                    updates[key] = state
                }
                NormalizedSourceOrder.EQUAL -> {
                    val resolvedPendingAvailability = last?.state == BusinessContinuityState.LOST &&
                        last.reason == "availability_seed_pending" && last.rawBoundaryId != null &&
                        last.rawBoundaryId == state.rawBoundaryId && state.state == BusinessContinuityState.AVAILABLE &&
                        state.seedValid && state.seedIdentity != null && state.seedRecordSequence != null
                    if (resolvedPendingAvailability) {
                        next[key] = state
                        updates[key] = state
                    } else check(
                        last != null && state.state == last.state && state.seedIdentity == last.seedIdentity &&
                            state.seedRecordSequence == last.seedRecordSequence
                    ) { "business continuity source identity reused with different state: $key" }
                }
                NormalizedSourceOrder.OLDER,
                NormalizedSourceOrder.INCOMPARABLE -> Unit
            }
        }
        return BusinessContinuityMerge(next.toMap(), updates)
    }

    internal fun isAppObservedSourceLoss(state: BusinessInputContinuity?): Boolean =
        state?.stamp?.generatorId?.startsWith(APP_SOURCE_LOSS_GENERATOR_PREFIX) == true

    /** APP death evidence invalidates a held value only when its source is that exact helper stream. */
    internal fun appObservedSourceLossTargetsCurrentSource(
        loss: BusinessInputContinuity,
        previous: BusinessInputContinuity?,
        currentSource: NormalizedSourceInput?
    ): Boolean {
        val lostGeneration = loss.helperGeneration ?: return false
        val lostStream = loss.stream ?: return false
        if (currentSource != null && currentSource.stamp.bootId == loss.stamp.bootId) {
            return sourceBelongsToHelper(currentSource, loss.stamp.bootId, lostGeneration, lostStream)
        }
        val last = previous?.takeUnless(::isAppObservedSourceLoss) ?: return true
        if (last.stamp.bootId != loss.stamp.bootId) return true
        return last.helperGeneration == lostGeneration && last.stream == lostStream
    }

    /** A qualified seed from the current helper can recover after an unrelated helper's APP death. */
    internal fun shouldAcceptCurrentSeedAfterAppObservedSourceLoss(
        previous: BusinessInputContinuity?,
        incoming: BusinessInputContinuity,
        currentSource: NormalizedSourceInput?
    ): Boolean {
        if (!isAppObservedSourceLoss(previous) || incoming.state != BusinessContinuityState.AVAILABLE) return false
        val previousLoss = previous ?: return false
        val lostGeneration = previousLoss.helperGeneration ?: return false
        val lostStream = previousLoss.stream ?: return false
        val incomingGeneration = incoming.helperGeneration ?: return false
        val incomingStream = incoming.stream ?: return false
        if (previousLoss.stamp.bootId != incoming.stamp.bootId ||
            (lostGeneration == incomingGeneration && lostStream == incomingStream)
        ) return false
        return matchesQualifiedCurrentSeed(incoming, currentSource)
    }

    private fun matchesQualifiedCurrentSeed(
        state: BusinessInputContinuity,
        currentSource: NormalizedSourceInput?
    ): Boolean {
        if (!state.seedValid) return false
        val helperGeneration = state.helperGeneration ?: return false
        val stream = state.stream ?: return false
        val sourceEpoch = state.sourceEpoch ?: state.stamp.sourceEpoch ?: return false
        val source = currentSource?.stamp ?: return false
        if (source.bootId != state.stamp.bootId || source.sourceEpoch != sourceEpoch ||
            source.generatorId != normalizedHelperSourceGeneratorId(helperGeneration, stream, sourceEpoch)
        ) return false
        return normalizedSourceMatchesSeed(source, state.seedKind, state.seedIdentity, state.seedRecordSequence)
    }

    private fun sourceBelongsToHelper(
        source: NormalizedSourceInput,
        bootId: String,
        helperGeneration: String,
        stream: Int
    ): Boolean {
        val sourceStamp = source.stamp
        val sourceEpoch = sourceStamp.sourceEpoch ?: return false
        return sourceStamp.bootId == bootId &&
            sourceStamp.generatorId == normalizedHelperSourceGeneratorId(helperGeneration, stream, sourceEpoch)
    }

    fun merge(
        previous: Map<String, NormalizedSourceInput>,
        incoming: Map<String, NormalizedSourceInput>,
        currentBootId: String?
    ): BusinessInputMerge {
        require(previous.keys.all { it in BusinessInputCheckpointKeys.ALL })
        val next = previous.toMutableMap()
        val updates = linkedMapOf<String, NormalizedSourceInput>()
        var older = 0
        var incomparable = 0
        incoming.forEach { (key, input) ->
            require(key in BusinessInputCheckpointKeys.ALL && input.reading.rawKey == key) {
                "Invalid business source key: $key"
            }
            val last = next[key]
            when (last?.let { NormalizedSourceOrdering.compare(input.stamp, it.stamp, currentBootId) }
                ?: NormalizedSourceOrder.NEWER) {
                NormalizedSourceOrder.NEWER -> {
                    next[key] = input
                    updates[key] = input
                }
                NormalizedSourceOrder.EQUAL -> check(
                    last != null && input.reading.rawValue == last.reading.rawValue &&
                        input.reading.descValue == last.reading.descValue
                ) { "Business source identity reused with a different value: $key" }
                NormalizedSourceOrder.OLDER -> older++
                NormalizedSourceOrder.INCOMPARABLE -> incomparable++
            }
        }
        return BusinessInputMerge(next.toMap(), updates, older, incomparable)
    }
}
