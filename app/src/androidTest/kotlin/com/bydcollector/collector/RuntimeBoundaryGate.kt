package com.bydcollector.collector

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.runtime.RuntimeEndpoint
import java.io.File
import java.io.ObjectInputStream

/** Real runtime snapshot, foreign-UID denial, and owner survival across UI death. */
internal object RuntimeBoundaryGate {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val pm = context.packageManager
        val adb = AdbLocalClient(File(context.filesDir, "adb_keys"))
        val authorization = adb.checkAuthorizationReadOnly()
        check(authorization.category == "adb_authorization_connected") {
            "Requires the owner's pre-authorized local ADB key: ${authorization.category}: ${authorization.message}"
        }
        val owner = pm.getApplicationInfo(RuntimeEndpoint.OWNER_PACKAGE, 0)
        val ui = pm.getApplicationInfo(RuntimeEndpoint.UI_PACKAGE, 0)
        check(owner.uid != ui.uid)
        check(pm.checkSignatures(owner.uid, ui.uid) == android.content.pm.PackageManager.SIGNATURE_MATCH)

        fun shell(command: String): String {
            val result = adb.execShell(command, allowAuthorizationPrompt = false)
            check(result.error == null) { "ADB shell command failed: ${result.error}; ${result.output}" }
            return result.output
        }

        val dashboardArgs = Bundle().apply {
            putInt("protocol", RuntimeEndpoint.PROTOCOL)
            putString("revision", BuildConfig.RUNTIME_REVISION)
            putString("operation", "dashboard")
            putString("profile", com.bydcollector.collector.ui.DashboardLoadProfile.ALL_PARAMETERS.name)
            putString("language", "UK")
        }
        context.contentResolver.openTypedAssetFileDescriptor(
            RuntimeEndpoint.uri, "application/vnd.bydcollector.query", dashboardArgs
        )!!.use { descriptor ->
            ObjectInputStream(descriptor.createInputStream()).use { input ->
                check(input.readObject() is com.bydcollector.collector.ui.DashboardState) {
                    "Owner did not return a real dashboard snapshot"
                }
                descriptor.parcelFileDescriptor.checkError()
            }
        }

        check(pm.checkPermission(RuntimeEndpoint.PERMISSION, "com.android.shell") ==
            android.content.pm.PackageManager.PERMISSION_DENIED)
        val foreignUidResult = adb.execShell(
            "content call --uri ${RuntimeEndpoint.uri} --method identity " +
                "--extra int:protocol:${RuntimeEndpoint.PROTOCOL} " +
                "--extra string:revision:${BuildConfig.RUNTIME_REVISION} 2>&1",
            allowAuthorizationPrompt = false
        )
        check(foreignUidResult.error == null || Regex("shell exit code \\d+").matches(foreignUidResult.error)) {
            "Could not capture the shell-UID provider response: ${foreignUidResult.error}; ${foreignUidResult.output}"
        }
        val foreignUidCall = foreignUidResult.output
        check(foreignUidCall.contains("SecurityException", ignoreCase = true) ||
            foreignUidCall.contains("Permission Denial", ignoreCase = true) ||
                (foreignUidCall.contains("requires", ignoreCase = true) &&
                    foreignUidCall.contains(RuntimeEndpoint.PERMISSION))) {
            "A real shell-UID ContentProvider call was not rejected: $foreignUidCall"
        }
        check(!foreignUidCall.contains("package=${RuntimeEndpoint.OWNER_PACKAGE}") &&
            !foreignUidCall.contains("uid=${owner.uid}")) {
            "Foreign UID received the owner identity response: $foreignUidCall"
        }

        val ownerPid = android.os.Process.myPid()
        context.startActivity(Intent().setClassName(RuntimeEndpoint.UI_PACKAGE,
            "com.bydcollector.collector.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(2_000L)
        val firstUiPid = shell("pidof ${RuntimeEndpoint.UI_PACKAGE}").trim()
        check(firstUiPid.isNotEmpty()) { "UI failed to start; inspect logcat" }
        shell("am force-stop ${RuntimeEndpoint.UI_PACKAGE}")
        SystemClock.sleep(500L)
        val stoppedUi = adb.execShell("pidof ${RuntimeEndpoint.UI_PACKAGE}", allowAuthorizationPrompt = false)
        check(stoppedUi.error == "shell exit code 1" && stoppedUi.output.isBlank()) {
            "UI process survived force-stop or pidof failed unexpectedly: ${stoppedUi.error}; ${stoppedUi.output}"
        }
        check(shell("pidof ${RuntimeEndpoint.OWNER_PACKAGE}").trim().split(' ').contains(ownerPid.toString())) {
            "UI force-stop killed the distinct runtime owner"
        }
        context.startActivity(Intent().setClassName(RuntimeEndpoint.UI_PACKAGE,
            "com.bydcollector.collector.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(2_000L)
        val relaunchedUiPid = shell("pidof ${RuntimeEndpoint.UI_PACKAGE}").trim()
        check(relaunchedUiPid.isNotEmpty() && relaunchedUiPid != firstUiPid) {
            "UI did not restart as a fresh process after force-stop"
        }
        return "PASS: owner snapshot read; real shell-UID provider call denied; owner survived UI force-stop and UI relaunched"
    }
}
