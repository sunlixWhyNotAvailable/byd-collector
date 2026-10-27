package com.bydcollector.collector.service

data class TelegramTelemetryHealthSnapshot(
    val error: String? = null,
    val failureSinceMs: Long? = null,
    val recoveredAtMs: Long? = null,
    val confirmedHealthy: Boolean = false
)

/** Acquisition evidence only; quiet callbacks and business replay are not failures. */
class TelegramTelemetryHealth {
    enum class Channel { READ, CALLBACK }
    private data class Observation(val ticket: Long, val error: String?)
    private val observations = mutableMapOf<Channel, Observation>()
    private var ticket = 0L
    private var failureSinceMs: Long? = null
    private var recoveredAtMs: Long? = null

    @Synchronized fun beginObservation(): Long = ++ticket

    @Synchronized fun observe(channel: Channel, started: Long, error: String?, nowMs: Long) {
        // A Binder-death notification must win over an earlier in-flight successful reply.
        if (started <= (observations[channel]?.ticket ?: Long.MIN_VALUE)) return
        val wasHealthy = healthy()
        observations[channel] = Observation(started, error)
        if (observations.values.any { it.error != null }) {
            if (failureSinceMs == null) failureSinceMs = nowMs
        } else if (healthy()) {
            failureSinceMs = null
            if (!wasHealthy) recoveredAtMs = nowMs
        }
    }

    @Synchronized fun snapshot() = TelegramTelemetryHealthSnapshot(
        error = observations.entries.mapNotNull { (channel, value) ->
            value.error?.let { "${channel.name.lowercase()}: $it" }
        }.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        failureSinceMs = failureSinceMs,
        recoveredAtMs = recoveredAtMs,
        confirmedHealthy = healthy()
    )

    private fun healthy() = observations.size == Channel.entries.size && observations.values.all { it.error == null }
}
