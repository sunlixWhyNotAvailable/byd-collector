package com.bydcollector.collector.data.callback

enum class CallbackQueuePhase { UNKNOWN, HEALTHY, CATCHING_UP, WAITING, ERROR }

data class CallbackQueueState(
    val phase: CallbackQueuePhase,
    val reason: String? = null
)

/** Tracks only batches observed in-flight by the existing callback download path. */
class CallbackQueueStatusTracker(
    private val monotonicMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val wallTimeMs: () -> Long = System::currentTimeMillis,
    private val pendingAgeThresholdMs: Long = DEFAULT_PENDING_AGE_THRESHOLD_MS,
    private val progressFreshnessMs: Long = DEFAULT_PROGRESS_FRESHNESS_MS,
    private val caughtUpStabilityMs: Long = DEFAULT_CAUGHT_UP_STABILITY_MS
) {
    private var workerActive = false
    private var downloadStartedAtMs: Long? = null
    private var pendingBatchObserved = false
    private var pendingHeadWallMs: Long? = null
    private var lastProgressAtMs: Long? = null
    private var caughtUpAtMs: Long? = null
    private var holdCatchingUpAfterEmpty = false
    private var catchingUpHoldUntilMs: Long? = null
    private var lastConfirmedEmptyAtMs: Long? = null
    private var healthyEstablished = false
    private var waitingReason: String? = null
    private var errorReason: String? = null
    private var hadKnownActivity = false

    init {
        require(pendingAgeThresholdMs > 0)
        require(progressFreshnessMs > 0)
        require(caughtUpStabilityMs >= 0)
    }

    @Synchronized
    fun start() {
        clear()
        workerActive = true
    }

    @Synchronized
    fun stop() {
        clear()
        workerActive = false
    }

    @Synchronized
    fun downloadStarted() {
        if (!workerActive) return
        advanceStableCaughtUp(monotonicMs())
        downloadStartedAtMs = monotonicMs()
        hadKnownActivity = true
    }

    /** Called after a descriptor is downloaded and before its import/ACK begins. */
    @Synchronized
    fun pendingBatchObserved(oldestEventWallMs: Long?) {
        if (!workerActive) return
        val now = monotonicMs()
        advanceStableCaughtUp(now)
        val headAge = oldestEventWallMs?.let { (wallTimeMs() - it).coerceAtLeast(0L) }
        if (!healthyEstablished && (headAge == null || headAge > pendingAgeThresholdMs)) caughtUpAtMs = null
        downloadStartedAtMs = null
        pendingBatchObserved = true
        pendingHeadWallMs = oldestEventWallMs
        waitingReason = null
        hadKnownActivity = true
        refreshActualCatchingUp(wallTimeMs(), now)
    }

    /** Durable raw commit is genuine progress, but does not clear the unacknowledged head. */
    @Synchronized
    fun rawCommitCompleted() {
        if (!workerActive) return
        val now = monotonicMs()
        recordProgress(now)
        refreshActualCatchingUp(wallTimeMs(), now)
    }

    /** Clears only the batch whose exact ACK or quarantine succeeded. */
    @Synchronized
    fun pendingBatchCompleted() {
        if (!workerActive) return
        val now = monotonicMs()
        advanceStableCaughtUp(now)
        val actualCatchUp = isActuallyCatchingUp(wallTimeMs(), now)
        val holdAlreadyActive = isCatchingUpHoldActive(now)
        val wasCatchingUp = actualCatchUp || holdAlreadyActive
        pendingBatchObserved = false
        pendingHeadWallMs = null
        downloadStartedAtMs = null
        if (wasCatchingUp) {
            healthyEstablished = false
            caughtUpAtMs = null
            holdCatchingUpAfterEmpty = false
            if (actualCatchUp || !holdAlreadyActive) catchingUpHoldUntilMs = now + caughtUpStabilityMs
        }
        recordProgress(now)
    }

    /** An explicit empty helper response is the only authority for caught-up. */
    @Synchronized
    fun confirmedEmpty() {
        if (!workerActive) return
        val now = monotonicMs()
        advanceStableCaughtUp(now)
        val priorDownloadStalled = downloadStartedAtMs?.let {
            (now - it).coerceAtLeast(0L) > pendingAgeThresholdMs
        } == true
        val actualCatchUp = isActuallyCatchingUp(wallTimeMs(), now)
        val holdAlreadyActive = isCatchingUpHoldActive(now)
        val wasCatchingUp = actualCatchUp || holdAlreadyActive
        if (priorDownloadStalled) {
            healthyEstablished = false
            caughtUpAtMs = null
        }
        pendingBatchObserved = false
        pendingHeadWallMs = null
        downloadStartedAtMs = null
        waitingReason = null
        errorReason = null
        if (wasCatchingUp) {
            healthyEstablished = false
            if (actualCatchUp || caughtUpAtMs == null) caughtUpAtMs = null
            holdCatchingUpAfterEmpty = true
            if (!holdAlreadyActive) catchingUpHoldUntilMs = now + caughtUpStabilityMs
        }
        if (!healthyEstablished && caughtUpAtMs == null) caughtUpAtMs = now
        lastConfirmedEmptyAtMs = now
        hadKnownActivity = true
    }

    @Synchronized
    fun waiting(reason: String?) {
        if (!workerActive) return
        downloadStartedAtMs = null
        healthyEstablished = false
        caughtUpAtMs = null
        holdCatchingUpAfterEmpty = false
        catchingUpHoldUntilMs = null
        waitingReason = cleanReason(reason) ?: "callback stream is waiting for progress"
        errorReason = null
        hadKnownActivity = true
    }

    @Synchronized
    fun failed(reason: String?) {
        if (!workerActive) return
        downloadStartedAtMs = null
        healthyEstablished = false
        caughtUpAtMs = null
        holdCatchingUpAfterEmpty = false
        catchingUpHoldUntilMs = null
        errorReason = cleanReason(reason) ?: "callback stream failed"
        waitingReason = null
        hadKnownActivity = true
    }

    /** Uses the coordinator's durable commit/ACK wall timestamp, never its historical head as pending. */
    @Synchronized
    fun progressRecordedAt(wallMs: Long?) {
        if (!workerActive || wallMs == null) return
        val nowMono = monotonicMs()
        val ageMs = (wallTimeMs() - wallMs).coerceAtLeast(0L)
        val progressAt = (nowMono - ageMs).coerceAtLeast(0L)
        recordProgress(progressAt)
        refreshActualCatchingUp(wallTimeMs(), nowMono)
    }

    @Synchronized
    fun snapshot(): CallbackQueueState {
        val now = monotonicMs()
        advanceStableCaughtUp(now)
        return stateAt(wallTimeMs(), now)
    }

    private fun stateAt(nowWallMs: Long, nowMonoMs: Long): CallbackQueueState {
        if (!workerActive) return CallbackQueueState(CallbackQueuePhase.UNKNOWN)
        errorReason?.let { return CallbackQueueState(CallbackQueuePhase.ERROR, it) }
        waitingReason?.let { return CallbackQueueState(CallbackQueuePhase.WAITING, it) }

        downloadStartedAtMs?.let { startedAt ->
            val age = (nowMonoMs - startedAt).coerceAtLeast(0L)
            if (age > pendingAgeThresholdMs) {
                healthyEstablished = false
                return CallbackQueueState(
                    CallbackQueuePhase.WAITING,
                    "callback download has not returned for ${age / 1_000}s"
                )
            }
        }

        if (pendingBatchObserved) {
            val headWall = pendingHeadWallMs ?: return CallbackQueueState(
                if (isCatchingUpHoldActive(nowMonoMs)) CallbackQueuePhase.CATCHING_UP else CallbackQueuePhase.WAITING,
                if (isCatchingUpHoldActive(nowMonoMs)) "confirming callback queue progress"
                else "pending callback batch has no event timestamp to measure"
            )
            val headAge = (nowWallMs - headWall).coerceAtLeast(0L)
            if (headAge > pendingAgeThresholdMs) {
                val progressAge = lastProgressAtMs?.let { (nowMonoMs - it).coerceAtLeast(0L) }
                if (progressAge != null && progressAge <= progressFreshnessMs) {
                    beginCatchingUp()
                    return CallbackQueueState(
                        CallbackQueuePhase.CATCHING_UP,
                        "pending callback head is ${headAge / 1_000}s old; commit/ACK progress was ${progressAge / 1_000}s ago"
                    )
                }
                val staleFor = progressAge?.let { "no commit/ACK progress for ${it / 1_000}s" }
                    ?: "no commit/ACK progress observed yet"
                healthyEstablished = false
                caughtUpAtMs = null
                catchingUpHoldUntilMs = null
                return CallbackQueueState(
                    CallbackQueuePhase.WAITING,
                    "pending callback head is ${headAge / 1_000}s old; $staleFor"
                )
            }
            if (isCatchingUpHoldActive(nowMonoMs)) {
                return CallbackQueueState(CallbackQueuePhase.CATCHING_UP, "confirming callback queue progress")
            }
            if (healthyEstablished) return CallbackQueueState(CallbackQueuePhase.HEALTHY)
            return CallbackQueueState(CallbackQueuePhase.UNKNOWN,
                "pending callback head is within the ${pendingAgeThresholdMs / 1_000}s catch-up window")
        }

        caughtUpAtMs?.let { emptyAt ->
            if ((nowMonoMs - emptyAt).coerceAtLeast(0L) < caughtUpStabilityMs) {
                return if (holdCatchingUpAfterEmpty) {
                    CallbackQueueState(CallbackQueuePhase.CATCHING_UP, "confirming the empty callback queue")
                } else {
                    CallbackQueueState(CallbackQueuePhase.UNKNOWN, "confirming the empty callback queue")
                }
            }
            healthyEstablished = true
            caughtUpAtMs = null
            holdCatchingUpAfterEmpty = false
            catchingUpHoldUntilMs = null
            return CallbackQueueState(CallbackQueuePhase.HEALTHY)
        }

        if (isCatchingUpHoldActive(nowMonoMs)) {
            return CallbackQueueState(CallbackQueuePhase.CATCHING_UP, "confirming callback queue progress")
        }

        if (healthyEstablished) {
            val lastSignalAt = listOfNotNull(lastProgressAtMs, lastConfirmedEmptyAtMs).maxOrNull()
            val staleFor = lastSignalAt?.let { (nowMonoMs - it).coerceAtLeast(0L) }
            if (staleFor != null && staleFor > progressFreshnessMs) {
                healthyEstablished = false
                return CallbackQueueState(CallbackQueuePhase.WAITING,
                    "no commit/ACK progress or empty response for ${staleFor / 1_000}s")
            }
            return CallbackQueueState(CallbackQueuePhase.HEALTHY)
        }

        if (hadKnownActivity) {
            val progressAge = lastProgressAtMs?.let { (nowMonoMs - it).coerceAtLeast(0L) }
            if (progressAge != null && progressAge > progressFreshnessMs) {
                return CallbackQueueState(
                    CallbackQueuePhase.WAITING,
                    "no commit/ACK progress for ${progressAge / 1_000}s; queue has not been confirmed empty"
                )
            }
            return CallbackQueueState(
                CallbackQueuePhase.UNKNOWN,
                "awaiting an observed callback head or explicit empty response"
            )
        }
        return CallbackQueueState(CallbackQueuePhase.UNKNOWN)
    }

    private fun recordProgress(atMs: Long) {
        lastProgressAtMs = atMs
        waitingReason = null
        errorReason = null
        hadKnownActivity = true
    }

    private fun isActuallyCatchingUp(nowWallMs: Long, nowMonoMs: Long): Boolean {
        if (!pendingBatchObserved) return false
        val headWall = pendingHeadWallMs ?: return false
        val headAge = (nowWallMs - headWall).coerceAtLeast(0L)
        val progressAge = lastProgressAtMs?.let { (nowMonoMs - it).coerceAtLeast(0L) }
        return headAge > pendingAgeThresholdMs && progressAge != null && progressAge <= progressFreshnessMs
    }

    private fun refreshActualCatchingUp(nowWallMs: Long, nowMonoMs: Long) {
        if (isActuallyCatchingUp(nowWallMs, nowMonoMs)) beginCatchingUp()
    }

    private fun beginCatchingUp() {
        healthyEstablished = false
        caughtUpAtMs = null
        holdCatchingUpAfterEmpty = false
        catchingUpHoldUntilMs = null
    }

    private fun isCatchingUpHoldActive(nowMonoMs: Long): Boolean =
        catchingUpHoldUntilMs?.let { nowMonoMs < it } == true

    private fun advanceStableCaughtUp(nowMonoMs: Long) {
        val emptyAt = caughtUpAtMs ?: return
        if ((nowMonoMs - emptyAt).coerceAtLeast(0L) >= caughtUpStabilityMs) {
            healthyEstablished = true
            caughtUpAtMs = null
            holdCatchingUpAfterEmpty = false
            catchingUpHoldUntilMs = null
        }
    }

    private fun clear() {
        downloadStartedAtMs = null
        pendingBatchObserved = false
        pendingHeadWallMs = null
        lastProgressAtMs = null
        caughtUpAtMs = null
        holdCatchingUpAfterEmpty = false
        catchingUpHoldUntilMs = null
        lastConfirmedEmptyAtMs = null
        healthyEstablished = false
        waitingReason = null
        errorReason = null
        hadKnownActivity = false
    }

    private fun cleanReason(reason: String?): String? = reason?.trim()?.take(256)?.ifEmpty { null }

    companion object {
        const val DEFAULT_PENDING_AGE_THRESHOLD_MS = 5_000L
        const val DEFAULT_PROGRESS_FRESHNESS_MS = 5_000L
        const val DEFAULT_CAUGHT_UP_STABILITY_MS = 2_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
