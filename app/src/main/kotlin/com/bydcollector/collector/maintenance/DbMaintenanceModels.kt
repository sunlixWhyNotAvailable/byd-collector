package com.bydcollector.collector.maintenance

enum class DbMaintenanceOperation(
    val key: String,
    val stepsUk: List<String>,
    val stepsEn: List<String>
) {
    ARCHIVE(
        key = "archive",
        stepsUk = listOf(
            "Завершуємо поточний запис",
            "Записуємо накопичену чергу",
            "Контрольна точка та закриття бази",
            "Переносимо базу в архів",
            "Створюємо нову базу даних",
            "Перевіряємо нову базу",
            "Відновлюємо попередній стан"
        ),
        stepsEn = listOf(
            "Finishing the current write",
            "Draining the queued records",
            "Checkpointing and closing database",
            "Moving database to archive",
            "Creating new database",
            "Verifying new database",
            "Restoring previous state"
        )
    ),
    DEBUG_ARCHIVE(
        key = "debug_archive",
        stepsUk = listOf(
            "Завершуємо поточний вторинний запис",
            "Записуємо накопичену вторинну чергу",
            "Контрольна точка та закриття вторинної бази",
            "Переносимо вторинну базу в архів",
            "Створюємо нову вторинну базу",
            "Перевіряємо нову вторинну базу",
            "Відновлюємо вторинний збір"
        ),
        stepsEn = listOf(
            "Finishing the current secondary write",
            "Draining the queued secondary records",
            "Checkpointing and closing secondary database",
            "Moving secondary database to archive",
            "Creating new secondary database",
            "Verifying new secondary database",
            "Restoring secondary collection"
        )
    );

    companion object {
        fun fromKey(key: String?): DbMaintenanceOperation? = entries.firstOrNull { it.key == key }
    }
}

data class DbMaintenanceRuntimeStatus(
    val operation: DbMaintenanceOperation? = null,
    val running: Boolean = false,
    val completed: Boolean = false,
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val messageUk: String = "",
    val messageEn: String = "",
    val error: String? = null,
    val warning: String? = null,
    val archivePath: String? = null,
    val startedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val cancelAvailable: Boolean = false
) : java.io.Serializable

data class DbMaintenanceUiState(
    val operation: DbMaintenanceOperation,
    val running: Boolean = false,
    val completed: Boolean = false,
    val stepIndex: Int = 0,
    val stepCount: Int = operation.stepsUk.size,
    val messageUk: String = "",
    val messageEn: String = "",
    val error: String? = null,
    val warning: String? = null,
    val archivePath: String? = null,
    val cancelAvailable: Boolean = false,
    val mainArchivePreflight: MainArchivePreflight? = null
)

data class DbMaintenanceResult(
    val ok: Boolean,
    val message: String,
    val archivePath: String? = null,
    val warning: String? = null
)

data class MainArchivePreflight(
    val telegramPending: Long = 0L,
    val mqttPending: Long = 0L,
    val influxPending: Long = 0L,
    val telegramStatePresent: Boolean = false,
    val telegramStorageWarning: String? = null,
    val warning: String? = null
) : java.io.Serializable {
    val blocksAutomaticCutover: Boolean
        get() = warning != null || telegramPending > 0L || mqttPending > 0L || influxPending > 0L || telegramStatePresent
}

data class StorageCutoverJournal(
    val family: String,
    val archivePath: String?,
    val phase: String,
    val sourceFormat: StorageFormat,
    /** Manual archives tolerate inspection-only failures; old journals default to strict automatic recovery. */
    val manual: Boolean = false,
    /** Exact stable source set captured after SQLite owners are closed. */
    val sourceNames: Set<String> = emptySet(),
    /** Exact active filename before the archive operation. */
    val sourceDatabaseName: String = "",
    /** Exact filename opened after the archive operation. */
    val targetDatabaseName: String = sourceDatabaseName
)
