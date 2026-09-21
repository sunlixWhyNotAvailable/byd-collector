package com.bydcollector.collector.data.debug

import com.bydcollector.collector.data.direct.DirectHelperReadResult
import com.bydcollector.collector.data.direct.DirectVehicleHelper
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

//cycles through non-main direct parameters so debug discovery can progress without one huge poll
class DirectDebugRoundRobinCursor(
    private val parameters: List<DirectDebugParameter>
) {
    private var nextIndex = 0

    fun nextBatch(requestedCount: Int): List<DirectDebugParameter> {
        if (parameters.isEmpty()) return emptyList()
        val count = requestedCount.coerceAtLeast(1).coerceAtMost(parameters.size - nextIndex)
        val batch = parameters.subList(nextIndex, nextIndex + count)
        nextIndex += count
        if (nextIndex == parameters.size) nextIndex = 0
        return batch
    }
}

internal object SecondaryLiveCycleGate {
    fun <T> run(
        pause: () -> Boolean,
        drain: () -> SecondaryReplayDrainResult,
        resume: () -> Boolean,
        live: () -> T
    ): T {
        var resumed = false
        return try {
            check(pause()) { "secondary pause/fence failed" }
            val replay = drain()
            check(replay.drained) { replay.blockedReason ?: "secondary replay did not drain" }
            check(resume()) { "secondary resume failed" }
            resumed = true
            live()
        } finally {
            if (!resumed) runCatching { resume() }
        }
    }
}

//stores exploratory direct reads separately from main telemetry so noisy candidates do not pollute main db
class DirectDebugRoundRobinPoller(
    private val parameters: List<DirectDebugParameter>,
    private val helper: DirectVehicleHelper,
    private val store: DirectDebugStore,
    private val clock: Clock = SystemClockAdapter(),
    private val onCycle: (DirectDebugCycleSummary) -> Unit = {},
    private val pauseSecondary: () -> Boolean = { true },
    private val drainSecondaryReplay: (Long) -> SecondaryReplayDrainResult = {
        SecondaryReplayDrainResult(drained = true, 0, 0, 0)
    },
    private val resumeSecondary: () -> Boolean = { true },
    private val onStarted: (Long) -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    private val onTerminalFailure: () -> Unit = {},
    private val onStopped: () -> Unit = {}
) {
    private val executor = namedSingleThreadExecutor("byd-round-robin")
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val cursor = DirectDebugRoundRobinCursor(parameters)
    @Volatile private var future: Future<*>? = null
    @Volatile private var sessionId: Long? = null
    @Volatile private var stopReason: String = "stopped"
    private var cycleNumber: Long = 0

    fun isRunning(): Boolean = running.get()

    fun start(batchSize: Int) {
        if (!running.compareAndSet(false, true)) return
        val safeBatchSize = batchSize.coerceAtLeast(1)
        stopRequested.set(false)
        stopReason = "stopped"
        try {
            future = executor.submit {
                var openedSessionId: Long? = null
                try {
                    runCatching {
                        //keeps round-robin work below ui/service priority on the car tablet
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    }
                    val opened = store.openSession(parameters, safeBatchSize)
                    openedSessionId = opened
                    sessionId = opened
                    onStarted(opened)
                    while (!stopRequested.get()) {
                        val cycleStartedElapsed = clock.elapsedRealtimeMs()
                        val startedAt = clock.nowIso()
                        val summary = pollOnce(opened, safeBatchSize, startedAt)
                        onCycle(summary)
                        //backs off when a cycle overruns so wide secondary polling does not peg a core continuously
                        val sleepMs = nextSleepMs(clock.elapsedRealtimeMs() - cycleStartedElapsed)
                        if (sleepMs > 0) Thread.sleep(sleepMs)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: RuntimeException) {
                    runCatching {
                        onFailure("${error::class.java.simpleName}: ${error.message ?: "secondary polling failed"}")
                    }
                    if (!stopRequested.get()) runCatching(onTerminalFailure)
                } finally {
                    openedSessionId?.let { opened ->
                        runCatching { store.endSession(opened, stopReason) }
                            .onFailure { error ->
                                runCatching {
                                    onFailure(
                                        "${error::class.java.simpleName}: " +
                                            (error.message ?: "secondary session close failed")
                                    )
                                }
                            }
                    }
                    sessionId = null
                    running.set(false)
                    future = null
                    runCatching(onStopped)
                }
            }
        } catch (error: RuntimeException) {
            running.set(false)
            stopRequested.set(true)
            throw error
        }
    }

    fun stop(reason: String) {
        stopReason = reason
        stopRequested.set(true)
        future?.cancel(true)
    }

    fun shutdown(reason: String = "shutdown") {
        shutdownAndAwait(reason, 0L)
    }

    fun shutdownAndAwait(reason: String = "shutdown", timeoutMs: Long): Boolean {
        stop(reason)
        executor.shutdownNow()
        return try {
            executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun pollOnce(sessionId: Long, batchSize: Int, startedAt: String = clock.nowIso()): DirectDebugCycleSummary {
        val startedElapsed = clock.elapsedRealtimeMs()
        val batch = cursor.nextBatch(batchSize)
        cycleNumber += 1
        val entries = batch.map { it.toDirectFidEntry() }
        val batchResult = SecondaryLiveCycleGate.run(
            pause = pauseSecondary,
            drain = { drainSecondaryReplay(sessionId) },
            resume = resumeSecondary
        ) {
            try {
                helper.readSecondaryBatch(entries)
            } catch (error: RuntimeException) {
                throw IllegalStateException(
                    "secondary live batch failed: ${error::class.java.simpleName}: ${error.message ?: "no message"}",
                    error
                )
            }
        }
        check(batchResult.diagnostics.status == CollectorHelperProtocol.STATUS_OK) {
            "secondary live batch rejected: ${batchResult.diagnostics.summary()}"
        }
        check(batchResult.results.size == batch.size && batchResult.diagnostics.returnedCount == batch.size) {
            "secondary live batch incomplete: expected=${batch.size} results=${batchResult.results.size} " +
                "returned=${batchResult.diagnostics.returnedCount}"
        }
        val reads = batch.mapIndexed { index, parameter ->
            parameter to batchResult.results.getOrElse(index) {
                DirectHelperReadResult(-951, null, "batch result missing at index $index")
            }
        }
        return store.recordCycle(
            sessionId = sessionId,
            cycleNumber = cycleNumber,
            batch = batch,
            reads = reads,
            startedAt = startedAt,
            elapsedMs = clock.elapsedRealtimeMs() - startedElapsed
        ).copy(batchDiagnostics = batchResult.diagnostics)
    }

    companion object {
        const val INTERVAL_MS = 500L
        const val MAX_OVERLOAD_BACKOFF_MS = 30_000L

        fun nextSleepMs(cycleElapsedMs: Long): Long {
            return if (cycleElapsedMs < INTERVAL_MS) {
                INTERVAL_MS - cycleElapsedMs
            } else {
                cycleElapsedMs.coerceAtMost(MAX_OVERLOAD_BACKOFF_MS)
            }
        }
    }
}
