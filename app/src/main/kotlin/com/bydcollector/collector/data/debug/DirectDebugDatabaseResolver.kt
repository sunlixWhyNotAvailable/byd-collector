package com.bydcollector.collector.data.debug

import android.content.Context
import com.bydcollector.collector.maintenance.StorageFormat
import com.bydcollector.collector.maintenance.StorageCutoverJournal
import com.bydcollector.collector.service.CollectorSettings
import java.io.File

object DirectDebugDatabaseResolver {
    fun databaseFile(context: Context): File {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(CollectorSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val journal = prefs.getString(CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_FAMILY, null)?.let { family ->
            StorageCutoverJournal(
                family = family,
                archivePath = prefs.getString(CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_ARCHIVE_PATH, null),
                phase = prefs.getString(CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_PHASE, null).orEmpty(),
                sourceFormat = prefs.getString(CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_FORMAT, null)
                    ?.let { runCatching { StorageFormat.valueOf(it) }.getOrNull() }
                    ?: StorageFormat.UNKNOWN,
                sourceDatabaseName = prefs.getString(
                    CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_DATABASE_NAME,
                    null
                ).orEmpty(),
                targetDatabaseName = prefs.getString(
                    CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_TARGET_DATABASE_NAME,
                    null
                ).orEmpty()
            )
        }
        return resolveDatabaseFile(
            checkNotNull(appContext.getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME).parentFile),
            journal
        )
    }

    internal fun resolveDatabaseFile(databaseDirectory: File, journal: StorageCutoverJournal?): File {
        val legacy = File(databaseDirectory, DirectDebugDatabaseHelper.LEGACY_DATABASE_NAME)
        val secondary = File(databaseDirectory, DirectDebugDatabaseHelper.DATABASE_NAME)
        return File(databaseDirectory, resolveName(databaseSetExists(legacy), databaseSetExists(secondary), journal))
    }

    internal fun resolveName(
        legacyExists: Boolean,
        secondaryExists: Boolean,
        journal: StorageCutoverJournal?
    ): String {
        if (legacyExists && secondaryExists) {
            val debugJournal = journal?.takeIf {
                it.family == DirectDebugDatabaseHelper.SCHEMA_FAMILY &&
                    it.sourceDatabaseName in DirectDebugDatabaseHelper.DATABASE_NAMES &&
                    it.targetDatabaseName in DirectDebugDatabaseHelper.DATABASE_NAMES
            } ?: error("Both secondary database names exist without a definitive cutover journal")
            return when (debugJournal.phase) {
                "ARCHIVING", "ROLLBACK" -> debugJournal.sourceDatabaseName
                "CREATING", "VERIFYING" -> debugJournal.targetDatabaseName
                else -> error("Both secondary database names exist with an invalid cutover phase")
            }
        }
        if (legacyExists) return DirectDebugDatabaseHelper.LEGACY_DATABASE_NAME
        if (secondaryExists) return DirectDebugDatabaseHelper.DATABASE_NAME
        val debugJournal = journal?.takeIf {
            it.family == DirectDebugDatabaseHelper.SCHEMA_FAMILY &&
                it.targetDatabaseName in DirectDebugDatabaseHelper.DATABASE_NAMES &&
                it.phase in setOf("CREATING", "VERIFYING")
        }
        return debugJournal?.targetDatabaseName ?: DirectDebugDatabaseHelper.DATABASE_NAME
    }

    private fun databaseSetExists(databaseFile: File): Boolean =
        listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"), File(databaseFile.path + "-journal"))
            .any(File::exists)
}
