package com.bydcollector.collector.maintenance

enum class ArchiveStorageJobMode {
    COMPRESS,
    DELETE,
    RETENTION
}

enum class ArchiveStorageItemPhase {
    QUEUED,
    VERIFYING_DATABASE,
    CREATING_ZIP,
    VERIFYING_ZIP,
    FINALIZING,
    READY,
    FAILED,
    DELETING,
    DELETED,
    RETENTION;

    val inProgress: Boolean
        get() = this in setOf(QUEUED, VERIFYING_DATABASE, CREATING_ZIP, VERIFYING_ZIP, FINALIZING, DELETING, RETENTION)
}

data class ArchiveStorageItemState(
    val archiveId: String,
    val operationId: String,
    val phase: ArchiveStorageItemPhase,
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val completedAtMs: Long? = null,
    val error: String? = null
)

data class ArchiveStorageItemsReconciliation(
    val itemStates: List<ArchiveStorageItemState>,
    val knownArchiveIds: Set<String>,
    val scanSucceeded: Boolean,
    val sourceItemStates: List<ArchiveStorageItemState>? = null
)

enum class ArchiveEntryStatus {
    RAW_DIRECTORY,
    COMPRESSED_ZIP,
    TMP
}

data class ArchiveStorageEntry(
    val id: String,
    val displayName: String,
    val path: String,
    val createdAtMs: Long,
    val sizeBytes: Long,
    val status: ArchiveEntryStatus,
    val deletable: Boolean
)

data class ArchiveStorageSnapshot(
    val archiveRootPath: String,
    val mainDatabaseSizeBytes: Long,
    val debugDatabaseSizeBytes: Long,
    val archiveBytes: Long,
    val archiveLimitBytes: Long,
    val entries: List<ArchiveStorageEntry>,
    val tripsDatabaseSizeBytes: Long = 0L
) {
    val activeDatabaseSizeBytes: Long
        get() = mainDatabaseSizeBytes + debugDatabaseSizeBytes + tripsDatabaseSizeBytes
}

data class ArchiveStorageJobStatus(
    val mode: ArchiveStorageJobMode? = null,
    val running: Boolean = false,
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val messageUk: String = "",
    val messageEn: String = "",
    val itemId: String? = null,
    val operationId: String? = null,
    val phase: ArchiveStorageItemPhase? = null,
    val error: String? = null,
    val updatedAtMs: Long = 0L
)

enum class ArchiveStorageAdmissionKind {
    ACCEPTED,
    DEFERRED,
    REJECTED
}

data class ArchiveStorageAdmission(
    val kind: ArchiveStorageAdmissionKind,
    val operationId: String? = null
)

/** Serializes archive work ownership while retaining one deferred background audit. */
class ArchiveStorageOperationCoordinator {
    private var activeOperationId: String? = null
    private var deferredAuditOperationId: String? = null

    @Synchronized
    fun admit(operationId: String, deferIfBusy: Boolean): ArchiveStorageAdmission {
        if (activeOperationId != null) {
            if (!deferIfBusy) return ArchiveStorageAdmission(ArchiveStorageAdmissionKind.REJECTED)
            val deferred = deferredAuditOperationId ?: operationId.also { deferredAuditOperationId = it }
            return ArchiveStorageAdmission(ArchiveStorageAdmissionKind.DEFERRED, deferred)
        }
        val accepted = if (deferIfBusy) deferredAuditOperationId ?: operationId else operationId
        activeOperationId = accepted
        return ArchiveStorageAdmission(ArchiveStorageAdmissionKind.ACCEPTED, accepted)
    }

    @Synchronized
    fun acceptDeferredAudit(operationId: String) {
        if (activeOperationId == operationId && deferredAuditOperationId == operationId) {
            deferredAuditOperationId = null
        }
    }

    @Synchronized
    fun finish(operationId: String): String? {
        if (activeOperationId != operationId) return null
        activeOperationId = null
        return deferredAuditOperationId
    }

    @Synchronized
    fun reject(operationId: String, deferRejectedAudit: Boolean = false): String? {
        if (activeOperationId != operationId) return null
        if (deferRejectedAudit && deferredAuditOperationId == null) deferredAuditOperationId = operationId
        activeOperationId = null
        return deferredAuditOperationId
    }

    @Synchronized
    fun pendingAuditOperationId(): String? = deferredAuditOperationId

    @Synchronized
    fun isActive(): Boolean = activeOperationId != null
}

/** Keeps an earlier item's failure visible when later items complete successfully. */
class ArchiveStorageOperationOutcome {
    private val failures = linkedMapOf<String, String>()

    fun record(status: ArchiveStorageJobStatus) {
        val error = status.error ?: return
        val itemId = status.itemId ?: "archive_storage"
        failures.putIfAbsent(itemId, error)
    }

    fun itemFailures(): Map<String, String> = failures.toMap()

    fun terminalError(workError: Throwable? = null): String? {
        val operationError = workError?.let {
            "${it::class.java.simpleName}: ${it.message ?: "no message"}"
        }
        val itemError = failures.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { (id, error) ->
            "$id=$error"
        }?.let { "archive_item_failures: $it" }
        return listOfNotNull(operationError, itemError).joinToString("; ").takeIf { it.isNotEmpty() }
    }
}
