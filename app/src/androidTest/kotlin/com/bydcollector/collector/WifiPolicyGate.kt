package com.bydcollector.collector

import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.bydcollector.collector.adb.AdbEndpoint
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.keepalive.AdbKeepAliveShell
import com.bydcollector.collector.keepalive.KeepAliveShell
import com.bydcollector.collector.keepalive.KeepAliveShellResult
import com.bydcollector.collector.keepalive.WifiPolicyRecovery
import com.bydcollector.collector.keepalive.WifiPolicyState
import com.bydcollector.collector.runtime.RuntimePreferences
import com.bydcollector.collector.service.CollectorSettings
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Exercises the real owner journal and Android shell policy writes on an emulator. */
internal object WifiPolicyGate {
    private const val SLEEP = "wifi_sleep_policy"
    private const val OFF = "byd_off_wifi_switch"
    private const val EXIT_MARKER = "__BYD_WIFI_POLICY_EXIT__"
    private val commandPattern = Regex(
        "settings (?:get global (?:$SLEEP|$OFF)|put global (?:$SLEEP|$OFF) -?[0-9]+|delete global (?:$SLEEP|$OFF))"
    )

    fun run(instrumentation: Instrumentation): String {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Wi-Fi gate requires UiAutomation.executeShellCommandRw (Android 12/API 31+)"
        }
        val context = instrumentation.targetContext
        val shell = InstrumentationKeepAliveShell(instrumentation)
        val initial = readSnapshot(shell)
        check((initial.sleepPolicy == null || initial.sleepPolicy.toIntOrNull() != null) &&
            (initial.offSwitch == null || initial.offSwitch.toIntOrNull() != null)) {
            "Wi-Fi gate requires numeric or absent original global values so it can restore them safely"
        }
        val stores = linkedMapOf<String, android.content.SharedPreferences>()
        val events = Collections.synchronizedList(mutableListOf<Triple<String, String, String?>>())
        val passes = mutableListOf<String>()
        var failure: Throwable? = null

        fun freshPrefs(): android.content.SharedPreferences {
            val name = "wifi_policy_gate_${UUID.randomUUID()}"
            return context.getSharedPreferences(name, Context.MODE_PRIVATE).also { stores[name] = it }
        }

