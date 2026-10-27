package com.bydcollector.collector.data.normalized

import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectValueDecoders
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.polling.PollSampleSource
import java.time.Instant
import java.time.OffsetDateTime

private val sourceEntriesByKey by lazy { DirectFidRegistry.entries.associateBy { it.key } }

internal fun usableNormalizedCallbackQuality(quality: String): Boolean =
    quality == "callback" || quality == "usable"

internal fun normalizedGetSeedIdentity(readIdentity: String, recordSequence: Long): String =
    "get-seed:${readIdentity.length}:$readIdentity:$recordSequence"

internal fun normalizedSourceMatchesSeed(
    stamp: NormalizedSourceStamp,
    seedKind: String?,
    seedIdentity: String?,
    seedRecordSequence: Long?
): Boolean {
    if (seedIdentity == null || seedRecordSequence == null || stamp.sequence != seedRecordSequence || stamp.sourceEpoch == null) {
        return false
    }
    return when (seedKind) {
        "callback" -> stamp.kind == NormalizedSourceKind.CALLBACK && stamp.identity == seedIdentity
        "get" -> stamp.kind == NormalizedSourceKind.POLL &&
            stamp.identity == normalizedGetSeedIdentity(seedIdentity, seedRecordSequence)
        else -> false
    }
}

internal fun normalizedPollSourceStamp(source: PollSampleSource, timestamp: String): NormalizedSourceStamp =
    NormalizedSourceStamp(
        kind = NormalizedSourceKind.POLL,
        identity = source.identity,
        bootId = source.bootId,
        generatorId = source.generatorId,
        sequence = source.sequence,
        wallMs = runCatching { OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() }
            .getOrElse { Instant.parse(timestamp).toEpochMilli() },
        elapsedMs = source.capturedElapsedMs
    )

/** A cached callback keeps its acquisition identity/time, never the enclosing poll's. */
internal fun normalizedSourceInputForPollReading(
    reading: PollReading,
    pollStamp: NormalizedSourceStamp,
    pollId: Long? = null
): NormalizedSourceInput? {
    val entry = sourceEntriesByKey[reading.rawKey] ?: return null
    val callback = reading.callbackSource ?: run {
        if (reading.getLineage?.supersededByCallback == true) return null
        return NormalizedSourceInput(reading, reading.getLineage?.sourceStamp() ?: pollStamp, pollId)
    }
    val raw = reading.rawInt
    val valid = raw != null && usableNormalizedCallbackQuality(callback.quality) &&
        callback.matches(entry.tx, entry.dev, entry.fid, raw) && DirectValueDecoders.decode(entry, raw) != null
    val sourceStamp = normalizedCallbackSourceStamp(
        callback.bootId, callback.helperGeneration, callback.stream, callback.epoch,
        callback.eventSequence, callback.receivedWallMs, callback.receivedElapsedMs,
        callback.sourceEpoch.takeIf { callback.hasSourceEpoch }
    )
    return NormalizedSourceInput(
        if (valid) reading.copy(callbackSource = null) else reading.copy(rawValue = null, descValue = null),
        sourceStamp,
        null
    )
}

internal fun normalizedCallbackSourceStamp(
    bootId: String,
    helperGeneration: String,
    stream: Int,
    epoch: Long,
    eventSequence: Long,
    receivedWallMs: Long,
    receivedElapsedMs: Long,
    sourceEpoch: Long? = null
): NormalizedSourceStamp = NormalizedSourceStamp(
    kind = NormalizedSourceKind.CALLBACK,
    identity = "callback:${callbackComponent(bootId)}:${callbackComponent(helperGeneration)}:$stream:${sourceEpoch ?: epoch}:$eventSequence",
    bootId = bootId,
    generatorId = if (sourceEpoch == null) "callback:${callbackComponent(helperGeneration)}:$stream:$epoch"
        else normalizedHelperSourceGeneratorId(helperGeneration, stream, sourceEpoch),
    sequence = eventSequence,
    wallMs = receivedWallMs,
    elapsedMs = receivedElapsedMs,
    sourceEpoch = sourceEpoch
)

internal fun normalizedHelperSourceGeneratorId(helperGeneration: String, stream: Int, sourceEpoch: Long): String =
    "source:${callbackComponent(helperGeneration)}:$stream:$sourceEpoch"

private fun callbackComponent(value: String): String = "${value.length}:$value"
