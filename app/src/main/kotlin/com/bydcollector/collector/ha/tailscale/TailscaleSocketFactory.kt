package com.bydcollector.collector.ha.tailscale

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** Paho connects to loopback; the scoped lease carries the unchanged broker destination. */
internal class TailscaleSocketFactory(
    private val host: String,
    private val port: Int,
    private val lease: TailscaleBridgeLease
) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress) = connect(endpoint, 5_000)
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw IOException("Unsupported MQTT socket endpoint")
            require(target.port == lease.port && target.hostString == "127.0.0.1") { "MQTT bridge endpoint changed" }
            val previousTimeout = soTimeout
            try {
                super.connect(InetSocketAddress("127.0.0.1", lease.port), timeout)
                soTimeout = if (timeout > 0) timeout else 5_000
                val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
                require(authority.none { it == '\r' || it == '\n' })
                getOutputStream().write(("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n" +
                    "Proxy-Authorization: Bearer ${lease.token}\r\n\r\n").toByteArray(Charsets.US_ASCII))
                getOutputStream().flush()
                // Do not buffer beyond the headers: the next bytes belong to MQTT.
                val header = StringBuilder()
                val input = getInputStream()
                while (!header.endsWith("\r\n\r\n")) {
                    if (header.length >= 16_384) throw IOException("Tailscale CONNECT headers exceed 16 KiB")
                    val byte = input.read()
                    if (byte < 0) throw IOException("Tailscale CONNECT ended before headers")
                    header.append(byte.toChar())
                }
                val lines = header.toString().split("\r\n")
                if (lines.first().split(' ').getOrNull(1) != "200") {
                    val detail = lines.firstOrNull { it.startsWith("X-Collector-Bridge-Error:", true) }
                        ?.substringAfter(':')?.trim().orEmpty()
                    throw TailscaleTransportException("${lines.first()} $detail".trim())
                }
                soTimeout = previousTimeout
            } catch (error: Throwable) {
                try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply {
        connect(InetSocketAddress.createUnresolved(host, port))
    }
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress!!, port)
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket().apply {
        bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress.createUnresolved(host, port))
    }
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress!!, port, local, localPort)
}
