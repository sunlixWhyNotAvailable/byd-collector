package com.bydcollector.collector.runtime

import android.os.DeadObjectException
import android.os.DeadSystemException
import android.os.RemoteException

/** Results of the off-main identity preflight, classified without weakening endpoint checks. */
internal enum class RuntimePreflightResult {
    COMPATIBLE,
    CLIENT_MISMATCH,
    OWNER_UNAVAILABLE,
    REJECTED
}

internal enum class RuntimeRecoveryStatus {
    WAITING,
    CHECKING,
    RETRYING,
    FAILED,
    READY,
    BOOTSTRAPPING
}

/** Foreground recovery may use either the Activity window or a focused app-owned Dialog window. */
internal fun runtimeRecoveryEligible(
    activityResumed: Boolean,
    activityWindowFocused: Boolean,
    ownedDialogWindowFocused: Boolean
): Boolean = activityResumed && (activityWindowFocused || ownedDialogWindowFocused)

/** Classifies only known owner replacement and Binder-unavailable failures as recoverable. */
internal fun classifyRuntimePreflightFailure(error: Throwable): RuntimePreflightResult {
    val causes = generateSequence(error as Throwable?) { it.cause }.take(16).toList()
    if (causes.any { it is SecurityException }) return RuntimePreflightResult.REJECTED

    val messages = causes.mapNotNull { it.message }.toSet()
    if (messages.any { it in CLIENT_MISMATCH_MESSAGES }) return RuntimePreflightResult.CLIENT_MISMATCH
    if (messages.any { it in OWNER_UNAVAILABLE_MESSAGES } || causes.any {
            it is DeadObjectException || it is DeadSystemException || it is RemoteException
        }) return RuntimePreflightResult.OWNER_UNAVAILABLE
    return RuntimePreflightResult.REJECTED
}

private val CLIENT_MISMATCH_MESSAGES = setOf(
    "Runtime protocol mismatch",
    "Runtime/client build mismatch; open BYD Collector from its launcher"
)

private val OWNER_UNAVAILABLE_MESSAGES = setOf(
    "Collector runtime is not installed",
    "Collector runtime returned no response"
)

/**
 * Foreground-only owner readiness gate. Callbacks are expected on the main thread; probe work
 * itself is supplied by the caller and may complete asynchronously.
 */
internal class RuntimeForegroundRecovery(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val probe: ((RuntimePreflightResult) -> Unit) -> Unit,
    private val onStatusChanged: (RuntimeRecoveryStatus) -> Unit,
    private val onClientMismatch: () -> Unit
) {
    private var eligible = false
    private var generation = 0L
    private var probeInFlight = false
    private var retryAttempt = 0
    private var bootstrapAttempted = false
    private var pendingRetry: Runnable? = null
    private var status = RuntimeRecoveryStatus.WAITING

    fun setEligible(isEligible: Boolean) {
        if (eligible == isEligible) return
        eligible = isEligible
        generation++
        cancelRetry()
        probeInFlight = false
        if (!eligible) {
            // A modal can take window focus while this Activity is still RESUMED. Keep a
            // previously proven owner usable for ordinary UI reads until a real pause.
            if (status != RuntimeRecoveryStatus.READY) publish(RuntimeRecoveryStatus.WAITING)
            return
        }
        if (status == RuntimeRecoveryStatus.READY) return
        if (bootstrapAttempted) {
            publish(RuntimeRecoveryStatus.FAILED)
            return
        }
        checkNow()
    }

    /** A lifecycle pause starts a new foreground episode and requires a fresh owner check. */
    fun endForeground() {
        setEligible(false)
        retryAttempt = 0
        if (status == RuntimeRecoveryStatus.READY) publish(RuntimeRecoveryStatus.WAITING)
    }

    fun retry() {
        if (!eligible) return
        // A user retry starts a fresh recovery episode; lifecycle re-entry alone never does.
        bootstrapAttempted = false
        generation++
        cancelRetry()
        probeInFlight = false
        retryAttempt = 0
        checkNow()
    }

    /** Used when the compatibility probe passed but a required cold-start load failed. */
    fun failCurrentRecovery() {
        generation++
        cancelRetry()
        probeInFlight = false
        // If focus was lost during cold initialization, force a new preflight when it returns.
        publish(if (eligible) RuntimeRecoveryStatus.FAILED else RuntimeRecoveryStatus.WAITING)
    }

    private fun checkNow() {
        if (!eligible || probeInFlight || bootstrapAttempted) return
        val attemptGeneration = generation
        probeInFlight = true
        publish(RuntimeRecoveryStatus.CHECKING)
        probe { result ->
            if (!eligible || attemptGeneration != generation) return@probe
            probeInFlight = false
            when (result) {
                RuntimePreflightResult.COMPATIBLE -> {
                    retryAttempt = 0
                    publish(RuntimeRecoveryStatus.READY)
                }
                RuntimePreflightResult.CLIENT_MISMATCH -> {
                    bootstrapAttempted = true
                    publish(RuntimeRecoveryStatus.BOOTSTRAPPING)
                    onClientMismatch()
                }
                RuntimePreflightResult.OWNER_UNAVAILABLE -> retryOrFail()
                RuntimePreflightResult.REJECTED -> publish(RuntimeRecoveryStatus.FAILED)
            }
        }
    }

    private fun retryOrFail() {
        val delayMs = RETRY_DELAYS_MS.getOrNull(retryAttempt)
        if (delayMs == null) {
            publish(RuntimeRecoveryStatus.FAILED)
            return
        }
        retryAttempt++
        val retryGeneration = generation
        lateinit var task: Runnable
        task = Runnable {
            if (pendingRetry !== task) return@Runnable
            pendingRetry = null
            if (eligible && retryGeneration == generation) checkNow()
        }
        pendingRetry = task
        publish(RuntimeRecoveryStatus.RETRYING)
        schedule(task, delayMs)
    }

    private fun cancelRetry() {
        pendingRetry?.let(cancel)
        pendingRetry = null
    }

    private fun publish(next: RuntimeRecoveryStatus) {
        if (status == next) return
        status = next
        onStatusChanged(next)
    }

    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)
    }
}
