package com.bydcollector.collector.ha

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
    fun delayedSequenceLaunchesThenMinimizes() {
        val calls = mutableListOf<String>()

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
            onEvent = { category, _ -> calls += category }
        )

        assertEquals(
            listOf(
                "tailscale_launch_delayed",
                "sleep:5000",
                "launch",
                "tailscale_launch_succeeded",
                "sleep:5000",
                "home",
                "tailscale_minimize_succeeded"
            ),
            calls
        )
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
