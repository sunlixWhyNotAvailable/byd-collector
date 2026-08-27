package com.bydcollector.collector.influx

import com.bydcollector.collector.util.readBoundedUtf8
import java.io.IOException
import java.net.ConnectException
import java.io.OutputStreamWriter
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLException

interface InfluxClient {
    fun test(config: InfluxConfig): InfluxActionResult
    fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult
}

//minimal influxdb v1 http client used by foreground-service export actions
class HttpInfluxClient : InfluxClient {
    override fun test(config: InfluxConfig): InfluxActionResult {
        val ping = request(config, "GET", "/ping", null)
        if (!ping.ok) return ping
        //writes a tiny point because /ping can succeed even when database/write permissions are wrong
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
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(config.baseUrl + path).openConnection() as HttpURLConnection).apply {
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
                if (code in 200..299) {
                    InfluxActionResult.ok("http $code")
                } else {
                    val response = boundedInfluxResponse(
                        connection.errorStream ?: runCatching { connection.inputStream }.getOrNull()
                    )?.let { sanitizeInfluxDiagnostic(it, config) }
                    val message = response?.takeIf { it.isNotBlank() }
                        ?.let { "HTTP $code: $it" }
                        ?: "HTTP $code"
                    InfluxActionResult.fail(
                        "influx_http_error",
                        message,
                        httpStatus = code,
                        failureKind = when {
                            code == 401 || code == 403 -> InfluxFailureKind.AUTHENTICATION
                            code == 400 || code == 422 -> InfluxFailureKind.DATA
                            code in 502..504 -> InfluxFailureKind.TRANSPORT
                            else -> InfluxFailureKind.OTHER
                        }
                    )
                }
            } finally {
                connection?.disconnect()
            }
        }.getOrElse { error ->
            val detail = sanitizeInfluxDiagnostic(error.message ?: "no message", config)
            InfluxActionResult.fail(
                "influx_network_error",
                "${error::class.java.simpleName}: $detail",
                failureKind = classifyNetworkFailure(error)
            )
        }
    }

    private fun classifyNetworkFailure(error: Throwable): InfluxFailureKind {
        return classifyInfluxNetworkFailure(error)
    }

    private fun InfluxConfig.basicAuthHeader(): String? {
        val user = username ?: return null
        val pass = password ?: ""
        //supports optional basic auth for ha add-ons while allowing unauthenticated local influx setups
        val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(StandardCharsets.UTF_8))
        return "Basic $token"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}

internal fun classifyInfluxNetworkFailure(error: Throwable): InfluxFailureKind {
    var transportCause = false
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth++ < 16) {
        if (current is SSLException) return InfluxFailureKind.AUTHENTICATION
        if (current is ProtocolException) return InfluxFailureKind.PROTOCOL
        if (
            current is SocketTimeoutException ||
            current is ConnectException ||
            current is NoRouteToHostException ||
            current is IOException
        ) {
            transportCause = true
        }
        current = current.cause
    }
    return if (transportCause) InfluxFailureKind.TRANSPORT else InfluxFailureKind.OTHER
}

internal fun boundedInfluxResponse(input: InputStream?, maxChars: Int = 2_048): String? {
    return readBoundedUtf8(input, maxChars)?.text
}

internal fun sanitizeInfluxDiagnostic(value: String, config: InfluxConfig): String {
    val user = config.username.orEmpty()
    val password = config.password.orEmpty()
    val authPayload = if (user.isNotEmpty() || password.isNotEmpty()) "$user:$password" else ""
    val encodedAuth = authPayload.takeIf { it.isNotEmpty() }
        ?.let { Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) }
        .orEmpty()
    return listOf(user, password, authPayload, encodedAuth)
        .filter { it.isNotEmpty() }
        .distinct()
        .fold(value) { sanitized, secret -> sanitized.replace(secret, "[redacted]") }
        .replace(Regex("[\\p{Cntrl}\\s]+"), " ")
        .trim()
}
