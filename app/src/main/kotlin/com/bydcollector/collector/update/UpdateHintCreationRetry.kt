package com.bydcollector.collector.update

/** Bounds native-window creation attempts for one cached result; presentation remains first-draw-owned. */
internal class UpdateHintCreationRetry(
    private val nowElapsedMs: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val isEligible: (Long) -> Boolean,
    private val startAttempt: (resultId: Long, attemptToken: Long) -> Unit,
    private val onEvent: (name: String, detail: String) -> Unit = { _, _ -> }
) {
    private var resultId: Long? = null
    private var nextAttemptToken = 0L
    private var attemptsStarted = 0
    private var technicalFailures = 0
    private var activeAttemptToken: Long? = null
    private var attachedAttemptToken: Long? = null
    private var attached = false
    private var exhausted = false
    private var retryAtElapsedMs: Long? = null
    private var retryRunnable: Runnable? = null
    private var retryGeneration = 0L
    private var lastAttemptToken: Long? = null

    fun offer(resultId: Long?) {
        if (this.resultId != resultId) replaceResult(resultId)
        val currentId = resultId ?: return
        // Once attached, creation is terminal and first draw owns consumption.
        // In particular, the now-consumed presentation gate must not invalidate
        // the callback token needed to maintain the visible window.
        if (attached) return
        if (!isEligible(currentId)) {
            cancelPending("not_eligible")
            return
        }
        if (exhausted || activeAttemptToken != null) return

        val retryAt = retryAtElapsedMs
        if (retryAt != null && nowElapsedMs() < retryAt) {
            scheduleRetry(currentId, retryAt)
            return
        }
        if (attemptsStarted >= MAX_ATTEMPTS) {
            exhausted = true
            return
        }

        cancelRetry(clearDeadline = true)
        attemptsStarted++
        val token = ++nextAttemptToken
        lastAttemptToken = token
        activeAttemptToken = token
        emit(
            "hint_creation_attempt",
            "result_id=$currentId attempt_token=$token attempts=$attemptsStarted/$MAX_ATTEMPTS reason=${if (technicalFailures > 0) "technical_retry" else "available_result"}"
        )
        try {
            startAttempt(currentId, token)
        } catch (error: RuntimeException) {
            onTechnicalFailure(currentId, token, "preparation", error::class.java.simpleName)
        }
    }

    /** A preparation/addView failure is the only event that advances the failure backoff. */
    fun onTechnicalFailure(
        resultId: Long,
        attemptToken: Long,
        phase: String = "creation",
        errorClass: String = "Unknown"
    ): Boolean {
        if (this.resultId != resultId || activeAttemptToken != attemptToken) return false
        activeAttemptToken = null
        technicalFailures++
        if (attemptsStarted >= MAX_ATTEMPTS) {
            exhausted = true
            cancelRetry(clearDeadline = true)
            emit(
                "hint_creation_exhausted",
                failureDetail(resultId, attemptToken, phase, errorClass, "attempts=$attemptsStarted/$MAX_ATTEMPTS reason=technical_failure")
            )
            return true
        }
        val delay = RETRY_DELAYS_MS[(technicalFailures - 1).coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        retryAtElapsedMs = nowElapsedMs() + delay
        scheduleRetry(resultId, checkNotNull(retryAtElapsedMs))
        emit(
            "hint_creation_retry_scheduled",
            failureDetail(
                resultId,
                attemptToken,
                phase,
                errorClass,
                "attempts=$attemptsStarted/$MAX_ATTEMPTS delay_ms=$delay retry_at_elapsed_ms=$retryAtElapsedMs"
            )
        )
        return true
    }

    /** Permission/display/access races before a window attempt begins do not spend the budget. */
    fun onSkippedBeforeCreation(resultId: Long, attemptToken: Long, reason: String = "not_eligible"): Boolean {
        if (this.resultId != resultId || activeAttemptToken != attemptToken) return false
        val attemptsBefore = attemptsStarted
        activeAttemptToken = null
        attemptsStarted = (attemptsStarted - 1).coerceAtLeast(0)
        emit(
            "hint_creation_skipped",
            "result_id=$resultId attempt_token=$attemptToken attempts=$attemptsBefore/$MAX_ATTEMPTS refunded_to=$attemptsStarted/$MAX_ATTEMPTS reason=$reason"
        )
        return true
    }

    /** Successful WindowManager attachment is terminal for automatic creation retries. */
    fun onAttached(resultId: Long, attemptToken: Long): Boolean {
        if (this.resultId != resultId || activeAttemptToken != attemptToken) return false
        activeAttemptToken = null
        attachedAttemptToken = attemptToken
        attached = true
        cancelRetry(clearDeadline = true)
        return true
    }

    /** Cancellation fences old callbacks but preserves attempts, exhaustion, and any retry deadline. */
    fun cancelPending(reason: String = "lifecycle") {
        val hadLiveWork = hasLiveWork()
        val cancelledToken = activeAttemptToken ?: attachedAttemptToken ?: lastAttemptToken
        val attemptsBefore = attemptsStarted
        cancelRetry(clearDeadline = false)
        refundUncreatedAttempt()
        attachedAttemptToken = null
        val currentResultId = resultId
        if (hadLiveWork && currentResultId != null) {
            emit(
                "hint_creation_cancelled",
                "result_id=$currentResultId attempt_token=${cancelledToken ?: "none"} attempts=$attemptsBefore/$MAX_ATTEMPTS reason=$reason"
            )
        }
    }

    fun cancelAttempt(resultId: Long, attemptToken: Long, reason: String = "dismissed") {
        if (this.resultId != resultId) return
        val wasActive = activeAttemptToken == attemptToken
        val wasAttached = attachedAttemptToken == attemptToken
        if (!wasActive && !wasAttached) return
        val attemptsBefore = attemptsStarted
        if (wasActive) {
            refundUncreatedAttempt()
        }
        if (wasAttached) attachedAttemptToken = null
        emit(
            "hint_creation_cancelled",
            "result_id=$resultId attempt_token=$attemptToken attempts=$attemptsBefore/$MAX_ATTEMPTS reason=$reason"
        )
    }

    private fun refundUncreatedAttempt() {
        if (activeAttemptToken != null) attemptsStarted = (attemptsStarted - 1).coerceAtLeast(0)
        activeAttemptToken = null
    }

    fun isCurrentCreationAttempt(resultId: Long, attemptToken: Long): Boolean =
        this.resultId == resultId && activeAttemptToken == attemptToken

    fun isCurrentAttachedAttempt(resultId: Long, attemptToken: Long): Boolean =
        this.resultId == resultId && attached && attachedAttemptToken == attemptToken

    private fun replaceResult(newResultId: Long?) {
        cancelPending("result_replaced")
        cancelRetry(clearDeadline = true)
        resultId = newResultId
        attemptsStarted = 0
        technicalFailures = 0
        activeAttemptToken = null
        attachedAttemptToken = null
        attached = false
        exhausted = false
        lastAttemptToken = null
    }

    private fun scheduleRetry(resultId: Long, deadline: Long) {
        if (retryRunnable != null && retryAtElapsedMs == deadline) return
        cancelRetry(clearDeadline = false)
        val generation = retryGeneration
        lateinit var task: Runnable
        task = Runnable {
            if (generation != retryGeneration || retryRunnable !== task || this.resultId != resultId ||
                retryAtElapsedMs != deadline) return@Runnable
            retryRunnable = null
            if (!isEligible(resultId)) return@Runnable
            retryAtElapsedMs = null
            offer(resultId)
        }
        retryRunnable = task
        postDelayed(task, (deadline - nowElapsedMs()).coerceAtLeast(0L))
    }

    private fun cancelRetry(clearDeadline: Boolean) {
        retryRunnable?.let(removeCallbacks)
        retryRunnable = null
        retryGeneration++
        if (clearDeadline) retryAtElapsedMs = null
    }

    private fun hasLiveWork(): Boolean =
        retryRunnable != null || activeAttemptToken != null || attachedAttemptToken != null

    private fun failureDetail(
        resultId: Long,
        attemptToken: Long,
        phase: String,
        errorClass: String,
        outcome: String
    ): String =
        "result_id=$resultId attempt_token=$attemptToken phase=$phase error_class=$errorClass $outcome"

    private fun emit(name: String, detail: String) {
        runCatching { onEvent(name, detail) }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)
    }
}
