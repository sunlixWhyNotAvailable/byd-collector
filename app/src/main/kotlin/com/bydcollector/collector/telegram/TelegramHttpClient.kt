package com.bydcollector.collector.telegram

import com.bydcollector.collector.util.BoundedUtf8Text
import com.bydcollector.collector.util.readBoundedUtf8
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

data class TelegramSendMessage(
    val botToken: String,
    val chatId: String,
    val text: String
)

enum class TelegramSendFailureKind(val retryable: Boolean) {
    CONFIGURATION(false),
    INVALID_MESSAGE(false),
    BAD_REQUEST(false),
    UNAUTHORIZED(false),
    FORBIDDEN(false),
    NOT_FOUND(false),
    RATE_LIMITED(true),
    SERVER_ERROR(true),
    API_ERROR(false),
    INVALID_RESPONSE(true),
    NETWORK_ERROR(true)
}

sealed interface TelegramSendResult {
    data class Success(val messageId: Long? = null) : TelegramSendResult {
        init {
            require(messageId == null || messageId > 0L) { "Telegram message id must be positive" }
        }
    }

    data class Failure(
        val kind: TelegramSendFailureKind,
        val httpStatus: Int? = null,
        val telegramErrorCode: Int? = null,
        val retryAfterSeconds: Long? = null,
        val exceptionClass: String? = null,
        val diagnostic: String? = null
    ) : TelegramSendResult {
        init {
            require(httpStatus == null || httpStatus in 100..599) { "Telegram HTTP status is invalid" }
            require(telegramErrorCode == null || telegramErrorCode > 0) { "Telegram error code must be positive" }
            require(retryAfterSeconds == null || retryAfterSeconds >= 0L) { "Telegram retry delay must be nonnegative" }
            require(exceptionClass == null || (exceptionClass.length <= 512 &&
                exceptionClass.toByteArray(StandardCharsets.UTF_8).size <= 2 * 1024)) {
                "Telegram exception class exceeds its limit"
            }
            require(diagnostic == null || (diagnostic.length <= TelegramHelperProtocol.MAX_DIAGNOSTIC_CHARS &&
                diagnostic.toByteArray(StandardCharsets.UTF_8).size <= TelegramHelperProtocol.MAX_DIAGNOSTIC_BYTES)) {
                "Telegram diagnostic exceeds its limit"
            }
        }
    }
}

class TelegramHttpClient(
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) {
    fun sendMessage(request: TelegramSendMessage): TelegramSendResult {
        if (!BOT_TOKEN.matches(request.botToken)) return failure(TelegramSendFailureKind.CONFIGURATION)
        if (request.chatId.isBlank()) return failure(TelegramSendFailureKind.CONFIGURATION)
        val length = request.text.codePointCount(0, request.text.length)
        if (request.text.isBlank() || length > TELEGRAM_MESSAGE_MAX_CHARS) {
            return failure(TelegramSendFailureKind.INVALID_MESSAGE)
        }

        val body = "chat_id=${encode(request.chatId)}&text=${encode(request.text)}"
            .toByteArray(StandardCharsets.UTF_8)
        return request(request, body)
    }

    private fun request(request: TelegramSendMessage, body: ByteArray): TelegramSendResult {
        var connection: HttpURLConnection? = null
        return try {
            val activeConnection = connectionFactory(URL("https://api.telegram.org/bot${request.botToken}/sendMessage"))
            connection = activeConnection
            activeConnection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "BYDCollector")
            }
            activeConnection.outputStream.use { it.write(body) }
            val responseStatus = activeConnection.responseCode
            val response = responseBody(activeConnection, responseStatus)
            if (response.truncated) {
                if (responseStatus in 200..299 && TRUNCATED_SUCCESS_PREFIX.containsMatchIn(response.text)) {
                    TelegramSendResult.Success()
                } else {
                    oversizedResponse(responseStatus)
                }
            } else {
                classify(responseStatus, response.text, request)
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            failure(
                TelegramSendFailureKind.NETWORK_ERROR,
                exceptionClass = error::class.java.name,
                diagnostic = redactedDiagnostic(error, request)
            )
        } finally {
            // Cleanup must not turn an already-confirmed send into a retryable failure.
            runCatching { connection?.disconnect() }
        }
    }

    private fun classify(httpStatus: Int, body: String, request: TelegramSendMessage): TelegramSendResult {
        val json = try {
            JSONObject(body)
        } catch (error: Exception) {
            return failure(
                kind = if (httpStatus in 200..299) TelegramSendFailureKind.INVALID_RESPONSE else kindFor(httpStatus),
                httpStatus = httpStatus,
                telegramErrorCode = httpStatus.takeIf { it !in 200..299 },
                exceptionClass = error::class.java.name,
                diagnostic = redactedDiagnostic(error, request)
            )
        }
        if (httpStatus in 200..299 && json.optBoolean("ok", false)) {
            // A proven send stays successful even when optional receipt metadata is absent.
            val messageId = json.optJSONObject("result")?.opt("message_id")
                ?.toString()?.toLongOrNull()?.takeIf { it > 0L }
            return TelegramSendResult.Success(messageId)
        }

        val errorCode = json.optInt("error_code", httpStatus).takeIf { it > 0 }
        val retryAfter = json.optJSONObject("parameters")
            ?.optLong("retry_after", -1L)
            ?.takeIf { it >= 0L }
        return failure(
            kind = kindFor(errorCode ?: httpStatus),
            httpStatus = httpStatus,
            telegramErrorCode = errorCode,
            retryAfterSeconds = retryAfter
        )
    }

    private fun responseBody(connection: HttpURLConnection, status: Int): BoundedUtf8Text {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return readBoundedUtf8(stream, TELEGRAM_RESPONSE_MAX_CHARS) ?: BoundedUtf8Text("", truncated = false)
    }

    private fun oversizedResponse(httpStatus: Int): TelegramSendResult.Failure {
        return if (httpStatus in 200..299) {
            failure(TelegramSendFailureKind.INVALID_RESPONSE, httpStatus)
        } else {
            failure(kindFor(httpStatus), httpStatus, httpStatus)
        }
    }

    private fun kindFor(code: Int): TelegramSendFailureKind = when (code) {
        400 -> TelegramSendFailureKind.BAD_REQUEST
        401 -> TelegramSendFailureKind.UNAUTHORIZED
        403 -> TelegramSendFailureKind.FORBIDDEN
        404 -> TelegramSendFailureKind.NOT_FOUND
        429 -> TelegramSendFailureKind.RATE_LIMITED
        in 500..599 -> TelegramSendFailureKind.SERVER_ERROR
        else -> TelegramSendFailureKind.API_ERROR
    }

    private fun failure(
        kind: TelegramSendFailureKind,
        httpStatus: Int? = null,
        telegramErrorCode: Int? = null,
        retryAfterSeconds: Long? = null,
        exceptionClass: String? = null,
        diagnostic: String? = null
    ) = TelegramSendResult.Failure(kind, httpStatus, telegramErrorCode, retryAfterSeconds, exceptionClass, diagnostic)

    private fun redactedDiagnostic(error: Throwable, request: TelegramSendMessage): String {
        return TelegramHelperProtocol.exceptionDiagnostic(error, "telegram_http", request)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        val BOT_TOKEN = Regex("[A-Za-z0-9:_-]+")
        val TRUNCATED_SUCCESS_PREFIX = Regex("^\\s*\\{\\s*\"ok\"\\s*:\\s*true(?=\\s*(?:[,}]))")
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}

internal const val TELEGRAM_RESPONSE_MAX_CHARS = 16_384
