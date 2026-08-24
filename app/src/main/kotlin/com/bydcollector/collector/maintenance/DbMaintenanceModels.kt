package com.bydcollector.collector.maintenance

enum class DbMaintenanceOperation(
    val key: String,
    val stepsUk: List<String>,
    val stepsEn: List<String>
) {
    ARCHIVE(
        key = "archive",
        stepsUk = listOf(
            "Зупиняємо збір та експорт",
            "Закриваємо поточну базу даних",
            "Переносимо базу в архів",
            "Створюємо нову базу даних",
            "Перевіряємо нову базу",
            "Відновлюємо попередній стан"
        ),
        stepsEn = listOf(
            "Stopping collection and export",
            "Closing current database",
            "Moving database to archive",
            "Creating new database",
            "Verifying new database",
            "Restoring previous state"
        )
    ),
    DEBUG_ARCHIVE(
        key = "debug_archive",
        stepsUk = listOf(
            "Зупиняємо round-robin збір",
            "Закриваємо тестову базу даних",
            "Переносимо тестову базу в архів",
            "Створюємо нову тестову базу",
            "Перевіряємо нову тестову базу",
            "Відновлюємо round-robin збір"
        ),
        stepsEn = listOf(
            "Stopping round-robin collection",
            "Closing test database",
            "Moving test database to archive",
            "Creating new test database",
            "Verifying new test database",
            "Restoring round-robin collection"
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
)

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
    val telegramDeferred: Boolean = false,
    val warning: String? = null
) {
    val blocksAutomaticCutover: Boolean
        get() = warning != null || telegramPending > 0L || mqttPending > 0L || influxPending > 0L || telegramDeferred
}

data class StorageCutoverJournal(
    val family: String,
    val archivePath: String?,
    val phase: String,
    val sourceFormat: StorageFormat,
    /** Manual archives tolerate inspection-only failures; old journals default to strict automatic recovery. */
    val manual: Boolean = false,
    /** Exact stable source set captured after SQLite owners are closed. */
    val sourceNames: Set<String> = emptySet()
)
