package com.bydcollector.collector.ha

import android.content.Context
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.AdbShellResult
import java.io.File

object TailscaleActivator {
    const val LAUNCH_DELAY_MS = 10_000L
    const val MINIMIZE_DELAY_MS = 10_000L

    fun packageCandidates(): List<String> = listOf("com.tailscale.ipn")

    fun activate(context: Context): TailscaleActivationResult {
        val launchIntent = packageCandidates()
            .asSequence()
            .mapNotNull { packageName -> context.packageManager.getLaunchIntentForPackage(packageName) }
            .firstOrNull()
            ?: return TailscaleActivationResult(false, "tailscale_not_installed")
        val component = launchIntent.component
            ?: return TailscaleActivationResult(false, "tailscale_launcher_missing")

        val result = AdbLocalClient(File(context.filesDir, "adb_keys")).execShell(
            launchCommand(component.packageName, component.className)
        )
        return if (result.ok) {
            TailscaleActivationResult(true, "tailscale_launch_requested_via_adb")
        } else {
            TailscaleActivationResult(
                false,
                "tailscale_adb_launch_failed: ${result.error ?: result.output.trim().ifEmpty { "no detail" }}"
            )
        }
    }

    fun minimize(context: Context): TailscaleActivationResult {
        val result = AdbLocalClient(File(context.filesDir, "adb_keys")).execShell(
            guardedHomeCommand(packageCandidates().single())
        )
        return interpretMinimizeResult(result)
    }

    internal fun runDelayedSequence(
        isEnabled: () -> Boolean,
        sleeper: (Long) -> Unit,
        activate: () -> TailscaleActivationResult,
        minimize: () -> TailscaleActivationResult,
        onEvent: (String, String) -> Unit
    ): TailscaleSequenceResult {
        onEvent(
            "tailscale_launch_delayed",
            "Tailscale launch delayed by ${LAUNCH_DELAY_MS / 1_000} seconds"
        )
        sleeper(LAUNCH_DELAY_MS)
        if (!isEnabled()) {
            onEvent("tailscale_launch_cancelled", "Tailscale activation disabled before launch")
            return TailscaleSequenceResult(launched = false, minimized = false)
        }

        val launch = activate()
        if (!launch.ok) {
            onEvent("tailscale_launch_failed", launch.message)
            return TailscaleSequenceResult(launched = false, minimized = false)
        }
        onEvent("tailscale_launch_succeeded", launch.message)

        sleeper(MINIMIZE_DELAY_MS)
        val home = minimize()
        onEvent(
            when {
                home.message == MINIMIZE_SKIPPED_NOT_FOREGROUND -> MINIMIZE_SKIPPED_NOT_FOREGROUND
                home.ok -> "tailscale_minimize_succeeded"
                else -> "tailscale_minimize_failed"
            },
            home.message
        )
        return TailscaleSequenceResult(launched = true, minimized = home.ok)
    }

    fun launchCommand(packageName: String, className: String): String =
        "am start -n $packageName/$className"

    fun homeCommand(): String = "input keyevent KEYCODE_HOME"

    fun guardedHomeCommand(packageName: String): String =
        "if dumpsys activity activities | grep -m 1 -E 'mResumedActivity|topResumedActivity' | grep -q '$packageName/'; " +
            "then ${homeCommand()} && echo $HOME_SENT_MARKER; else echo $NOT_FOREGROUND_MARKER; fi"

    internal fun interpretMinimizeResult(result: AdbShellResult): TailscaleActivationResult = when {
        !result.ok -> TailscaleActivationResult(
            false,
            "tailscale_adb_minimize_failed: ${result.error ?: result.output.trim().ifEmpty { "no detail" }}"
        )
        HOME_SENT_MARKER in result.output -> TailscaleActivationResult(true, "tailscale_minimized_via_adb")
        NOT_FOREGROUND_MARKER in result.output -> TailscaleActivationResult(false, MINIMIZE_SKIPPED_NOT_FOREGROUND)
        else -> TailscaleActivationResult(false, "tailscale_foreground_check_unrecognized")
    }

    internal const val MINIMIZE_SKIPPED_NOT_FOREGROUND = "tailscale_minimize_skipped_not_foreground"
    private const val HOME_SENT_MARKER = "BYDCOLLECTOR_TAILSCALE_HOME_SENT"
    private const val NOT_FOREGROUND_MARKER = "BYDCOLLECTOR_TAILSCALE_NOT_FOREGROUND"
}

data class TailscaleActivationResult(
    val ok: Boolean,
    val message: String
)

data class TailscaleSequenceResult(
    val launched: Boolean,
    val minimized: Boolean
)
