package com.bydcollector.collector.ha

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.AdbShellResult
import java.io.File

object TailscaleActivator {
    const val LAUNCH_DELAY_MS = 10_000L
    const val RESTORE_DELAY_MS = 10_000L

    fun packageCandidates(): List<String> = listOf("com.tailscale.ipn")

    fun checkProcess(context: Context): TailscaleProcessCheck {
        val result = adbClient(context).execShell(
            processCheckCommand(packageCandidates().single()),
            timeoutMs = ADB_SHELL_TIMEOUT_MS
        )
        return interpretProcessCheck(result)
    }

    internal fun launchIfNeeded(context: Context): TailscaleLaunchResult {
        val launchIntent = packageCandidates()
            .asSequence()
            .mapNotNull { packageName -> context.packageManager.getLaunchIntentForPackage(packageName) }
            .firstOrNull()
            ?: return TailscaleLaunchResult(false, false, null, "tailscale_not_installed")
        val component = launchIntent.component
            ?: return TailscaleLaunchResult(false, false, null, "tailscale_launcher_missing")
        val homePackageName = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName

        val result = adbClient(context).execShell(
            launchIfNeededCommand(component.packageName, component.className)
        )
        return interpretLaunchResult(result, homePackageName)
    }

    internal fun restoreForeground(
        context: Context,
        target: TailscaleForegroundTarget
    ): TailscaleActivationResult {
        val result = adbClient(context).execShell(
            guardedRestoreCommand(packageCandidates().single(), target)
        )
        return interpretRestoreResult(result)
    }

    internal fun runDelayedSequence(
        isEnabled: () -> Boolean,
        sleeper: (Long) -> Unit,
        launch: () -> TailscaleLaunchResult,
        restoreForeground: (TailscaleForegroundTarget) -> TailscaleActivationResult,
        onEvent: (String, String) -> Unit
    ): TailscaleSequenceResult {
        onEvent(
            "tailscale_launch_delayed",
            "Tailscale launch delayed by ${LAUNCH_DELAY_MS / 1_000} seconds"
        )
        sleeper(LAUNCH_DELAY_MS)
        if (!isEnabled()) {
            onEvent("tailscale_launch_cancelled", "Tailscale activation disabled before launch")
            return TailscaleSequenceResult(launched = false, foregroundRestored = false)
        }

        val launchResult = launch()
        if (!launchResult.ok) {
            val category = if (launchResult.message.startsWith("tailscale_process_check_")) {
                "tailscale_launch_cancelled_process_check_failed"
            } else {
                "tailscale_launch_failed"
            }
            onEvent(category, launchResult.message)
            return TailscaleSequenceResult(launched = false, foregroundRestored = false)
        }
        if (!launchResult.launched) {
            onEvent("tailscale_launch_cancelled_running", launchResult.message)
            return TailscaleSequenceResult(launched = false, foregroundRestored = false)
        }

        val foregroundTarget = launchResult.previousForeground
            ?: TailscaleForegroundTarget.Unknown("tailscale_foreground_capture_missing")
        recordForegroundCapture(foregroundTarget, onEvent)
        onEvent("tailscale_launch_succeeded", launchResult.message)

        sleeper(RESTORE_DELAY_MS)
        val restore = restoreForeground(foregroundTarget)
        onEvent(restoreEventCategory(restore), restore.message)
        return TailscaleSequenceResult(launched = true, foregroundRestored = restore.ok)
    }

    fun launchCommand(packageName: String, className: String): String =
        "am start -n $packageName/$className"

    fun homeCommand(): String = "input keyevent KEYCODE_HOME"

    internal fun processCheckCommand(packageName: String): String =
        "if pidof $packageName >/dev/null 2>&1; then echo $PROCESS_RUNNING_MARKER; " +
            "else echo $PROCESS_NOT_RUNNING_MARKER; fi"

    internal fun foregroundActivityCommand(): String =
        "dumpsys activity activities | " +
            "sed -n '/Display #0 /,/Display #[1-9][0-9]* /p' | " +
            "grep -m 1 -E 'mResumedActivity|topResumedActivity'"

    internal fun launchIfNeededCommand(packageName: String, className: String): String =
        "pidof $packageName >/dev/null 2>&1; process_rc=${'$'}?; " +
            "if [ ${'$'}process_rc -eq 0 ]; then echo $PROCESS_RUNNING_MARKER; " +
            "elif [ ${'$'}process_rc -eq 1 ]; then " +
            "previous=\"${'$'}(${foregroundActivityCommand()})\"; " +
            "printf '$PREVIOUS_FOREGROUND_MARKER%s\\n' \"${'$'}previous\"; " +
            "if ${launchCommand(packageName, className)}; then echo $LAUNCH_REQUESTED_MARKER; " +
            "else echo $LAUNCH_FAILED_MARKER; exit 1; fi; " +
            "else echo $PROCESS_CHECK_FAILED_MARKER; exit ${'$'}process_rc; fi"

