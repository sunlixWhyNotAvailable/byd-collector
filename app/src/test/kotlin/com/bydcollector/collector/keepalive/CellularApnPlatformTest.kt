package com.bydcollector.collector.keepalive

import org.junit.Test
import java.io.IOException
import java.net.NetworkInterface
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellularApnPlatformTest {
    @Test
    fun readsActualKernelInterfaceWithoutIpOrAndroidNetwork() {
        val iface = NetworkInterface.getNetworkInterfaces().toList().first().name
        val result = CellularApnPlatform.readApnInterface(iface)
        assertTrue(result.ready)
        assertEquals(iface, result.iface)
        assertEquals("interface_present", result.reason)
        // Actual host lookup; this does not validate the vehicle's OEM APN2 property mapping.
        val missing = generateSequence(0) { it + 1 }.map { "bydtest$it" }
            .first { NetworkInterface.getByName(it) == null }
        assertFalse(CellularApnPlatform.readApnInterface(missing).ready)
        assertFalse(CellularApnPlatform.readApnInterface("").ready)
    }

    @Test
    fun invalidObservationIsAnErrorRatherThanMissingInterface() {
        assertFailsWith<IOException> { CellularApnPlatform.readApnInterface(null) }
        assertFailsWith<IOException> { CellularApnPlatform.readApnInterface("../apn2") }
    }
}
