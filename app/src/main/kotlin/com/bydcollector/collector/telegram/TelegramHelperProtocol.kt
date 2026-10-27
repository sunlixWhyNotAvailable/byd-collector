package com.bydcollector.collector.telegram

import android.os.Parcel
import com.bydcollector.collector.util.diagnosticDetail
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class TelegramHelperAttemptKey(
    val ownerId: UUID,
    val attemptId: UUID,
    val sequence: Long,
    val requestDigest: String,
    val destinationDigest: String,
    val dedupeRef: String
) {
    init {
        require(sequence > 0L) { "Telegram helper sequence must be positive" }
        require(SHA256.matches(requestDigest)) { "invalid Telegram request digest" }
        require(SHA256.matches(destinationDigest)) { "invalid Telegram destination digest" }
        require(SHA256.matches(dedupeRef)) { "invalid Telegram dedupe reference" }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

enum class TelegramHelperState { ABSENT, RUNNING, RESULT, ACKED, AMBIGUOUS }

enum class TelegramHelperAmbiguity { INTERRUPTED_ATTEMPT, LEDGER_MISSING }

data class TelegramHelperOutcome(
    val state: TelegramHelperState,
    val result: TelegramSendResult? = null,
    val confirmedAtMs: Long? = null,
    val ambiguity: TelegramHelperAmbiguity? = null,
    val diagnostic: String? = null
) {
    init {
        require(confirmedAtMs == null || confirmedAtMs >= 0L) { "negative Telegram confirmation time" }
        require(diagnostic == null || (diagnostic.length <= TelegramHelperProtocol.MAX_DIAGNOSTIC_CHARS &&
            diagnostic.toByteArray(StandardCharsets.UTF_8).size <= TelegramHelperProtocol.MAX_DIAGNOSTIC_BYTES)) {
            "Telegram helper diagnostic is too long"
        }
        require(state == TelegramHelperState.RESULT || result == null) {
            "only RESULT may carry a Telegram result"
        }
        require(state != TelegramHelperState.RESULT || (result != null && confirmedAtMs != null)) {
            "RESULT requires a durable result and confirmation time"
        }
        require(state == TelegramHelperState.RESULT || confirmedAtMs == null || state == TelegramHelperState.ACKED) {
            "confirmation time is only valid for RESULT or ACKED"
        }
        require((state == TelegramHelperState.AMBIGUOUS) == (ambiguity != null)) {
            "AMBIGUOUS requires one typed ambiguity reason"
        }
        require(state == TelegramHelperState.AMBIGUOUS || diagnostic == null) {
            "only AMBIGUOUS may carry a helper diagnostic"
        }
    }
}

class TelegramHelperRemoteFailure(
    val operation: String,
    val exceptionType: String,
    val diagnostic: String,
    cause: Throwable? = null
) : RuntimeException("$operation failed in helper ($exceptionType)\n$diagnostic", cause)

object TelegramHelperProtocol {
    const val TX_SUBMIT = 23
    const val TX_QUERY = 24
    const val TX_ACK = 25
    const val MAX_IPC_BYTES = 24 * 1024
    const val MAX_BOT_TOKEN_CHARS = 256
    const val MAX_CHAT_ID_CHARS = 256
    const val MAX_TEXT_BYTES = 16 * 1024
    const val MAX_DIAGNOSTIC_CHARS = 8 * 1024
    const val MAX_DIAGNOSTIC_BYTES = 16 * 1024
    private const val MAX_OPERATION_CHARS = 128
    private const val MAX_OPERATION_BYTES = 512
    private const val MAX_EXCEPTION_TYPE_CHARS = 512
    private const val MAX_EXCEPTION_TYPE_BYTES = 2 * 1024

    fun writeKey(parcel: Parcel, key: TelegramHelperAttemptKey) {
        parcel.writeString(key.ownerId.toString())
        parcel.writeString(key.attemptId.toString())
        parcel.writeLong(key.sequence)
        parcel.writeString(key.requestDigest)
        parcel.writeString(key.destinationDigest)
        parcel.writeString(key.dedupeRef)
    }

    fun readKey(parcel: Parcel): TelegramHelperAttemptKey {
        require(parcel.dataAvail() <= MAX_IPC_BYTES) { "oversize Telegram helper key" }
        val ownerId = canonicalUuid(requireNotNull(parcel.readString()) { "Telegram owner id missing" })
        val attemptId = canonicalUuid(requireNotNull(parcel.readString()) { "Telegram attempt id missing" })
        return TelegramHelperAttemptKey(
            ownerId,
            attemptId,
            parcel.readLong(),
            requireNotNull(parcel.readString()) { "Telegram request digest missing" },
            requireNotNull(parcel.readString()) { "Telegram destination digest missing" },
            requireNotNull(parcel.readString()) { "Telegram dedupe reference missing" }
        )
    }

    fun writeRequest(parcel: Parcel, request: TelegramSendMessage) {
        validateRequestBounds(request)
        parcel.writeString(request.botToken)
        parcel.writeString(request.chatId)
        parcel.writeString(request.text)
    }

    fun readRequest(parcel: Parcel): TelegramSendMessage {
        require(parcel.dataAvail() <= MAX_IPC_BYTES) { "oversize Telegram helper request" }
        val request = TelegramSendMessage(
            requireNotNull(parcel.readString()) { "Telegram bot token missing" },
            requireNotNull(parcel.readString()) { "Telegram chat id missing" },
            requireNotNull(parcel.readString()) { "Telegram message text missing" }
        ).also(::validateRequestBounds)
        require(parcel.dataAvail() == 0) { "unexpected Telegram request data" }
        return request
    }

    fun writeOutcome(parcel: Parcel, outcome: TelegramHelperOutcome) {
        parcel.writeInt(outcome.state.ordinal)
        parcel.writeInt(outcome.ambiguity?.ordinal ?: -1)
        writeResult(parcel, outcome.result)
        writeNullableLong(parcel, outcome.confirmedAtMs)
        writeBoundedString(parcel, outcome.diagnostic, MAX_DIAGNOSTIC_CHARS, MAX_DIAGNOSTIC_BYTES)
    }

    fun readOutcome(parcel: Parcel): TelegramHelperOutcome {
        require(parcel.dataAvail() <= MAX_IPC_BYTES) { "oversize Telegram helper reply" }
        val stateIndex = parcel.readInt()
        require(stateIndex in TelegramHelperState.values().indices) { "invalid Telegram helper state" }
        val ambiguityIndex = parcel.readInt()
        require(ambiguityIndex == -1 || ambiguityIndex in TelegramHelperAmbiguity.values().indices) {
            "invalid Telegram helper ambiguity reason"
        }
        val result = readResult(parcel)
        val confirmedAtMs = readNullableLong(parcel)
        val diagnostic = readBoundedString(parcel, MAX_DIAGNOSTIC_CHARS, MAX_DIAGNOSTIC_BYTES)
        require(parcel.dataAvail() == 0) { "unexpected Telegram helper reply data" }
        return TelegramHelperOutcome(
            TelegramHelperState.values()[stateIndex], result, confirmedAtMs,
            ambiguityIndex.takeIf { it >= 0 }?.let { TelegramHelperAmbiguity.values()[it] }, diagnostic
        )
    }

    fun writeSuccessReply(parcel: Parcel, outcome: TelegramHelperOutcome) {
        parcel.writeNoException()
        parcel.writeInt(REPLY_SUCCESS)
        writeOutcome(parcel, outcome)
    }

    fun writeFailureReply(
        parcel: Parcel,
        operation: String,
        error: Throwable,
        request: TelegramSendMessage? = null
    ) {
        val failure = if (error is TelegramHelperRemoteFailure) error else TelegramHelperRemoteFailure(
            operation = operation,
            exceptionType = error.javaClass.name,
            diagnostic = exceptionDiagnostic(error, operation, request)
        )
        parcel.writeNoException()
        parcel.writeInt(REPLY_FAILURE)
        writeBoundedString(parcel, failure.operation, MAX_OPERATION_CHARS, MAX_OPERATION_BYTES)
        writeBoundedString(parcel, failure.exceptionType, MAX_EXCEPTION_TYPE_CHARS, MAX_EXCEPTION_TYPE_BYTES)
        writeBoundedString(parcel, failure.diagnostic, MAX_DIAGNOSTIC_CHARS, MAX_DIAGNOSTIC_BYTES)
    }

    fun readReply(parcel: Parcel): TelegramHelperOutcome {
        parcel.readException()
        require(parcel.dataAvail() <= MAX_IPC_BYTES) { "oversize Telegram helper reply" }
        when (parcel.readInt()) {
            REPLY_SUCCESS -> return readOutcome(parcel)
            REPLY_FAILURE -> {
                val operation = requireNotNull(readBoundedString(parcel, MAX_OPERATION_CHARS, MAX_OPERATION_BYTES)) {
                    "Telegram helper failure operation missing"
                }
                val exceptionType = requireNotNull(
                    readBoundedString(parcel, MAX_EXCEPTION_TYPE_CHARS, MAX_EXCEPTION_TYPE_BYTES)
                ) { "Telegram helper failure type missing" }
                val diagnostic = requireNotNull(
                    readBoundedString(parcel, MAX_DIAGNOSTIC_CHARS, MAX_DIAGNOSTIC_BYTES)
                ) { "Telegram helper failure diagnostic missing" }
                require(parcel.dataAvail() == 0) { "unexpected Telegram helper failure data" }
                throw TelegramHelperRemoteFailure(operation, exceptionType, diagnostic)
            }
            else -> error("invalid Telegram helper reply marker")
        }
    }

    fun exceptionDiagnostic(
        error: Throwable,
        operation: String,
        request: TelegramSendMessage? = null
    ): String {
        var detail = error.diagnosticDetail("stage=$operation")
        val sensitiveValues = request?.let {
            listOf(it.botToken, it.chatId, it.text, URLEncoder.encode(it.chatId, "UTF-8"), URLEncoder.encode(it.text, "UTF-8"))
        }.orEmpty().filter(String::isNotEmpty).distinct().sortedByDescending(String::length)
        for (sensitiveValue in sensitiveValues) detail = detail.replace(sensitiveValue, "[REDACTED]")
        return boundDiagnostic(detail)
    }

    fun requestDigest(request: TelegramSendMessage): String = digestParts(request.botToken, request.chatId, request.text)

    fun destinationDigest(request: TelegramSendMessage): String = digestParts(request.botToken, request.chatId)

    fun dedupeReference(dedupeKey: String): String = digestParts(dedupeKey)

    fun keyToJson(key: TelegramHelperAttemptKey) = org.json.JSONObject()
        .put("owner_id", key.ownerId.toString())
        .put("attempt_id", key.attemptId.toString())
        .put("sequence", key.sequence)
        .put("request_digest", key.requestDigest)
        .put("destination_digest", key.destinationDigest)
        .put("dedupe_ref", key.dedupeRef)

    fun keyFromJson(json: org.json.JSONObject): TelegramHelperAttemptKey = TelegramHelperAttemptKey(
        canonicalUuid(json.getString("owner_id")),
        canonicalUuid(json.getString("attempt_id")),
        json.getLong("sequence"),
        json.getString("request_digest"),
        json.getString("destination_digest"),
        json.getString("dedupe_ref")
    )

    fun validateRequest(key: TelegramHelperAttemptKey, request: TelegramSendMessage) {
        validateRequestBounds(request)
        require(key.destinationDigest == destinationDigest(request)) { "Telegram destination digest mismatch" }
        require(key.requestDigest == requestDigest(request)) { "Telegram request digest mismatch" }
    }

    private fun digestParts(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun canonicalUuid(value: String): UUID = UUID.fromString(value).also {
        require(it.toString() == value) { "noncanonical Telegram UUID" }
    }

    private fun validateRequestBounds(request: TelegramSendMessage) {
        require(request.botToken.length <= MAX_BOT_TOKEN_CHARS) { "Telegram bot token exceeds helper limit" }
        require(request.chatId.length in 1..MAX_CHAT_ID_CHARS) { "Telegram chat id is invalid" }
        require(request.text.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_BYTES) {
            "Telegram message exceeds helper IPC limit"
        }
    }

    private fun writeResult(parcel: Parcel, result: TelegramSendResult?) {
        when (result) {
            null -> parcel.writeInt(0)
            is TelegramSendResult.Success -> {
                parcel.writeInt(1)
                writeNullableLong(parcel, result.messageId)
            }
            is TelegramSendResult.Failure -> {
                parcel.writeInt(2)
                parcel.writeString(result.kind.name)
                writeNullableInt(parcel, result.httpStatus)
                writeNullableInt(parcel, result.telegramErrorCode)
                writeNullableLong(parcel, result.retryAfterSeconds)
                writeBoundedString(parcel, result.exceptionClass, 512, 2 * 1024)
                writeBoundedString(parcel, result.diagnostic, MAX_DIAGNOSTIC_CHARS, MAX_DIAGNOSTIC_BYTES)
            }
        }
    }

    private fun readResult(parcel: Parcel): TelegramSendResult? = when (val marker = parcel.readInt()) {
        0 -> null
        1 -> TelegramSendResult.Success(readNullableLong(parcel)?.also {
            require(it > 0L) { "invalid Telegram message id" }
        })
        2 -> {
            val kind = TelegramSendFailureKind.valueOf(
                requireNotNull(parcel.readString()) { "Telegram failure kind missing" }
            )
            val httpStatus = readNullableInt(parcel)?.also {
                require(it in 100..599) { "invalid Telegram HTTP status" }
            }
            val telegramErrorCode = readNullableInt(parcel)?.also {
                require(it > 0) { "invalid Telegram error code" }
            }
            val retryAfter = readNullableLong(parcel)?.also {
                require(it >= 0L) { "invalid Telegram retry delay" }
            }
            val exceptionClass = readBoundedString(parcel, 512, 2 * 1024)
            val diagnostic = readBoundedString(parcel, MAX_DIAGNOSTIC_CHARS, MAX_DIAGNOSTIC_BYTES)
            TelegramSendResult.Failure(kind, httpStatus, telegramErrorCode, retryAfter, exceptionClass, diagnostic)
        }
        else -> error("invalid Telegram result marker: $marker")
    }

    private fun writeNullableInt(parcel: Parcel, value: Int?) {
        parcel.writeInt(if (value == null) 0 else 1)
        if (value != null) parcel.writeInt(value)
    }

    private fun readNullableInt(parcel: Parcel): Int? = when (val marker = parcel.readInt()) {
        0 -> null
        1 -> parcel.readInt()
        else -> error("invalid nullable Telegram integer marker: $marker")
    }

    private fun writeNullableLong(parcel: Parcel, value: Long?) {
        parcel.writeInt(if (value == null) 0 else 1)
        if (value != null) parcel.writeLong(value)
    }

    private fun readNullableLong(parcel: Parcel): Long? = when (val marker = parcel.readInt()) {
        0 -> null
        1 -> parcel.readLong()
        else -> error("invalid nullable Telegram long marker: $marker")
    }

    private fun writeBoundedString(parcel: Parcel, value: String?, maxChars: Int, maxBytes: Int) {
        require(value == null || (value.length <= maxChars && value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes)) {
            "Telegram helper string exceeds IPC limit"
        }
        parcel.writeString(value)
    }

    private fun readBoundedString(parcel: Parcel, maxChars: Int, maxBytes: Int): String? = parcel.readString()?.also {
        require(it.length <= maxChars && it.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) {
            "Telegram helper string exceeds IPC limit"
        }
    }

    private fun boundDiagnostic(detail: String): String {
        val suffix = "\n[TRUNCATED; Telegram helper diagnostic limit=$MAX_DIAGNOSTIC_BYTES bytes]"
        val maxChars = MAX_DIAGNOSTIC_CHARS
        val maxBytes = MAX_DIAGNOSTIC_BYTES
        if (detail.length <= maxChars && detail.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return detail
        val prefixCharLimit = maxChars - suffix.length
        val prefixByteLimit = maxBytes - suffix.toByteArray(StandardCharsets.UTF_8).size
        val prefix = buildString {
            var offset = 0
            var chars = 0
            var bytes = 0
            while (offset < detail.length) {
                val codePoint = detail.codePointAt(offset)
                val chunk = String(Character.toChars(codePoint))
                val chunkBytes = chunk.toByteArray(StandardCharsets.UTF_8).size
                if (chars + chunk.length > prefixCharLimit || bytes + chunkBytes > prefixByteLimit) break
                append(chunk)
                chars += chunk.length
                bytes += chunkBytes
                offset += Character.charCount(codePoint)
            }
        }
        return prefix + suffix
    }

    private const val REPLY_SUCCESS = 0
    private const val REPLY_FAILURE = 1
}