        try {
            legacyMigrationAndRestore(context, freshPrefs(), shell, events)
            passes += "legacy_migration_idle_replay_and_restore_missing_original"

            shutdownPausesApplyAndRestore(context, freshPrefs(), shell)
            passes += "shutdown_preserves_pending_apply_and_restore"

            restoreWithoutCaptureIsSkipped(context, freshPrefs(), shell, events)
            passes += "restore_without_capture_skips_system_writes"

            rapidOwnerTogglesInvalidateStaleGeneration(context, freshPrefs(), shell)
            passes += "rapid_owner_toggles_invalidate_stale_generation"

            unavailableLocalAdbRetainsFailure(context, freshPrefs(), initial, shell, events)
            passes += "unavailable_local_adb_failure_is_retained"
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                restoreOriginalSnapshot(shell, initial)
            } catch (restoreError: Throwable) {
                if (failure == null) failure = restoreError else failure!!.addSuppressed(restoreError)
            }
            stores.forEach { (name, prefs) ->
                try {
                    check(prefs.edit().clear().commit()) { "Could not clear Wi-Fi gate preferences: $name" }
                    check(context.deleteSharedPreferences(name)) { "Could not delete Wi-Fi gate preferences: $name" }
                } catch (cleanupError: Throwable) {
                    if (failure == null) failure = cleanupError else failure!!.addSuppressed(cleanupError)
                }
            }
        }

        failure?.let { throw it }
        return buildString {
            passes.forEach { appendLine(it) }
            append("shell_adapter=UiAutomation+actual_exit_marker:$EXIT_MARKER")
        }
    }

    private fun legacyMigrationAndRestore(
        context: Context,
        prefs: android.content.SharedPreferences,
        shell: KeepAliveShell,
        events: MutableList<Triple<String, String, String?>>
    ) {
        val original = WifiPolicyState.Snapshot("1", null)
        writeSnapshot(shell, original)
        check(prefs.edit().putBoolean(CollectorSettings.KEY_KEEP_WIFI, true).commit())
        val state = WifiPolicyState(prefs)
        check(!state.pending()) { "Legacy enabled preference unexpectedly has a policy journal" }
        val recovery = recovery(prefs, shell, events)
        recovery.reconcile()
        check(readSnapshot(shell) == WifiPolicyState.Snapshot("2", "1")) {
            "Legacy enabled preferences were not migrated to both requested policies"
        }
        check(!state.pending() && !state.needsWork(true)) { "Migrated policy did not finish its journal" }

        writeSnapshot(shell, WifiPolicyState.Snapshot("0", "0"))
        recovery.reconcile()
        check(readSnapshot(shell) == WifiPolicyState.Snapshot("0", "0")) {
            "Idle recovery overwrote externally changed Wi-Fi policies"
        }

        ownerEdit(context, prefs, CollectorSettings.KEY_KEEP_WIFI to false)
        check(state.pending()) { "Owner OFF edit did not stage restore" }
        recovery.reconcile()
        check(readSnapshot(shell) == original) { "OFF did not restore the captured present and missing values" }
        check(!state.pending()) { "Restore journal remained pending after verified restoration" }
    }

    private fun shutdownPausesApplyAndRestore(
        context: Context,
        prefs: android.content.SharedPreferences,
        shell: KeepAliveShell
    ) {
        val original = WifiPolicyState.Snapshot("0", "0")
        writeSnapshot(shell, original)
        val state = WifiPolicyState(prefs)
        val recovery = recovery(prefs, shell) { _, _, _ -> }

        ownerEdit(context, prefs,
            CollectorSettings.KEY_KEEP_WIFI to true,
            CollectorSettings.KEY_USER_SHUTDOWN to true)
        recovery.reconcile()
        check(state.pending() && readSnapshot(shell) == original) {
            "Shutdown changed global policies or discarded pending apply"
        }
        ownerEdit(context, prefs, CollectorSettings.KEY_USER_SHUTDOWN to false)
        recovery.reconcile()
        check(!state.pending() && readSnapshot(shell) == WifiPolicyState.Snapshot("2", "1")) {
            "Pending apply did not resume after Shutdown cleared"
        }

        ownerEdit(context, prefs,
            CollectorSettings.KEY_KEEP_WIFI to false,
            CollectorSettings.KEY_USER_SHUTDOWN to true)
        recovery.reconcile()
        check(state.pending() && readSnapshot(shell) == WifiPolicyState.Snapshot("2", "1")) {
            "Shutdown changed global policies or discarded pending restore"
        }
        ownerEdit(context, prefs, CollectorSettings.KEY_USER_SHUTDOWN to false)
        recovery.reconcile()
        check(!state.pending() && readSnapshot(shell) == original) {
            "Pending restore did not resume after Shutdown cleared"
        }
    }

    private fun restoreWithoutCaptureIsSkipped(
        context: Context,
        prefs: android.content.SharedPreferences,
        shell: KeepAliveShell,
        events: MutableList<Triple<String, String, String?>>
    ) {
        val before = WifiPolicyState.Snapshot("1", null)
        writeSnapshot(shell, before)
        check(prefs.edit().putBoolean(CollectorSettings.KEY_KEEP_WIFI, true).commit())
        val state = WifiPolicyState(prefs)
        ownerEdit(context, prefs, CollectorSettings.KEY_KEEP_WIFI to false)
        check(state.pending()) { "OFF before capture did not stage restore" }
        recovery(prefs, shell, events).reconcile()
        check(readSnapshot(shell) == before) { "Restore without a snapshot changed a system policy" }
        check(!state.pending()) { "Uncaptured restore should finish without writing system policies" }
        check(events.any { it.first == "wifi_policy_restore_skipped" &&
            it.third.orEmpty().contains("original_snapshot_not_saved") }) {
            "Uncaptured restore did not retain its explicit skipped diagnostic"
        }
    }

    private fun rapidOwnerTogglesInvalidateStaleGeneration(
        context: Context,
        prefs: android.content.SharedPreferences,
        shell: KeepAliveShell
    ) {
        writeSnapshot(shell, WifiPolicyState.Snapshot("0", "0"))
        ownerEdit(context, prefs, CollectorSettings.KEY_KEEP_WIFI to true)
        val state = WifiPolicyState(prefs)
        val stale = checkNotNull(state.beginAttempt())
        ownerEdit(context, prefs, CollectorSettings.KEY_KEEP_WIFI to false)
        ownerEdit(context, prefs, CollectorSettings.KEY_KEEP_WIFI to true)
        check(state.generation() > stale.generation && !state.isCurrent(stale)) {
            "Rapid owner edits did not supersede the previous generation"
        }
        check(!state.finish(stale) && state.pending()) {
            "A stale generation completed the current owner journal"
        }
        recovery(prefs, shell) { _, _, _ -> }.reconcile()
        check(!state.pending() && readSnapshot(shell) == WifiPolicyState.Snapshot("2", "1")) {
            "Recovery did not apply the latest owner toggle"
        }
    }

    private fun unavailableLocalAdbRetainsFailure(
        context: Context,
        prefs: android.content.SharedPreferences,
        expectedSystem: WifiPolicyState.Snapshot,
        shell: KeepAliveShell,
        events: MutableList<Triple<String, String, String?>>
    ) {
        writeSnapshot(shell, expectedSystem)
        check(prefs.edit().putBoolean(CollectorSettings.KEY_KEEP_WIFI, true).commit())
        val port = ServerSocket(0).use { it.localPort }
        val adb = AdbLocalClient(
            File(context.cacheDir, "wifi_policy_gate_${UUID.randomUUID()}_adb"),
            endpoints = listOf(AdbEndpoint("127.0.0.1", port))
        )
        val state = WifiPolicyState(prefs)
        recovery(prefs, AdbKeepAliveShell(adb), events).reconcile()
        check(state.pending()) { "Unavailable ADB endpoint falsely completed the pending apply" }
        val failure = events.lastOrNull { it.first == "wifi_policy_failed" }?.third.orEmpty()
        check(failure.contains("ConnectException") || failure.contains("refused", ignoreCase = true)) {
            "Unavailable ADB failure lost its concrete connection error: $failure"
        }
        check(readSnapshot(shell) == expectedSystem) {
            "Unavailable ADB endpoint changed global policies"
        }
        recovery(prefs, shell, events).reconcile()
        check(!state.pending() && readSnapshot(shell) == WifiPolicyState.Snapshot("2", "1")) {
            "A fresh recovery controller did not resume the retained operation after ADB recovered"
        }
    }

    private fun recovery(
        prefs: android.content.SharedPreferences,
        shell: KeepAliveShell,
        events: (String, String, String?) -> Unit
    ) = WifiPolicyRecovery(prefs, { shell }, events)

    private fun recovery(
        prefs: android.content.SharedPreferences,
        shell: KeepAliveShell,
        events: MutableList<Triple<String, String, String?>>
    ) = recovery(prefs, shell) { category, message, detail -> events += Triple(category, message, detail) }

    private fun ownerEdit(
        context: Context,
        prefs: android.content.SharedPreferences,
        vararg changes: Pair<String, Boolean>
    ) {
        val values = Bundle().apply { changes.forEach { (key, value) -> putBoolean(key, value) } }
        val result = RuntimePreferences.editOwner(context, prefs, Bundle().apply { putBundle("changes", values) })
        check(result.getBoolean("committed")) { "Owner preferences edit did not commit" }
    }

    private fun readSnapshot(shell: KeepAliveShell): WifiPolicyState.Snapshot =
        WifiPolicyState.Snapshot(read(shell, SLEEP), read(shell, OFF))

    private fun read(shell: KeepAliveShell, key: String): String? {
        val result = shell.exec("settings get global $key", timeoutMs = 5_000)
        check(result.ok) { "Could not read global $key: ${result.error}; ${result.output}" }
        return result.output.trim().takeUnless { it == "null" }
    }

    private fun writeSnapshot(shell: KeepAliveShell, snapshot: WifiPolicyState.Snapshot) {
        for ((key, value) in listOf(SLEEP to snapshot.sleepPolicy, OFF to snapshot.offSwitch)) {
            writeKey(shell, key, value)
        }
        check(readSnapshot(shell) == snapshot) { "Global Wi-Fi policy setup did not read back as requested" }
    }

    private fun restoreOriginalSnapshot(shell: KeepAliveShell, snapshot: WifiPolicyState.Snapshot) {
        var failure: Throwable? = null
        for ((key, value) in listOf(SLEEP to snapshot.sleepPolicy, OFF to snapshot.offSwitch)) {
            try {
                writeKey(shell, key, value)
                check(read(shell, key) == value) { "Could not restore original global $key value" }
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun writeKey(shell: KeepAliveShell, key: String, value: String?) {
        val command = if (value == null) "settings delete global $key" else "settings put global $key $value"
        val result = shell.exec(command, timeoutMs = 5_000)
        check(result.ok) { "Could not set global $key: ${result.error}; ${result.output}" }
    }

    private class InstrumentationKeepAliveShell(
        private val instrumentation: Instrumentation
    ) : KeepAliveShell {
        override fun exec(command: String, timeoutMs: Int): KeepAliveShellResult {
            require(commandPattern.matches(command)) { "Refusing non-policy shell command in Wi-Fi gate: $command" }
            val started = SystemClock.elapsedRealtime()
            val (stdout, stdin) = instrumentation.uiAutomation.executeShellCommandRw("/system/bin/sh")
            val captured = StringBuilder()
            val reader = FutureTask {
                ParcelFileDescriptor.AutoCloseInputStream(stdout).use { stream ->
                    val text = InputStreamReader(stream, StandardCharsets.UTF_8)
                    val buffer = CharArray(1024)
                    while (true) {
                        val count = text.read(buffer)
                        if (count < 0) break
                        synchronized(captured) { captured.append(buffer, 0, count) }
                    }
                }
            }
            val readerThread = Thread(reader, "wifi-policy-shell-output").apply { isDaemon = true; start() }
            val script = "$command 2>&1\n__byd_wifi_exit=\$?\nprintf '\\n$EXIT_MARKER%s\\n' \"\$__byd_wifi_exit\"\nexit \"\$__byd_wifi_exit\"\n"
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { pipe ->
                    pipe.write(script.toByteArray(StandardCharsets.UTF_8))
                    pipe.flush()
                }
                reader.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                closeQuietly(stdin)
                closeQuietly(stdout)
                reader.cancel(true)
                readerThread.join(250L)
                return KeepAliveShellResult(
                    false,
                    synchronized(captured) { captured.toString() },
                    "shell_timeout_ms=$timeoutMs",
                    SystemClock.elapsedRealtime() - started
                )
            } finally {
                closeQuietly(stdin)
                closeQuietly(stdout)
            }
            val raw = synchronized(captured) { captured.toString() }
            val marker = raw.lastIndexOf(EXIT_MARKER)
            val elapsed = SystemClock.elapsedRealtime() - started
            if (marker < 0) return KeepAliveShellResult(
                false, raw, "shell_exit_marker_missing marker=$EXIT_MARKER", elapsed
            )
            val code = raw.substring(marker + EXIT_MARKER.length).lineSequence().firstOrNull()?.trim()?.toIntOrNull()
                ?: return KeepAliveShellResult(false, raw, "shell_exit_marker_invalid marker=$EXIT_MARKER", elapsed)
            val output = raw.substring(0, marker).trimEnd('\r', '\n')
            return KeepAliveShellResult(code == 0, output, if (code == 0) null else "shell_exit_code=$code", elapsed)
        }

        private fun closeQuietly(descriptor: ParcelFileDescriptor) {
            try {
                descriptor.close()
            } catch (_: Exception) {
                // Closing the peer pipe can race the reader's normal EOF cleanup.
            }
        }
    }
}
