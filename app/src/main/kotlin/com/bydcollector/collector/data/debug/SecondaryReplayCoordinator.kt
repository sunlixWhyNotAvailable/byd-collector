package com.bydcollector.collector.data.debug

import android.database.sqlite.SQLiteDatabaseLockedException
import com.bydcollector.collector.data.direct.SecondarySpoolActionResult
import com.bydcollector.collector.data.direct.SecondarySpoolPage
import com.bydcollector.collector.direct.SecondaryTelemetrySpool
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

data class SecondaryReplayDrainResult(
    val drained: Boolean,
    val committedRecords: Int,
    val duplicateRecords: Int,
    val quarantinedFiles: Int,
    val blockedReason: String? = null,
    val retryable: Boolean = false
)

/** Passive APP-side replay. Runtime ordering and activation are owned by the caller. */
class SecondaryReplayCoordinator(
    private val fetchPage: (
        descriptor: SecondaryTelemetrySpool.Descriptor?,
        offset: Long,
        limit: Int
    ) -> SecondarySpoolPage,
    private val acknowledge: (SecondaryTelemetrySpool.Descriptor) -> SecondarySpoolActionResult,
    private val quarantine: (
        descriptor: SecondaryTelemetrySpool.Descriptor,
        reason: String
    ) -> SecondarySpoolActionResult,
    private val importRecord: (
        record: SecondaryTelemetrySpool.Record,
        digest: String
    ) -> SecondaryImportResult,
    private val diagnostic: ((String) -> Unit)? = null
) {
    private var lastDiagnosticAtNanos: Long? = null

    fun drain(): SecondaryReplayDrainResult {
        var committed = 0
        var duplicates = 0
        var quarantined = 0
        while (true) {
            ensureNotInterrupted()
            val first = try {
                fetchPage(null, 0L, SecondaryTelemetrySpool.MAX_SLICE_BYTES)
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                return blocked("secondary page failed: ${safe(error.message)}", committed, duplicates, quarantined, retryable = true)
            }
            ensureNotInterrupted()
            if (!first.ok) {
                return blocked(
                    "secondary page status=${first.status}: ${safe(first.error)}",
                    committed,
                    duplicates,
                    quarantined,
                    retryable = retryableStatus(first.status)
                )
            }
            val descriptor = first.descriptor
                ?: return if (first.offset == 0L && first.bytes.isEmpty()) {
                    SecondaryReplayDrainResult(true, committed, duplicates, quarantined)
                } else {
                    blocked("secondary empty descriptor carried page data", committed, duplicates, quarantined)
                }
            val bytes = try {
                readRecord(descriptor, first)
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                return blocked("secondary paging rejected: ${safe(error.message)}", committed, duplicates, quarantined, retryable = true)
            }
            val digest = sha256(bytes)
            if (digest != descriptor.sha256) {
                return blocked("secondary content digest mismatch", committed, duplicates, quarantined, retryable = true)
            }
            val record = try {
                SecondaryTelemetrySpool.Codec.decode(bytes).also { validateDescriptor(descriptor, it) }
            } catch (error: Exception) {
                return blocked("secondary record rejected: ${safe(error.message)}", committed, duplicates, quarantined, retryable = true)
            }
            when (val imported = try {
                ensureNotInterrupted()
                importRecord(record, digest).also { ensureNotInterrupted() }
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                return blocked(
                    "secondary import failed: ${safe(error.message)}", committed, duplicates, quarantined,
                    retryable = error is SQLiteDatabaseLockedException
                )
            }) {
                is SecondaryImportResult.Committed -> {
                    if (imported.duplicate) duplicates++ else committed++
                    ensureNotInterrupted()
                    val action = try {
                        acknowledge(descriptor)
                    } catch (error: InterruptedException) {
                        throw error
                    } catch (error: Exception) {
                        return blocked("secondary ACK failed: ${safe(error.message)}", committed, duplicates, quarantined, retryable = true)
                    }
                    ensureNotInterrupted()
                    if (!action.ok) {
                        return blocked(
                            "secondary ACK status=${action.status}: ${safe(action.error)}",
                            committed,
                            duplicates,
                            quarantined,
                            retryable = retryableStatus(action.status)
                        )
                    }
                }
                is SecondaryImportResult.Rejected -> {
                    ensureNotInterrupted()
                    val action = try {
                        quarantine(descriptor, imported.reason.take(MAX_DIAGNOSTIC_CHARS))
                    } catch (error: InterruptedException) {
                        throw error
                    } catch (error: Exception) {
                        return blocked(
                            "secondary quarantine failed: ${safe(error.message)}",
                            committed,
                            duplicates,
                            quarantined,
                            retryable = true
                        )
                    }
                    ensureNotInterrupted()
                    if (!action.ok || action.affected < 1) {
                        return blocked(
                            "secondary quarantine status=${action.status}: ${safe(action.error)}",
                            committed,
                            duplicates,
                            quarantined,
                            retryable = retryableStatus(action.status)
                        )
                    }
                    quarantined += action.affected
                }
            }
        }
    }

    private fun readRecord(
        expected: SecondaryTelemetrySpool.Descriptor,
        first: SecondarySpoolPage
    ): ByteArray {
        require(expected.length in 1..MAX_RECORD_BYTES) { "secondary record length out of bounds" }
        val output = ByteArrayOutputStream(expected.length.toInt())
        var page = first
        var offset = 0L
        while (offset < expected.length) {
            ensureNotInterrupted()
            require(page.ok) { "secondary page status=${page.status}: ${safe(page.error)}" }
            require(page.descriptor.sameContent(expected)) { "secondary descriptor changed during paging" }
            require(page.offset == offset) { "secondary page offset mismatch" }
            require(page.bytes.isNotEmpty()) { "secondary page made no progress" }
            require(page.bytes.size <= SecondaryTelemetrySpool.MAX_SLICE_BYTES) { "secondary page exceeds limit" }
            require(page.bytes.size.toLong() <= expected.length - offset) { "secondary page exceeds record length" }
            output.write(page.bytes)
            offset += page.bytes.size
            if (offset < expected.length) {
                page = fetchPage(expected, offset, SecondaryTelemetrySpool.MAX_SLICE_BYTES)
                ensureNotInterrupted()
            }
        }
        return output.toByteArray()
    }

    private fun validateDescriptor(
        descriptor: SecondaryTelemetrySpool.Descriptor,
        record: SecondaryTelemetrySpool.Record
    ) {
        require(record.spoolOrder == descriptor.spoolOrder) { "secondary spool order mismatch" }
        require(record.identity == descriptor.identity) { "secondary identity mismatch" }
        require(record.kind == descriptor.kind) { "secondary record kind mismatch" }
        require(record.capturedWallMs == descriptor.capturedWallMs) { "secondary wall clock mismatch" }
        require(record.capturedElapsedMs == descriptor.capturedElapsedMs) { "secondary elapsed clock mismatch" }
    }

    private fun SecondaryTelemetrySpool.Descriptor?.sameContent(
        expected: SecondaryTelemetrySpool.Descriptor
    ): Boolean = this != null &&
        spoolOrder == expected.spoolOrder && identity == expected.identity && kind == expected.kind &&
        capturedWallMs == expected.capturedWallMs && capturedElapsedMs == expected.capturedElapsedMs &&
        fileName == expected.fileName && length == expected.length && sha256 == expected.sha256

    private fun blocked(
        reason: String,
        committed: Int,
        duplicates: Int,
        quarantined: Int,
        retryable: Boolean = false
    ): SecondaryReplayDrainResult {
        val bounded = reason.take(MAX_DIAGNOSTIC_CHARS)
        val now = System.nanoTime()
        val previous = lastDiagnosticAtNanos
        if (previous == null || now - previous >= DIAGNOSTIC_INTERVAL_NANOS) {
            lastDiagnosticAtNanos = now
            runCatching { diagnostic?.invoke(bounded) }
        }
        return SecondaryReplayDrainResult(false, committed, duplicates, quarantined, bounded, retryable)
    }

    private fun retryableStatus(status: Int): Boolean = status !=
        com.bydcollector.collector.direct.CollectorHelperProtocol.STATUS_INVALID_REQUEST

    private fun ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("secondary replay interrupted")
    }

    private fun safe(value: String?): String = value?.take(MAX_DIAGNOSTIC_CHARS) ?: "unknown"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02X".format(it) }

    companion object {
        private const val MAX_DIAGNOSTIC_CHARS = 512
        private const val MAX_RECORD_BYTES = SecondaryTelemetrySpool.MAX_SPOOL_BYTES
        private const val DIAGNOSTIC_INTERVAL_NANOS = 30_000_000_000L
    }
}
