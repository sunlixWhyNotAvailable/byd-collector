package com.bydcollector.collector.update

import android.os.SystemClock
import java.util.concurrent.Executor

/**
 * Process-owned update check state. It deliberately has no Activity or Context
 * references, so an Activity can come and go without losing an in-flight check
 * or an available result.
 */
class UpdateCheckSession(
    private val dispatch: ((() -> Unit) -> Unit),
    private val checker: () -> UpdateCheckResult,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    constructor(executor: Executor, checker: () -> UpdateCheckResult) : this(
        dispatch = { task -> executor.execute(Runnable { task() }) },
        checker = checker,
        elapsedRealtimeMs = { SystemClock.elapsedRealtime() }
    )

    data class Snapshot(
        val uiState: UpdateUiState,
        val inFlight: Boolean,
        val revision: Long,
        val availableResultId: Long?,
        val generation: Long = 0L,
        val completion: Completion? = null
    )

    /** Immutable physical-check outcome, independent from UI visibility/dismissal. */
    data class Completion(
        val token: Long,
        val generation: Long,
        val result: UpdateCheckResult,
        val completedAtElapsedMs: Long
    )

    private val lock = Any()
    private val listeners = mutableSetOf<() -> Unit>()
    private var uiState: UpdateUiState = UpdateUiState.Hidden
    private var presentationManual = false
    private var inFlight = false
    private var inFlightManual = false
    private var presentationInvalidated = false
    private var availableResultId: Long? = null
    private var revision = 0L
    private var nextToken = 0L
    private var activeToken: Long? = null
    private var activeGeneration = 0L
    private var sessionGeneration = 0L
    private val completions = mutableListOf<Completion>()

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            uiState = uiState,
            inFlight = inFlight,
            revision = revision,
            availableResultId = availableResultId,
            generation = sessionGeneration,
            completion = completions.lastOrNull()
        )
    }

    /** Returns every completion after [token], preventing listener coalescing from losing an event. */
    fun completionsAfter(token: Long): List<Completion> = synchronized(lock) {
        completions.filter { it.token > token }
    }

    /** Releases completion history already consumed by the process runtime. */
    fun acknowledgeCompletionsThrough(token: Long) {
        synchronized(lock) { completions.removeAll { it.token <= token } }
    }

    fun hasCurrentManualRequest(): Boolean = synchronized(lock) {
        inFlight && inFlightManual && activeGeneration == sessionGeneration
    }

    /** Returns false when an existing physical request is still running. */
    fun request(manual: Boolean): Boolean {
        val token: Long
        var joined = false
        synchronized(lock) {
            if (inFlight) {
                if (manual && (!inFlightManual || presentationInvalidated) && activeGeneration == sessionGeneration) {
                    // A manual action can opt into the existing automatic
                    // request without creating a second physical HTTP call.
                    inFlightManual = true
                    presentationInvalidated = false
                    uiState = UpdateUiState.Checking
                    revision++
                    joined = true
                }
                if (!joined) return false
                token = checkNotNull(activeToken)
            } else {
                inFlight = true
                inFlightManual = manual
                presentationManual = false
                presentationInvalidated = false
                uiState = if (manual) UpdateUiState.Checking else UpdateUiState.Hidden
                availableResultId = null
                token = ++nextToken
                activeToken = token
                activeGeneration = sessionGeneration
                revision++
            }
        }
        notifyListeners()

        if (joined) return false
        try {
            dispatch { settle(token) }
        } catch (error: Throwable) {
            settle(token, error)
        }
        return true
    }

    /** Hides an offer and invalidates any late callback that could reopen it. */
    fun dismiss(): Boolean {
        val wasAvailable: Boolean
        var changed = false
        var notify = false
        synchronized(lock) {
            wasAvailable = uiState is UpdateUiState.Available
            if (inFlight) presentationInvalidated = true
            if (uiState != UpdateUiState.Hidden) {
                uiState = UpdateUiState.Hidden
                presentationManual = false
                availableResultId = null
                changed = true
            }
            notify = inFlight || changed
            if (notify) revision++
        }
        if (notify) notifyListeners()
        return wasAvailable
    }

    /** Hides current presentation for the download handoff without cancelling the check. */
    fun clearPresentation() {
        dismiss()
    }

    /**
     * Fences automatic work for a sleep/wake boundary without cancelling a
     * physical request. A manual request (including one joined to an automatic
     * flight) remains current; an automatic-only flight completes as stale.
     */
    fun invalidateAutomatic() {
        synchronized(lock) {
            val preserveManual = inFlight && inFlightManual && activeGeneration == sessionGeneration
            sessionGeneration++
            if (inFlight) {
                if (preserveManual) activeGeneration = sessionGeneration
                else presentationInvalidated = true
            }
            if (!preserveManual && !presentationManual) {
                uiState = UpdateUiState.Hidden
                availableResultId = null
            }
            revision++
        }
        notifyListeners()
    }

    /** Invalidates callbacks and clears this process session for shutdown/new runtime. */
    fun reset() {
        synchronized(lock) {
            sessionGeneration++
            // Keep a physical request busy until its worker settles. Reset only
            // invalidates the presentation of that request.
            if (inFlight) presentationInvalidated = true
            else inFlightManual = false
            uiState = UpdateUiState.Hidden
            presentationManual = false
            availableResultId = null
            revision++
        }
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        synchronized(lock) { listeners += listener }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(lock) { listeners -= listener }
    }

    private fun settle(token: Long) {
        val result = runCatching { checker() }
            .getOrElse { error -> UpdateCheckResult.Error(error.message ?: error::class.java.simpleName) }
        settle(token, result)
    }

    private fun settle(token: Long, error: Throwable) {
        settle(token, UpdateCheckResult.Error(error.message ?: error::class.java.simpleName))
    }

    private fun settle(token: Long, result: UpdateCheckResult) {
        synchronized(lock) {
            if (!inFlight || token != activeToken) return
            inFlight = false
            completions += Completion(
                token = token,
                generation = activeGeneration,
                result = result,
                completedAtElapsedMs = elapsedRealtimeMs()
            )
            val publish = !presentationInvalidated && activeGeneration == sessionGeneration
            activeToken = null
            if (publish) {
                uiState = when (result) {
                    is UpdateCheckResult.Available -> UpdateUiState.Available(result.info)
                    UpdateCheckResult.UpToDate -> if (inFlightManual) {
                        UpdateUiState.UpToDate
                    } else {
                        UpdateUiState.Hidden
                    }
                    is UpdateCheckResult.Error -> if (inFlightManual) {
                        UpdateUiState.Error(result.message)
                    } else {
                        UpdateUiState.Hidden
                    }
                }
                availableResultId = if (result is UpdateCheckResult.Available) token else null
                presentationManual = inFlightManual
            } else {
                uiState = UpdateUiState.Hidden
                availableResultId = null
                presentationManual = false
            }
            inFlightManual = false
            presentationInvalidated = false
            revision++
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        val current = synchronized(lock) { listeners.toList() }
        current.forEach { listener -> runCatching { listener() } }
    }
}
