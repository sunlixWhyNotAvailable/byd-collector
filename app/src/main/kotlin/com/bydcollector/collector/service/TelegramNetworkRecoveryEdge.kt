package com.bydcollector.collector.service

/** Immutable subset of capabilities relevant to deciding whether a retry hint is useful. */
internal data class TelegramNetworkCapabilitiesFingerprint(
    val hasInternet: Boolean,
    val validated: Boolean,
    val notRestricted: Boolean,
    val vpn: Boolean,
    val wifi: Boolean,
    val cellular: Boolean,
    val ethernet: Boolean
) {
    val mayCarryTraffic: Boolean
        get() = hasInternet || validated || notRestricted || vpn || wifi || cellular || ethernet
}

/** Immutable link state; intentionally excludes signal strength and other churn-only fields. */
internal data class TelegramNetworkLinkFingerprint(
    val dnsServers: List<String>,
    val routes: List<String>,
    val linkAddresses: List<String>,
    val interfaceName: String?,
    val domains: String?,
    val mtu: Int
) {
    val mayCarryTraffic: Boolean
        get() = dnsServers.isNotEmpty() || routes.isNotEmpty() || linkAddresses.isNotEmpty() || !interfaceName.isNullOrBlank()
}

/**
 * Detects useful changes on the active default network without retaining the
 * mutable Android NetworkCapabilities/LinkProperties objects.
 */
internal class TelegramNetworkRecoveryEdge {
    private data class Snapshot(
        val networkKey: String,
        val capabilities: TelegramNetworkCapabilitiesFingerprint? = null,
        val links: TelegramNetworkLinkFingerprint? = null
    )

    private var active: Snapshot? = null

    @Synchronized
    fun onAvailable(networkKey: String): Boolean {
        require(networkKey.isNotBlank()) { "Network key must not be blank" }
        if (active?.networkKey == networkKey) return false
        active = Snapshot(networkKey)
        return true
    }

    @Synchronized
    fun onCapabilitiesChanged(
        networkKey: String,
        fingerprint: TelegramNetworkCapabilitiesFingerprint
    ): Boolean {
        val previous = active
        // Default-network callbacks deliver onAvailable before capability/link
        // updates. Ignore late updates from the network that has already lost
        // default ownership instead of resurrecting its state.
        if (previous?.networkKey != networkKey) return false
        if (previous.capabilities == null) {
            active = previous.copy(capabilities = fingerprint)
            return false
        }
        if (previous.capabilities == fingerprint) return false
        active = previous.copy(capabilities = fingerprint)
        return fingerprint.mayCarryTraffic || previous.links?.mayCarryTraffic == true
    }

    @Synchronized
    fun onLinkPropertiesChanged(
        networkKey: String,
        fingerprint: TelegramNetworkLinkFingerprint
    ): Boolean {
        val previous = active
        if (previous?.networkKey != networkKey) return false
        if (previous.links == null) {
            active = previous.copy(links = fingerprint)
            return false
        }
        if (previous.links == fingerprint) return false
        active = previous.copy(links = fingerprint)
        return fingerprint.mayCarryTraffic || previous.capabilities?.mayCarryTraffic == true
    }

    @Synchronized
    fun onLost(networkKey: String) {
        if (active?.networkKey == networkKey) active = null
    }

    @Synchronized
    fun reset() {
        active = null
    }
}
