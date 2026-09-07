package com.bydcollector.collector.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramNetworkRecoveryEdgeTest {
    private val vpn = TelegramNetworkCapabilitiesFingerprint(
        hasInternet = false,
        validated = false,
        notRestricted = false,
        vpn = true,
        wifi = false,
        cellular = false,
        ethernet = false
    )

    @Test
    fun availableAndIdenticalCapabilitiesProduceOneHint() {
        val edge = TelegramNetworkRecoveryEdge()

        assertTrue(edge.onAvailable("vpn-1"))
        assertFalse(edge.onAvailable("vpn-1"))
        assertFalse(edge.onCapabilitiesChanged("vpn-1", vpn))
        assertFalse(edge.onCapabilitiesChanged("vpn-1", vpn))
    }

    @Test
    fun validatedAndLinkChangesHintWithoutBeingARequirement() {
        val edge = TelegramNetworkRecoveryEdge()
        val firstLink = TelegramNetworkLinkFingerprint(
            dnsServers = listOf("10.0.0.1"),
            routes = listOf("0.0.0.0/0 via vpn"),
            linkAddresses = listOf("10.0.0.2/24"),
            interfaceName = "tun0",
            domains = null,
            mtu = 1400
        )
        val secondLink = firstLink.copy(dnsServers = listOf("10.0.0.2"))

        assertTrue(edge.onAvailable("vpn-1"))
        assertFalse(edge.onCapabilitiesChanged("vpn-1", vpn))
        assertTrue(edge.onCapabilitiesChanged("vpn-1", vpn.copy(validated = true)))
        assertFalse(edge.onLinkPropertiesChanged("vpn-1", firstLink))
        assertTrue(edge.onLinkPropertiesChanged("vpn-1", secondLink))
        assertFalse(edge.onLinkPropertiesChanged("vpn-1", secondLink))
    }

    @Test
    fun lostNetworkAllowsAReplacementHint() {
        val edge = TelegramNetworkRecoveryEdge()

        assertTrue(edge.onAvailable("wifi-1"))
        assertTrue(edge.onAvailable("wifi-2"))
        assertFalse(edge.onCapabilitiesChanged("old-vpn", vpn))
        assertFalse(edge.onLinkPropertiesChanged("old-vpn", TelegramNetworkLinkFingerprint(emptyList(), emptyList(), emptyList(), null, null, 0)))
        edge.onLost("wifi-1")
        assertFalse(edge.onAvailable("wifi-2"))
        edge.onLost("wifi-2")
        assertTrue(edge.onAvailable("wifi-3"))
    }
}
