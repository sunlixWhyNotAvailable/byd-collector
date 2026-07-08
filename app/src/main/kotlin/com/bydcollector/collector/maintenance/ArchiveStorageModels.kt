package com.bydcollector.collector.maintenance

enum class ArchiveStorageJobMode {
    COMPRESS,
    DELETE,
    RETENTION
}

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
    val activeDatabasePath: String,
    val activeDatabaseSizeBytes: Long,
    val archiveBytes: Long,
    val archiveLimitBytes: Long,
    val entries: List<ArchiveStorageEntry>
)

data class ArchiveStorageJobStatus(
    val mode: ArchiveStorageJobMode? = null,
    val running: Boolean = false,
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val messageUk: String = "",
    val messageEn: String = "",
    val itemId: String? = null,
    val error: String? = null,
    val updatedAtMs: Long = 0L
)
