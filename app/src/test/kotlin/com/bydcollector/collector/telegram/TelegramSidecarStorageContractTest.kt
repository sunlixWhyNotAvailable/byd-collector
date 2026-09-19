package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelegramLegacySnapshot
import com.bydcollector.collector.data.local.TelegramLegacyMigrationDecision
import com.bydcollector.collector.data.local.TelegramMigrationResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramSidecarStorageContractTest {
    @Test
    fun legacySnapshotRequiresPreservationForAnyDataOrUncertainRead() {
        val empty = TelegramLegacySnapshot()
        val invalidData = TelegramLegacySnapshot(runtimeStatePresent = true, runtimeStateValid = false)
        val unknown = TelegramLegacySnapshot(readError = "read failed")

        assertFalse(empty.requiresPreservation)
        assertTrue(empty.provenEmpty)
        assertTrue(invalidData.requiresPreservation)
        assertTrue(invalidData.hasProvenLegacyData)
        assertTrue(unknown.requiresPreservation)
        assertTrue(unknown.readUnknown)
        assertFalse(unknown.hasProvenLegacyData)
        assertFalse(unknown.provenEmpty)
        assertTrue(TelegramLegacySnapshot(truncated = true).requiresPreservation)
    }

    @Test
    fun migrationDecisionSeparatesUnknownProvenMissingAndCompletedSidecar() {
        val unknown = TelegramLegacySnapshot(readError = "read failed")
        val empty = TelegramLegacySnapshot()
        val withData = TelegramLegacySnapshot(runtimeStateJson = "{}", runtimeStateUpdatedAtMs = 1L)

        assertTrue(
            unknown.migrationDecision(importAlreadyComplete = false, migrationPreviouslyRequired = false) ==
                TelegramLegacyMigrationDecision.RETRY_UNKNOWN_READ
        )
        assertTrue(
            empty.migrationDecision(importAlreadyComplete = false, migrationPreviouslyRequired = true) ==
                TelegramLegacyMigrationDecision.FAIL_PROVEN_MISSING
        )
        assertTrue(
            withData.migrationDecision(importAlreadyComplete = false, migrationPreviouslyRequired = false) ==
                TelegramLegacyMigrationDecision.IMPORT_PROVEN_DATA
        )
        assertTrue(
            unknown.migrationDecision(importAlreadyComplete = true, migrationPreviouslyRequired = true) ==
                TelegramLegacyMigrationDecision.VERIFY_COMPLETED_IMPORT
        )
        val partialRead = TelegramLegacySnapshot(runtimeStatePresent = true, readError = "state payload failed")
        assertTrue(
            partialRead.migrationDecision(importAlreadyComplete = false, migrationPreviouslyRequired = false) ==
                TelegramLegacyMigrationDecision.IMPORT_PROVEN_DATA
        )
    }

    @Test
    fun completedMarkerCanVerifyRuntimeWhileDenyingStaleLegacyCleanup() {
        val staleLegacy = TelegramLegacySnapshot(runtimeStateJson = "{}", runtimeStateUpdatedAtMs = 1L)
        assertFalse(staleLegacy.completedImportCleanupVerified(exactSidecarSnapshot = false))
        assertTrue(TelegramLegacySnapshot().completedImportCleanupVerified(exactSidecarSnapshot = false))
        assertFalse(
            TelegramLegacySnapshot(readError = "read failed")
                .completedImportCleanupVerified(exactSidecarSnapshot = true)
        )

        val completed = TelegramMigrationResult(
            status = TelegramMigrationResult.Status.ALREADY_COMPLETE,
            sidecarVerified = true,
            cleanupVerified = false
        )

        assertTrue(completed.verified)
        assertFalse(completed.cleanupVerified)
    }

    @Test
    fun sidecarSchemaAndRetentionAreDurableAndBounded() {
        val helper = sourceFile("com/bydcollector/collector/data/local/TelegramDatabaseHelper.kt")
        val store = sourceFile("com/bydcollector/collector/data/local/TelegramStore.kt")
        val source = helper.readText()
        val storeSource = store.readText()

        assertTrue(source.contains("bydcollector_telegram.db"))
        assertTrue(source.contains("DATABASE_VERSION = 4"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS telegram_trip_completion_receipt"))
        assertTrue(source.contains("setWriteAheadLoggingEnabled(true)"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS telegram_outbox"))
        assertTrue(source.contains("waits_for_summary_key TEXT"))
        assertTrue(source.contains("ALTER TABLE telegram_outbox ADD COLUMN waits_for_summary_key TEXT"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS telegram_runtime_state"))
        assertTrue(source.contains("main_import_complete"))
        assertTrue(storeSource.contains("TelegramDatabaseHelper.MAX_PENDING"))
        assertTrue(storeSource.contains("created_at_ms < ? AND attempt_count > 0"))
        assertTrue(storeSource.contains("ORDER BY id"))
        assertTrue(storeSource.contains("db.beginTransactionNonExclusive()"))
        assertTrue(storeSource.contains("check(copiedCount == snapshot.outbox.size)"))
        assertTrue(storeSource.contains("snapshot.provenEmpty"))
        assertTrue(storeSource.contains("exactSnapshot(db, snapshot, nowMs)"))
        assertTrue(storeSource.contains("snapshot.completedImportCleanupVerified("))
        assertTrue(storeSource.contains("marker.importedStatePresent"))
        assertTrue(storeSource.contains("sidecarVerified = true"))
        assertTrue(storeSource.contains("cleanupVerified = cleanupVerified"))
        assertTrue(storeSource.contains("TelegramEventState.fromJsonOrNull(state)"))
        assertTrue(storeSource.contains("fun verifyRuntimeState()"))
        assertTrue(storeSource.contains("TelegramEventState.fromJsonOrNull(cursor.getString(0))"))
        assertFalse(storeSource.contains("VACUUM", ignoreCase = true))
    }

    @Test
    fun mainSnapshotIsTransactionalAndCleanupRequiresVerification() {
        val store = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()
        val snapshot = store.substringAfter("fun readLegacyTelegramSnapshot")
            .substringBefore("fun cleanupLegacyTelegramStorage")
        val cleanup = store.substringAfter("fun cleanupLegacyTelegramStorage")
            .substringBefore("override fun ensureInfluxCursors")

        assertTrue(snapshot.contains("db.beginTransaction()"))
        assertTrue(snapshot.contains("telegram_outbox"))
        assertTrue(snapshot.contains("telegram_runtime_state"))
        assertTrue(snapshot.contains("runtimeStateRowPresent"))
        assertTrue(snapshot.contains("runtimeStatePresent = runtimeStateRowPresent"))
        assertTrue(snapshot.contains("LIMIT ?"))
        assertTrue(cleanup.contains("if (!sidecarCommitVerified || !snapshot.validForImport) return false"))
        assertTrue(cleanup.contains("migration.cleanupVerified"))
        assertTrue(cleanup.contains("db.setTransactionSuccessful()"))
        assertTrue(cleanup.contains("legacyTableRowCount(db, \"telegram_outbox\") != 0L"))
        assertTrue(cleanup.contains("legacyTableRowCount(db, \"telegram_runtime_state\") != 0L"))
        assertFalse(store.contains("fun commitTelegramEvents"))
        assertFalse(store.contains("fun markTelegramDelivered"))
        assertFalse(store.contains("fun telegramRuntimeState"))
    }

    @Test
    fun migrationChecksDurableMarkerBeforeMainAndRetriesWithAFreshSidecarHandle() {
        val app = sourceFile("com/bydcollector/collector/BydCollectorApplication.kt").readText()
        val sidecar = sourceFile("com/bydcollector/collector/data/local/TelegramStore.kt").readText()
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()
        val initialize = app.substringAfter("private fun initializeTelegramStorage")
            .substringBefore("private fun markTelegramStorageFailure")
        val import = sidecar.substringAfter("fun importLegacySnapshot")
            .substringBefore("fun migrateFromMain")

        assertInOrder(
            initialize,
            "sidecar.isMainImportComplete()",
            "mainStore.readLegacyTelegramSnapshot()",
            "snapshot.migrationDecision("
        )
        assertInOrder(
            import,
            "mainImportMarker(db)",
            "if (!snapshot.validForImport"
        )
        assertTrue(initialize.contains("TelegramLegacyMigrationDecision.RETRY_UNKNOWN_READ"))
        assertTrue(initialize.contains("TelegramLegacyMigrationDecision.IMPORT_PROVEN_DATA"))
        assertTrue(initialize.contains("TelegramLegacyMigrationDecision.FAIL_PROVEN_MISSING"))
        assertTrue(initialize.contains("telegramLegacyMigrationUnresolved = true"))
        assertTrue(initialize.contains("settings.setTelegramLegacyMigrationRequired(true)"))
        assertTrue(initialize.contains("Legacy Telegram data remains in an archived Main database"))
        assertTrue(initialize.contains("settings.setTelegramLegacyMigrationRequired(false)"))
        assertTrue(initialize.contains("TelegramLegacySnapshot("))
        assertTrue(initialize.contains("if (error is InterruptedException) throw error"))
        assertTrue(initialize.contains("if (migration.cleanupVerified)"))
        assertInOrder(
            initialize,
            "settings.setTelegramLegacyMigrationRequired(false)",
            "mainStore.cleanupLegacyTelegramStorage(snapshot, migration)",
            "telegramLegacyMigrationUnresolved = false"
        )
        val afterCleanup = initialize.substringAfter("mainStore.cleanupLegacyTelegramStorage(snapshot, migration)")
        assertFalse(afterCleanup.contains("settings.setTelegramLegacyMigrationRequired(true)"))
        assertFalse(afterCleanup.contains("markTelegramStorageFailure"))
        assertTrue(afterCleanup.contains("telegram_legacy_cleanup_deferred"))
        assertTrue(app.contains("internal fun reconcileTelegramStorage(mainStore: TelemetryStore): TelegramStore?"))
        assertInOrder(
            app.substringAfter("internal fun reconcileTelegramStorage"),
            "telegramStoreOrNull()?.let { return it }",
            "initializeTelegramStorage(mainStore)",
            "return telegramStoreOrNull()"
        )
        assertTrue(app.contains("telegramStore?.let {"))
        assertFalse(app.contains("runCatching { it.close() }"))
        assertTrue(app.contains("telegramStore = null"))
        assertTrue(app.contains("StorageFormatCutoverCoordinator.readMainPreflight(mainStore.databaseFile())"))
        assertInOrder(initialize, "sidecar.verifyRuntimeState()", "settings.setTelegramLegacyMigrationRequired(false)")
        assertTrue(settings.contains("KEY_TELEGRAM_LEGACY_MIGRATION_REQUIRED"))
        assertTrue(settings.contains("putBoolean(KEY_TELEGRAM_LEGACY_MIGRATION_REQUIRED, true)"))
        assertTrue(settings.contains("remove(KEY_TELEGRAM_LEGACY_MIGRATION_REQUIRED)"))
    }

    private fun sourceFile(path: String): File = listOf(
        File("src/main/kotlin/$path"),
        File("app/src/main/kotlin/$path")
    ).firstOrNull { it.isFile } ?: error("Missing source file: $path")

    private fun assertInOrder(source: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val current = source.indexOf(token, previous + 1)
            assertTrue(current > previous, "Missing or out-of-order token: $token")
            previous = current
        }
    }
}
