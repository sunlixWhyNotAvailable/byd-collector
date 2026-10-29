package com.bydcollector.collector.keepalive

import android.net.LinkAddress
import android.net.LinkProperties
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class CellularApnPlatformTest {
    private val capture = javaClass.getResourceAsStream("/keepalive/apn2-screen-observations.json")!!
        .bufferedReader().use { JSONObject(it.readText()) }

    @Test
    fun capturedStaleIpIsNotReadyAndSuccessfulNetworkTupleMatches() {
        val before = capture.getJSONObject("before")
        val traffic = capture.getJSONObject("traffic")
        val iface = traffic.getString("interface")
        val ip = traffic.getString("source")
        // Only the interface/address tuple is replayed; this does not emulate Binder or HTTP success.
        val link = LinkProperties().apply {
            interfaceName = iface
            val address = LinkAddress::class.java.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
                .newInstance(InetAddress.getByName(ip), 32)
            LinkProperties::class.java.getMethod("addLinkAddress", LinkAddress::class.java).invoke(this, address)
        }
        assertFalse(CellularApnPlatform.assessApn2(
            before.getString("interface"), before.getString("ip"), listOf(link),
            before.getString("interface"), before.getString("ip")
        ).ready)
        val ready = CellularApnPlatform.assessApn2(iface, ip, listOf(link), iface, ip)
        assertTrue(ready.ready)
        assertEquals("network_matched", ready.reason)
        // A leftover address from the earlier captured connection cannot match the current network.
        assertFalse(CellularApnPlatform.assessApn2(
            iface, before.getString("ip"), listOf(link), iface, before.getString("ip")
        ).ready)
    }

    @Test
    fun observationsStraddlingCapturedReconnectStayUnknown() {
        val before = capture.getJSONObject("before")
        val after = capture.getJSONObject("traffic")
        assertFailsWith<IOException> {
            CellularApnPlatform.assessApn2(
                before.getString("interface"), before.getString("ip"), emptyList(),
                after.getString("interface"), after.getString("source")
            )
        }
        assertFailsWith<IOException> {
            CellularApnPlatform.assessApn2("rmnet_data1", "not-an-address", emptyList(),
                "rmnet_data1", "not-an-address")
        }
    }
}
