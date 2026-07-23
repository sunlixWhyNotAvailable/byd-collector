package com.bydcollector.collector.telegram

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramHttpClientTest {
    @Test
    fun sendsUtf8FormWithoutParseMode() {
        val connection = FakeConnection(200, """{"ok":true,"result":{"message_id":1}}""")
        var openedUrl: URL? = null
        val client = TelegramHttpClient { url ->
            openedUrl = url
            connection
        }

        val result = client.sendMessage(TelegramSendMessage("123:secret", "-100 42", "Привіт & 81%"))
        val body = connection.sentBody.toString(StandardCharsets.UTF_8.name())

        assertEquals(TelegramSendResult.Success, result)
        assertEquals("https://api.telegram.org/bot123:secret/sendMessage", openedUrl.toString())
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", connection.getRequestProperty("Content-Type"))
        assertEquals("chat_id=-100+42&text=%D0%9F%D1%80%D0%B8%D0%B2%D1%96%D1%82+%26+81%25", body)
        assertFalse(body.contains("parse_mode"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun classifiesRateLimitAndCarriesRetryAfter() {
        val connection = FakeConnection(
            429,
            """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":17}}"""
        )
        val failure = assertIs<TelegramSendResult.Failure>(
            TelegramHttpClient { connection }.sendMessage(TelegramSendMessage("123:secret", "42", "hello"))
        )

        assertEquals(TelegramSendFailureKind.RATE_LIMITED, failure.kind)
        assertEquals(429, failure.httpStatus)
        assertEquals(429, failure.telegramErrorCode)
        assertEquals(17L, failure.retryAfterSeconds)
        assertTrue(failure.kind.retryable)
    }

    @Test
    fun classifiesCommonApiFailures() {
        mapOf(
            400 to TelegramSendFailureKind.BAD_REQUEST,
            401 to TelegramSendFailureKind.UNAUTHORIZED,
            403 to TelegramSendFailureKind.FORBIDDEN,
            404 to TelegramSendFailureKind.NOT_FOUND,
            429 to TelegramSendFailureKind.RATE_LIMITED,
            500 to TelegramSendFailureKind.SERVER_ERROR
        ).forEach { (code, expected) ->
            val failure = assertIs<TelegramSendResult.Failure>(
                TelegramHttpClient { FakeConnection(code, """{"ok":false,"error_code":$code,"description":"secret detail"}""") }
                    .sendMessage(TelegramSendMessage("123:secret", "42", "hello"))
            )

            assertEquals(expected, failure.kind)
            assertFalse(failure.toString().contains("secret detail"))
        }
    }

    @Test
    fun classifiesInvalidSuccessResponseAsRetryable() {
        val invalid = assertIs<TelegramSendResult.Failure>(
            TelegramHttpClient { FakeConnection(200, "not json") }
                .sendMessage(TelegramSendMessage("123:secret", "42", "hello"))
        )

        assertEquals(TelegramSendFailureKind.INVALID_RESPONSE, invalid.kind)
        assertTrue(invalid.kind.retryable)
    }

    @Test
    fun failuresNeverExposeCredentialsOrTelegramDescriptions() {
        val token = "123:top_secret"
        val chatId = "private-chat"
        val failure = assertIs<TelegramSendResult.Failure>(
            TelegramHttpClient { throw IllegalStateException("$token $chatId leaked") }
                .sendMessage(TelegramSendMessage(token, chatId, "hello"))
        )

        assertEquals(TelegramSendFailureKind.NETWORK_ERROR, failure.kind)
        assertNull(failure.httpStatus)
        assertFalse(failure.toString().contains(token))
        assertFalse(failure.toString().contains(chatId))
        assertFalse(failure.toString().contains("leaked"))
    }

    private class FakeConnection(
        private val status: Int,
        response: String
    ) : HttpURLConnection(URL("https://example.invalid")) {
        val sentBody = ByteArrayOutputStream()
        var disconnected = false
        private val responseBytes = response.toByteArray(StandardCharsets.UTF_8)

        override fun getOutputStream() = sentBody
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(responseBytes)
        override fun getErrorStream(): InputStream? = ByteArrayInputStream(responseBytes)
        override fun disconnect() {
            disconnected = true
        }
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }
}
