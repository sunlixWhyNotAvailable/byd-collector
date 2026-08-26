package com.bydcollector.collector.maintenance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageFormatCutoverContractTest {
    @Test
    fun detectsKnownFormatsByMarkerAndShapeInsteadOfUserVersion() {
        val source = source("com/bydcollector/collector/maintenance/StorageFormatCutover.kt")

        assertTrue(source.contains("ABSENT"))
        assertTrue(source.contains("LEGACY_V1"))
        assertTrue(source.contains("COMPACT_V2"))
        assertTrue(source.contains("UNKNOWN"))
        assertTrue(source.contains("markerMatches(db, MAIN_FAMILY"))
        assertTrue(source.contains("markerMatches(db, DEBUG_FAMILY"))
        assertTrue(source.contains("setOf(\"field_key\", \"quality\", \"observed_at\", \"changed_at\")"))
        assertTrue(source.contains("setOf(\"field_id\", \"quality_code\", \"observed_at_ms\", \"changed_at_ms\")"))
        assertTrue(source.contains("\"normalized_history_field_catalog\" in tables"))
        assertTrue(source.contains("val historyFields = columns(db, \"normalized_history_field_catalog\")"))
        assertTrue(source.contains("setOf(\"started_at\", \"source_version\")"))
        assertTrue(source.contains("setOf(\"started_at_ms\", \"catalog_version_id\", \"cycle_count\")"))
        assertFalse(source.contains("PRAGMA user_version"))
    }

    @Test
    fun strictMainPreflightUsesOneTransactionAndAllExistingInfluxCursors() {
        val source = source("com/bydcollector/collector/maintenance/StorageFormatCutover.kt")
        val preflight = source.substringAfter("fun readMainPreflight").substringBefore("internal fun detectMain")

        assertTrue(preflight.contains("db.beginTransactionNonExclusive()"))
        assertTrue(preflight.contains("SELECT COUNT(*) FROM telegram_outbox"))
        assertTrue(preflight.contains("SELECT COUNT(*) FROM mqtt_outbox"))
        assertTrue(preflight.contains("JOIN normalized_history_field_catalog f ON f.id = h.field_id"))
        assertTrue(preflight.contains("JOIN influx_export_cursor c ON c.field_key = f.field_key"))
        assertTrue(preflight.contains("JOIN influx_export_cursor c ON c.field_key = h.field_key"))
        assertTrue(preflight.contains("check(json.isNotBlank())"))
        assertTrue(preflight.contains("TelegramEventState.fromJsonOrNull(json)"))
        assertTrue(preflight.contains("telegramStatePresent"))
        assertFalse(preflight.contains("hasDeferredStorageWork()"))
        assertFalse(preflight.contains("telemetryExpectedSinceMs"))
        assertTrue(source.contains("settings.mainStorageCutoverDeferredReason() == reason"))
        assertFalse(source.contains("if (settings.mainStorageCutoverDeferredReason() != null) return true"))
    }

    @Test
    fun journalPrecedesRenameAndFailureRestoresOnlyExactDatabaseSet() {
        val source = source("com/bydcollector/collector/maintenance/StorageFormatCutover.kt")
        val cutover = source.substringAfter("private fun cutoverLegacy").substringBefore("private fun rollbackNewDatabase")
        val rollback = source.substringAfter("private fun rollbackNewDatabase").substringBefore("private fun recoverInterruptedCutover")

        assertInOrder(
            cutover,
            "storageCutoverJournal()",
            "plannedArchiveDirectory",
            "setStorageCutoverJournal",
            "DatabaseArchiveManager.archive",
            "PHASE_CREATING",
            "createDatabase()",
            "PHASE_VERIFYING",
            "quickCheck(databaseFile)"
        )
        assertTrue(source.contains("cursor.moveToFirst() && cursor.getInt(0) == 0"))
        assertTrue(rollback.contains("deleteExactDatabaseSet(databaseFile)"))
        assertTrue(rollback.contains("DatabaseArchiveManager.restore(databaseFile, movedFiles)"))
        assertInOrder(rollback, "legacyFormat(family, databaseFile)", "quickCheck(databaseFile)", "clearStorageCutoverJournal()")
        assertTrue(source.contains("DatabaseArchiveManager.sidecarFiles(expected).forEach"))
    }

    @Test
    fun unresolvedJournalBlocksMainAndAnIntactSourceIsNeverDeleted() {
        val source = source("com/bydcollector/collector/maintenance/StorageFormatCutover.kt")
        val ensureLegacy = source.substringAfter("private fun ensureLegacyMain").substringBefore("private fun deferMain")
        val recovery = source.substringAfter("private fun recoverInterruptedCutover").substringBefore("private fun createMainDatabase")
        val intactSource = recovery.substringAfter("if (activeFormat == journal.sourceFormat && archivedFiles.isEmpty())")
            .substringBefore("if (journal.phase == PHASE_ROLLBACK")

        assertInOrder(
            ensureLegacy,
            "settings.storageCutoverJournal() == null",
            "detectMain(databaseFile) == StorageFormat.LEGACY_V1",
            "quickCheck(databaseFile)"
        )
        assertInOrder(
            intactSource,
            "quickCheck(databaseFile)",
            "clearStorageCutoverJournal()"
        )
        assertFalse(intactSource.contains("deleteExactDatabaseSet(databaseFile)"))
        assertFalse(intactSource.contains("DatabaseArchiveManager.restore(databaseFile, archivedFiles)"))
    }

    @Test
    fun journaledSourceFormatRecoversManualCompactArchivesForBothFamilies() {
        val source = source("com/bydcollector/collector/maintenance/StorageFormatCutover.kt")
        val recovery = source.substringAfter("private fun recoverInterruptedCutover")
            .substringBefore("private fun recoverInterruptedFreshCreation")
        val maintenance = source("com/bydcollector/collector/maintenance/DbMaintenanceCoordinator.kt")
        val settings = source("com/bydcollector/collector/service/CollectorSettings.kt")

        assertTrue(maintenance.contains("val sourceFormat = StorageFormatCutoverCoordinator.detectMain(databaseFile)"))
        assertTrue(maintenance.contains("val sourceFormat = StorageFormatCutoverCoordinator.detectDebug(databaseFile)"))
        assertTrue(maintenance.contains("val journal = StorageCutoverJournal("))
        assertTrue(maintenance.contains("PHASE_ARCHIVING"))
        assertTrue(maintenance.contains("PHASE_CREATING"))
        assertTrue(maintenance.contains("PHASE_VERIFYING"))
        assertTrue(maintenance.contains("manual = true"))
        assertTrue(maintenance.contains("sourceNames = sourceNames"))
        assertTrue(recovery.contains("val expectedNames = if (journal.manual) journal.sourceNames else allowedNames"))
        assertTrue(recovery.contains("activeSourceFileSetIntact(databaseFile, expectedNames)"))
        assertTrue(recovery.contains("archivedFormatMatches(family, archivedDatabase, journal.sourceFormat)"))
        assertTrue(recovery.contains("formatMatches(family, databaseFile, journal.sourceFormat)"))
        assertTrue(recovery.contains("journal.manual"))
        assertTrue(recovery.contains("!journal.manual && (!archivedFormatMatches"))
        assertTrue(recovery.contains("!quickCheck(databaseFile)"))
        assertTrue(settings.contains("KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_FORMAT"))
        assertTrue(settings.contains("KEY_STORAGE_CUTOVER_JOURNAL_MANUAL"))
        assertTrue(settings.contains("KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_NAMES"))
        assertTrue(settings.contains("putStringSet(KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_NAMES"))
        assertTrue(settings.contains("journal.sourceFormat.name"))
        assertTrue(settings.contains("runCatching { journalKeys.none(prefs::contains) }.getOrDefault(false)"))
        assertTrue(settings.contains("fun journalString(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()"))
        assertTrue(settings.contains("runCatching { StorageFormat.valueOf(it) }.getOrNull()"))
        assertTrue(settings.contains("?: StorageFormat.UNKNOWN"))
        assertInOrder(
            recovery,
            "journal.family !in setOf(MAIN_FAMILY, DEBUG_FAMILY)",
            "journal.phase !in setOf(PHASE_ARCHIVING, PHASE_CREATING, PHASE_VERIFYING, PHASE_ROLLBACK)",
            "journal.family != family"
        )
    }

    @Test
    fun partialRollbackFailsClosedBeforeAnyDeleteOrRestore() {
        val source = source("com/bydcollector/collector/maintenance/StorageFormatCutover.kt")
        val recovery = source.substringAfter("private fun recoverInterruptedCutover")
            .substringBefore("private fun recoverInterruptedFreshCreation")

        assertInOrder(
            recovery,
            "recoveryAction == StorageCutoverRecovery.Action.CLEAR_INTACT_SOURCE",
            "if (journal.phase == PHASE_ROLLBACK) return false",
            "recoveryAction != StorageCutoverRecovery.Action.RESTORE_ARCHIVE",
            "deleteActive = { file ->",
            "deleteExactDatabaseSet(file)",
            "restore = { file, moved -> DatabaseArchiveManager.restore(file, moved) }"
        )
    }

    @Test
    fun interruptedFreshCreationIsJournaledAndRetriedWithoutArchivePendingState() {
        val source = source("com/bydcollector/collector/maintenance/StorageFormatCutover.kt")
        val createFresh = source.substringAfter("private fun createFreshDatabase")
            .substringBefore("private fun cutoverLegacy")
        val recovery = source.substringAfter("private fun recoverInterruptedFreshCreation")
            .substringBefore("private fun createMainDatabase")

        assertTrue(createFresh.contains("sourceFormat = StorageFormat.ABSENT"))
        assertInOrder(createFresh, "setStorageCutoverJournal(journal)", "createDatabase()", "PHASE_VERIFYING")
        assertTrue(recovery.contains("StorageFormat.UNKNOWN"))
        assertTrue(recovery.contains("deleteExactDatabaseSet(databaseFile)"))
        assertTrue(recovery.contains("StorageFormat.COMPACT_V2"))
        assertFalse(recovery.contains("setCutoverArchiveStoragePending"))
    }

    @Test
    fun sqliteInspectionRecoversWalWhenReadOnlyOpenIsRejected() {
        val source = source("com/bydcollector/collector/maintenance/StorageFormatCutover.kt")
        val inspection = source.substringAfter("private fun <T> inspectDatabaseFile")
            .substringBefore("private fun detectFile")

        assertTrue(source.contains("import android.database.sqlite.SQLiteReadOnlyDatabaseException"))
        assertInOrder(
            inspection,
            "open(databaseFile, readOnly = true)",
            "catch (error: SQLiteReadOnlyDatabaseException)",
            "if (!recoverWal) throw error",
            "open(databaseFile, readOnly = false)"
        )
        assertTrue(source.contains("detectFile(databaseFile, recoverWal = true, detector = ::detectDebug)"))
        assertTrue(source.contains("detectFile(databaseFile, recoverWal = false, detector = ::detectDebug)"))
        assertTrue(source.contains("quickCheckFile(archivedDatabase, recoverWal = false)"))
        assertTrue(source.contains("internal fun checkpointDatabase(databaseFile: File): Boolean"))
    }

    @Test
    fun applicationKeepsColdOnCreateSqliteFreeAndCentralizesBothGates() {
        val app = source("com/bydcollector/collector/BydCollectorApplication.kt")
        val onCreate = app.substringAfter("override fun onCreate()").substringBefore("override fun onTerminate")
        val store = app.substringAfter("private fun store()")

        assertFalse(onCreate.contains("ensureMainReady"))
        assertFalse(onCreate.contains("ensureDebugReady"))
        assertInOrder(store, "telemetryStore?.let", "coordinator().ensureMainReady()", "TelemetryStore(")
        assertTrue(app.contains("fun ensureDebugStorageReady(context: Context): Boolean"))
        assertTrue(app.contains("debugStorageReady ?: coordinator().ensureDebugReady()"))
        assertTrue(app.contains("debugStorageReady = ready.takeIf { it }"))
    }

    @Test
    fun serviceDefersDebugReadinessToStartAndRetriesPendingArchiveCompression() {
        val service = source("com/bydcollector/collector/service/CollectorService.kt")
        val onCreate = service.substringAfter("override fun onCreate()").substringBefore("override fun onStartCommand")
        val startDebug = service.substringAfter("private fun startDebugIfNeeded").substringBefore("private fun stopDebug")
        val archive = service.substringAfter("private fun enqueueArchiveStorageMaintenance").substringBefore("private fun enqueueArchiveDelete")

        assertInOrder(onCreate, "isDebugStorageReady(applicationContext)", "DirectDebugStore(")
        assertFalse(onCreate.contains("ensureDebugStorageReady(applicationContext)"))
        assertInOrder(startDebug, "ensureDebugStorageReady(applicationContext)", "if (!debugStorageReady)")
        assertTrue(service.contains("reconcilePendingCutoverArchiveStorage(action)"))
        assertInOrder(archive, "compressPendingRawArchives", "check(!rawArchiveRemains)", "enforceRetention", "setCutoverArchiveStoragePending(false)")
    }

    private fun source(path: String): String {
        return listOf(File("src/main/kotlin/$path"), File("app/src/main/kotlin/$path"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("Missing source file: $path")
    }

    private fun assertInOrder(source: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val current = source.indexOf(token, previous + 1)
            assertTrue(current > previous, "Missing or out-of-order token: $token")
            previous = current
        }
    }
}
