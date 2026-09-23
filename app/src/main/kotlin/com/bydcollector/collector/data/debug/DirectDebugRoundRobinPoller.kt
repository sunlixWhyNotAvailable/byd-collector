package com.bydcollector.collector.data.debug

import com.bydcollector.collector.data.direct.DirectStreamCredentials
import com.bydcollector.collector.data.direct.DirectHelperReadResult
import com.bydcollector.collector.data.direct.DirectVehicleHelper
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.SystemClockAdapter
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.maintenance.DatabaseMaintenanceGate
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
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

internal class SecondaryOwnershipHandover(
    private val currentIdentity: () -> DirectStreamCredentials?,
    private val pause: () -> Boolean,
    private val drain: (Long) -> SecondaryReplayDrainResult,
    private val resume: () -> Boolean
) {
    private var completedIdentity: DirectStreamCredentials? = null

    fun reset() {
        completedIdentity = null
    }

    fun <T> run(sessionId: Long, live: (DirectStreamCredentials) -> T): T {
        val identity = currentIdentity() ?: pending("secondary ownership is not ready")
        if (completedIdentity != identity) handover(sessionId, identity)

        val liveIdentity = currentIdentity() ?: pending("secondary ownership changed before live read")
        if (liveIdentity != completedIdentity) {
            pending("secondary ownership changed before live read")
        }
        return live(liveIdentity)
    }

    private fun handover(sessionId: Long, initialIdentity: DirectStreamCredentials) {
        var resumed = false
        try {
            // Attempt a best-effort resume even if the pause reply is lost or rejected.
            if (!pause()) {
                if (currentIdentity() != initialIdentity) {
                    pending("secondary ownership changed during pause/fence")
                }
                error("secondary pause/fence failed")
            }

            val pausedIdentity = currentIdentity() ?: pending("secondary ownership is not ready after pause/fence")
            // A previously interrupted handover can leave the stream already paused, making this fence idempotent.
            val expectedPausedEpoch = nextEpoch(initialIdentity.epoch)
            if (pausedIdentity.controllerToken != initialIdentity.controllerToken ||
                (pausedIdentity.epoch != initialIdentity.epoch && pausedIdentity.epoch != expectedPausedEpoch)
            ) {
                pending("secondary ownership changed during pause/fence")
            }

            val replay = drain(sessionId)
            if (!replay.drained) {
                val reason = replay.blockedReason ?: "secondary replay did not drain"
                if (replay.retryable) pending(reason)
                error(reason)
            }

            if (currentIdentity() != pausedIdentity) {
                pending("secondary ownership changed during replay drain")
            }
            if (!resume()) {
                val afterFailedResume = currentIdentity()
                if (afterFailedResume == null ||
                    afterFailedResume.controllerToken != pausedIdentity.controllerToken ||
                    afterFailedResume.epoch != pausedIdentity.epoch
                ) {
                    pending("secondary ownership changed during resume")
                }
                error("secondary resume failed")
            }
            resumed = true

            val resumedIdentity = currentIdentity() ?: pending("secondary ownership is not ready after resume")
            if (resumedIdentity.controllerToken != pausedIdentity.controllerToken ||
                resumedIdentity.epoch != nextEpoch(pausedIdentity.epoch)
            ) {
                pending("secondary ownership changed during resume")
            }
            completedIdentity = resumedIdentity
        } finally {
            if (!resumed) runCatching { resume() }
        }
    }

    private fun pending(message: String): Nothing = throw SecondaryReplayPendingException(message)

    private fun nextEpoch(epoch: Long): Long = if (epoch == Long.MAX_VALUE) 1L else epoch + 1L
}

internal class SecondaryReplayPendingException(message: String) : RuntimeException(message)

internal fun newRoundRobinPollerExecutor(onTerminated: () -> Unit): ThreadPoolExecutor =
    object : ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(),
        ThreadFactory { task -> Thread(task, "byd-round-robin") }
    ) {
        override fun terminated() {
            runCatching(onTerminated)
        }
    }

