package com.bydcollector.collector.update

import android.view.Display
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateHintDisplayReadinessTest {
    @Test
    fun readinessRequiresInteractiveDeviceAndOnMainDisplay() {
        assertTrue(isUpdateHintDisplayReady(isInteractive = true, mainDisplayState = Display.STATE_ON))
        assertFalse(isUpdateHintDisplayReady(isInteractive = false, mainDisplayState = Display.STATE_ON))
        assertFalse(isUpdateHintDisplayReady(isInteractive = true, mainDisplayState = Display.STATE_OFF))
        assertFalse(isUpdateHintDisplayReady(isInteractive = true, mainDisplayState = null))
    }
}
