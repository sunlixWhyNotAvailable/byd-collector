package com.bydcollector.collector.data.polling

import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import java.util.concurrent.atomic.AtomicBoolean

class TelemetryPoller(
    private val coordinator: PollCycleRunner,
    private val clock: Clock = SystemClockAdapter(),
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val onCycleResult: (PollCycleResult) -> Unit = {},
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    fun isRunning(): Boolean = running.get()

    fun start(sessionId: Long): Boolean {
        if (!running.compareAndSet(false, true)) return false
        worker = Thread({ loop(sessionId) }, "bydcollector-telemetry-poller").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    private fun loop(sessionId: Long) {
        while (running.get()) {
            val startedAt = clock.elapsedRealtimeMs()
            try {
                onCycleResult(coordinator.pollOnce(sessionId))
            } catch (_: InterruptedException) {
                running.set(false)
            } catch (_: RuntimeException) {
                // The coordinator records failure categories; the next poll should continue.
                runCatching {
                    onCycleResult(PollCycleResult(null, ok = false, category = "poller_runtime_error", elapsedMs = 0, requestCount = 0))
                }
            }

            val elapsed = clock.elapsedRealtimeMs() - startedAt
            val sleepMs = intervalMs - elapsed
            if (running.get() && sleepMs > 0) {
                try {
                    sleeper(sleepMs)
                } catch (_: InterruptedException) {
                    running.set(false)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L
    }
}