internal fun <T> withSecondaryDatabaseRead(
    gate: DatabaseMaintenanceGate?,
    ownerActive: () -> Boolean,
    workerActive: () -> Boolean,
    allowStopping: Boolean = false,
    action: () -> T
): T {
    val guardedAction: () -> T = {
        if (!ownerActive() || (!allowStopping &&
                (!workerActive() || Thread.currentThread().isInterrupted))) {
            throw InterruptedException("Secondary database owner stopped")
        }
        action()
    }
    return if (gate == null) guardedAction() else gate.withRead(guardedAction)
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
    private val onRuntimeError: (Throwable) -> Unit = {},
    private val onTerminalFailure: () -> Unit = {},
    private val onStopped: () -> Unit = {},
    private val handoverIdentity: () -> DirectStreamCredentials?,
    private val databaseMaintenanceGate: DatabaseMaintenanceGate? = null,
    private val ownerActive: () -> Boolean = { true }
) {
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val stoppedCallbackDelivered = AtomicBoolean(false)
    private val executor = newRoundRobinPollerExecutor(::onExecutorTerminated)
    private val cursor = DirectDebugRoundRobinCursor(parameters)
    private var retryBatch: List<DirectDebugParameter>? = null
    private val ownershipHandover = SecondaryOwnershipHandover(
        currentIdentity = handoverIdentity,
        pause = pauseSecondary,
        drain = drainSecondaryReplay,
        resume = resumeSecondary
    )
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
        ownershipHandover.reset()
        try {
            val task = FutureTask(Callable {
                var openedSessionId: Long? = null
                try {
                    runCatching {
                        //keeps round-robin work below ui/service priority on the car tablet
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    }
                    val opened = withStoreRead { store.openSession(parameters, safeBatchSize) }
                    openedSessionId = opened
                    sessionId = opened
                    onStarted(opened)
                    var handoverRetryAttempt = 0
                    while (!stopRequested.get()) {
                        val cycleStartedElapsed = clock.elapsedRealtimeMs()
                        val startedAt = clock.nowIso()
                        val summary = try {
                            pollOnce(opened, safeBatchSize, startedAt)
                        } catch (_: SecondaryReplayPendingException) {
                            if (!stopRequested.get() && !Thread.currentThread().isInterrupted) {
                                Thread.sleep(handoverRetryDelayMs(handoverRetryAttempt))
                                handoverRetryAttempt = (handoverRetryAttempt + 1)
                                    .coerceAtMost(HANDOVER_RETRY_DELAYS_MS.lastIndex)
                            }
                            continue
                        }
                        onCycle(summary)
                        handoverRetryAttempt = 0
                        //backs off when a cycle overruns so wide secondary polling does not peg a core continuously
                        val sleepMs = nextSleepMs(clock.elapsedRealtimeMs() - cycleStartedElapsed)
                        if (sleepMs > 0) Thread.sleep(sleepMs)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: RuntimeException) {
                    if (failureCallbacksAllowed()) runCatching { onRuntimeError(error) }
                    if (failureCallbacksAllowed()) runCatching {
                        onFailure("${error::class.java.simpleName}: ${error.message ?: "secondary polling failed"}")
                    }
                    if (failureCallbacksAllowed()) runCatching(onTerminalFailure)
                } finally {
                    openedSessionId?.let { opened ->
                        runCatching {
                            withStoreRead(allowStopping = true) { store.endSession(opened, stopReason) }
                        }
                            .onFailure { error ->
                                if (failureCallbacksAllowed()) runCatching {
                                    onFailure(
                                        "${error::class.java.simpleName}: " +
                                            (error.message ?: "secondary session close failed")
                                    )
                                }
                            }
                    }
                    sessionId = null
                    // Termination delivers onStopped after this worker can no longer write to the store.
                    executor.shutdown()
                }
                Unit
            })
            future = task
            executor.execute(task)
        } catch (error: RuntimeException) {
            stopRequested.set(true)
            future?.cancel(true)
            executor.shutdownNow()
            throw error
        }
    }

    fun stop(reason: String) {
        stopReason = reason
        stopRequested.set(true)
        future?.cancel(true)
        executor.shutdownNow()
    }

    fun shutdown(reason: String = "shutdown") {
        shutdownAndAwait(reason, 0L)
    }

    fun shutdownAndAwait(reason: String = "shutdown", timeoutMs: Long): Boolean {
        stop(reason)
        return try {
            executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun pollOnce(sessionId: Long, batchSize: Int, startedAt: String = clock.nowIso()): DirectDebugCycleSummary {
        val startedElapsed = clock.elapsedRealtimeMs()
        return ownershipHandover.run(sessionId) { owner ->
            val batch = retryBatch ?: cursor.nextBatch(batchSize)
            val nextCycleNumber = cycleNumber + 1L
            val entries = batch.map { it.toDirectFidEntry() }
            val batchResult = try {
                helper.readSecondaryBatch(entries)
            } catch (error: RuntimeException) {
                throw IllegalStateException(
                    "secondary live batch failed: ${error::class.java.simpleName}: ${error.message ?: "no message"}",
                    error
                )
            }
            if (isOwnershipRetryStatus(batchResult.diagnostics.status)) {
                retryBatch = batch
                throw SecondaryReplayPendingException(
                    "secondary live batch ownership pending: ${batchResult.diagnostics.summary()}"
                )
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
            val currentIdentity = handoverIdentity()
            if (currentIdentity == null || currentIdentity != owner) {
                retryBatch = batch
                throw SecondaryReplayPendingException("secondary ownership changed before live write")
            }
            val summary = withStoreRead {
                store.recordCycle(
                    sessionId = sessionId,
                    cycleNumber = nextCycleNumber,
                    batch = batch,
                    reads = reads,
                    startedAt = startedAt,
                    elapsedMs = clock.elapsedRealtimeMs() - startedElapsed
                ).copy(batchDiagnostics = batchResult.diagnostics)
            }
            cycleNumber = nextCycleNumber
            retryBatch = null
            summary
        }
    }

    private fun failureCallbacksAllowed(): Boolean =
        shouldReportFailureCallbacks(stopRequested.get(), Thread.currentThread().isInterrupted)

    private fun <T> withStoreRead(allowStopping: Boolean = false, action: () -> T): T =
        withSecondaryDatabaseRead(
            gate = databaseMaintenanceGate,
            ownerActive = ownerActive,
            workerActive = { !stopRequested.get() },
            allowStopping = allowStopping,
            action = action
        )

    private fun onExecutorTerminated() {
        if (!stoppedCallbackDelivered.compareAndSet(false, true)) return
        sessionId = null
        running.set(false)
        future = null
        runCatching(onStopped)
    }

    companion object {
        const val INTERVAL_MS = 500L
        const val MAX_OVERLOAD_BACKOFF_MS = 30_000L
        private val HANDOVER_RETRY_DELAYS_MS = longArrayOf(100L, 250L, 500L, 1_000L)

        internal fun handoverRetryDelayMs(attempt: Int): Long =
            HANDOVER_RETRY_DELAYS_MS[attempt.coerceAtLeast(0).coerceAtMost(HANDOVER_RETRY_DELAYS_MS.lastIndex)]

        internal fun shouldReportFailureCallbacks(stopRequested: Boolean, interrupted: Boolean): Boolean =
            !stopRequested && !interrupted

        internal fun isOwnershipRetryStatus(status: Int): Boolean =
            status == CollectorHelperProtocol.STATUS_REPLAY_PENDING ||
                status == CollectorHelperProtocol.STATUS_STALE_TOKEN

        fun nextSleepMs(cycleElapsedMs: Long): Long {
            return if (cycleElapsedMs < INTERVAL_MS) {
                INTERVAL_MS - cycleElapsedMs
            } else {
                cycleElapsedMs.coerceAtMost(MAX_OVERLOAD_BACKOFF_MS)
            }
        }
    }
}
