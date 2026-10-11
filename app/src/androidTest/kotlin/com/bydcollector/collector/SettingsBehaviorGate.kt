package com.bydcollector.collector

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.AdbShellResult
import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import com.bydcollector.collector.data.direct.DirectVehicleHelperClient
import com.bydcollector.collector.data.remote.DirectBridgeManager
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.service.CollectorSettings
import java.io.File

/** T06: real helper Binder replacement, retry, handshake, and durable update confirmation. */
internal object SettingsBehaviorGate {
    fun cases(context: Context, prefix: String, instrumentation: Instrumentation): List<Pair<String, () -> Unit>> = listOf(
        "helper_update_replacement_stops_absent_launches_pings_then_confirms" to {
            isolated(context, prefix) { scoped -> runHelperReplacementSequence(scoped, instrumentation) }
        }
    )

    private fun isolated(base: Context, prefix: String, run: (Context) -> Unit) {
        val names = linkedSetOf<String>()
        val scoped = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scopedName = "${prefix}_$name"
                names += scopedName
                return base.getSharedPreferences(scopedName, mode)
            }
        }
        try {
            run(scoped)
        } finally {
            names.forEach { check(base.deleteSharedPreferences(it)) { "Could not remove test preferences: $it" } }
        }
    }

    private fun runHelperReplacementSequence(context: Context, instrumentation: Instrumentation) {
        val adb = AdbLocalClient(File(context.filesDir, "adb_keys"))
        val authorization = adb.checkAuthorizationReadOnly()
        check(authorization.category == "adb_authorization_connected") {
            "T06 requires the owner's pre-authorized local ADB key: ${authorization.category}: ${authorization.message}"
        }
        @Suppress("DEPRECATION")
        val updateTime = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        check(updateTime > 0L) { "installed package update timestamp is unavailable" }
        val helper = DirectVehicleHelperClient()
        check(helper.ownerModeForStop() == null && helperPid(adb).isBlank()) {
            "Refusing to replace a helper process that was not created by this fixture"
        }

        val settings = CollectorSettings(context)
        settings.setPollingEnabled(true)
        check(settings.markHelperReplacementPending())

        val fixtureApk = instrumentation.context.applicationInfo.sourceDir
        val targetApk = context.applicationInfo.sourceDir
        val fixturePath = "/data/local/tmp/collector_helper_test_${java.util.UUID.randomUUID()}"
        val fixtureLog = "$fixturePath.log"
        val fixtureLock = "$fixturePath.lock"
        fun fixtureLaunch(command: String): String {
            val productionClasspath = "CLASSPATH='$targetApk'"
            check(command.contains(productionClasspath) && command.contains(CollectorHelperProtocol.HELPER_CLASS) &&
                command.contains(CollectorHelperProtocol.LOG_PATH) && command.contains(CollectorHelperProtocol.LOCK_PATH)) {
                "Production launch shape changed; refusing to start a non-fixture helper"
            }
            return command.replace(productionClasspath, "CLASSPATH='$fixtureApk'")
                .replace(CollectorHelperProtocol.HELPER_CLASS, HELPER_FIXTURE_CLASS)
                .replace(CollectorHelperProtocol.LOG_PATH, fixtureLog)
                .replace(CollectorHelperProtocol.LOCK_PATH, fixtureLock)
        }

        var launchCalls = 0
        fun managerCall() = DirectBridgeManager.ensureRunning(
            context = context,
            adbClient = adb,
            helper = helper,
            ownerMode = DirectHelperOwnerMode.APP_GAP_SPOOL,
            shellRunner = { command, _ ->
                when {
                    command == DirectBridgeManager.helperAbsenceCommand() -> {
                        adb.execShell(command, allowAuthorizationPrompt = false)
                    }
                    command.contains("setsid app_process") -> {
                        launchCalls++
                        if (launchCalls == 1) {
                            AdbShellResult(false, "", "injected first launch failure", 0L)
                        } else {
                            adb.execShell(fixtureLaunch(command), allowAuthorizationPrompt = false)
                        }
                    }
                    else -> error("Unexpected shell command during helper replacement: $command")
                }
            }
        )

        try {
            val oldCommand = fixtureLaunch(DirectBridgeManager.launchCommand(context, DirectHelperOwnerMode.APP_GAP_SPOOL))
            val oldStarted = adb.execShell(oldCommand, allowAuthorizationPrompt = false)
            check(oldStarted.ok) { "Could not start old Binder helper fixture: ${oldStarted.error}; ${oldStarted.output}" }
            val oldPid = awaitHelperPid(adb)
            check(helper.ownerModeForStop() == DirectHelperOwnerMode.APP_GAP_SPOOL) {
                "Old fixture did not answer the production Binder PING"
            }
            val firstAttempt = managerCall()
            check(!firstAttempt.ok) { "First launch unexpectedly succeeded" }
            check(helperPid(adb).isBlank()) { "Old helper survived its real TX_STOP_OWNER" }
            check(settings.helperReplacementPending(updateTime)) { "Failed launch cleared the retry marker" }
            check(launchCalls == 1)

            val retry = managerCall()
            check(retry.ok) { "Retry did not start the replacement helper: ${retry.message}" }
            val replacementPid = awaitHelperPid(adb)
            check(replacementPid != oldPid) { "Replacement reused the old helper process" }
            check(helper.ownerModeForStop() == DirectHelperOwnerMode.APP_GAP_SPOOL) {
                "Replacement did not complete the actual production Binder PING"
            }
            check(launchCalls == 2) { "Retry launched more than once: $launchCalls" }
            check(!settings.helperReplacementPending(updateTime)) { "Successful PING did not confirm the installed update" }

            val repeated = DirectBridgeManager.ensureRunning(
                context = context,
                adbClient = adb,
                helper = helper,
                ownerMode = DirectHelperOwnerMode.APP_GAP_SPOOL,
                shellRunner = { _, _ -> error("confirmed helper must survive repeated recovery") }
            )
            check(repeated.ok && launchCalls == 2) { "Confirmed helper was replaced again: ${repeated.message}" }
        } finally {
            val pid = helperPid(adb)
            if (pid.isNotBlank()) {
                val stopped = adb.execShell("kill $pid 2>/dev/null || true", allowAuthorizationPrompt = false)
                check(stopped.error == null) { "Could not stop helper fixture: ${stopped.error}; ${stopped.output}" }
            }
            val fixtureFiles = listOf(fixtureLock, fixtureLog) +
                (1..3).flatMap { listOf("$fixtureLog.$it", "$fixtureLog.$it.tmp") }
            val cleared = adb.execShell("rm -f ${fixtureFiles.joinToString(" ")}", allowAuthorizationPrompt = false)
            check(cleared.ok) { "Could not remove helper fixture files: ${cleared.error}; ${cleared.output}" }
        }
    }

    private fun helperPid(adb: AdbLocalClient): String {
        val result = adb.execShell("pidof ${CollectorHelperProtocol.PROCESS_NAME}", allowAuthorizationPrompt = false)
        check(result.error == null || result.error == "shell exit code 1") {
            "Could not inspect helper process: ${result.error}; ${result.output}"
        }
        return result.output.trim()
    }

    private fun awaitHelperPid(adb: AdbLocalClient): String {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            helperPid(adb).takeIf { it.isNotBlank() }?.let { return it }
            SystemClock.sleep(25L)
        }
        error("Helper fixture did not start within the deadline")
    }

    private const val HELPER_FIXTURE_CLASS = "com.bydcollector.collector.HelperReplacementProcessFixture"
}
