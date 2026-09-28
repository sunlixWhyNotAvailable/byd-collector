package com.bydcollector.collector.data.normalized

import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.polling.PollSampleSource
import java.time.Instant
import java.time.OffsetDateTime

private val sourceEntriesByKey by lazy { DirectFidRegistry.entries.associateBy { it.key } }

internal fun usableNormalizedCallbackQuality(quality: String): Boolean =
    quality == "callback" || quality == "usable"

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
    val callback = reading.callbackSource ?: return NormalizedSourceInput(reading, pollStamp, pollId)
    val raw = reading.rawInt ?: return null
    if (!usableNormalizedCallbackQuality(callback.quality) ||
        !callback.matches(entry.tx, entry.dev, entry.fid, raw)
    ) return null
    return NormalizedSourceInput(
        reading.copy(callbackSource = null),
        normalizedCallbackSourceStamp(
            callback.bootId, callback.helperGeneration, callback.stream, callback.epoch,
            callback.eventSequence, callback.receivedWallMs, callback.receivedElapsedMs
        ),
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
    receivedElapsedMs: Long
): NormalizedSourceStamp = NormalizedSourceStamp(
    kind = NormalizedSourceKind.CALLBACK,
    identity = "callback:${callbackComponent(bootId)}:${callbackComponent(helperGeneration)}:$stream:$epoch:$eventSequence",
    bootId = bootId,
    generatorId = "callback:${callbackComponent(helperGeneration)}:$stream:$epoch",
    sequence = eventSequence,
    wallMs = receivedWallMs,
    elapsedMs = receivedElapsedMs
)

private fun callbackComponent(value: String): String = "${value.length}:$value"
