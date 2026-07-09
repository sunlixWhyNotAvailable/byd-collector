package com.bydcollector.collector.ha

import java.net.InetSocketAddress
import java.net.Socket

data class HaEndpoint(
    val channel: String,
    val host: String,
    val port: Int
) {
    val valid: Boolean get() = host.isNotBlank() && port in 1..65_535
}

class SocketHaEndpointProbe(
    private val timeoutMs: Int = 2_000
) {
    fun isReachable(endpoint: HaEndpoint): Boolean {
        if (!endpoint.valid) return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.host.trim(), endpoint.port), timeoutMs)
            }
            true
        }.getOrDefault(false)
    }
}
