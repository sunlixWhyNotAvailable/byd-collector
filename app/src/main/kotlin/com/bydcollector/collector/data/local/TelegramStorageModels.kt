package com.bydcollector.collector.data.local

data class TelegramOutboxEntry(
    val id: Long,
    val dedupeKey: String,
    val eventType: String,
    val payload: String,
    val attemptCount: Int,
    val nextAttemptAtMs: Long,
    val blocked: Boolean,
    val waitsForSummaryKey: String? = null
)

data class TelegramEnqueueResult(
    val inserted: Boolean,
    val expiredCount: Int,
    val overflowCount: Int
)

data class TelegramOutboxMessage(
    val dedupeKey: String,
    val eventType: String,
    val payload: String,
    val waitsForSummaryKey: String? = null
)

/** A lossless copy of one row from the pre-sidecar Main database. */
data class TelegramLegacyOutboxRow(
    val id: Long,
    val dedupeKey: String,
    val eventType: String,
    val payload: String,
    val createdAtMs: Long,
    val nextAttemptAtMs: Long,
    val attemptCount: Int,
    val lastAttemptAtMs: Long?,
    val lastError: String?,
    val blocked: Boolean,
    val blockedCode: Int = if (blocked) 1 else 0
)

/**
 * Bounded, single-transaction snapshot of the two legacy Main Telegram tables.
 * runtimeStatePresent distinguishes an absent row from a malformed row with null columns.
 */
data class TelegramLegacySnapshot(
    val outbox: List<TelegramLegacyOutboxRow> = emptyList(),
    val runtimeStateJson: String? = null,
    val runtimeStateUpdatedAtMs: Long? = null,
    val runtimeStatePresent: Boolean = runtimeStateJson != null || runtimeStateUpdatedAtMs != null,
    val runtimeStateValid: Boolean = true,
    val truncated: Boolean = false,
    val readError: String? = null
) {
    val validForImport: Boolean
        get() = readError == null && !truncated && runtimeStateValid

    val requiresPreservation: Boolean
        get() = outbox.isNotEmpty() || runtimeStatePresent || !validForImport
}

data class TelegramMigrationResult(
    val status: Status,
    val copiedOutboxCount: Int = 0,
    val expectedOutboxCount: Int = 0,
    val stateCopied: Boolean = false,
    val errorMessage: String? = null,
    val sidecarVerified: Boolean = status == Status.COMMITTED
) {
    enum class Status {
        COMMITTED,
        ALREADY_COMPLETE,
        SKIPPED_INVALID,
        FAILED
    }

    val committed: Boolean
        get() = status == Status.COMMITTED || (status == Status.ALREADY_COMPLETE && sidecarVerified)

    val alreadyComplete: Boolean
        get() = status == Status.ALREADY_COMPLETE

    val verified: Boolean
        get() = sidecarVerified
}

internal data class TelegramDiagnosticRow(
    val id: Long,
    val dedupeKey: String,
    val eventType: String,
    val createdAtMs: Long,
    val nextAttemptAtMs: Long,
    val attemptCount: Int,
    val blocked: Boolean,
    val waitsForSummaryKey: String?
)

internal data class TelegramDiagnosticSnapshot(
    val status: String,
    val runtimeStatePresent: Boolean,
    val runtimeStateValid: Boolean,
    val runtimeStateUpdatedAtMs: Long?,
    val pendingPowerOffLocationTripId: String?,
    val pendingPowerOffLocationPowerSessionId: String?,
    val pendingPowerOffLocationSummaryDelivered: Boolean,
    val outboxTotal: Long,
    val relevantRowsTotal: Long,
    val rows: List<TelegramDiagnosticRow>,
    val rowsTruncated: Boolean
)
