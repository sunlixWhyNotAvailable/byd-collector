package com.bydcollector.collector.influx

import com.bydcollector.collector.util.readBoundedUtf8
import com.bydcollector.collector.ha.HaEndpointProfile
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
import java.util.UUID
import javax.net.ssl.SSLException

interface InfluxClient {
    fun test(config: InfluxConfig): InfluxActionResult
    fun test(config: InfluxConfig, requestId: String): InfluxActionResult = test(config)
    fun test(config: InfluxConfig, requestId: String, profile: HaEndpointProfile?): InfluxActionResult = test(config, requestId)
    fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult
    fun write(config: InfluxConfig, lines: List<String>, requestId: String): InfluxActionResult = write(config, lines)
    fun write(config: InfluxConfig, lines: List<String>, requestId: String, profile: HaEndpointProfile?): InfluxActionResult =
        write(config, lines, requestId)
}

//minimal influxdb v1 http client used by foreground-service export actions
class HttpInfluxClient(
    private val diagnostics: InfluxDiagnosticSink = InfluxRuntimeDiagnosticsProcess.instance::record
) : InfluxClient {
    override fun test(config: InfluxConfig): InfluxActionResult =
        test(config, UUID.randomUUID().toString())

    override fun test(config: InfluxConfig, requestId: String): InfluxActionResult {
        return test(config, requestId, null)
    }

    override fun test(config: InfluxConfig, requestId: String, profile: HaEndpointProfile?): InfluxActionResult {
        val context = InfluxRequestContext(requestId, "test", profile, source = "current_test")
        val ping = request(config, "GET", "/ping", null, context)
        if (!ping.ok) return ping
        //writes a tiny point because /ping can succeed even when database/write permissions are wrong
        val line = "bydcollector_connectivity_test value=1i ${System.currentTimeMillis() * 1_000_000L}"
        return request(config.copy(enabled = true), "POST", "/write?db=${encode(config.normalizedDatabase())}", line, context)
    }

    override fun write(config: InfluxConfig, lines: List<String>): InfluxActionResult =
        write(config, lines, UUID.randomUUID().toString())

    override fun write(config: InfluxConfig, lines: List<String>, requestId: String): InfluxActionResult {
        return write(config, lines, requestId, null)
    }

    override fun write(
        config: InfluxConfig,
        lines: List<String>,
        requestId: String,
        profile: HaEndpointProfile?
    ): InfluxActionResult {
        if (!config.enabled) return InfluxActionResult.fail("influx_disabled", "InfluxDB export is disabled")
        if (config.host.isBlank()) return InfluxActionResult.fail("influx_host_missing", "InfluxDB host is blank")
        if (lines.isEmpty()) return InfluxActionResult.ok("nothing pending")
        val path = "/write?db=${encode(config.normalizedDatabase())}"
        return request(
            config,
            "POST",
            path,
            lines.joinToString("\n"),
            InfluxRequestContext(requestId, "export", profile, source = "frozen_export")
        )
    }

    private fun request(
        config: InfluxConfig,
        method: String,
        path: String,
        body: String?,
        context: InfluxRequestContext
    ): InfluxActionResult {
        if (config.host.isBlank()) return InfluxActionResult.fail("influx_host_missing", "InfluxDB host is blank")
        return runCatching {
            var connection: HttpURLConnection? = null
            try {
                val openStarted = System.nanoTime()
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
                    emitStage(context, config, "open_connection", elapsedMs(openStarted))
                } catch (error: Throwable) {
                    emitStage(context, config, "open_connection", elapsedMs(openStarted), error = error)
                    throw error
                }
                if (body != null) {
                    val writeStarted = System.nanoTime()
                    try {
                        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                            writer.write(body)
                        }
                        emitStage(context, config, "write_body", elapsedMs(writeStarted))
                    } catch (error: Throwable) {
                        emitStage(context, config, "write_body", elapsedMs(writeStarted), error = error)
                        throw error
                    }
                }
                val responseStarted = System.nanoTime()
                val code = try {
                    connection.responseCode
                } catch (error: Throwable) {
                    emitStage(context, config, "response", elapsedMs(responseStarted), error = error)
                    throw error
                }
                emitStage(context, config, "response", elapsedMs(responseStarted), httpStatus = code)
                if (code in 200..299) {
                    InfluxActionResult.ok("http $code")
                } else {
                    val bodyStarted = System.nanoTime()
                    val response = try {
                        boundedInfluxResponse(
                            connection.errorStream ?: runCatching { connection.inputStream }.getOrNull()
                        )?.let { sanitizeInfluxDiagnostic(it, config) }
                    } catch (error: Throwable) {
                        emitStage(context, config, "read_error_body", elapsedMs(bodyStarted), httpStatus = code, error = error)
                        throw error
                    }
                    emitStage(context, config, "read_error_body", elapsedMs(bodyStarted), httpStatus = code)
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
            emitStage(context, config, "request", 0L, error = error)
            val detail = sanitizeInfluxDiagnostic(error.message ?: "no message", config)
            InfluxActionResult.fail(
                "influx_network_error",
                "${error::class.java.simpleName}: $detail",
                failureKind = classifyNetworkFailure(error)
            )
        }
    }

    private fun emitStage(
        context: InfluxRequestContext,
        config: InfluxConfig,
        stage: String,
        durationMs: Long,
        httpStatus: Int? = null,
        error: Throwable? = null
    ) {
        val details = linkedMapOf(
            "request_id" to context.requestId,
            "mode" to context.mode,
            "source" to context.source,
            "profile" to (context.profile?.name?.lowercase() ?: "unknown"),
            "host" to safeInfluxDiagnosticHost(config.host),
            "port" to config.port.toString(),
            "stage" to stage,
            "duration_ms" to durationMs.toString()
        )
        httpStatus?.let { details["http_status"] = it.toString() }
        error?.let {
            details["error_class"] = it::class.java.simpleName
            details["error_kind"] = classifyInfluxNetworkFailure(it).name.lowercase()
        }
        runCatching { diagnostics(InfluxDiagnosticEvent("influx_http_stage", details)) }
    }

    private fun elapsedMs(startedNs: Long): Long = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)

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
