package com.bydcollector.collector.maintenance

enum class DbMaintenanceOperation(
    val key: String,
    val stepsUk: List<String>,
    val stepsEn: List<String>
) {
    COMPACT(
        key = "compact",
        stepsUk = listOf(
            "Зупиняємо збір та експорт",
            "Відʼєднуємо raw історію від нормалізованих даних",
            "Видаляємо raw історію",
            "Стискаємо базу даних",
            "Відновлюємо попередній стан"
        ),
        stepsEn = listOf(
            "Stopping collection and export",
            "Detaching raw history from normalized data",
            "Deleting raw history",
            "Compacting database",
            "Restoring previous state"
        )
    ),
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
    val archivePath: String? = null,
    val cancelAvailable: Boolean = false
)

data class DbMaintenanceResult(
    val ok: Boolean,
    val message: String,
    val archivePath: String? = null
)
