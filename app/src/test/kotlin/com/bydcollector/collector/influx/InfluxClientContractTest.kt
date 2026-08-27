package com.bydcollector.collector.influx

import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfluxClientContractTest {
    @Test
    fun httpConnectionDisconnectsInFinally() {
        val source = sourceFile("com/bydcollector/collector/influx/InfluxClient.kt").readText()

        assertTrue(source.contains("var connection: HttpURLConnection? = null"))
        assertTrue(source.contains("finally"))
        assertTrue(source.contains("httpStatus = code"))
        assertInOrder(source, "finally", "connection?.disconnect()")
    }

    @Test
    fun actionResultCarriesHttpStatusOnlyForFailures() {
        assertEquals(400, InfluxActionResult.fail("influx_http_error", "bad request", 400).httpStatus)
        assertEquals(null, InfluxActionResult.ok().httpStatus)
    }

    @Test
    fun responseDiagnosticsAreBoundedAndCredentialSafe() {
        val raw = "user=anton password=secret auth=YW50b246c2VjcmV0\n" + "x".repeat(5_000)
        val bounded = boundedInfluxResponse(ByteArrayInputStream(raw.toByteArray()), maxChars = 2_048)
            ?: error("missing body")
        val sanitized = sanitizeInfluxDiagnostic(
            bounded,
            InfluxConfig(
                enabled = true,
                host = "influx.local",
                port = 8086,
                database = "bydcollector",
                username = "anton",
                password = "secret",
                measurement = "byd_state",
                enabledCategories = emptySet()
            )
        )

        assertEquals(2_048, bounded.length)
        assertFalse(sanitized.contains("anton"))
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("YW50b246c2VjcmV0"))
        assertFalse(sanitized.contains('\n'))
    }

    @Test
    fun failureClassifierPrioritizesTlsAndProtocolOverWrappedTransport() {
        val wrappedTls = IOException("socket").apply { initCause(SSLException("tls")) }

        assertEquals(InfluxFailureKind.AUTHENTICATION, classifyInfluxNetworkFailure(wrappedTls))
        assertEquals(InfluxFailureKind.PROTOCOL, classifyInfluxNetworkFailure(ProtocolException("bad protocol")))
        assertEquals(InfluxFailureKind.TRANSPORT, classifyInfluxNetworkFailure(ConnectException("offline")))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing first token: $first")
        assertTrue(secondIndex >= 0, "Missing second token: $second")
        assertTrue(firstIndex < secondIndex, "Expected `$first` before `$second`")
    }
}