    internal fun guardedRestoreCommand(
        tailscalePackageName: String,
        target: TailscaleForegroundTarget
    ): String {
        val tailscaleIsForeground = foregroundPackageCheck(tailscalePackageName)
        val restoreAction = when (target) {
            TailscaleForegroundTarget.Home ->
                "${homeCommand()} && echo $HOME_RESTORED_MARKER"
            is TailscaleForegroundTarget.Task -> {
                val targetTaskIsForeground =
                    "${foregroundActivityCommand()} | grep -E -q ' t${target.taskId}([ }]|${'$'})'"
                "if am task focus ${target.taskId} >/dev/null 2>&1 && sleep 1 && $targetTaskIsForeground; " +
                    "then echo $TASK_RESTORED_MARKER; " +
                    "elif $tailscaleIsForeground; " +
                    "then ${homeCommand()} && echo $FALLBACK_HOME_MARKER; " +
                    "else echo $NOT_FOREGROUND_MARKER; fi"
            }
            is TailscaleForegroundTarget.Unknown ->
                "${homeCommand()} && echo $FALLBACK_HOME_MARKER"
        }
        return "if $tailscaleIsForeground; then $restoreAction; " +
            "else echo $NOT_FOREGROUND_MARKER; fi"
    }

    internal fun interpretProcessCheck(result: AdbShellResult): TailscaleProcessCheck = when {
        !result.ok -> TailscaleProcessCheck(
            checked = false,
            running = false,
            message = "tailscale_process_check_failed: ${result.detail()}"
        )
        PROCESS_RUNNING_MARKER in result.output -> TailscaleProcessCheck(
            checked = true,
            running = true,
            message = PROCESS_RUNNING_MESSAGE
        )
        PROCESS_NOT_RUNNING_MARKER in result.output -> TailscaleProcessCheck(
            checked = true,
            running = false,
            message = PROCESS_NOT_RUNNING_MESSAGE
        )
        else -> TailscaleProcessCheck(
            checked = false,
            running = false,
            message = "tailscale_process_check_unrecognized"
        )
    }

    internal fun interpretLaunchResult(
        result: AdbShellResult,
        homePackageName: String?
    ): TailscaleLaunchResult = when {
        PROCESS_RUNNING_MARKER in result.output -> TailscaleLaunchResult(
            ok = true,
            launched = false,
            previousForeground = null,
            message = PROCESS_RUNNING_MESSAGE
        )
        PROCESS_CHECK_FAILED_MARKER in result.output -> TailscaleLaunchResult(
            ok = false,
            launched = false,
            previousForeground = null,
            message = "tailscale_process_check_failed: ${result.detail()}"
        )
        !result.ok || LAUNCH_FAILED_MARKER in result.output -> TailscaleLaunchResult(
            ok = false,
            launched = false,
            previousForeground = null,
            message = "tailscale_adb_launch_failed: ${result.detail()}"
        )
        LAUNCH_REQUESTED_MARKER in result.output -> {
            val previousLine = result.output.lineSequence()
                .firstOrNull { it.startsWith(PREVIOUS_FOREGROUND_MARKER) }
                ?.removePrefix(PREVIOUS_FOREGROUND_MARKER)
                .orEmpty()
            TailscaleLaunchResult(
                ok = true,
                launched = true,
                previousForeground = interpretForegroundCapture(previousLine, homePackageName),
                message = "tailscale_launch_requested_via_adb"
            )
        }
        else -> TailscaleLaunchResult(
            ok = false,
            launched = false,
            previousForeground = null,
            message = "tailscale_launch_result_unrecognized"
        )
    }

    internal fun interpretForegroundCapture(
        output: String,
        homePackageName: String?
    ): TailscaleForegroundTarget {
        val match = RESUMED_ACTIVITY_REGEX.find(output)
            ?: return TailscaleForegroundTarget.Unknown("tailscale_foreground_capture_unrecognized")
        val componentName = match.groupValues[1]
        val packageName = componentName.substringBefore('/')
        val taskId = match.groupValues[2].toIntOrNull()
            ?: return TailscaleForegroundTarget.Unknown("tailscale_foreground_task_id_invalid")
        if (packageName == homePackageName) return TailscaleForegroundTarget.Home
        return TailscaleForegroundTarget.Task(taskId, packageName, componentName)
    }

    internal fun interpretRestoreResult(result: AdbShellResult): TailscaleActivationResult = when {
        !result.ok -> TailscaleActivationResult(
            false,
            "tailscale_adb_restore_failed: ${result.detail()}"
        )
        TASK_RESTORED_MARKER in result.output -> TailscaleActivationResult(true, TASK_RESTORED_MESSAGE)
        HOME_RESTORED_MARKER in result.output -> TailscaleActivationResult(true, HOME_RESTORED_MESSAGE)
        FALLBACK_HOME_MARKER in result.output -> TailscaleActivationResult(true, FALLBACK_HOME_MESSAGE)
        NOT_FOREGROUND_MARKER in result.output -> TailscaleActivationResult(false, RESTORE_SKIPPED_NOT_FOREGROUND)
        else -> TailscaleActivationResult(false, "tailscale_foreground_restore_unrecognized")
    }

