package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelegramLegacySnapshot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramSidecarStorageContractTest {
    @Test
    fun legacySnapshotRequiresPreservationForAnyDataOrUncertainRead() {
        assertFalse(TelegramLegacySnapshot().requiresPreservation)
        assertTrue(TelegramLegacySnapshot(runtimeStatePresent = true, runtimeStateValid = false).requiresPreservation)
        assertTrue(TelegramLegacySnapshot(readError = "read failed").requiresPreservation)
        assertTrue(TelegramLegacySnapshot(truncated = true).requiresPreservation)
    }

    @Test
    fun sidecarSchemaAndRetentionAreDurableAndBounded() {
        val helper = sourceFile("com/bydcollector/collector/data/local/TelegramDatabaseHelper.kt")
        val store = sourceFile("com/bydcollector/collector/data/local/TelegramStore.kt")
        val source = helper.readText()
        val storeSource = store.readText()

        assertTrue(source.contains("bydcollector_telegram.db"))
        assertTrue(source.contains("DATABASE_VERSION = 2"))
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
        assertTrue(storeSource.contains("snapshot.outbox.isEmpty() && snapshot.runtimeStateJson == null"))
        assertTrue(storeSource.contains("sidecarVerified = exact"))
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
        assertTrue(cleanup.contains("db.setTransactionSuccessful()"))
        assertTrue(cleanup.contains("legacyTableRowCount(db, \"telegram_outbox\") != 0L"))
        assertTrue(cleanup.contains("legacyTableRowCount(db, \"telegram_runtime_state\") != 0L"))
        assertFalse(store.contains("fun commitTelegramEvents"))
        assertFalse(store.contains("fun markTelegramDelivered"))
        assertFalse(store.contains("fun telegramRuntimeState"))
    }

    @Test
    fun migrationFailurePersistsAcrossMainArchiveAndRetriesWithAFreshSidecarHandle() {
        val app = sourceFile("com/bydcollector/collector/BydCollectorApplication.kt").readText()
        val settings = sourceFile("com/bydcollector/collector/service/CollectorSettings.kt").readText()

        assertTrue(app.contains("snapshot.requiresPreservation"))
        assertTrue(app.contains("telegramLegacyMigrationUnresolved = true"))
        assertTrue(app.contains("settings.setTelegramLegacyMigrationRequired(true)"))
        assertTrue(app.contains("settings.telegramLegacyMigrationRequired() || telegramLegacyMigrationUnresolved"))
        assertTrue(app.contains("Legacy Telegram data remains in an archived Main database"))
        assertTrue(app.contains("settings.setTelegramLegacyMigrationRequired(false)"))
        assertInOrder(
            app,
            "settings.setTelegramLegacyMigrationRequired(false)",
            "mainStore.cleanupLegacyTelegramStorage(snapshot, migration)",
            "telegramLegacyMigrationUnresolved = false"
        )
        assertTrue(app.contains("telegramStore?.let { runCatching { it.close() } }"))
        assertTrue(app.contains("telegramStore = null"))
        assertTrue(app.contains("StorageFormatCutoverCoordinator.readMainPreflight(mainStore.databaseFile())"))
        assertInOrder(app, "sidecar.verifyRuntimeState()", "settings.setTelegramLegacyMigrationRequired(false)")
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
