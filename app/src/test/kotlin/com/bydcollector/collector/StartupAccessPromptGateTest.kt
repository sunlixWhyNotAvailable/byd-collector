package com.bydcollector.collector

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupAccessPromptGateTest {
    @Test
    fun focusedForegroundActivityWithoutDialogsAllowsStartupAccess() {
        assertFalse(blocked())
    }

    @Test
    fun everyActivityOrCollectorDialogConditionBlocksStartupAccess() {
        assertTrue(blocked(foreground = false))
        assertTrue(blocked(windowFocused = false))
        assertTrue(blocked(backgroundPromptVisible = true))
        assertTrue(blocked(updateDialogVisible = true))
        assertTrue(blocked(databaseMaintenanceVisible = true))
        assertTrue(blocked(archiveStorageRunning = true))
        assertTrue(blocked(archiveDeletePromptVisible = true))
    }

    private fun blocked(
        foreground: Boolean = true,
        windowFocused: Boolean = true,
        backgroundPromptVisible: Boolean = false,
        updateDialogVisible: Boolean = false,
        databaseMaintenanceVisible: Boolean = false,
        archiveStorageRunning: Boolean = false,
        archiveDeletePromptVisible: Boolean = false
    ): Boolean = startupAccessPromptBlocked(
        foreground = foreground,
        windowFocused = windowFocused,
        backgroundPromptVisible = backgroundPromptVisible,
        updateDialogVisible = updateDialogVisible,
        databaseMaintenanceVisible = databaseMaintenanceVisible,
        archiveStorageRunning = archiveStorageRunning,
        archiveDeletePromptVisible = archiveDeletePromptVisible
    )
}
