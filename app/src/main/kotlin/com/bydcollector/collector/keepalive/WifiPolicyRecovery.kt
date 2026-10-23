package com.bydcollector.collector.keepalive

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.bydcollector.collector.service.CollectorSettings

/** Runs in the owner APP's existing serialized keep-alive executor; commands retain shell identity. */
internal class WifiPolicyRecovery(
    private val prefs: SharedPreferences,
    private val shellFactory: () -> KeepAliveShell,
    private val record: (String, String, String?) -> Unit
) {
    private val state = WifiPolicyState(prefs)
    private var lastGeneration = -1L
    private var nextAttemptAt = 0L

    fun reconcile() {
        if (stopped()) return
        var request: WifiPolicyState.Request? = null
        try {
            val enabled = enabled()
            state.initializeEnabledUser(enabled)
            if (!state.pending()) return
            val generation = state.generation()
            if (generation == lastGeneration && SystemClock.elapsedRealtime() < nextAttemptAt) return
            request = state.beginAttempt() ?: return
            lastGeneration = request.generation
            nextAttemptAt = SystemClock.elapsedRealtime() + com.bydcollector.collector.system.CollectorAutoStart.WATCHDOG_DELAY_MS
            event("attempt", request, "operation=${request.operation} captured=${request.captured}")
            var original: WifiPolicyState.Snapshot? = null
            if (request.captured) {
                try {
                    original = WifiPolicyState.Snapshot.decode(checkNotNull(request.snapshot) { "Saved Wi-Fi policy snapshot is missing" })
                } catch (error: Exception) {
                    if (request.operation != WifiPolicyState.RESTORE) throw error
                    event("restore_skipped", request, "snapshot_unusable\n${error.stackTraceToString()}")
                    state.finish(request)
                    return
                }
            } else if (request.operation == WifiPolicyState.RESTORE) {
                event("restore_skipped", request, "original_snapshot_not_saved; system policies unchanged")
                state.finish(request)
                return
            }
            val shell = shellFactory()
            if (original == null) {
                original = WifiPolicyState.Snapshot(read(shell, request, SLEEP), read(shell, request, OFF))
                if (!current(request) || !state.capture(request, original)) return
                event("snapshot_saved", request, "sleep=${original.sleepPolicy ?: "<absent>"} off=${original.offSwitch ?: "<absent>"}")
            }
            val target = if (request.operation == WifiPolicyState.APPLY) WifiPolicyState.Snapshot("2", "1") else original
            var failed = false
            for ((key, value) in listOf(SLEEP to target.sleepPolicy, OFF to target.offSwitch)) {
                if (!current(request)) return
                try {
                    val before = read(shell, request, key)
                    if (!current(request)) return
                    if (before != value) {
                        val command = if (value == null) "settings delete global $key" else "settings put global $key $value"
                        execute(shell, request, command)
                    }
                    if (!current(request)) return
                    val after = read(shell, request, key)
                    check(after == value) { "Wi-Fi policy readback mismatch: key=$key expected=$value actual=$after" }
                    event("verified", request, "key=$key before=${before ?: "<absent>"} target=${value ?: "<absent>"} after=${after ?: "<absent>"}")
                } catch (error: Exception) {
                    failed = true
                    event("failed", request, "key=$key\n${error.stackTraceToString()}")
                    if (Thread.currentThread().isInterrupted) return
                }
            }
            if (!failed && current(request) && state.finish(request)) event("complete", request, "operation=${request.operation}")
        } catch (error: Exception) {
            event("failed", request, error.stackTraceToString())
        }
    }

    private fun enabled() = prefs.getBoolean(CollectorSettings.KEY_KEEP_WIFI, false) ||
        prefs.getBoolean(CollectorSettings.KEY_KEEP_MOBILE_DATA, false)

    private fun stopped() = Thread.currentThread().isInterrupted ||
        prefs.getBoolean(CollectorSettings.KEY_USER_SHUTDOWN, false)

    private fun current(request: WifiPolicyState.Request): Boolean = !stopped() && state.isCurrent(request) &&
        (enabled() == (request.operation == WifiPolicyState.APPLY))

    private fun read(shell: KeepAliveShell, request: WifiPolicyState.Request, key: String): String? {
        val value = execute(shell, request, "settings get global $key").trim()
        if (value == "null") return null
        require(value.toIntOrNull() != null) { "Invalid Wi-Fi policy response: key=$key response=$value" }
        return value
    }

    private fun execute(shell: KeepAliveShell, request: WifiPolicyState.Request, command: String): String {
        check(current(request)) { "Wi-Fi policy operation superseded or Shutdown requested" }
        val result = shell.exec(command, timeoutMs = 5_000)
        val detail = "command=$command ok=${result.ok} elapsed_ms=${result.elapsedMs} output=${result.output} error=${result.error}"
        event("command", request, detail)
        check(result.ok) { detail }
        return result.output
    }

    private fun event(stage: String, request: WifiPolicyState.Request?, detail: String) {
        val text = "generation=${request?.generation} attempt=${request?.attempt} $detail"
        val bounded = if (text.length <= 32768) text else text.take(32768) + "\n[truncated]"
        try {
            record("wifi_policy_$stage", "Wi-Fi policy $stage", bounded)
        } catch (loggingError: Exception) {
            Log.e("WifiPolicyRecovery", "Could not persist Wi-Fi policy event: $bounded", loggingError)
        }
    }

    companion object {
        private const val SLEEP = "wifi_sleep_policy"
        private const val OFF = "byd_off_wifi_switch"
    }
}
