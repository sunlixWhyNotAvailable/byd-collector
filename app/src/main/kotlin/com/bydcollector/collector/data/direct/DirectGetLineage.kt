package com.bydcollector.collector.data.direct

import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.data.normalized.normalizedGetSeedIdentity
import com.bydcollector.collector.data.normalized.normalizedHelperSourceGeneratorId

/** Helper capture provenance for one real GET address; APP poll identity stays separate. */
data class DirectGetLineage(
    val helperBootId: String,
    val helperGeneration: String,
    val stream: Int,
    val sourceEpoch: Long,
    val readIdentity: String,
    val recordSequence: Long,
    val startedWallMs: Long,
    val startedElapsedMs: Long,
    val completedWallMs: Long,
    val completedElapsedMs: Long,
    val callbackWatermarkStart: Long,
    val callbackWatermarkEnd: Long,
    val supersededByCallback: Boolean
) {
    init {
        require(helperBootId.isNotBlank() && helperGeneration.isNotBlank() && readIdentity.isNotBlank())
        require(stream in 1..2 && sourceEpoch >= 0L && recordSequence >= 0L)
        require(startedWallMs >= 0L && completedWallMs >= 0L)
        require(startedElapsedMs >= 0L && completedElapsedMs >= startedElapsedMs)
        require(callbackWatermarkStart >= -1L && callbackWatermarkEnd >= -1L)
    }

    fun sourceStamp(): NormalizedSourceStamp = NormalizedSourceStamp(
        kind = NormalizedSourceKind.POLL,
        identity = normalizedGetSeedIdentity(readIdentity, recordSequence),
        bootId = helperBootId,
        generatorId = normalizedHelperSourceGeneratorId(helperGeneration, stream, sourceEpoch),
        sequence = recordSequence,
        wallMs = completedWallMs,
        elapsedMs = completedElapsedMs,
        sourceEpoch = sourceEpoch
    )
}
