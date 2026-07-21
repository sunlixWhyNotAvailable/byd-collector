package com.bydcollector.collector

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupAccessPromptGateTest {
    @Test
    fun focusedForegroundActivityWithoutHardSystemFlowAllowsStartupAccess() {
        assertFalse(blocked())
    }

    @Test
    fun lifecycleAndHardSystemFlowsBlockStartupAccess() {
        assertTrue(blocked(foreground = false))
        assertTrue(blocked(windowFocused = false))
        assertTrue(blocked(backgroundPromptVisible = true))
        assertTrue(blocked(runtimePermissionRequestInFlight = true))
    }

    private fun blocked(
        foreground: Boolean = true,
        windowFocused: Boolean = true,
        backgroundPromptVisible: Boolean = false,
        runtimePermissionRequestInFlight: Boolean = false
    ): Boolean = startupHardFlowBlocked(
        foreground = foreground,
        windowFocused = windowFocused,
        backgroundPromptVisible = backgroundPromptVisible,
        runtimePermissionRequestInFlight = runtimePermissionRequestInFlight
    )
}
