package com.bydcollector.collector.ha

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
