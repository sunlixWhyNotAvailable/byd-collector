package com.bydcollector.collector.maintenance

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.service.TelegramEventState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class StorageFormat {
    ABSENT,
    LEGACY_V1,
    COMPACT_V2,
    UNKNOWN
}

/** Performs the one-time, archive-first transition to the compact storage schemas. */
internal class StorageFormatCutoverCoordinator(
    context: Context,
    private val settings: CollectorSettings
) {
    private val appContext = context.applicationContext
    private val archiveRoot = File(appContext.filesDir, ARCHIVE_ROOT_NAME)

    fun ensureMainReady(): Boolean {
        val databaseFile = appContext.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
        if (!recoverInterruptedCutover(MAIN_FAMILY, databaseFile)) return mainTerminal("Interrupted cutover cannot be recovered")
        return when (detectMain(databaseFile)) {
            StorageFormat.ABSENT -> createFreshMain(databaseFile)
            StorageFormat.COMPACT_V2 -> {
                settings.clearMainStorageCutoverStatus()
                true
            }
            StorageFormat.UNKNOWN -> mainTerminal("Unknown main database format")
            StorageFormat.LEGACY_V1 -> ensureLegacyMain(databaseFile)
        }
    }

    fun ensureDebugReady(): Boolean {
        val databaseFile = appContext.getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME)
        if (!recoverInterruptedCutover(DEBUG_FAMILY, databaseFile)) return debugTerminal("Interrupted cutover cannot be recovered")
        return when (detectDebug(databaseFile)) {
            StorageFormat.ABSENT -> createFreshDebug(databaseFile)
            StorageFormat.COMPACT_V2 -> {
                settings.setDebugStorageCutoverError(null)
                true
            }
            StorageFormat.LEGACY_V1 -> cutoverLegacy(databaseFile, DEBUG_FAMILY, ::createDebugDatabase)
                .also { ready ->
                    settings.setDebugStorageCutoverError(if (ready) null else "Debug database cutover failed")
                }
            StorageFormat.UNKNOWN -> debugTerminal("Unknown debug database format")
        }
    }

    private fun ensureLegacyMain(databaseFile: File): Boolean {
        if (settings.mainStorageCutoverDeferredReason() != null) return true
        val preflight = runCatching { readMainPreflight(databaseFile) }.getOrElse { error ->
            return deferMain("preflight_error:${error::class.java.simpleName}")
        }
        if (preflight.blocksAutomaticCutover) {
            return deferMain(
                "telegram=${preflight.telegramPending};mqtt=${preflight.mqttPending};" +
                    "influx=${preflight.influxPending};telegram_state=${preflight.telegramDeferred}"
            )
        }
        val ready = cutoverLegacy(databaseFile, MAIN_FAMILY, ::createMainDatabase)
        if (ready) {
            settings.clearMainStorageCutoverStatus()
            return true
        }
        val legacyStillUsable = settings.storageCutoverJournal() == null &&
            detectMain(databaseFile) == StorageFormat.LEGACY_V1 &&
            quickCheck(databaseFile)
        settings.setMainStorageCutoverError("Main database cutover failed")
        return legacyStillUsable
    }

    private fun deferMain(reason: String): Boolean {
        settings.deferMainStorageCutover(reason)
        return if (settings.mainStorageCutoverDeferredReason() == reason) true
        else mainTerminal("Cannot persist main database cutover deferral")
    }

    private fun createFreshMain(databaseFile: File): Boolean {
        val ready = createFreshDatabase(databaseFile, MAIN_FAMILY, ::detectMain, ::createMainDatabase)
        if (ready) settings.clearMainStorageCutoverStatus() else settings.setMainStorageCutoverError("Cannot create compact main database")
        return ready
    }

    private fun createFreshDebug(databaseFile: File): Boolean {
        val ready = createFreshDatabase(databaseFile, DEBUG_FAMILY, ::detectDebug, ::createDebugDatabase)
        settings.setDebugStorageCutoverError(if (ready) null else "Cannot create compact debug database")
        return ready
    }

    private fun createFreshDatabase(
        databaseFile: File,
        family: String,
        detect: (File) -> StorageFormat,
        createDatabase: () -> Unit
    ): Boolean {
        if (settings.storageCutoverJournal() != null) return false
        val journal = StorageCutoverJournal(
            family = family,
            archivePath = null,
            phase = PHASE_CREATING,
            sourceFormat = StorageFormat.ABSENT
        )
        if (!runCatching { settings.setStorageCutoverJournal(journal) }.getOrDefault(false)) return false
        return runCatching {
            createDatabase()
            if (!runCatching {
                    settings.setStorageCutoverJournal(journal.copy(phase = PHASE_VERIFYING))
                }.getOrDefault(false)
            ) return@runCatching false
            check(detect(databaseFile) == StorageFormat.COMPACT_V2 && quickCheck(databaseFile)) {
                "Fresh compact database verification failed"
            }
            runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)
        }.getOrElse {
            // A valid compact store is recoverable; keep its journal if clearing or
            // the phase update failed.  Delete only an invalid/partial fresh set.
            if (detect(databaseFile) == StorageFormat.COMPACT_V2 && quickCheck(databaseFile)) return@getOrElse false
            if (!deleteExactDatabaseSet(databaseFile) || detect(databaseFile) != StorageFormat.ABSENT) return@getOrElse false
            if (!runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)) return@getOrElse false
            false
        }
    }

    private fun cutoverLegacy(databaseFile: File, family: String, createDatabase: () -> Unit): Boolean {
        if (settings.storageCutoverJournal() != null) return false
        if (!checkpoint(databaseFile)) return false
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val plannedDirectory = DatabaseArchiveManager.plannedArchiveDirectory(databaseFile, archiveRoot, timestamp)
        val journal = StorageCutoverJournal(
            family = family,
            archivePath = plannedDirectory.absolutePath,
            phase = PHASE_ARCHIVING,
            sourceFormat = StorageFormat.LEGACY_V1
        )
        if (!runCatching { settings.setStorageCutoverJournal(journal) }.getOrDefault(false)) return false
        val archive = DatabaseArchiveManager.archive(databaseFile, archiveRoot, timestamp)
        if (!archive.ok) {
            if (archive.rollbackOk && legacyFormat(family, databaseFile) && quickCheck(databaseFile)) {
                if (!runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)) return false
            }
            return false
        }

        val archivedDatabase = File(plannedDirectory, databaseFile.name)
        if (!formatMatches(family, archivedDatabase, StorageFormat.LEGACY_V1) || !quickCheck(archivedDatabase)) {
            rollbackNewDatabase(databaseFile, family, archive.movedFiles)
            return false
        }
        if (!runCatching {
                settings.setStorageCutoverJournal(
                    StorageCutoverJournal(family, plannedDirectory.absolutePath, PHASE_CREATING, StorageFormat.LEGACY_V1)
                )
            }.getOrDefault(false)
        ) {
            rollbackNewDatabase(databaseFile, family, archive.movedFiles)
            return false
        }
        val created = runCatching { createDatabase() }.isSuccess
        if (!runCatching {
                settings.setStorageCutoverJournal(
                    StorageCutoverJournal(family, plannedDirectory.absolutePath, PHASE_VERIFYING, StorageFormat.LEGACY_V1)
                )
            }.getOrDefault(false)
        ) {
            rollbackNewDatabase(databaseFile, family, archive.movedFiles)
            return false
        }
        val verified = created && compactFormat(family, databaseFile) && quickCheck(databaseFile)
        if (verified) {
            if (runCatching { settings.setCutoverArchiveStoragePending(true) }.isFailure) return false
            return runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)
        }
        rollbackNewDatabase(databaseFile, family, archive.movedFiles)
        return false
    }

    private fun rollbackNewDatabase(databaseFile: File, family: String, movedFiles: List<File>): Boolean {
        settings.storageCutoverJournal()?.let { journal ->
            if (!runCatching {
                    settings.setStorageCutoverJournal(journal.copy(phase = PHASE_ROLLBACK))
                }.getOrDefault(false)
            ) return false
        }
        if (!deleteExactDatabaseSet(databaseFile)) return false
        if (!DatabaseArchiveManager.restore(databaseFile, movedFiles)) return false
        val restored = legacyFormat(family, databaseFile) && quickCheck(databaseFile)
        if (restored) {
            movedFiles.firstOrNull()?.parentFile?.takeIf { it.listFiles().orEmpty().isEmpty() }?.delete()
            if (!runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)) return false
        }
        return restored
    }

    private fun recoverInterruptedCutover(family: String, databaseFile: File): Boolean {
        val journal = settings.storageCutoverJournal() ?: return true
        if (journal.family !in setOf(MAIN_FAMILY, DEBUG_FAMILY)) return false
        if (journal.phase !in setOf(PHASE_ARCHIVING, PHASE_CREATING, PHASE_VERIFYING, PHASE_ROLLBACK)) return false
        if (journal.family != family) return true
        if (journal.sourceFormat == StorageFormat.ABSENT) {
            return recoverInterruptedFreshCreation(family, databaseFile, journal)
        }
        if (journal.sourceFormat !in setOf(StorageFormat.LEGACY_V1, StorageFormat.COMPACT_V2)) return false
        val archiveDirectory = validatedArchiveDirectory(databaseFile, journal.archivePath ?: return false) ?: return false
        val archivedFiles = archivedSidecars(databaseFile, archiveDirectory)
        val activeFormat = detectFormat(family, databaseFile)
        val archivedDatabase = File(archiveDirectory, databaseFile.name)
        val recoveryAction = StorageCutoverRecovery.decide(
            StorageCutoverRecovery.Snapshot(
                phase = journal.phase,
                sourceFormat = journal.sourceFormat,
                activeFormat = activeFormat,
                activeDatabaseExists = databaseFile.isFile,
                activeQuickCheck = quickCheck(databaseFile),
                archivedDatabasePresent = archivedDatabase.isFile,
                archivedFormat = detectFormat(family, archivedDatabase),
                archivedQuickCheck = quickCheck(archivedDatabase),
                archivedSidecarNames = archivedFiles.map { it.name }.toSet(),
                expectedSidecarNames = DatabaseArchiveManager.sidecarFiles(databaseFile).map { it.name }.toSet(),
                unknownArchiveFiles = archiveDirectory.listFiles().orEmpty().any {
                    it.name !in DatabaseArchiveManager.sidecarFiles(databaseFile).map { file -> file.name }
                }
            )
        )

        if (activeFormat == journal.sourceFormat && archivedFiles.isEmpty()) {
            if (recoveryAction != StorageCutoverRecovery.Action.CLEAR_INTACT_SOURCE || !quickCheck(databaseFile)) {
                return false
            }
            return runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)
        }
        // A partial rollback is ambiguous: active sidecars may already contain restored WAL data.
        // Never delete or move anything unless the complete source set above already verifies.
        if (journal.phase == PHASE_ROLLBACK) return false
        if (recoveryAction == StorageCutoverRecovery.Action.COMPLETE_FORWARD) {
            if (!formatMatches(family, archivedDatabase, journal.sourceFormat) ||
                !quickCheck(archivedDatabase) ||
                !quickCheck(databaseFile)
            ) return false
            if (runCatching { settings.setCutoverArchiveStoragePending(true) }.isFailure) return false
            return runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)
        }
        if (recoveryAction != StorageCutoverRecovery.Action.RESTORE_ARCHIVE || archivedFiles.isEmpty()) return false
        if (journal.phase == PHASE_ARCHIVING && databaseFile.exists()) return false
        return StorageCutoverRecovery.execute(
            action = recoveryAction,
            databaseFile = databaseFile,
            movedFiles = archivedFiles,
            deleteActive = { file ->
                if (journal.phase == PHASE_ARCHIVING && !file.exists()) true else deleteExactDatabaseSet(file)
            },
            restore = { file, moved -> DatabaseArchiveManager.restore(file, moved) },
            verifyActive = { formatMatches(family, databaseFile, journal.sourceFormat) && quickCheck(databaseFile) },
            clearJournal = { runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false) },
            cleanupArchive = { archiveDirectory.takeIf { it.listFiles().orEmpty().isEmpty() }?.delete() }
        )
    }

    private fun recoverInterruptedFreshCreation(
        family: String,
        databaseFile: File,
        journal: StorageCutoverJournal
    ): Boolean {
        if (journal.archivePath != null || journal.phase !in setOf(PHASE_CREATING, PHASE_VERIFYING)) return false
        return when (detectFormat(family, databaseFile)) {
            StorageFormat.COMPACT_V2 -> {
                if (!quickCheck(databaseFile)) return false
                runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)
            }
            StorageFormat.ABSENT -> {
                runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)
            }
            StorageFormat.UNKNOWN -> {
                if (!deleteExactDatabaseSet(databaseFile) || detectFormat(family, databaseFile) != StorageFormat.ABSENT) {
                    return false
                }
                runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)
            }
            StorageFormat.LEGACY_V1 -> false
        }
    }

    private fun createMainDatabase() {
        TelemetryDatabaseHelper(appContext).use { helper -> helper.writableDatabase }
    }

    private fun createDebugDatabase() {
        DirectDebugDatabaseHelper(appContext).use { helper -> helper.writableDatabase }
    }

    private fun checkpoint(databaseFile: File): Boolean = runCatching {
        open(databaseFile, readOnly = false).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) == 0
            }
        }
    }.getOrDefault(false)

    private fun quickCheck(databaseFile: File): Boolean {
        if (!databaseFile.isFile) return false
        return runCatching {
            open(databaseFile, readOnly = true).use { db ->
                db.rawQuery("PRAGMA quick_check", emptyArray()).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == "ok"
                }
            }
        }.getOrDefault(false)
    }

    private fun deleteExactDatabaseSet(databaseFile: File): Boolean {
        val expected = appContext.getDatabasePath(databaseFile.name).canonicalFile
        if (databaseFile.canonicalFile != expected) return false
        var deleted = true
        DatabaseArchiveManager.sidecarFiles(expected).forEach { file ->
            if (file.exists() && !file.delete()) deleted = false
        }
        return deleted
    }

    private fun validatedArchiveDirectory(databaseFile: File, path: String): File? {
        val directory = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val root = runCatching { archiveRoot.canonicalFile }.getOrNull() ?: return null
        val expectedPrefix = databaseFile.nameWithoutExtension + "_"
        return directory.takeIf { it.parentFile == root && it.name.startsWith(expectedPrefix) }
    }

    private fun archivedSidecars(databaseFile: File, archiveDirectory: File): List<File> {
        val allowed = DatabaseArchiveManager.sidecarFiles(databaseFile).map { it.name }.toSet()
        return archiveDirectory.listFiles().orEmpty().filter { it.isFile && it.name in allowed }
    }

    private fun compactFormat(family: String, databaseFile: File): Boolean = when (family) {
        MAIN_FAMILY -> detectMain(databaseFile) == StorageFormat.COMPACT_V2
        DEBUG_FAMILY -> detectDebug(databaseFile) == StorageFormat.COMPACT_V2
        else -> false
    }

    private fun legacyFormat(family: String, databaseFile: File): Boolean = when (family) {
        MAIN_FAMILY -> detectMain(databaseFile) == StorageFormat.LEGACY_V1
        DEBUG_FAMILY -> detectDebug(databaseFile) == StorageFormat.LEGACY_V1
        else -> false
    }

    private fun detectFormat(family: String, databaseFile: File): StorageFormat = when (family) {
        MAIN_FAMILY -> detectMain(databaseFile)
        DEBUG_FAMILY -> detectDebug(databaseFile)
        else -> StorageFormat.UNKNOWN
    }

    private fun formatMatches(family: String, databaseFile: File, expected: StorageFormat): Boolean =
        detectFormat(family, databaseFile) == expected

    private fun mainTerminal(message: String): Boolean {
        settings.setMainStorageCutoverError(message)
        return false
    }

    private fun debugTerminal(message: String): Boolean {
        settings.setDebugStorageCutoverError(message)
        return false
    }

    companion object {
        private const val MAIN_FAMILY = TelemetryDatabaseHelper.SCHEMA_FAMILY
        private const val DEBUG_FAMILY = DirectDebugDatabaseHelper.SCHEMA_FAMILY
        private const val ARCHIVE_ROOT_NAME = "db_archive"
        private const val PHASE_ARCHIVING = "ARCHIVING"
        private const val PHASE_CREATING = "CREATING"
        private const val PHASE_VERIFYING = "VERIFYING"
        private const val PHASE_ROLLBACK = "ROLLBACK"

        fun readMainPreflight(databaseFile: File): MainArchivePreflight {
            check(detectMain(databaseFile) in setOf(StorageFormat.LEGACY_V1, StorageFormat.COMPACT_V2)) {
                "Main database format is not recognized"
            }
            open(databaseFile, readOnly = false).use { db ->
                db.beginTransactionNonExclusive()
                try {
                    val telegramPending = scalarLong(db, "SELECT COUNT(*) FROM telegram_outbox")
                    val mqttPending = scalarLong(db, "SELECT COUNT(*) FROM mqtt_outbox")
                    val influxPending = scalarLong(
                        db,
                        if (detectMain(db) == StorageFormat.COMPACT_V2) {
                            """
                            SELECT COUNT(*)
                            FROM vehicle_state_history h
                            JOIN normalized_history_field_catalog f ON f.id = h.field_id
                            JOIN influx_export_cursor c ON c.field_key = f.field_key
                            WHERE h.id > c.last_exported_history_id
                            """.trimIndent()
                        } else {
                            """
                            SELECT COUNT(*)
                            FROM vehicle_state_history h
                            JOIN influx_export_cursor c ON c.field_key = h.field_key
                            WHERE h.id > c.last_exported_history_id
                            """.trimIndent()
                        }
                    )
                    val telegramDeferred = db.rawQuery(
                        "SELECT state_json FROM telegram_runtime_state WHERE id = 1",
                        emptyArray()
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) false else {
                            val json = cursor.getString(0)
                            check(json.isNotBlank()) { "Stored Telegram state is blank" }
                            checkNotNull(TelegramEventState.fromJsonOrNull(json)) {
                                "Stored Telegram state is malformed"
                            }.hasDeferredStorageWork()
                        }
                    }
                    db.setTransactionSuccessful()
                    return MainArchivePreflight(telegramPending, mqttPending, influxPending, telegramDeferred)
                } finally {
                    db.endTransaction()
                }
            }
        }

        internal fun detectMain(databaseFile: File): StorageFormat = detectFile(databaseFile, ::detectMain)

        internal fun detectDebug(databaseFile: File): StorageFormat = detectFile(databaseFile, ::detectDebug)

        internal fun verifyArchivedSource(
            family: String,
            databaseFile: File,
            archiveDirectory: File,
            sourceFormat: StorageFormat
        ): Boolean {
            val archivedDatabase = File(archiveDirectory, databaseFile.name)
            val detected = when (family) {
                MAIN_FAMILY -> detectMain(archivedDatabase)
                DEBUG_FAMILY -> detectDebug(archivedDatabase)
                else -> StorageFormat.UNKNOWN
            }
            return detected == sourceFormat && quickCheckFile(archivedDatabase)
        }

        internal fun verifyDatabaseQuickCheck(databaseFile: File): Boolean = quickCheckFile(databaseFile)

        private fun detectFile(databaseFile: File, detector: (SQLiteDatabase) -> StorageFormat): StorageFormat {
            if (!databaseFile.exists()) {
                return if (DatabaseArchiveManager.sidecarFiles(databaseFile).drop(1).any { it.exists() }) {
                    StorageFormat.UNKNOWN
                } else {
                    StorageFormat.ABSENT
                }
            }
            if (!databaseFile.isFile) return StorageFormat.UNKNOWN
            return runCatching { open(databaseFile, readOnly = true).use(detector) }.getOrDefault(StorageFormat.UNKNOWN)
        }

        private fun detectMain(db: SQLiteDatabase): StorageFormat {
            val tables = tableNames(db)
            val hasMeta = "storage_meta" in tables
            val history = columns(db, "vehicle_state_history")
            val fields = columns(db, "normalized_field_catalog")
            val historyFields = columns(db, "normalized_history_field_catalog")
            val pollValues = columns(db, "poll_values")
            val core = setOf("collection_sessions", "polls", "poll_values", "normalized_field_catalog", "vehicle_state_history")
                .all { it in tables }
            val legacyShape = core && setOf("field_key", "quality", "observed_at", "changed_at").all { it in history } &&
                "field_id" !in history && "id" !in fields && "created_at" in pollValues
            val compactShape = core && setOf("field_id", "quality_code", "observed_at_ms", "changed_at_ms").all { it in history } &&
                setOf("id", "field_key").all { it in fields } &&
                "normalized_history_field_catalog" in tables &&
                setOf("id", "field_key", "category", "value_type", "unit", "source_keys", "normalizer_id", "catalog_version")
                    .all { it in historyFields } &&
                "created_at" !in pollValues
            return when {
                !hasMeta && legacyShape -> StorageFormat.LEGACY_V1
                hasMeta && markerMatches(db, MAIN_FAMILY, TelemetryDatabaseHelper.FORMAT_VERSION) && compactShape -> StorageFormat.COMPACT_V2
                else -> StorageFormat.UNKNOWN
            }
        }

        private fun detectDebug(db: SQLiteDatabase): StorageFormat {
            val tables = tableNames(db)
            val hasMeta = "storage_meta" in tables
            val sessions = columns(db, "debug_direct_sessions")
            val state = columns(db, "debug_direct_candidate_state")
            val readings = columns(db, "debug_direct_readings")
            val core = setOf("debug_direct_sessions", "debug_direct_candidates", "debug_direct_cycles", "debug_direct_readings", "debug_direct_candidate_state")
                .all { it in tables }
            val legacyShape = core && setOf("started_at", "source_version").all { it in sessions } &&
                setOf("session_id", "read_count").all { it in state } &&
                setOf("sampled_at", "raw_hex", "float_value").all { it in readings }
            val compactShape = core && setOf("started_at_ms", "catalog_version_id", "cycle_count").all { it in sessions } &&
                setOf("catalog_version_id", "last_changed_at_ms").all { it in state } &&
                setOf("sampled_at_ms", "reason", "raw_int").all { it in readings } && "raw_hex" !in readings
            return when {
                !hasMeta && legacyShape -> StorageFormat.LEGACY_V1
                hasMeta && markerMatches(db, DEBUG_FAMILY, DirectDebugDatabaseHelper.FORMAT_VERSION) && compactShape -> StorageFormat.COMPACT_V2
                else -> StorageFormat.UNKNOWN
            }
        }

        private fun markerMatches(db: SQLiteDatabase, family: String, version: Int): Boolean {
            return db.rawQuery("SELECT schema_family, format_version FROM storage_meta", emptyArray()).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == family && cursor.getInt(1) == version && !cursor.moveToNext()
            }
        }

        private fun tableNames(db: SQLiteDatabase): Set<String> {
            return db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", emptyArray()).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }

        private fun columns(db: SQLiteDatabase, table: String): Set<String> {
            return db.rawQuery("PRAGMA table_info($table)", emptyArray()).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
        }

        private fun scalarLong(db: SQLiteDatabase, sql: String): Long {
            return db.rawQuery(sql, emptyArray()).use { cursor ->
                check(cursor.moveToFirst()) { "Count query returned no row" }
                cursor.getLong(0)
            }
        }

        private fun open(databaseFile: File, readOnly: Boolean): SQLiteDatabase {
            return SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE
            )
        }

        private fun quickCheckFile(databaseFile: File): Boolean {
            if (!databaseFile.isFile) return false
            return runCatching {
                open(databaseFile, readOnly = true).use { db ->
                    db.rawQuery("PRAGMA quick_check", emptyArray()).use { cursor ->
                        cursor.moveToFirst() && cursor.getString(0) == "ok"
                    }
                }
            }.getOrDefault(false)
        }
    }
}
