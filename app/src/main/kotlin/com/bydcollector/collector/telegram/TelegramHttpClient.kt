package com.bydcollector.collector.telegram

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

data class TelegramRequestEvidence(
    val operation: String,
    val epochMs: Long,
    val elapsedRealtimeMs: Long,
    val durationMs: Long,
    val httpStatus: Int?,
    val networkReached: Boolean,
    val authenticated: Boolean,
    val result: String,
    val failureKind: String?,
    val exceptionClass: String?
) {
    companion object {
        fun localFailure(
            operation: String,
            epochMs: Long,
            elapsedRealtimeMs: Long,
            durationMs: Long,
            result: TelegramSendResult
        ): TelegramRequestEvidence {
            val failure = result as? TelegramSendResult.Failure
            return TelegramRequestEvidence(
                operation = operation,
                epochMs = epochMs,
                elapsedRealtimeMs = elapsedRealtimeMs,
                durationMs = durationMs,
                httpStatus = null,
                networkReached = false,
                authenticated = false,
                result = "failure",
                failureKind = failure?.kind?.name?.lowercase() ?: "local_rejection",
                exceptionClass = failure?.exceptionClass
            )
        }
    }
}

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
        val retryAfterSeconds: Long? = null,
        val exceptionClass: String? = null
    ) : TelegramSendResult
}

class TelegramHttpClient(
    private val epochMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val requestObserver: (TelegramRequestEvidence) -> Unit = {},
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
        return request("sendMessage", request.botToken, "POST", body)
    }

    fun getMe(botToken: String): TelegramSendResult {
        if (!BOT_TOKEN.matches(botToken)) return failure(TelegramSendFailureKind.CONFIGURATION)
        return request("getMe", botToken, "GET", null)
    }

    private fun request(operation: String, botToken: String, method: String, body: ByteArray?): TelegramSendResult {
        val startedEpochMs = epochMs()
        val startedElapsedMs = elapsedRealtimeMs()
        var connection: HttpURLConnection? = null
        var status: Int? = null
        val result = try {
            val activeConnection = connectionFactory(URL("https://api.telegram.org/bot$botToken/$operation"))
            connection = activeConnection
            activeConnection.apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "BYDCollector")
            }
            if (body != null) activeConnection.outputStream.use { it.write(body) }
            val responseStatus = activeConnection.responseCode
            status = responseStatus
            classify(responseStatus, responseBody(activeConnection, responseStatus))
        } catch (error: Exception) {
            failure(
                TelegramSendFailureKind.NETWORK_ERROR,
                exceptionClass = error::class.java.name
            )
        } finally {
            connection?.disconnect()
        }
        val failure = result as? TelegramSendResult.Failure
        runCatching {
            requestObserver(
                TelegramRequestEvidence(
                    operation = operation,
                    epochMs = startedEpochMs,
                    elapsedRealtimeMs = startedElapsedMs,
                    durationMs = (elapsedRealtimeMs() - startedElapsedMs).coerceAtLeast(0L),
                    httpStatus = status,
                    networkReached = status != null,
                    authenticated = if (operation == "getMe") {
                        result == TelegramSendResult.Success
                    } else {
                        result == TelegramSendResult.Success || (
                            status != null && failure?.kind !in setOf(
                                TelegramSendFailureKind.UNAUTHORIZED,
                                TelegramSendFailureKind.NOT_FOUND
                            )
                        )
                    },
                    result = if (result == TelegramSendResult.Success) "success" else "failure",
                    failureKind = failure?.kind?.name?.lowercase(),
                    exceptionClass = failure?.exceptionClass
                )
            )
        }
        return result
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
        retryAfterSeconds: Long? = null,
        exceptionClass: String? = null
    ) = TelegramSendResult.Failure(kind, httpStatus, telegramErrorCode, retryAfterSeconds, exceptionClass)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        val BOT_TOKEN = Regex("[A-Za-z0-9:_-]+")
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}
