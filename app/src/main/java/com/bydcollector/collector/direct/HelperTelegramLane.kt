package com.bydcollector.collector.direct

import android.util.AtomicFile
import com.bydcollector.collector.telegram.TelegramHelperAttemptKey
import com.bydcollector.collector.telegram.TelegramHelperAmbiguity
import com.bydcollector.collector.telegram.TelegramHelperOutcome
import com.bydcollector.collector.telegram.TelegramHelperProtocol
import com.bydcollector.collector.telegram.TelegramHelperRemoteFailure
import com.bydcollector.collector.telegram.TelegramHelperState
import com.bydcollector.collector.telegram.TelegramSendFailureKind
import com.bydcollector.collector.telegram.TelegramSendMessage
import com.bydcollector.collector.telegram.TelegramSendResult
import com.bydcollector.collector.telegram.TelegramHttpClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

class HelperTelegramLane @JvmOverloads constructor(
    private val ledgerDirectory: File = File(DEFAULT_LEDGER_DIRECTORY)
) {
    private data class StoredAttempt(
        val key: TelegramHelperAttemptKey,
        val state: String,
        val result: TelegramSendResult? = null,
        val confirmedAtMs: Long? = null
    )

    private data class Ledger(
        val ownerId: UUID,
        val acknowledgedSequence: Long,
        val latest: StoredAttempt
    )

    private data class WorkerFailure(
        val key: TelegramHelperAttemptKey,
        val stage: String,
        val exceptionType: String,
        val diagnostic: String,
        val cause: Throwable
    ) {
        fun asRemoteFailure() = TelegramHelperRemoteFailure(
            operation = "telegram_$stage",
            exceptionType = exceptionType,
            diagnostic = diagnostic,
            cause = cause
        )
    }

    private val lock = Object()
    private val activeByOwner = HashMap<UUID, TelegramHelperAttemptKey>()
    private val workerFailures = HashMap<UUID, WorkerFailure>()
    private val httpClient = TelegramHttpClient()
    private val executor = Executors.newSingleThreadExecutor(ThreadFactory { runnable ->
        Thread(runnable, "byd-helper-telegram").apply { isDaemon = true }
    })
    private var stopping = false
    private var closed = false

    fun telegramSubmit(key: TelegramHelperAttemptKey, request: TelegramSendMessage): TelegramHelperOutcome {
        TelegramHelperProtocol.validateRequest(key, request)
        return synchronized(lock) {
            val previous = queryLocked(key)
            if (previous.state != TelegramHelperState.ABSENT) return previous
            check(!stopping && !closed) { "Telegram helper is stopping" }
            check(activeByOwner.isEmpty()) { "Telegram helper already has an active HTTP attempt" }
            val ledger = readLedger(key.ownerId)
            val acknowledged = ledger?.acknowledgedSequence ?: 0L
            require(key.sequence == acknowledged + 1L) { "Telegram sequence does not follow the acknowledged helper state" }
            val started = Ledger(key.ownerId, acknowledged, StoredAttempt(key, STARTED))
            writeLedger(started)
            activeByOwner[key.ownerId] = key
            workerFailures.remove(key.ownerId)
            try {
                executor.execute { sendAndPersist(key, request) }
            } catch (error: Throwable) {
                activeByOwner.remove(key.ownerId)
                lock.notifyAll()
                throw IllegalStateException("Telegram helper could not schedule HTTP request", error)
            }
            TelegramHelperOutcome(TelegramHelperState.RUNNING)
        }
    }

    fun telegramQuery(key: TelegramHelperAttemptKey): TelegramHelperOutcome = synchronized(lock) {
        queryLocked(key)
    }

    fun telegramAck(key: TelegramHelperAttemptKey): TelegramHelperOutcome = synchronized(lock) {
        workerFailures[key.ownerId]?.takeIf { it.key == key }?.let {
            throw it.asRemoteFailure()
        }
        val ledger = readLedger(key.ownerId)
            ?: return@synchronized if (key.sequence == 1L) {
                TelegramHelperOutcome(TelegramHelperState.ABSENT)
            } else {
                ambiguous(TelegramHelperAmbiguity.LEDGER_MISSING,
                    "Telegram helper ledger is absent for a noninitial sequence")
            }
        val latest = ledger.latest
        if (key.sequence == latest.key.sequence) requireSameKey(latest.key, key)
        if (key.sequence < latest.key.sequence && key.sequence <= ledger.acknowledgedSequence) {
            return@synchronized TelegramHelperOutcome(TelegramHelperState.ACKED)
        }
        if (key.sequence != latest.key.sequence) {
            throw IllegalArgumentException("Telegram helper has no exact active key for this sequence")
        }
        if (latest.state == ACKED) return@synchronized outcome(latest)
        activeByOwner[key.ownerId]?.let { activeKey ->
            requireSameKey(activeKey, key)
            return@synchronized TelegramHelperOutcome(TelegramHelperState.RUNNING)
        }

        val acknowledged = ledger.copy(
            acknowledgedSequence = key.sequence,
            latest = latest.copy(state = ACKED, result = null)
        )
        writeLedger(acknowledged)
        workerFailures.remove(key.ownerId)
        outcome(acknowledged.latest)
    }

    fun stopAcceptingSubmissions() = synchronized(lock) {
        stopping = true
    }

    fun awaitHttpIdle(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0L) { "negative Telegram drain timeout" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(lock) {
            stopping = true
            while (activeByOwner.isNotEmpty()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
                val nanos = (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
                try {
                    lock.wait(millis, nanos)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    fun closeAfterDrain(): Boolean {
        synchronized(lock) {
            stopping = true
            if (activeByOwner.isNotEmpty()) return false
            closed = true
            executor.shutdown()
        }
        return try {
            executor.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun queryLocked(key: TelegramHelperAttemptKey): TelegramHelperOutcome {
        val workerFailure = workerFailures[key.ownerId]
        if (workerFailure?.key == key) {
            throw workerFailure.asRemoteFailure()
        }
        val ledger = readLedger(key.ownerId) ?: return if (key.sequence == 1L) {
            TelegramHelperOutcome(TelegramHelperState.ABSENT)
        } else {
            ambiguous(TelegramHelperAmbiguity.LEDGER_MISSING,
                "Telegram helper ledger is absent for a noninitial sequence")
        }
        val latest = ledger.latest
        if (key.sequence == latest.key.sequence) {
            requireSameKey(latest.key, key)
            return when (latest.state) {
                STARTED -> if (activeByOwner[key.ownerId] == key) {
                    TelegramHelperOutcome(TelegramHelperState.RUNNING)
                } else {
                    ambiguous(TelegramHelperAmbiguity.INTERRUPTED_ATTEMPT,
                        "Telegram HTTP may have started before helper restart")
                }
                RESULT, ACKED -> outcome(latest)
                else -> error("invalid Telegram ledger state: ${latest.state}")
            }
        }
        if (key.sequence < latest.key.sequence && key.sequence <= ledger.acknowledgedSequence) {
            return TelegramHelperOutcome(TelegramHelperState.ACKED)
        }
        if (latest.state == ACKED && key.sequence == ledger.acknowledgedSequence + 1L) {
            return TelegramHelperOutcome(TelegramHelperState.ABSENT)
        }
        throw IllegalArgumentException("Telegram helper has no exact ledger entry for this sequence")
    }

    private fun sendAndPersist(key: TelegramHelperAttemptKey, request: TelegramSendMessage) {
        var result: TelegramSendResult? = null
        var failure: Throwable? = null
        var failureDiagnostic: String? = null
        try {
            result = httpClient.sendMessage(request)
        } catch (error: Throwable) {
            failure = error
            failureDiagnostic = TelegramHelperProtocol.exceptionDiagnostic(error, "telegram_http_send", request)
        }
        val httpFailure = failure
        val httpDiagnostic = failureDiagnostic
        synchronized(lock) {
            if (httpFailure != null) {
                workerFailures[key.ownerId] = WorkerFailure(
                    key, "http_send", httpFailure.javaClass.name, checkNotNull(httpDiagnostic), httpFailure
                )
            } else {
                try {
                    val ledger = checkNotNull(readLedger(key.ownerId)) { "Telegram helper ledger disappeared during HTTP" }
                    requireSameKey(ledger.latest.key, key)
                    check(ledger.latest.state == STARTED) { "Telegram helper attempt is not STARTED" }
                    val completedAt = System.currentTimeMillis().coerceAtLeast(0L)
                    writeLedger(ledger.copy(latest = ledger.latest.copy(
                        state = RESULT,
                        result = checkNotNull(result),
                        confirmedAtMs = completedAt
                    )))
                    workerFailures.remove(key.ownerId)
                } catch (error: Throwable) {
                    workerFailures[key.ownerId] = WorkerFailure(
                        key,
                        "result_persist",
                        error.javaClass.name,
                        TelegramHelperProtocol.exceptionDiagnostic(error, "telegram_result_persist", request),
                        error
                    )
                }
            }
            activeByOwner.remove(key.ownerId)
            lock.notifyAll()
        }
    }

    private fun outcome(attempt: StoredAttempt): TelegramHelperOutcome = TelegramHelperOutcome(
        state = if (attempt.state == RESULT) TelegramHelperState.RESULT else TelegramHelperState.ACKED,
        result = attempt.result.takeIf { attempt.state == RESULT },
        confirmedAtMs = attempt.confirmedAtMs
    )

    private fun ambiguous(reason: TelegramHelperAmbiguity, detail: String) = TelegramHelperOutcome(
        state = TelegramHelperState.AMBIGUOUS,
        ambiguity = reason,
        diagnostic = detail
    )

    private fun requireSameKey(stored: TelegramHelperAttemptKey, supplied: TelegramHelperAttemptKey) {
        require(stored == supplied) { "Telegram key conflicts with the persisted attempt at this sequence" }
    }

    private fun fileFor(ownerId: UUID): File = File(ledgerDirectory, "$ownerId.json")

    private fun readLedger(ownerId: UUID): Ledger? {
        val file = fileFor(ownerId)
        val backup = File(file.path + ".bak")
        if (!file.exists() && !backup.exists()) {
            if (ledgerDirectory.exists() && !ledgerDirectory.isDirectory) {
                throw IOException("Telegram helper ledger path is not a directory: $ledgerDirectory")
            }
            return null
        }
        try {
            val bytes = AtomicFile(file).openRead().use(::readBounded)
            val json = JSONObject(decodeUtf8(bytes))
            require(json.getInt("version") == LEDGER_VERSION) { "unsupported Telegram helper ledger version" }
            val storedOwner = UUID.fromString(json.getString("owner_id"))
            require(storedOwner == ownerId) { "Telegram helper ledger owner mismatch" }
            val acknowledged = json.getLong("acknowledged_sequence")
            require(acknowledged >= 0L) { "negative Telegram helper acknowledgment sequence" }
            val latest = json.getJSONObject("latest")
            val key = TelegramHelperProtocol.keyFromJson(latest.getJSONObject("key"))
            require(key.ownerId == ownerId) { "Telegram attempt owner mismatch" }
            val state = latest.getString("state")
            require(state in setOf(STARTED, RESULT, ACKED)) { "invalid Telegram helper ledger state" }
            val result = if (latest.isNull("result")) null else resultFromJson(latest.getJSONObject("result"))
            val confirmedAtMs = if (latest.isNull("confirmed_at_ms")) null else latest.getLong("confirmed_at_ms")
            require(confirmedAtMs == null || confirmedAtMs >= 0L) { "negative Telegram result confirmation time" }
            when (state) {
                STARTED -> require(result == null && confirmedAtMs == null && acknowledged == key.sequence - 1L) {
                    "invalid STARTED Telegram ledger state"
                }
                RESULT -> require(result != null && confirmedAtMs != null && acknowledged == key.sequence - 1L) {
                    "invalid RESULT Telegram ledger state"
                }
                ACKED -> require(acknowledged == key.sequence && (result == null || confirmedAtMs != null)) {
                    "invalid ACKED Telegram ledger state"
                }
            }
            return Ledger(ownerId, acknowledged, StoredAttempt(key, state, result, confirmedAtMs))
        } catch (error: Exception) {
            throw IllegalStateException("cannot read Telegram helper ledger for owner $ownerId", error)
        }
    }

    private fun writeLedger(ledger: Ledger) {
        val directory = ledgerDirectory
        if (!directory.exists() && !directory.mkdirs()) throw IOException("cannot create Telegram helper ledger directory: $directory")
        if (!directory.isDirectory) throw IOException("Telegram helper ledger path is not a directory: $directory")
        val latest = ledger.latest
        val payload = JSONObject()
            .put("version", LEDGER_VERSION)
            .put("owner_id", ledger.ownerId.toString())
            .put("acknowledged_sequence", ledger.acknowledgedSequence)
            .put("latest", JSONObject()
                .put("key", TelegramHelperProtocol.keyToJson(latest.key))
                .put("state", latest.state)
                .put("result", resultToJson(latest.result) ?: JSONObject.NULL)
                .put("confirmed_at_ms", latest.confirmedAtMs ?: JSONObject.NULL))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_LEDGER_BYTES) { "Telegram helper ledger exceeds size limit" }
        val atomic = AtomicFile(fileFor(ledger.ownerId))
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(payload)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            if (output != null) {
                try {
                    atomic.failWrite(output)
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw IOException("cannot durably write Telegram helper ledger for owner ${ledger.ownerId}", error)
        }
    }

    private fun resultToJson(result: TelegramSendResult?): JSONObject? = when (result) {
        null -> null
        is TelegramSendResult.Success -> JSONObject()
            .put("type", "success")
            .put("message_id", result.messageId ?: JSONObject.NULL)
        is TelegramSendResult.Failure -> JSONObject()
            .put("type", "failure")
            .put("kind", result.kind.name)
            .put("http_status", result.httpStatus ?: JSONObject.NULL)
            .put("telegram_error_code", result.telegramErrorCode ?: JSONObject.NULL)
            .put("retry_after_seconds", result.retryAfterSeconds ?: JSONObject.NULL)
            .put("exception_class", result.exceptionClass ?: JSONObject.NULL)
            .put("diagnostic", result.diagnostic ?: JSONObject.NULL)
    }

    private fun resultFromJson(json: JSONObject): TelegramSendResult = when (json.getString("type")) {
        "success" -> TelegramSendResult.Success(nullableLong(json, "message_id")?.also {
            require(it > 0L) { "invalid Telegram message id in helper ledger" }
        })
        "failure" -> TelegramSendResult.Failure(
            kind = TelegramSendFailureKind.valueOf(json.getString("kind")),
            httpStatus = nullableLong(json, "http_status")?.toInt()?.also {
                require(it in 100..599) { "invalid HTTP status in Telegram helper ledger" }
            },
            telegramErrorCode = nullableLong(json, "telegram_error_code")?.toInt()?.also {
                require(it > 0) { "invalid Telegram error code in helper ledger" }
            },
            retryAfterSeconds = nullableLong(json, "retry_after_seconds")?.also {
                require(it >= 0L) { "invalid Telegram retry delay in helper ledger" }
            },
            exceptionClass = nullableString(json, "exception_class")?.also {
                require(it.length <= 512) { "oversize exception class in Telegram helper ledger" }
            },
            diagnostic = nullableString(json, "diagnostic")?.also {
                require(it.length <= TelegramHelperProtocol.MAX_DIAGNOSTIC_CHARS) {
                    "oversize Telegram diagnostic in helper ledger"
                }
                require(it.toByteArray(StandardCharsets.UTF_8).size <= TelegramHelperProtocol.MAX_DIAGNOSTIC_BYTES) {
                    "oversize UTF-8 Telegram diagnostic in helper ledger"
                }
            }
        )
        else -> error("invalid Telegram helper result type")
    }

    private fun nullableLong(json: JSONObject, name: String): Long? = if (json.isNull(name)) null else json.getLong(name)

    private fun nullableString(json: JSONObject, name: String): String? = if (json.isNull(name)) null else json.getString(name)

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() > MAX_LEDGER_BYTES - count) throw IOException("Telegram helper ledger exceeds size limit")
            output.write(buffer, 0, count)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private companion object {
        const val DEFAULT_LEDGER_DIRECTORY = "/data/local/tmp/bydcollector_telegram_lane"
        const val LEDGER_VERSION = 1
        const val MAX_LEDGER_BYTES = 32 * 1024
        const val CLOSE_WAIT_MS = 1_000L
        const val STARTED = "STARTED"
        const val RESULT = "RESULT"
        const val ACKED = "ACKED"
    }
}
