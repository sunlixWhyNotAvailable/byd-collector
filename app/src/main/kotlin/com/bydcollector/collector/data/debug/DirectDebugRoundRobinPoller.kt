package com.bydcollector.collector.data.debug

import com.bydcollector.collector.data.direct.DirectHelperReadResult
import com.bydcollector.collector.data.direct.DirectVehicleHelper
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

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

class DirectDebugRoundRobinPoller(
    private val parameters: List<DirectDebugParameter>,
    private val helper: DirectVehicleHelper,
    private val store: DirectDebugStore,
    private val clock: Clock = SystemClockAdapter(),
    private val onCycle: (DirectDebugCycleSummary) -> Unit = {}
) {
    private val executor = Executors.newSingleThreadExecutor()
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
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            }
            while (running.get()) {
                val cycleStartedElapsed = clock.elapsedRealtimeMs()
                val startedAt = clock.nowIso()
                val summary = pollOnce(sessionId ?: return@submit, safeBatchSize, startedAt)
                onCycle(summary)
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
        stop(reason)
        executor.shutdownNow()
    }

    fun pollOnce(sessionId: Long, batchSize: Int, startedAt: String = clock.nowIso()): DirectDebugCycleSummary {
        val startedElapsed = clock.elapsedRealtimeMs()
        val batch = cursor.nextBatch(batchSize)
        cycleNumber += 1
        val reads = batch.map { parameter ->
            val result = runCatching { helper.read(parameter.toDirectFidEntry()) }
                .getOrElse { error ->
                    DirectHelperReadResult(
                        status = -950,
                        raw = null,
                        error = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                    )
                }
            parameter to result
        }
        return store.recordCycle(
            sessionId = sessionId,
            cycleNumber = cycleNumber,
            batch = batch,
            reads = reads,
            startedAt = startedAt,
            elapsedMs = clock.elapsedRealtimeMs() - startedElapsed
        )
    }

    companion object {
        const val INTERVAL_MS = 1_000L
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
