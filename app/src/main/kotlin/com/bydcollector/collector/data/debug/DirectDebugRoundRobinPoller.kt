package com.bydcollector.collector.data.debug

import com.bydcollector.collector.data.direct.DirectHelperReadResult
import com.bydcollector.collector.data.direct.DirectBatchDiagnostics
import com.bydcollector.collector.data.direct.DirectHelperBatchResult
import com.bydcollector.collector.data.direct.DirectVehicleHelper
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

//cycles through non-main direct parameters so debug discovery can progress without one huge poll
class DirectDebugRoundRobinCursor(
    private val parameters: List<DirectDebugParameter>
) {
    private var nextIndex = 0

    fun nextBatch(requestedCount: Int): List<DirectDebugParameter> {
        if (parameters.isEmpty()) return emptyList()
        val count = min(requestedCount.coerceAtLeast(1), parameters.size)
        return List(count) {
            val parameter = parameters[nextIndex]
            nextIndex = (nextIndex + 1) % parameters.size
            parameter
        }
    }
}

//stores exploratory direct reads separately from main telemetry so noisy candidates do not pollute main db
class DirectDebugRoundRobinPoller(
    private val parameters: List<DirectDebugParameter>,
    private val helper: DirectVehicleHelper,
    private val store: DirectDebugStore,
    private val clock: Clock = SystemClockAdapter(),
    private val onCycle: (DirectDebugCycleSummary) -> Unit = {}
) {
    private val executor = namedSingleThreadExecutor("byd-round-robin")
    private val running = AtomicBoolean(false)
    private val cursor = DirectDebugRoundRobinCursor(parameters)
    private var future: Future<*>? = null
    private var sessionId: Long? = null
    private var cycleNumber: Long = 0

    fun isRunning(): Boolean = running.get()

    fun start(batchSize: Int) {
        if (!running.compareAndSet(false, true)) return
        val safeBatchSize = batchSize.coerceAtLeast(1)
        sessionId = store.openSession(parameters, safeBatchSize)
        future = executor.submit {
            runCatching {
                //keeps round-robin work below ui/service priority on the car tablet
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            }
            while (running.get()) {
                val cycleStartedElapsed = clock.elapsedRealtimeMs()
                val startedAt = clock.nowIso()
                val summary = pollOnce(sessionId ?: return@submit, safeBatchSize, startedAt)
                onCycle(summary)
                //backs off when a cycle overruns so wide debug polling does not peg a core continuously
                val sleepMs = nextSleepMs(clock.elapsedRealtimeMs() - cycleStartedElapsed)
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@submit
                    }
                }
            }
        }
    }

    fun stop(reason: String) {
        if (!running.getAndSet(false)) return
        future?.cancel(true)
        future = null
        sessionId?.let { store.endSession(it, reason) }
        sessionId = null
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
        val batchResult = runCatching { helper.readBatch(entries) }.getOrElse { error ->
            val message = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            DirectHelperBatchResult(
                results = List(batch.size) { DirectHelperReadResult(-950, null, message) },
                diagnostics = DirectBatchDiagnostics(
                    mode = "poller_error",
                    nativeAvailable = false,
                    nativeGroupCount = 0,
                    fallbackGroupCount = 0,
                    fallbackReadCount = 0,
                    groupFailureCount = 0,
                    helperElapsedMs = 0,
                    returnedCount = 0,
                    error = message
                )
            )
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
