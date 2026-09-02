package com.bydcollector.collector.update

import android.os.SystemClock

sealed interface UpdateAutoCheckAction {
    data object None : UpdateAutoCheckAction
    data object Run : UpdateAutoCheckAction
    data class Schedule(val delayMs: Long) : UpdateAutoCheckAction
}

//Tracks the process startup delay independently of foreground visibility.
class UpdateAutoCheckScheduler(
    private val delayMs: Long,
    private val nowMs: () -> Long,
    private val suppressionMs: Long = 60 * 60 * 1000L
) {
    private var initialized = false
    private var deadlineMs: Long? = null
    private var suppressedUntilMs: Long? = null

    fun onRuntimeStarted(enabled: Boolean): UpdateAutoCheckAction {
        if (!enabled) return UpdateAutoCheckAction.None
        if (!initialized) {
            val startedAt = nowMs()
            initialized = true
            deadlineMs = startedAt + delayMs
        }
        if (isSuppressed()) return UpdateAutoCheckAction.None
        val deadline = deadlineMs ?: return UpdateAutoCheckAction.None
        return UpdateAutoCheckAction.Schedule((deadline - nowMs()).coerceAtLeast(0L))
    }

    fun onForeground(enabled: Boolean): UpdateAutoCheckAction {
        if (!enabled || isSuppressed()) return UpdateAutoCheckAction.None
        val deadline = deadlineMs ?: return UpdateAutoCheckAction.None
        val remaining = deadline - nowMs()
        return if (remaining > 0L) {
            UpdateAutoCheckAction.Schedule(remaining)
        } else {
            UpdateAutoCheckAction.Run
        }
    }

    fun onTimerElapsed(enabled: Boolean, foreground: Boolean): UpdateAutoCheckAction {
        if (!enabled || isSuppressed()) return UpdateAutoCheckAction.None
        val deadline = deadlineMs ?: return UpdateAutoCheckAction.None
        val remaining = deadline - nowMs()
        if (remaining > 0L) {
            return UpdateAutoCheckAction.Schedule(remaining)
        }
        return if (foreground) UpdateAutoCheckAction.Run else UpdateAutoCheckAction.None
    }

    fun onAutoCheckEnabledChanged(enabled: Boolean): UpdateAutoCheckAction {
        if (!enabled) {
            // Turning the switch off cancels this runtime's pending timer, but
            // intentionally leaves a Close suppression window intact.
            initialized = false
            deadlineMs = null
            return UpdateAutoCheckAction.None
        }
        return onRuntimeStarted(enabled = true)
    }

    /** Arms the existing deadline after a foreground timer is torn down. */
    fun onBackground(enabled: Boolean): UpdateAutoCheckAction {
        if (!enabled || isSuppressed()) return UpdateAutoCheckAction.None
        val now = nowMs()
        if (deadlineMs == null) {
            // The previous accepted request consumed its deadline. A later
            // stop/return is a new automatic-check cycle.
            initialized = true
            deadlineMs = now + delayMs
        }
        val deadline = deadlineMs ?: return UpdateAutoCheckAction.None
        return UpdateAutoCheckAction.Schedule((deadline - now).coerceAtLeast(0L))
    }

    /** Marks an accepted request; its deadline is consumed only after acceptance. */
    fun onCheckStarted() {
        // A manual request during Close suppression must not erase the latent
        // post-TTL eligibility established by onDismissed().
        if (!isSuppressed()) deadlineMs = null
    }

    /** Starts the process-local close suppression window. */
    fun onDismissed() {
        val now = nowMs()
        suppressedUntilMs = now + suppressionMs
        initialized = true
        deadlineMs = now
    }

    /** Clears all process state for shutdown or a new runtime session. */
    fun reset() {
        initialized = false
        deadlineMs = null
        suppressedUntilMs = null
    }

    fun diagnosticState(): String =
        "initialized=$initialized deadline_elapsed_ms=${deadlineMs ?: "null"} " +
            "suppressed_until_elapsed_ms=${suppressedUntilMs ?: "null"}"

    private fun isSuppressed(): Boolean {
        val until = suppressedUntilMs ?: return false
        if (nowMs() < until) return true
        suppressedUntilMs = null
        return false
    }
}

//keeps startup update timing process-wide so services can age the 30s delay before the ui opens
object UpdateAutoCheckRuntime {
    private const val AUTO_CHECK_DELAY_MS = 30_000L
    private val scheduler = UpdateAutoCheckScheduler(
        delayMs = AUTO_CHECK_DELAY_MS,
        nowMs = { SystemClock.elapsedRealtime() }
    )

    @Synchronized
    fun onRuntimeStarted(enabled: Boolean): UpdateAutoCheckAction {
        return scheduler.onRuntimeStarted(enabled)
    }

    @Synchronized
    fun onForeground(enabled: Boolean): UpdateAutoCheckAction {
        return scheduler.onForeground(enabled)
    }

    @Synchronized
    fun onTimerElapsed(enabled: Boolean, foreground: Boolean): UpdateAutoCheckAction {
        return scheduler.onTimerElapsed(enabled = enabled, foreground = foreground)
    }

    @Synchronized
    fun onAutoCheckEnabledChanged(enabled: Boolean): UpdateAutoCheckAction {
        return scheduler.onAutoCheckEnabledChanged(enabled)
    }

    @Synchronized
    fun onBackground(enabled: Boolean): UpdateAutoCheckAction {
        return scheduler.onBackground(enabled)
    }

    @Synchronized
    fun onCheckStarted() {
        scheduler.onCheckStarted()
    }

    @Synchronized
    fun onDismissed() {
        scheduler.onDismissed()
    }

    @Synchronized
    fun reset() {
        scheduler.reset()
    }

    @Synchronized
    fun diagnosticState(): String {
        return scheduler.diagnosticState()
    }
}
