package com.bydcollector.collector.ha

import com.bydcollector.collector.adb.AdbShellResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TailscaleActivatorTest {
    @Test
    fun packageCandidatesPreferStableTailscalePackage() {
        assertEquals(
            listOf("com.tailscale.ipn"),
            TailscaleActivator.packageCandidates()
        )
    }

    @Test
    fun launchCommandUsesShellActivityStart() {
        assertEquals(
            "am start -n com.tailscale.ipn/com.tailscale.ipn.MainActivity",
            TailscaleActivator.launchCommand("com.tailscale.ipn", "com.tailscale.ipn.MainActivity")
        )
    }

    @Test
    fun homeCommandUsesAndroidHomeKey() {
        assertEquals("input keyevent KEYCODE_HOME", TailscaleActivator.homeCommand())
    }

    @Test
    fun guardedHomeCommandRequiresTailscaleForeground() {
        assertEquals(
            "if dumpsys activity activities | grep -m 1 -E 'mResumedActivity|topResumedActivity' | " +
                "grep -q 'com.tailscale.ipn/'; then input keyevent KEYCODE_HOME && " +
                "echo BYDCOLLECTOR_TAILSCALE_HOME_SENT; else echo BYDCOLLECTOR_TAILSCALE_NOT_FOREGROUND; fi",
            TailscaleActivator.guardedHomeCommand("com.tailscale.ipn")
        )
    }

    @Test
    fun foregroundResultSendsHomeAndOtherForegroundSkipsIt() {
        val minimized = TailscaleActivator.interpretMinimizeResult(
            AdbShellResult(true, "BYDCOLLECTOR_TAILSCALE_HOME_SENT", null, 1L)
        )
        val skipped = TailscaleActivator.interpretMinimizeResult(
            AdbShellResult(true, "BYDCOLLECTOR_TAILSCALE_NOT_FOREGROUND", null, 1L)
        )
        val unrecognized = TailscaleActivator.interpretMinimizeResult(
            AdbShellResult(true, "", null, 1L)
        )

        assertTrue(minimized.ok)
        assertEquals("tailscale_minimized_via_adb", minimized.message)
        assertFalse(skipped.ok)
        assertEquals(TailscaleActivator.MINIMIZE_SKIPPED_NOT_FOREGROUND, skipped.message)
        assertFalse(unrecognized.ok)
        assertEquals("tailscale_foreground_check_unrecognized", unrecognized.message)
    }

    @Test
    fun delayedSequenceLaunchesThenMinimizes() {
        val calls = mutableListOf<String>()
        var delayMessage = ""

        val result = TailscaleActivator.runDelayedSequence(
            isEnabled = { true },
            sleeper = { calls += "sleep:$it" },
            activate = {
                calls += "launch"
                TailscaleActivationResult(true, "launched")
            },
            minimize = {
                calls += "home"
                TailscaleActivationResult(true, "minimized")
            },
            onEvent = { category, message ->
                calls += category
                if (category == "tailscale_launch_delayed") delayMessage = message
            }
        )

        assertEquals(
            listOf(
                "tailscale_launch_delayed",
                "sleep:10000",
                "launch",
                "tailscale_launch_succeeded",
                "sleep:10000",
                "home",
                "tailscale_minimize_succeeded"
            ),
            calls
        )
        assertEquals("Tailscale launch delayed by 10 seconds", delayMessage)
        assertTrue(result.launched)
        assertTrue(result.minimized)
    }

    @Test
    fun failedLaunchSkipsMinimize() {
        var minimized = false
        val result = TailscaleActivator.runDelayedSequence(
            isEnabled = { true },
            sleeper = {},
            activate = { TailscaleActivationResult(false, "failed") },
            minimize = {
                minimized = true
                TailscaleActivationResult(true, "minimized")
            },
            onEvent = { _, _ -> }
        )

        assertFalse(result.launched)
        assertFalse(result.minimized)
        assertFalse(minimized)
    }

    @Test
    fun changedForegroundSkipsHomeWithDistinctEvent() {
        val events = mutableListOf<String>()
        val result = TailscaleActivator.runDelayedSequence(
            isEnabled = { true },
            sleeper = {},
            activate = { TailscaleActivationResult(true, "launched") },
            minimize = {
                TailscaleActivationResult(false, TailscaleActivator.MINIMIZE_SKIPPED_NOT_FOREGROUND)
            },
            onEvent = { category, _ -> events += category }
        )

        assertTrue(result.launched)
        assertFalse(result.minimized)
        assertEquals(TailscaleActivator.MINIMIZE_SKIPPED_NOT_FOREGROUND, events.last())
    }

    @Test
    fun disabledPolicyCancelsBeforeLaunch() {
        var launched = false
        val result = TailscaleActivator.runDelayedSequence(
            isEnabled = { false },
            sleeper = {},
            activate = {
                launched = true
                TailscaleActivationResult(true, "launched")
            },
            minimize = { TailscaleActivationResult(true, "minimized") },
            onEvent = { _, _ -> }
        )

        assertFalse(result.launched)
        assertFalse(launched)
    }
}
