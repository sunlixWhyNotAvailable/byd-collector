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
    private val onRuntimeError: (Throwable) -> Unit = {},
    private val onStopped: () -> Unit = {},
    private val onStarted: () -> Unit = {},
    private val onCycleDuration: (Long) -> Unit = {},
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    fun isRunning(): Boolean = running.get()

    fun isStopping(): Boolean = !running.get() && worker?.isAlive == true

    @Synchronized
    fun start(sessionId: Long): Boolean {
        if (worker?.isAlive == true) return false
        if (!running.compareAndSet(false, true)) return false
        //names the thread for live top/thread-dump diagnosis on the car tablet
        worker = Thread({ loop(sessionId) }, "bydcollector-telemetry-poller").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        stopAndJoin(0L)
    }

    /** Stops after the current poll cycle without interrupting a SQLite write in progress. */
    fun requestStopAfterCurrentCycle() {
        running.set(false)
    }

    fun awaitStopped(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val currentWorker = worker
        if (timeoutMs > 0 && currentWorker != null && currentWorker !== Thread.currentThread()) {
            currentWorker.join(timeoutMs)
        }
        val stopped = currentWorker?.isAlive != true
        if (stopped) synchronized(this) {
            if (worker === currentWorker && currentWorker?.isAlive != true) worker = null
        }
        return stopped
    }

    @Synchronized
    fun stopAndJoin(timeoutMs: Long): Boolean {
        running.set(false)
        val currentWorker = worker
        currentWorker?.interrupt()
        if (timeoutMs > 0 && currentWorker != null && currentWorker !== Thread.currentThread()) {
            currentWorker.join(timeoutMs)
        }
        val stopped = currentWorker?.isAlive != true
        if (stopped && worker === currentWorker) worker = null
        return stopped
    }

    private fun loop(sessionId: Long) {
        try {
            runCatching(onStarted)
            while (running.get()) {
                val startedAt = clock.elapsedRealtimeMs()
                var completedResult: PollCycleResult? = null
                try {
                    coordinator.pollOnce(sessionId)?.let { result ->
                        onCycleResult(result)
                        completedResult = result
                    }
                } catch (error: Throwable) {
                    if (error is InterruptedException || !running.get() || Thread.currentThread().isInterrupted) {
                        running.set(false)
                    } else {
                        //continues polling after one bad cycle because vehicle access can be transiently unavailable
                        runCatching { onRuntimeError(error) }
                        runCatching {
                            onCycleResult(PollCycleResult(
                                null, ok = false, category = "poller_runtime_error", elapsedMs = 0, requestCount = 0,
                                errorMessage = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                            ))
                        }
                    }
                }

                val elapsed = clock.elapsedRealtimeMs() - startedAt
                runCatching { onCycleDuration(elapsed.coerceAtLeast(0L)) }
                val sleepMs = completedResult?.nextDueDelayMs?.let { dueDelay ->
                    nextDueSleepMs(
                        requestedDelayMs = dueDelay,
                        pollCycleElapsedMs = elapsed,
                        sourceReadElapsedMs = completedResult?.elapsedMs ?: 0L
                    )
                } ?: (intervalMs - elapsed)
                //keeps the period close to intervalMs without overlapping poll cycles
                if (running.get() && sleepMs > 0) {
                    try {
                        sleeper(sleepMs)
                    } catch (_: InterruptedException) {
                        running.set(false)
                    }
                }
            }
        } finally {
            runCatching(onStopped)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 500L
        private const val MIN_NEXT_DUE_DELAY_MS = 100L
        private const val MAX_NEXT_DUE_DELAY_MS = 30_000L

        /** Bounds helper scheduling and accounts only for APP work after the helper reply. */
        internal fun nextDueSleepMs(
            requestedDelayMs: Long,
            pollCycleElapsedMs: Long,
            sourceReadElapsedMs: Long
        ): Long {
            val boundedDelay = requestedDelayMs.coerceIn(MIN_NEXT_DUE_DELAY_MS, MAX_NEXT_DUE_DELAY_MS)
            val postReadWork = (pollCycleElapsedMs - sourceReadElapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)
            return (boundedDelay - postReadWork).coerceAtLeast(0L)
        }
    }
}
