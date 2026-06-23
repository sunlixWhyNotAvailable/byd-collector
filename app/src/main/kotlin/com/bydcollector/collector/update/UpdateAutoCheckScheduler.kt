package com.bydcollector.collector.update

import android.os.SystemClock

sealed interface UpdateAutoCheckAction {
    data object None : UpdateAutoCheckAction
    data object Run : UpdateAutoCheckAction
    data class Schedule(val delayMs: Long) : UpdateAutoCheckAction
}

//tracks the one startup auto-check delay separately from foreground visibility
class UpdateAutoCheckScheduler(
    private val delayMs: Long,
    private val nowMs: () -> Long
) {
    private var runtimeStartedAtMs: Long? = null
    private var pendingAfterBackgroundExpiry = false
    private var consumed = false

    fun onRuntimeStarted(enabled: Boolean): UpdateAutoCheckAction {
        if (!enabled || consumed) return UpdateAutoCheckAction.None
        if (runtimeStartedAtMs == null) {
            runtimeStartedAtMs = nowMs()
            consumed = false
            pendingAfterBackgroundExpiry = false
        }
        val elapsedMs = nowMs() - (runtimeStartedAtMs ?: nowMs())
        return UpdateAutoCheckAction.Schedule((delayMs - elapsedMs).coerceAtLeast(0L))
    }

    fun onForeground(enabled: Boolean): UpdateAutoCheckAction {
        if (!enabled || consumed) return UpdateAutoCheckAction.None
        val startedAt = runtimeStartedAtMs ?: return UpdateAutoCheckAction.None
        if (pendingAfterBackgroundExpiry || nowMs() - startedAt >= delayMs) {
            pendingAfterBackgroundExpiry = false
            consumed = true
            return UpdateAutoCheckAction.Run
        }
        return UpdateAutoCheckAction.None
    }

    fun onTimerElapsed(enabled: Boolean, foreground: Boolean): UpdateAutoCheckAction {
        if (!enabled || consumed || runtimeStartedAtMs == null) return UpdateAutoCheckAction.None
        if (foreground) {
            consumed = true
            return UpdateAutoCheckAction.Run
        }
        pendingAfterBackgroundExpiry = true
        return UpdateAutoCheckAction.None
    }

    fun onAutoCheckEnabledChanged(enabled: Boolean): UpdateAutoCheckAction {
        runtimeStartedAtMs = null
        pendingAfterBackgroundExpiry = false
        consumed = false
        return onRuntimeStarted(enabled)
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
}
