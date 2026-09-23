package com.bydcollector.collector.data.polling

import java.io.File
import java.util.UUID

/** Capture identity/time, independent of Main row IDs and query duration. */
data class PollSampleSource(
    val identity: String,
    val bootId: String,
    val capturedElapsedMs: Long,
    val generatorId: String? = null,
    val sequence: Long? = null
)

internal class LivePollSource(
    private val bootId: String = liveBootId,
    private val generation: String = UUID.randomUUID().toString()
) {
    private var sequence = 0L
    fun capture(elapsedMs: Long): PollSampleSource {
        val nextSequence = ++sequence
        return PollSampleSource(
            "live:$bootId:$generation:$nextSequence",
            bootId,
            elapsedMs,
            generation,
            nextSequence
        )
    }

    companion object {
        val liveBootId: String by lazy {
            runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?: "unavailable-${UUID.randomUUID()}"
        }
    }
}
