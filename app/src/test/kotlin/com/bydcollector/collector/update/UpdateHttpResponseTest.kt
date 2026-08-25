package com.bydcollector.collector.update

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpdateHttpResponseTest {
    @Test
    fun oversizedResponseStopsAtTheChannelCapWithAControlledError() {
        val connection = FakeConnection(200, "x".repeat(UPDATE_RESPONSE_MAX_CHARS + 1))

        val error = assertFailsWith<IllegalStateException> { readUpdateResponse(connection) }

        assertEquals(
            "GitHub API response exceeds $UPDATE_RESPONSE_MAX_CHARS characters",
            error.message
        )
        assertTrue(connection.disconnected)
    }

    private class FakeConnection(
        private val status: Int,
        response: String
    ) : HttpURLConnection(URL("https://example.invalid")) {
        private val responseBytes = response.toByteArray(Charsets.UTF_8)
        var disconnected = false

        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(responseBytes)
        override fun disconnect() {
            disconnected = true
        }
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }
}
