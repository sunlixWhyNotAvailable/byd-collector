package com.bydcollector.collector.data.callback

import java.util.concurrent.atomic.AtomicBoolean

enum class CallbackIntakeCondition { RUNNING, WAITING, PENDING, FAULT, STOPPED }

data class CallbackIntakeStatus(
    val condition: CallbackIntakeCondition,
    val kind: CallbackDrainKind,
    val detail: String,
    val fault: Throwable?,
    val progressPasses: Long,
    val emptyPasses: Long,
    val pendingPasses: Long,
    val faultPasses: Long,
    val persistedEvents: Long,
    val replayedEvents: Long,
    val duplicateBatches: Long,
    val quarantinedBatches: Long,
    val stateTransition: Boolean = false,
    val lastObservedHeadWallMs: Long? = null,
    val lastObservedHeadAgeMs: Long? = null,
    val lastRawCommitWallMs: Long? = null,
    val lastProgressWallMs: Long? = null
)

/** Owns one callback stream and never waits for the other stream or a poll cycle. */
class CallbackIntakeWorker(
    private val threadName: String,
    private val drain: () -> CallbackDrainResult,
    private val ready: () -> Boolean = { true },
    private val onStatus: (CallbackIntakeStatus) -> Unit = {},
    private val onStopped: () -> Unit = {},
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val wallTimeMs: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    private var progressPasses = 0L
    private var emptyPasses = 0L
    private var pendingPasses = 0L
    private var faultPasses = 0L
    private var persistedEvents = 0L
    private var replayedEvents = 0L
    private var duplicateBatches = 0L
    private var quarantinedBatches = 0L
    private var lastObservedHeadWallMs: Long? = null
    private var lastRawCommitWallMs: Long? = null
    private var lastProgressWallMs: Long? = null

    fun isRunning(): Boolean = running.get()

    fun isStopping(): Boolean = !running.get() && worker?.isAlive == true

    fun start(): Boolean = synchronized(lock) {
        if (worker?.isAlive == true || !running.compareAndSet(false, true)) return@synchronized false
        val next = Thread(::runLoop, threadName).apply { isDaemon = true }
        worker = next
        next.start()
        true
    }

    fun stopAndJoin(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val current = synchronized(lock) {
            running.set(false)
            worker
        }
        current?.interrupt()
        if (timeoutMs > 0 && current != null && current !== Thread.currentThread()) current.join(timeoutMs)
        val stopped = current?.isAlive != true
        if (stopped) synchronized(lock) { if (worker === current) worker = null }
        return stopped
    }

    /** Stops after the active callback batch has committed and been acknowledged. */
    fun requestStopAfterCurrentBatch() {
        synchronized(lock) { running.set(false) }
    }

    fun awaitStopped(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val current = synchronized(lock) { worker }
        if (timeoutMs > 0 && current != null && current !== Thread.currentThread()) current.join(timeoutMs)
        val stopped = current?.isAlive != true
        if (stopped) synchronized(lock) { if (worker === current) worker = null }
        return stopped
    }

    private fun runLoop() {
        var backoffIndex = 0
        var latestResult = emptyResult()
        var lastReportedCondition: CallbackIntakeCondition? = null
        var lastReportedAt = Long.MIN_VALUE
        var lastFailureIdentity: String? = null

        fun report(condition: CallbackIntakeCondition, result: CallbackDrainResult, force: Boolean = false) {
            val now = monotonicNanos()
            val failureIdentity = if (condition == CallbackIntakeCondition.FAULT ||
                condition == CallbackIntakeCondition.PENDING
            ) {
                listOf(
                    result.status?.toString().orEmpty(),
                    result.fault?.let { it::class.java.name }.orEmpty(),
                    result.fault?.message.orEmpty(),
                    result.blockedReason.orEmpty()
                ).joinToString("|")
            } else null
            val distinctFailure = failureIdentity != null && failureIdentity != lastFailureIdentity
            if (failureIdentity == null) lastFailureIdentity = null else lastFailureIdentity = failureIdentity
            val changed = condition != lastReportedCondition || distinctFailure
            val aggregateDue = lastReportedAt == Long.MIN_VALUE || now - lastReportedAt >= SUMMARY_INTERVAL_NANOS
            if (!force && !changed && !aggregateDue) return
            lastReportedCondition = condition
            lastReportedAt = now
            val wallNowMs = wallTimeMs()
            val headAgeMs = lastObservedHeadWallMs?.let { (wallNowMs - it).coerceAtLeast(0L) }
            val fault = result.fault
            val detail = buildString {
                append("condition=").append(condition.name.lowercase())
                append(" kind=").append(result.kind.name.lowercase())
                append(" persisted_events=").append(persistedEvents)
                append(" replayed_events=").append(replayedEvents)
                append(" duplicate_batches=").append(duplicateBatches)
                append(" quarantined_batches=").append(quarantinedBatches)
                append(" progress_passes=").append(progressPasses)
                append(" empty_passes=").append(emptyPasses)
                append(" pending_passes=").append(pendingPasses)
                append(" fault_passes=").append(faultPasses)
                append(" last_observed_head_wall_ms=").append(lastObservedHeadWallMs ?: "unknown")
                append(" last_observed_head_age_ms=").append(headAgeMs ?: "unknown")
                append(" last_raw_commit_wall_ms=").append(lastRawCommitWallMs ?: "unknown")
                append(" last_progress_wall_ms=").append(lastProgressWallMs ?: "unknown")
                result.status?.let { append(" status=").append(it) }
                result.blockedReason?.let { append(" detail=").append(it.take(256)) }
                fault?.let {
                    append(" throwable=").append(it::class.java.simpleName)
                    it.message?.take(256)?.let { message -> append(": ").append(message) }
                }
            }
            runCatching {
                onStatus(
                    CallbackIntakeStatus(
                        condition = condition,
                        kind = result.kind,
                        detail = detail,
                        fault = fault,
                        progressPasses = progressPasses,
                        emptyPasses = emptyPasses,
                        pendingPasses = pendingPasses,
                        faultPasses = faultPasses,
                        persistedEvents = persistedEvents,
                        replayedEvents = replayedEvents,
                        duplicateBatches = duplicateBatches,
                        quarantinedBatches = quarantinedBatches,
                        stateTransition = changed,
                        lastObservedHeadWallMs = lastObservedHeadWallMs,
                        lastObservedHeadAgeMs = headAgeMs,
                        lastRawCommitWallMs = lastRawCommitWallMs,
                        lastProgressWallMs = lastProgressWallMs
                    )
                )
            }
        }

        fun record(result: CallbackDrainResult) {
            latestResult = result
            if (result.kind == CallbackDrainKind.EMPTY) lastObservedHeadWallMs = null
            else result.oldestObservedWallMs?.let { lastObservedHeadWallMs = it }
            result.lastRawCommitWallMs?.let { lastRawCommitWallMs = it }
            result.lastProgressWallMs?.let { lastProgressWallMs = it }
            persistedEvents += result.persistedEvents
            replayedEvents += result.replayedEvents
            duplicateBatches += result.duplicateBatches
            quarantinedBatches += result.quarantinedBatches
            when (result.kind) {
                CallbackDrainKind.EMPTY -> emptyPasses++
                CallbackDrainKind.PROGRESS -> progressPasses++
                CallbackDrainKind.PENDING -> pendingPasses++
                CallbackDrainKind.FAULT -> faultPasses++
            }
        }

        try {
            while (running.get()) {
                val isReady = try {
                    ready()
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    if (!running.get() || Thread.currentThread().isInterrupted) {
                        throw InterruptedException("callback intake stopped")
                    }
                    val failed = faultResult(error)
                    record(failed)
                    report(CallbackIntakeCondition.FAULT, failed)
                    sleepMs(BACKOFF_MS[backoffIndex])
                    backoffIndex = (backoffIndex + 1).coerceAtMost(BACKOFF_MS.lastIndex)
                    continue
                }
                if (!running.get() || Thread.currentThread().isInterrupted) {
                    throw InterruptedException("callback intake stopped")
                }
                if (!isReady) {
                    val waiting = CallbackDrainResult(
                        drained = false,
                        persistedEvents = 0,
                        replayedEvents = 0,
                        duplicateBatches = 0,
                        quarantinedBatches = 0,
                        blockedReason = "waiting for stream credentials",
                        retryable = true,
                        kind = CallbackDrainKind.PENDING
                    )
                    latestResult = waiting
                    report(CallbackIntakeCondition.WAITING, waiting)
                    sleepMs(EMPTY_WAIT_MS)
                    continue
                }

                val result = try {
                    drain()
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    if (!running.get() || Thread.currentThread().isInterrupted) {
                        throw InterruptedException("callback intake stopped")
                    }
                    faultResult(error)
                }
                if (!running.get() || Thread.currentThread().isInterrupted) {
                    if (result.kind == CallbackDrainKind.PROGRESS) record(result)
                    throw InterruptedException("callback intake stopped")
                }
                record(result)
                val condition = when (result.kind) {
                    CallbackDrainKind.EMPTY, CallbackDrainKind.PROGRESS -> CallbackIntakeCondition.RUNNING
                    CallbackDrainKind.PENDING -> CallbackIntakeCondition.PENDING
                    CallbackDrainKind.FAULT -> CallbackIntakeCondition.FAULT
                }
                report(condition, result)

                when (result.kind) {
                    CallbackDrainKind.PROGRESS -> backoffIndex = 0
                    CallbackDrainKind.EMPTY -> sleepMs(EMPTY_WAIT_MS)
                    CallbackDrainKind.PENDING, CallbackDrainKind.FAULT -> {
                        sleepMs(BACKOFF_MS[backoffIndex])
                        backoffIndex = (backoffIndex + 1).coerceAtMost(BACKOFF_MS.lastIndex)
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            running.set(false)
            report(CallbackIntakeCondition.STOPPED, latestResult, force = true)
            runCatching(onStopped)
        }
    }

    private fun faultResult(error: Throwable) = CallbackDrainResult(
        drained = false,
        persistedEvents = 0,
        replayedEvents = 0,
        duplicateBatches = 0,
        quarantinedBatches = 0,
        blockedReason = "${error::class.java.simpleName}: ${error.message ?: "no message"}".take(512),
        retryable = true,
        fault = error,
        kind = CallbackDrainKind.FAULT
    )

    private fun emptyResult() = CallbackDrainResult(
        drained = true,
        persistedEvents = 0,
        replayedEvents = 0,
        duplicateBatches = 0,
        quarantinedBatches = 0,
        kind = CallbackDrainKind.EMPTY
    )

    companion object {
        const val EMPTY_WAIT_MS = 250L
        private val BACKOFF_MS = longArrayOf(100L, 250L, 500L, 1_000L)
        private const val SUMMARY_INTERVAL_NANOS = 30_000_000_000L
    }
}
