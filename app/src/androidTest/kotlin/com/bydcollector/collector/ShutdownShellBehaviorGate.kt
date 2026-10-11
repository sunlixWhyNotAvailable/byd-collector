package com.bydcollector.collector

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.diagnostics.DiagnosticLogRecorder
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.service.UserShutdownShellPlanner
import java.io.File
import java.util.UUID

/** T07 composite: bounded real child, surfaced prior error/reopen, then host-verified force-stop. */
internal object ShutdownShellBehaviorGate {
    /** The host must verify owner-process death after observing SHUTDOWN_LIFECYCLE_HANDOFF. */
    fun startLifecycle(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val settings = CollectorSettings(context)
        instrumentation.runOnMainSync { context.startForegroundService(CollectorService.shutdownIntent(context)) }
        val deadline = SystemClock.elapsedRealtime() + 35_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            when (settings.userShutdownPhase()) {
                CollectorSettings.SHUTDOWN_PHASE_ERROR -> error(settings.userShutdownDetail().orEmpty())
                CollectorSettings.SHUTDOWN_PHASE_HANDOFF -> {
                    check(CollectorService.isUserShutdownInProgress()) { "HANDOFF released the coordinator prematurely" }
                    check(!CollectorService.clearShutdownForExplicitReopen(context)) { "Reopened live finalizer" }
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "SHUTDOWN_LIFECYCLE_HANDOFF token=${settings.userShutdownToken()} active=true reopen=false\n")
                    })
                    SystemClock.sleep(12_000L)
                    error("APP survived production finalizer: ${settings.userShutdownDetail()}")
                }
            }
            SystemClock.sleep(20L)
        }
        error("Shutdown never handed off: ${settings.userShutdownDetail()}")
    }

    /**
     * This entrypoint intentionally does not return success. The host sees the
     * handoff marker and must prove the owner process is gone after force-stop.
     */
    fun run(instrumentation: Instrumentation): String {
        val adb = AdbLocalClient(File(instrumentation.targetContext.filesDir, "adb_keys"))
        fun shell(command: String, expectedExit: Int? = null): String {
            val result = adb.execShell(command, timeoutMs = 15_000, allowAuthorizationPrompt = false)
            check(result.error == null || result.error == "shell exit code $expectedExit") { result.error.orEmpty() }
            return result.output
        }

        val keys = listOf(
            "bydcollector_recover_collector_service",
            "bydcollector_user_shutdown",
            "bydcollector_user_shutdown_generation"
        )
        val saved = keys.associateWith { shell("settings get global $it").trim() }
        val token = UUID.randomUUID().toString().replace("-", "")
        val evidencePath = UserShutdownShellPlanner.EVIDENCE_PREFIX + token + ".txt"
        val childPath = "$evidencePath.child"
        val context = instrumentation.targetContext
        val settings = CollectorSettings(context)
        check(settings.shutdownListenerPreviousState() == null) {
            "Explicitly reopen the app before this gate to restore its listener"
        }
        check(!settings.isUserShutdownRequested() && settings.userShutdownPhase() == CollectorSettings.SHUTDOWN_PHASE_IDLE) {
            "Explicitly reopen before running the Shutdown boundary gate"
        }
        val savedStops = booleanArrayOf(
            settings.isMainManuallyStopped(), settings.isDebugManuallyStopped(),
            settings.isMqttManuallyStopped(), settings.isInfluxManuallyStopped()
        )
        val savedShutdown = settings.isUserShutdownRequested()
        val savedPhase = settings.userShutdownPhase()
        val savedToken = settings.userShutdownToken()
        val savedDetail = settings.userShutdownDetail()
        var shellSummary: String? = null
        try {
            val fixtureApk = instrumentation.context.applicationInfo.sourceDir
            val hungWorker = "CLASSPATH='$fixtureApk' app_process /system/bin " +
                "--nice-name=bydcollector_shutdown_worker com.bydcollector.collector.ShutdownProcessFixture " +
                "</dev/null >/dev/null 2>&1 & shutdown_child=\$!; echo \$shutdown_child > '$childPath'; wait \$shutdown_child"
            val startedAt = SystemClock.elapsedRealtime()
            shell(UserShutdownShellPlanner.boundedFinalizerScript(token, hungWorker), expectedExit = 124)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val evidence = shell("cat '$evidencePath'")
            check(elapsed in 7_500L..12_000L) { "Finalizer exceeded its bounded deadline: $elapsed ms" }
            check(evidence.contains("result=error phase=finalizer_exit status=124")) { evidence }
            check(evidence.contains("finalizer_finished=$token")) { evidence }

            val childPid = shell("cat '$childPath'").trim()
            check(childPid.matches(Regex("[0-9]+"))) { "Finalizer did not record its actual worker PID: $childPid" }
            val childState = shell(
                "if [ -r /proc/$childPid/stat ]; then sed 's/^.*) //' /proc/$childPid/stat | cut -d ' ' -f 1; fi"
            ).trim()
            check(childState.isEmpty() || childState == "Z") { "Timeout left a running app_process child: $childState" }

            shell("settings put global bydcollector_user_shutdown_generation $token; settings put global bydcollector_user_shutdown 1")
            check(settings.setUserShutdownRequested(true))
            check(settings.setUserShutdownPhase(CollectorSettings.SHUTDOWN_PHASE_HANDOFF, token))
            var surfaced: String? = null
            check(CollectorService.clearShutdownForExplicitReopen(context) { surfaced = it })
            check(surfaced?.contains("result=error phase=finalizer_exit status=124") == true) {
                "Explicit reopen did not surface the prior timeout"
            }
            check(!settings.isUserShutdownRequested())
            val journal = File(context.filesDir, "diagnostic_journal/operational_events.jsonl").readText()
            check(journal.lineSequence().any {
                it.contains("user_shutdown_previous_finalizer_error") && it.contains(token)
            }) { "Previous finalizer failure was not journaled" }
            shellSummary = "bounded_ms=$elapsed child_not_running prior_error_journaled_and_surfaced reopen_allowed"
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
            shell("rm -f '$evidencePath' '$childPath'")
        }

        checkNotNull(shellSummary) { "The bounded-finalizer proof did not finish" }
        startLifecycle(instrumentation)
        error("Owner process survived the production finalizer")
    }
}
