package com.bydcollector.collector

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.diagnostics.DiagnosticLogRecorder
import com.bydcollector.collector.service.UserShutdownShellPlanner
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorSettings
import java.io.File
import java.util.UUID

/** Runs the production shell planner on the emulator; never invokes vehicle APIs. */
internal object ShutdownShellBehaviorGate {
    /** Expected to terminate instrumentation by the actual production force-stop. */
    fun startLifecycle(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val settings = CollectorSettings(context)
        instrumentation.runOnMainSync { context.startForegroundService(CollectorService.shutdownIntent(context)) }
        val deadline = SystemClock.elapsedRealtime() + 35_000
        while (SystemClock.elapsedRealtime() < deadline) {
            when (settings.userShutdownPhase()) {
                CollectorSettings.SHUTDOWN_PHASE_ERROR -> error(settings.userShutdownDetail().orEmpty())
                CollectorSettings.SHUTDOWN_PHASE_HANDOFF -> {
                    check(CollectorService.isUserShutdownInProgress()) { "HANDOFF released the coordinator prematurely" }
                    check(!CollectorService.clearShutdownForExplicitReopen(context)) { "Reopened live finalizer" }
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "SHUTDOWN_LIFECYCLE_HANDOFF_GATE_PASS token=${settings.userShutdownToken()} active=true reopen=false\n")
                    })
                    SystemClock.sleep(12_000)
                    error("APP survived production finalizer: ${settings.userShutdownDetail()}")
                }
            }
            SystemClock.sleep(20)
        }
        error("Shutdown never handed off: ${settings.userShutdownDetail()}")
    }

    fun run(instrumentation: Instrumentation): String {
        val adb = AdbLocalClient(File(instrumentation.targetContext.filesDir, "adb_keys"))
        fun shell(command: String, expectedExit: Int? = null): String {
            val result = adb.execShell(command, timeoutMs = 15_000, allowAuthorizationPrompt = false)
            check(result.error == null || result.error == "shell exit code $expectedExit") { result.error.orEmpty() }
            return result.output
        }
        val keys = listOf("bydcollector_keep_wifi", "bydcollector_keep_mobile_data", "bydcollector_keep_bluetooth",
            "bydcollector_recover_collector_service", "bydcollector_user_shutdown", "bydcollector_user_shutdown_generation",
            "bluetooth_disabled_profiles")
        val saved = keys.associateWith { shell("settings get global $it").trim() }
        val token = UUID.randomUUID().toString().replace("-", "")
        val timeoutToken = UUID.randomUUID().toString().replace("-", "")
        val path = UserShutdownShellPlanner.EVIDENCE_PREFIX + token + ".txt"
        val timeoutPath = UserShutdownShellPlanner.EVIDENCE_PREFIX + timeoutToken + ".txt"
        val childPath = "$timeoutPath.child"
        val context = instrumentation.targetContext
        val settings = CollectorSettings(context)
        check(settings.shutdownListenerPreviousState() == null) { "Explicitly reopen the app before this gate to restore its listener" }
        val savedStops = booleanArrayOf(settings.isMainManuallyStopped(), settings.isDebugManuallyStopped(),
            settings.isMqttManuallyStopped(), settings.isInfluxManuallyStopped())
        val savedShutdown = settings.isUserShutdownRequested()
        val savedPhase = settings.userShutdownPhase()
        val savedToken = settings.userShutdownToken()
        val savedDetail = settings.userShutdownDetail()
        try {
            check(shell(UserShutdownShellPlanner.beginGracefulStopCommand(
                DiagnosticLogRecorder.LOGCAT_OWNER_ENV, context.packageName, context.applicationInfo.sourceDir, token
            )).contains("BYDCOLLECTOR_SHUTDOWN_GRACEFUL_SIGNAL_SENT"))
            shell("settings put global bluetooth_disabled_profiles 202803")
            val handoff = shell(UserShutdownShellPlanner.detachedFinalizerCommand(
                context.packageName, token, DiagnosticLogRecorder.LOGCAT_OWNER_ENV, context.applicationInfo.sourceDir
            ))
            check(handoff.contains("SHUTDOWN_FINALIZER_HANDOFF=$token")) { handoff }
            val cancelled = shell(UserShutdownShellPlanner.awaitFinalizerCommand(token, cancel = true))
            check(cancelled.contains(UserShutdownShellPlanner.RETIRED_MARKER)) { cancelled }
            check(cancelled.contains("result=cancelled phase=explicit_reopen_before_force_stop")) { cancelled }
            check(shell("pidof ${context.packageName}").isNotBlank())
            check(shell("settings get global bluetooth_disabled_profiles").trim() == "0")

            // A stale cancellation must not clear the newer shutdown's generation or Boolean.
            shell("settings put global bydcollector_user_shutdown_generation $timeoutToken; settings put global bydcollector_user_shutdown 1")
            check(shell(UserShutdownShellPlanner.awaitFinalizerCommand(token, cancel = true), expectedExit = 123)
                .contains("BYDCOLLECTOR_NEWER_SHUTDOWN_ACTIVE"))
            check(shell("settings get global bydcollector_user_shutdown_generation").trim() == timeoutToken)
            check(shell("settings get global bydcollector_user_shutdown").trim() == "1")

            // Exercise native timeout against an actually hung child, not a mocked exit code.
            val started = SystemClock.elapsedRealtime()
            shell(UserShutdownShellPlanner.boundedFinalizerScript(timeoutToken,
                "sleep 60 & shutdown_child=\$!; echo \$shutdown_child > '$childPath'; wait \$shutdown_child"), expectedExit = 124)
            val elapsed = SystemClock.elapsedRealtime() - started
            val timeoutEvidence = shell("cat '$timeoutPath'")
            check(elapsed in 7_500..12_000) { "unbounded finalizer: $elapsed ms" }
            check(timeoutEvidence.contains("result=error phase=finalizer_exit status=")) { timeoutEvidence }
            check(timeoutEvidence.contains("finalizer_finished=$timeoutToken")) { timeoutEvidence }
            val childPid = shell("cat '$childPath'").trim()
            check(childPid.matches(Regex("[0-9]+")))
            val childState = shell("if [ -r /proc/$childPid/stat ]; then sed 's/^.*) //' /proc/$childPid/stat | cut -d ' ' -f 1; fi").trim()
            check(childState.isEmpty() || childState == "Z") { "timeout left a running child: $childState" }
            check(settings.setUserShutdownRequested(true))
            check(settings.setUserShutdownPhase(CollectorSettings.SHUTDOWN_PHASE_HANDOFF, timeoutToken))
            var surfaced: String? = null
            check(CollectorService.clearShutdownForExplicitReopen(context) { surfaced = it })
            check(surfaced?.contains("result=error phase=finalizer_exit status=124") == true)
            check(!settings.isUserShutdownRequested())
            val journal = File(context.filesDir, "diagnostic_journal/operational_events.jsonl").readText()
            check(journal.lineSequence().any { it.contains("user_shutdown_previous_finalizer_error") && it.contains(timeoutToken) })
            return "SHUTDOWN_SHELL_GATE_PASS cancellation_before_force_stop newer_generation_preserved bluetooth_restored timeout_ms=$elapsed child_not_running prior_error_journaled_and_surfaced reopen_allowed\n$cancelled$timeoutEvidence"
        } finally {
            settings.setMainManuallyStopped(savedStops[0])
            settings.setDebugManuallyStopped(savedStops[1])
            settings.setMqttManuallyStopped(savedStops[2])
            settings.setInfluxManuallyStopped(savedStops[3])
            settings.setUserShutdownRequested(savedShutdown)
            settings.setUserShutdownPhase(savedPhase, savedToken, savedDetail)
            saved.forEach { (key, value) ->
                if (value == "null" || value.isBlank()) shell("settings delete global $key")
                else {
                    check(value.matches(Regex("[A-Za-z0-9_-]+")))
                    shell("settings put global $key $value")
                }
            }
            shell("rm -f '$path' '$timeoutPath' '$childPath'")
        }
    }
}