    private fun recordForegroundCapture(
        target: TailscaleForegroundTarget,
        onEvent: (String, String) -> Unit
    ) {
        when (target) {
            TailscaleForegroundTarget.Home -> onEvent(
                "tailscale_foreground_captured",
                "Foreground target captured: home"
            )
            is TailscaleForegroundTarget.Task -> onEvent(
                "tailscale_foreground_captured",
                "Foreground target captured: task=${target.taskId} component=${target.componentName}"
            )
            is TailscaleForegroundTarget.Unknown -> onEvent(
                "tailscale_foreground_capture_failed",
                target.reason
            )
        }
    }

    private fun restoreEventCategory(result: TailscaleActivationResult): String = when (result.message) {
        TASK_RESTORED_MESSAGE -> TASK_RESTORED_MESSAGE
        HOME_RESTORED_MESSAGE -> HOME_RESTORED_MESSAGE
        FALLBACK_HOME_MESSAGE -> FALLBACK_HOME_MESSAGE
        RESTORE_SKIPPED_NOT_FOREGROUND -> RESTORE_SKIPPED_NOT_FOREGROUND
        else -> "tailscale_foreground_restore_failed"
    }

    private fun foregroundPackageCheck(packageName: String): String =
        "${foregroundActivityCommand()} | grep -F -q '$packageName/'"

    private fun AdbShellResult.detail(): String =
        error ?: output.trim().ifEmpty { "no detail" }

    private fun adbClient(context: Context): AdbLocalClient =
        AdbLocalClient(File(context.filesDir, "adb_keys"))

    internal const val PROCESS_RUNNING_MESSAGE = "tailscale_process_running"
    internal const val PROCESS_NOT_RUNNING_MESSAGE = "tailscale_process_not_running"
    internal const val TASK_RESTORED_MESSAGE = "tailscale_foreground_task_restored"
    internal const val HOME_RESTORED_MESSAGE = "tailscale_foreground_home_restored"
    internal const val FALLBACK_HOME_MESSAGE = "tailscale_foreground_fallback_home"
    internal const val RESTORE_SKIPPED_NOT_FOREGROUND = "tailscale_minimize_skipped_not_foreground"

    private const val ADB_SHELL_TIMEOUT_MS = 5_000
    private const val PROCESS_RUNNING_MARKER = "BYDCOLLECTOR_TAILSCALE_PROCESS_RUNNING"
    private const val PROCESS_NOT_RUNNING_MARKER = "BYDCOLLECTOR_TAILSCALE_PROCESS_NOT_RUNNING"
    private const val PROCESS_CHECK_FAILED_MARKER = "BYDCOLLECTOR_TAILSCALE_PROCESS_CHECK_FAILED"
    private const val PREVIOUS_FOREGROUND_MARKER = "BYDCOLLECTOR_TAILSCALE_PREVIOUS="
    private const val LAUNCH_REQUESTED_MARKER = "BYDCOLLECTOR_TAILSCALE_LAUNCH_REQUESTED"
    private const val LAUNCH_FAILED_MARKER = "BYDCOLLECTOR_TAILSCALE_LAUNCH_FAILED"
    private const val TASK_RESTORED_MARKER = "BYDCOLLECTOR_TAILSCALE_TASK_RESTORED"
    private const val HOME_RESTORED_MARKER = "BYDCOLLECTOR_TAILSCALE_HOME_RESTORED"
    private const val FALLBACK_HOME_MARKER = "BYDCOLLECTOR_TAILSCALE_FALLBACK_HOME"
    private const val NOT_FOREGROUND_MARKER = "BYDCOLLECTOR_TAILSCALE_NOT_FOREGROUND"
    private val RESUMED_ACTIVITY_REGEX = Regex("""\bu\d+\s+([^\s}]+/[^\s}]+).*?\bt(\d+)\b""")
}

data class TailscaleActivationResult(
    val ok: Boolean,
    val message: String
)

data class TailscaleProcessCheck(
    val checked: Boolean,
    val running: Boolean,
    val message: String
)

internal data class TailscaleLaunchResult(
    val ok: Boolean,
    val launched: Boolean,
    val previousForeground: TailscaleForegroundTarget?,
    val message: String
)

internal sealed interface TailscaleForegroundTarget {
    data object Home : TailscaleForegroundTarget

    data class Task(
        val taskId: Int,
        val packageName: String,
        val componentName: String
    ) : TailscaleForegroundTarget

    data class Unknown(val reason: String) : TailscaleForegroundTarget
}

data class TailscaleSequenceResult(
    val launched: Boolean,
    val foregroundRestored: Boolean
)
