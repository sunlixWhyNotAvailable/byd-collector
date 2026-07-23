package com.bydcollector.collector.telegram

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
    data object Success : TelegramSendResult

    data class Failure(
        val kind: TelegramSendFailureKind,
        val httpStatus: Int? = null,
        val telegramErrorCode: Int? = null,
        val retryAfterSeconds: Long? = null
    ) : TelegramSendResult
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

        var connection: HttpURLConnection? = null
        return try {
            val body = "chat_id=${encode(request.chatId)}&text=${encode(request.text)}"
                .toByteArray(StandardCharsets.UTF_8)
            val activeConnection = connectionFactory(URL("https://api.telegram.org/bot${request.botToken}/sendMessage"))
            connection = activeConnection
            activeConnection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                useCaches = false
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "BYDCollector")
            }
            activeConnection.outputStream.use { it.write(body) }
            val status = activeConnection.responseCode
            val response = responseBody(activeConnection, status)
            classify(status, response)
        } catch (_: Exception) {
            failure(TelegramSendFailureKind.NETWORK_ERROR)
        } finally {
            connection?.disconnect()
        }
    }

    private fun classify(httpStatus: Int, body: String): TelegramSendResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return if (httpStatus in 200..299) {
                failure(TelegramSendFailureKind.INVALID_RESPONSE, httpStatus)
            } else {
                failure(kindFor(httpStatus), httpStatus, httpStatus)
            }
        if (httpStatus in 200..299 && json.optBoolean("ok", false)) return TelegramSendResult.Success

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

    private fun responseBody(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
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
        retryAfterSeconds: Long? = null
    ) = TelegramSendResult.Failure(kind, httpStatus, telegramErrorCode, retryAfterSeconds)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        val BOT_TOKEN = Regex("[A-Za-z0-9:_-]+")
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}
