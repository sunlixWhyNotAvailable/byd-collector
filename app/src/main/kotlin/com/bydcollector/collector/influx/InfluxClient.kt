package com.bydcollector.collector.influx

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

interface InfluxClient {
    fun test(config: InfluxConfig): InfluxActionResult
    fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult
}

class HttpInfluxClient : InfluxClient {
    override fun test(config: InfluxConfig): InfluxActionResult {
        val ping = request(config, "GET", "/ping", null)
        if (!ping.ok) return ping
        val line = "bydcollector_connectivity_test value=1i ${System.currentTimeMillis() * 1_000_000L}"
        return write(config.copy(enabled = true), listOf(line))
    }

    override fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult {
        if (!config.enabled) return InfluxActionResult.fail("influx_disabled", "InfluxDB export is disabled")
        if (config.host.isBlank()) return InfluxActionResult.fail("influx_host_missing", "InfluxDB host is blank")
        if (lines.isEmpty()) return InfluxActionResult.ok("nothing pending")
        val path = "/write?db=${encode(config.normalizedDatabase())}"
        return request(config, "POST", path, lines.joinToString("\n"))
    }

    private fun request(
        config: InfluxConfig,
        method: String,
        path: String,
        body: String?
    ): InfluxActionResult {
        if (config.host.isBlank()) return InfluxActionResult.fail("influx_host_missing", "InfluxDB host is blank")
        return runCatching {
            val connection = (URL(config.baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "BYDCollector")
                config.basicAuthHeader()?.let { setRequestProperty("Authorization", it) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
            }
            if (body != null) {
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(body)
                }
            }
            val code = connection.responseCode
            connection.disconnect()
            if (code in 200..299) {
                InfluxActionResult.ok("http $code")
            } else {
                InfluxActionResult.fail("influx_http_error", "HTTP $code")
            }
        }.getOrElse { error ->
            InfluxActionResult.fail("influx_network_error", "${error::class.java.simpleName}: ${error.message ?: "no message"}")
        }
    }

    private fun InfluxConfig.basicAuthHeader(): String? {
        val user = username ?: return null
        val pass = password ?: ""
        val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(StandardCharsets.UTF_8))
        return "Basic $token"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}
