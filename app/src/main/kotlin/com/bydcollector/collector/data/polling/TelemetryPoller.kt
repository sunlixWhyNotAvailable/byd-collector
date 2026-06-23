package com.bydcollector.collector.data.polling

import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import java.util.concurrent.atomic.AtomicBoolean

//runs fixed-interval poll cycles on one worker thread so service state and sqlite writes stay ordered
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
        //names the thread for live top/thread-dump diagnosis on the car tablet
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
                //continues polling after one bad cycle because vehicle access can be transiently unavailable
                runCatching {
                    onCycleResult(PollCycleResult(null, ok = false, category = "poller_runtime_error", elapsedMs = 0, requestCount = 0))
                }
            }

            val elapsed = clock.elapsedRealtimeMs() - startedAt
            val sleepMs = intervalMs - elapsed
            //keeps the period close to intervalMs without overlapping poll cycles
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
