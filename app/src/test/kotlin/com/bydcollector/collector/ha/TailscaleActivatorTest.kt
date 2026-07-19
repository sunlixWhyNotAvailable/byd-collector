package com.bydcollector.collector.ha

import com.bydcollector.collector.adb.AdbShellResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TailscaleActivatorTest {
    @Test
    fun packageAndLaunchCommandTargetStableTailscale() {
        assertEquals(listOf("com.tailscale.ipn"), TailscaleActivator.packageCandidates())
        assertEquals(
            "am start -n com.tailscale.ipn/com.tailscale.ipn.MainActivity",
            TailscaleActivator.launchCommand("com.tailscale.ipn", "com.tailscale.ipn.MainActivity")
        )
    }

    @Test
    fun processCheckDistinguishesRunningStoppedFailureAndUnknownOutput() {
        assertEquals(
            "if pidof com.tailscale.ipn >/dev/null 2>&1; then echo BYDCOLLECTOR_TAILSCALE_PROCESS_RUNNING; " +
                "else echo BYDCOLLECTOR_TAILSCALE_PROCESS_NOT_RUNNING; fi",
            TailscaleActivator.processCheckCommand("com.tailscale.ipn")
        )

        assertEquals(
            TailscaleProcessCheck(true, true, TailscaleActivator.PROCESS_RUNNING_MESSAGE),
            TailscaleActivator.interpretProcessCheck(
                shellResult(true, "BYDCOLLECTOR_TAILSCALE_PROCESS_RUNNING")
            )
        )
        assertEquals(
            TailscaleProcessCheck(true, false, TailscaleActivator.PROCESS_NOT_RUNNING_MESSAGE),
            TailscaleActivator.interpretProcessCheck(
                shellResult(true, "BYDCOLLECTOR_TAILSCALE_PROCESS_NOT_RUNNING")
            )
        )
        assertEquals(
            TailscaleProcessCheck(false, false, "tailscale_process_check_failed: adb unavailable"),
            TailscaleActivator.interpretProcessCheck(shellResult(false, error = "adb unavailable"))
        )
        assertEquals(
            TailscaleProcessCheck(false, false, "tailscale_process_check_unrecognized"),
            TailscaleActivator.interpretProcessCheck(shellResult(true, "unexpected marker"))
        )
    }

    @Test
    fun launchTransactionChecksProcessCapturesDisplayZeroAndStartsTailscale() {
        val command = TailscaleActivator.launchIfNeededCommand(
            "com.tailscale.ipn",
            "com.tailscale.ipn.MainActivity"
        )
        assertTrue(command.contains("pidof com.tailscale.ipn"))
        assertTrue(command.contains("dumpsys activity activities"))
        assertTrue(command.contains("sed -n '/Display #0 /,/Display #[1-9][0-9]* /p'"))
        assertTrue(command.contains("BYDCOLLECTOR_TAILSCALE_PREVIOUS=%s"))
        assertTrue(command.contains("am start -n com.tailscale.ipn/com.tailscale.ipn.MainActivity"))

        val target = TailscaleForegroundTarget.Task(42, "com.example.app", "com.example.app/.MainActivity")
        val launched = TailscaleActivator.interpretLaunchResult(
            shellResult(
                true,
                "BYDCOLLECTOR_TAILSCALE_PREVIOUS=" +
                    "mResumedActivity: ActivityRecord{abc u0 com.example.app/.MainActivity t42}\n" +
                    "BYDCOLLECTOR_TAILSCALE_LAUNCH_REQUESTED"
            ),
            homePackageName = "com.android.launcher"
        )
        val running = TailscaleActivator.interpretLaunchResult(
            shellResult(true, "BYDCOLLECTOR_TAILSCALE_PROCESS_RUNNING"),
            homePackageName = "com.android.launcher"
        )
        val processFailure = TailscaleActivator.interpretLaunchResult(
            shellResult(false, "BYDCOLLECTOR_TAILSCALE_PROCESS_CHECK_FAILED", "shell exit code 2"),
            homePackageName = "com.android.launcher"
        )

        assertEquals(TailscaleLaunchResult(true, true, target, "tailscale_launch_requested_via_adb"), launched)
        assertEquals(TailscaleLaunchResult(true, false, null, TailscaleActivator.PROCESS_RUNNING_MESSAGE), running)
        assertFalse(processFailure.ok)
        assertEquals("tailscale_process_check_failed: shell exit code 2", processFailure.message)
    }

    @Test
    fun foregroundCaptureParsesAppHomeMalformedAndOverflowTaskId() {
        assertEquals(
            "dumpsys activity activities | sed -n '/Display #0 /,/Display #[1-9][0-9]* /p' | " +
                "grep -m 1 -E 'mResumedActivity|topResumedActivity'",
            TailscaleActivator.foregroundActivityCommand()
        )
        assertEquals(
            TailscaleForegroundTarget.Task(42, "com.example.app", "com.example.app/.MainActivity"),
            TailscaleActivator.interpretForegroundCapture(
                "mResumedActivity: ActivityRecord{abc u0 com.example.app/.MainActivity t42}",
                "com.android.launcher"
            )
        )
        assertEquals(
            TailscaleForegroundTarget.Home,
            TailscaleActivator.interpretForegroundCapture(
                "topResumedActivity=ActivityRecord{def u0 com.android.launcher/.Launcher t7}",
                "com.android.launcher"
            )
        )
        assertEquals(
            TailscaleForegroundTarget.Unknown("tailscale_foreground_capture_unrecognized"),
            TailscaleActivator.interpretForegroundCapture("no resumed activity", "com.android.launcher")
        )
        assertEquals(
            TailscaleForegroundTarget.Unknown("tailscale_foreground_task_id_invalid"),
            TailscaleActivator.interpretForegroundCapture(
                "mResumedActivity: ActivityRecord{abc u0 com.example.app/.MainActivity t999999999999999999999}",
                "com.android.launcher"
            )
        )
    }

    @Test
    fun guardedRestoreVerifiesExactTaskAndPreservesManualForegroundChange() {
        val task = TailscaleActivator.guardedRestoreCommand(
            "com.tailscale.ipn",
            TailscaleForegroundTarget.Task(42, "com.example.app", "com.example.app/.MainActivity")
        )
        val home = TailscaleActivator.guardedRestoreCommand(
            "com.tailscale.ipn",
            TailscaleForegroundTarget.Home
        )
        val fallback = TailscaleActivator.guardedRestoreCommand(
            "com.tailscale.ipn",
            TailscaleForegroundTarget.Unknown("capture failed")
        )

        assertTrue(task.contains("grep -F -q 'com.tailscale.ipn/'"))
        assertTrue(task.contains("am task focus 42"))
        assertTrue(task.contains("am task focus 42 >/dev/null 2>&1 && sleep 1"))
        assertTrue(task.contains("grep -E -q ' t42([ }]|$)'"))
        assertFalse(task.contains("grep -F -q 'com.example.app/'"))
        assertTrue(task.contains("elif dumpsys activity activities"))
        assertTrue(task.contains("BYDCOLLECTOR_TAILSCALE_TASK_RESTORED"))
        assertTrue(task.contains("BYDCOLLECTOR_TAILSCALE_FALLBACK_HOME"))
        assertTrue(home.contains("input keyevent KEYCODE_HOME && echo BYDCOLLECTOR_TAILSCALE_HOME_RESTORED"))
        assertTrue(fallback.contains("input keyevent KEYCODE_HOME && echo BYDCOLLECTOR_TAILSCALE_FALLBACK_HOME"))
        assertTrue(task.endsWith("else echo BYDCOLLECTOR_TAILSCALE_NOT_FOREGROUND; fi"))

        assertEquals(
            TailscaleActivationResult(true, TailscaleActivator.TASK_RESTORED_MESSAGE),
            TailscaleActivator.interpretRestoreResult(shellResult(true, "BYDCOLLECTOR_TAILSCALE_TASK_RESTORED"))
        )
        assertEquals(
            TailscaleActivationResult(true, TailscaleActivator.HOME_RESTORED_MESSAGE),
            TailscaleActivator.interpretRestoreResult(shellResult(true, "BYDCOLLECTOR_TAILSCALE_HOME_RESTORED"))
        )
        assertEquals(
            TailscaleActivationResult(true, TailscaleActivator.FALLBACK_HOME_MESSAGE),
            TailscaleActivator.interpretRestoreResult(shellResult(true, "BYDCOLLECTOR_TAILSCALE_FALLBACK_HOME"))
        )
        assertEquals(
            TailscaleActivationResult(false, TailscaleActivator.RESTORE_SKIPPED_NOT_FOREGROUND),
            TailscaleActivator.interpretRestoreResult(shellResult(true, "BYDCOLLECTOR_TAILSCALE_NOT_FOREGROUND"))
        )
        assertEquals(
            "tailscale_adb_restore_failed: adb unavailable",
            TailscaleActivator.interpretRestoreResult(shellResult(false, error = "adb unavailable")).message
        )
        assertEquals(
            "tailscale_foreground_restore_unrecognized",
            TailscaleActivator.interpretRestoreResult(shellResult(true, "unexpected marker")).message
        )
    }

    @Test
    fun delayedSequenceLaunchesThenRestoresCapturedTask() {
        val calls = mutableListOf<String>()
        val target = TailscaleForegroundTarget.Task(42, "com.example.app", "com.example.app/.MainActivity")

        val result = TailscaleActivator.runDelayedSequence(
            isEnabled = { true },
            sleeper = { calls += "sleep:$it" },
            launch = {
                calls += "launch"
                TailscaleLaunchResult(true, true, target, "launched")
            },
            restoreForeground = {
                calls += "restore:$it"
                TailscaleActivationResult(true, TailscaleActivator.TASK_RESTORED_MESSAGE)
            },
            onEvent = { category, _ -> calls += category }
        )

        assertEquals(
            listOf(
                "tailscale_launch_delayed",
                "sleep:10000",
                "launch",
                "tailscale_foreground_captured",
                "tailscale_launch_succeeded",
                "sleep:10000",
                "restore:$target",
                TailscaleActivator.TASK_RESTORED_MESSAGE
            ),
            calls
        )
        assertEquals(TailscaleSequenceResult(true, true), result)
    }

    @Test
    fun delayedSequenceCancelsWhenProcessStartsOrCheckFails() {
        listOf(
            TailscaleLaunchResult(true, false, null, TailscaleActivator.PROCESS_RUNNING_MESSAGE) to
                "tailscale_launch_cancelled_running",
            TailscaleLaunchResult(false, false, null, "tailscale_process_check_failed: failed") to
                "tailscale_launch_cancelled_process_check_failed"
        ).forEach { (launchResult, expectedEvent) ->
            val events = mutableListOf<String>()
            val result = TailscaleActivator.runDelayedSequence(
                isEnabled = { true },
                sleeper = {},
                launch = { launchResult },
                restoreForeground = { error("must not restore foreground") },
                onEvent = { category, _ -> events += category }
            )

            assertEquals(listOf("tailscale_launch_delayed", expectedEvent), events)
            assertEquals(TailscaleSequenceResult(false, false), result)
        }
    }

    @Test
    fun disabledPolicyCancelsBeforeLaunch() {
        val events = mutableListOf<String>()
        val result = TailscaleActivator.runDelayedSequence(
            isEnabled = { false },
            sleeper = {},
            launch = { error("must not launch") },
            restoreForeground = { error("must not restore foreground") },
            onEvent = { category, _ -> events += category }
        )

        assertEquals(listOf("tailscale_launch_delayed", "tailscale_launch_cancelled"), events)
        assertEquals(TailscaleSequenceResult(false, false), result)
    }

    @Test
    fun failedLaunchSkipsForegroundRestore() {
        val result = TailscaleActivator.runDelayedSequence(
            isEnabled = { true },
            sleeper = {},
            launch = { TailscaleLaunchResult(false, false, null, "launch failed") },
            restoreForeground = { error("must not restore after failed launch") },
            onEvent = { _, _ -> }
        )

        assertEquals(TailscaleSequenceResult(false, false), result)
    }

    @Test
    fun manualForegroundChangeProducesDistinctRestoreEvent() {
        val events = mutableListOf<String>()
        val result = TailscaleActivator.runDelayedSequence(
            isEnabled = { true },
            sleeper = {},
            launch = { TailscaleLaunchResult(true, true, TailscaleForegroundTarget.Home, "launched") },
            restoreForeground = {
                TailscaleActivationResult(false, TailscaleActivator.RESTORE_SKIPPED_NOT_FOREGROUND)
            },
            onEvent = { category, _ -> events += category }
        )

        assertEquals(TailscaleActivator.RESTORE_SKIPPED_NOT_FOREGROUND, events.last())
        assertEquals(TailscaleSequenceResult(true, false), result)
    }

    @Test
    fun unknownForegroundCaptureFlowsToFallbackRestore() {
        val target = TailscaleForegroundTarget.Unknown("capture failed")
        val events = mutableListOf<String>()
        var restoredTarget: TailscaleForegroundTarget? = null

        val result = TailscaleActivator.runDelayedSequence(
            isEnabled = { true },
            sleeper = {},
            launch = { TailscaleLaunchResult(true, true, target, "launched") },
            restoreForeground = {
                restoredTarget = it
                TailscaleActivationResult(true, TailscaleActivator.FALLBACK_HOME_MESSAGE)
            },
            onEvent = { category, _ -> events += category }
        )

        assertEquals(target, restoredTarget)
        assertTrue("tailscale_foreground_capture_failed" in events)
        assertEquals(TailscaleActivator.FALLBACK_HOME_MESSAGE, events.last())
        assertEquals(TailscaleSequenceResult(true, true), result)
    }

    @Test
    fun runningLaunchResultCarriesNoForegroundTarget() {
        val result = TailscaleActivator.interpretLaunchResult(
            shellResult(true, "BYDCOLLECTOR_TAILSCALE_PROCESS_RUNNING"),
            homePackageName = "com.android.launcher"
        )

        assertFalse(result.launched)
        assertNull(result.previousForeground)
    }

    private fun shellResult(
        ok: Boolean,
        output: String = "",
        error: String? = null
    ) = AdbShellResult(ok, output, error, 1L)
}
