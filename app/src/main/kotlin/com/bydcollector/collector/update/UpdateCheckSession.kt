package com.bydcollector.collector.update

import java.util.concurrent.Executor

/**
 * Process-owned update check state. It deliberately has no Activity or Context
 * references, so an Activity can come and go without losing an in-flight check
 * or an available result.
 */
class UpdateCheckSession(
    private val dispatch: ((() -> Unit) -> Unit),
    private val checker: () -> UpdateCheckResult
) {
    constructor(executor: Executor, checker: () -> UpdateCheckResult) : this(
        dispatch = { task -> executor.execute(Runnable { task() }) },
        checker = checker
    )

    data class Snapshot(
        val uiState: UpdateUiState,
        val inFlight: Boolean,
        val revision: Long,
        val availableResultId: Long?
    )

    private val lock = Any()
    private val listeners = mutableSetOf<() -> Unit>()
    private var uiState: UpdateUiState = UpdateUiState.Hidden
    private var inFlight = false
    private var inFlightManual = false
    private var presentationInvalidated = false
    private var availableResultId: Long? = null
    private var revision = 0L
    private var nextToken = 0L
    private var activeToken: Long? = null
    private var activeGeneration = 0L
    private var sessionGeneration = 0L

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            uiState = uiState,
            inFlight = inFlight,
            revision = revision,
            availableResultId = availableResultId
        )
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

    /** Invalidates callbacks and clears this process session for shutdown/new runtime. */
    fun reset() {
        synchronized(lock) {
            sessionGeneration++
            // Keep a physical request busy until its worker settles. Reset only
            // invalidates the presentation of that request.
            if (inFlight) presentationInvalidated = true
            else inFlightManual = false
            uiState = UpdateUiState.Hidden
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
            } else {
                uiState = UpdateUiState.Hidden
                availableResultId = null
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
