package com.bydcollector.collector.maintenance

import android.content.Context
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.service.CollectorSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class DbMaintenanceCoordinator(
    private val context: Context,
    private val settings: CollectorSettings,
    private val storeProvider: () -> TelemetryStore,
    private val debugStoreProvider: () -> DirectDebugStore,
    private val application: BydCollectorApplication,
    private val stopRuntime: (DbMaintenanceOperation) -> Unit,
    private val onStoreReopened: (TelemetryStore) -> Unit,
    private val closeDebugStore: () -> Unit,
    private val onDebugStoreReopened: (DirectDebugStore) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val cancelAllowed = AtomicBoolean(false)
    private val cancelLock = Any()

    fun requestCancel(): Boolean {
        return synchronized(cancelLock) {
            if (!cancelAllowed.get()) {
                false
            } else {
                cancelRequested.set(true)
                true
            }
        }
    }

    fun run(operation: DbMaintenanceOperation, restoreRuntime: () -> Unit): DbMaintenanceResult {
        if (!running.compareAndSet(false, true)) {
            return DbMaintenanceResult(false, "Database maintenance already running")
        }

        cancelRequested.set(false)
        var restored = false
        var skipRestore = false
        return try {
            publish(operation, 1, cancelAvailable = true)
            stopRuntime(operation)
            closeCancelWindowAndCheck(operation)
            publish(operation, 1, cancelAvailable = false)
            val result = archive(operation)
            publish(operation, operation.stepsUk.size)
            restoreRuntime()
            restored = true
            publishComplete(operation, result)
            result
        } catch (cancelled: DbMaintenanceCancelled) {
            publishCancelled(operation)
            DbMaintenanceResult(false, "Cancelled")
        } catch (error: TerminalArchiveFailure) {
            skipRestore = true
            val message = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            publishError(operation, message)
            DbMaintenanceResult(false, message)
        } catch (error: RuntimeException) {
            val message = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            publishError(operation, message)
            DbMaintenanceResult(false, message)
        } finally {
            if (!restored && !skipRestore) {
                runCatching { restoreRuntime() }
                    .onFailure { publishError(operation, "${it::class.java.simpleName}: ${it.message ?: "restore failed"}") }
            }
            setCancelAvailable(false)
            running.set(false)
        }
    }

    private fun archive(operation: DbMaintenanceOperation): DbMaintenanceResult = when (operation) {
        DbMaintenanceOperation.ARCHIVE -> archiveMain(operation)
        DbMaintenanceOperation.DEBUG_ARCHIVE -> archiveDebug(operation)
    }

    private fun archiveMain(operation: DbMaintenanceOperation): DbMaintenanceResult {
        check(settings.storageCutoverJournal() == null) { "Database cutover recovery is pending" }
        val store = storeProvider()
        val databaseFile = store.databaseFile()
        val sourceFormat = StorageFormatCutoverCoordinator.detectMain(databaseFile)
        check(sourceFormat in setOf(StorageFormat.LEGACY_V1, StorageFormat.COMPACT_V2)) {
            "Main database format is not recognized"
        }
        store.checkpointForArchive()
        publish(operation, 2)
        application.closeTelemetryStoreForMaintenance()

        publish(operation, 3)
        val archiveRoot = File(context.filesDir, "db_archive")
        val archiveTimestamp = timestamp()
        val archiveDirectory = DatabaseArchiveManager.plannedArchiveDirectory(databaseFile, archiveRoot, archiveTimestamp)
        runCatching {
            settings.setStorageCutoverJournal(
                StorageCutoverJournal(
                    TelemetryDatabaseHelper.SCHEMA_FAMILY,
                    archiveDirectory.absolutePath,
                    PHASE_ARCHIVING,
                    sourceFormat
                )
            )
        }.onFailure {
            reopenMainAndVerifyRestored(databaseFile)
            throw it
        }
        val archive = DatabaseArchiveManager.archive(databaseFile, archiveRoot, archiveTimestamp)
        if (!archive.ok) {
            if (!archive.rollbackOk || !databaseFile.exists()) {
                throw TerminalArchiveFailure("Database archive failed and original database was not restored: ${archive.error ?: "unknown"}")
            }
            reopenMainAndVerifyRestored(databaseFile)
            settings.clearStorageCutoverJournal()
            error(archive.error ?: "Database archive failed")
        }

        publish(operation, 4)
        val createFailure = runCatching {
            settings.setStorageCutoverJournal(
                StorageCutoverJournal(
                    TelemetryDatabaseHelper.SCHEMA_FAMILY,
                    archiveDirectory.absolutePath,
                    PHASE_CREATING,
                    sourceFormat
                )
            )
            val newStore = application.reopenTelemetryStoreForMaintenance()
            onStoreReopened(newStore)
            publish(operation, 5)
            settings.setStorageCutoverJournal(
                StorageCutoverJournal(
                    TelemetryDatabaseHelper.SCHEMA_FAMILY,
                    archiveDirectory.absolutePath,
                    PHASE_VERIFYING,
                    sourceFormat
                )
            )
            check(newStore.verifyWritableDatabase()) { "New database quick_check failed" }
        }.exceptionOrNull()
        if (createFailure != null) rollbackMain(databaseFile, archive, createFailure)

        settings.clearStorageCutoverJournal()
        return DbMaintenanceResult(true, "Database archived", archive.archiveDirectory.absolutePath)
    }

    private fun rollbackMain(
        databaseFile: File,
        archive: DatabaseArchiveManager.ArchiveResult,
        cause: Throwable
    ): Nothing {
        runCatching { application.closeTelemetryStoreForMaintenance() }
        markRollbackPhase()
        if (!deleteExactNewDatabaseSet(databaseFile)) {
            throw TerminalArchiveFailure("Cannot remove failed new database before restoring archive: ${cause.message ?: cause::class.java.simpleName}")
        }
        if (!DatabaseArchiveManager.restore(databaseFile, archive.movedFiles)) {
            throw TerminalArchiveFailure("Database archive restore was incomplete after new database failure: ${cause.message ?: cause::class.java.simpleName}")
        }
        reopenMainAndVerifyRestored(databaseFile)
        archive.archiveDirectory.takeIf { it.listFiles().orEmpty().isEmpty() }?.delete()
        settings.clearStorageCutoverJournal()
        throw RuntimeException(cause.message ?: "New database verification failed", cause)
    }

    private fun reopenMainAndVerifyRestored(databaseFile: File) {
        val format = StorageFormatCutoverCoordinator.detectMain(databaseFile)
        if (format != StorageFormat.LEGACY_V1 && format != StorageFormat.COMPACT_V2) {
            throw TerminalArchiveFailure("Restored database format is not recognized")
        }
        val restoredStore = runCatching { application.reopenTelemetryStoreForMaintenance() }
            .getOrElse { error ->
                throw TerminalArchiveFailure("Restored database could not be reopened: ${error.message ?: error::class.java.simpleName}")
            }
        val verified = runCatching { restoredStore.verifyWritableDatabase() }.getOrDefault(false)
        if (!verified) {
            runCatching { application.closeTelemetryStoreForMaintenance() }
            throw TerminalArchiveFailure("Restored database quick_check failed")
        }
        onStoreReopened(restoredStore)
    }

    private fun archiveDebug(operation: DbMaintenanceOperation): DbMaintenanceResult {
        check(settings.storageCutoverJournal() == null) { "Database cutover recovery is pending" }
        val debugStore = debugStoreProvider()
        val databaseFile = debugStore.databaseFile()
        val sourceFormat = StorageFormatCutoverCoordinator.detectDebug(databaseFile)
        check(sourceFormat in setOf(StorageFormat.LEGACY_V1, StorageFormat.COMPACT_V2)) {
            "Debug database format is not recognized"
        }
        debugStore.checkpointForArchive()
        publish(operation, 2)
        closeDebugStore()

        publish(operation, 3)
        val archiveRoot = File(context.filesDir, "db_archive")
        val archiveTimestamp = timestamp()
        val archiveDirectory = DatabaseArchiveManager.plannedArchiveDirectory(databaseFile, archiveRoot, archiveTimestamp)
        runCatching {
            settings.setStorageCutoverJournal(
                StorageCutoverJournal(
                    DirectDebugDatabaseHelper.SCHEMA_FAMILY,
                    archiveDirectory.absolutePath,
                    PHASE_ARCHIVING,
                    sourceFormat
                )
            )
        }.onFailure {
            reopenDebugAndVerifyRestored(databaseFile)
            throw it
        }
        val archive = DatabaseArchiveManager.archive(databaseFile, archiveRoot, archiveTimestamp)
        if (!archive.ok) {
            if (!archive.rollbackOk || !databaseFile.exists()) {
                throw TerminalArchiveFailure("Debug database archive failed and original database was not restored: ${archive.error ?: "unknown"}")
            }
            reopenDebugAndVerifyRestored(databaseFile)
            settings.clearStorageCutoverJournal()
            error(archive.error ?: "Debug database archive failed")
        }

        publish(operation, 4)
        val createFailure = runCatching {
            settings.setStorageCutoverJournal(
                StorageCutoverJournal(
                    DirectDebugDatabaseHelper.SCHEMA_FAMILY,
                    archiveDirectory.absolutePath,
                    PHASE_CREATING,
                    sourceFormat
                )
            )
            val newStore = reopenDebugAndRebind()
            publish(operation, 5)
            settings.setStorageCutoverJournal(
                StorageCutoverJournal(
                    DirectDebugDatabaseHelper.SCHEMA_FAMILY,
                    archiveDirectory.absolutePath,
                    PHASE_VERIFYING,
                    sourceFormat
                )
            )
            check(newStore.verifyWritableDatabase()) { "New debug database quick_check failed" }
        }.exceptionOrNull()
        if (createFailure != null) rollbackDebug(databaseFile, archive, createFailure)

        settings.clearStorageCutoverJournal()
        return DbMaintenanceResult(true, "Debug database archived", archive.archiveDirectory.absolutePath)
    }

    private fun rollbackDebug(
        databaseFile: File,
        archive: DatabaseArchiveManager.ArchiveResult,
        cause: Throwable
    ): Nothing {
        runCatching { closeDebugStore() }
        markRollbackPhase()
        if (!deleteExactNewDatabaseSet(databaseFile)) {
            throw TerminalArchiveFailure("Cannot remove failed new debug database before restoring archive: ${cause.message ?: cause::class.java.simpleName}")
        }
        if (!DatabaseArchiveManager.restore(databaseFile, archive.movedFiles)) {
            throw TerminalArchiveFailure("Debug database archive restore was incomplete after new database failure: ${cause.message ?: cause::class.java.simpleName}")
        }
        reopenDebugAndVerifyRestored(databaseFile)
        archive.archiveDirectory.takeIf { it.listFiles().orEmpty().isEmpty() }?.delete()
        settings.clearStorageCutoverJournal()
        throw RuntimeException(cause.message ?: "New debug database verification failed", cause)
    }

    private fun reopenDebugAndVerifyRestored(databaseFile: File) {
        val format = StorageFormatCutoverCoordinator.detectDebug(databaseFile)
        if (format != StorageFormat.LEGACY_V1 && format != StorageFormat.COMPACT_V2) {
            throw TerminalArchiveFailure("Restored debug database format is not recognized")
        }
        val restoredStore = runCatching { DirectDebugStore(context) }
            .getOrElse { error ->
                throw TerminalArchiveFailure("Restored debug database could not be reopened: ${error.message ?: error::class.java.simpleName}")
            }
        val verified = runCatching { restoredStore.verifyWritableDatabase() }.getOrDefault(false)
        if (!verified) {
            runCatching { restoredStore.close() }
            throw TerminalArchiveFailure("Restored debug database quick_check failed")
        }
        onDebugStoreReopened(restoredStore)
    }

    private fun reopenDebugAndRebind(): DirectDebugStore {
        return DirectDebugStore(context).also(onDebugStoreReopened)
    }

    private fun markRollbackPhase() {
        val journal = checkNotNull(settings.storageCutoverJournal()) { "Database rollback journal is missing" }
        settings.setStorageCutoverJournal(journal.copy(phase = PHASE_ROLLBACK))
    }

    private fun deleteExactNewDatabaseSet(databaseFile: File): Boolean {
        val allowedName = databaseFile.name == TelemetryDatabaseHelper.DATABASE_NAME ||
            databaseFile.name == DirectDebugDatabaseHelper.DATABASE_NAME
        val expected = runCatching { context.getDatabasePath(databaseFile.name).canonicalFile }.getOrNull()
            ?: return false
        if (!allowedName || runCatching { databaseFile.canonicalFile }.getOrNull() != expected) return false
        var deleted = true
        DatabaseArchiveManager.sidecarFiles(expected).forEach { file ->
            if (file.exists() && !file.delete()) deleted = false
        }
        return deleted && DatabaseArchiveManager.sidecarFiles(expected).none { it.exists() }
    }

    private fun checkCancelled(operation: DbMaintenanceOperation) {
        if (cancelRequested.get()) throw DbMaintenanceCancelled(operation)
    }

    private fun closeCancelWindowAndCheck(operation: DbMaintenanceOperation) {
        synchronized(cancelLock) {
            cancelAllowed.set(false)
            checkCancelled(operation)
        }
    }

    private fun publish(operation: DbMaintenanceOperation, step: Int, cancelAvailable: Boolean = false) {
        setCancelAvailable(cancelAvailable)
        settings.setDbMaintenanceStatus(
            DbMaintenanceRuntimeStatus(
                operation = operation,
                running = true,
                completed = false,
                stepIndex = step,
                stepCount = operation.stepsUk.size,
                messageUk = operation.stepsUk.getOrElse(step - 1) { "" },
                messageEn = operation.stepsEn.getOrElse(step - 1) { "" },
                cancelAvailable = cancelAvailable
            ),
            synchronous = true
        )
    }

    private fun publishComplete(operation: DbMaintenanceOperation, result: DbMaintenanceResult) {
        settings.setDbMaintenanceStatus(
            DbMaintenanceRuntimeStatus(
                operation = operation,
                running = false,
                completed = true,
                stepIndex = operation.stepsUk.size,
                stepCount = operation.stepsUk.size,
                messageUk = operation.stepsUk.last(),
                messageEn = operation.stepsEn.last(),
                archivePath = result.archivePath,
                cancelAvailable = false
            ),
            synchronous = true
        )
    }

    private fun setCancelAvailable(value: Boolean) {
        synchronized(cancelLock) {
            cancelAllowed.set(value)
        }
    }

    private fun publishCancelled(operation: DbMaintenanceOperation) {
        val status = settings.dbMaintenanceStatus()
        settings.setDbMaintenanceStatus(
            status.copy(
                operation = operation,
                running = false,
                completed = false,
                stepCount = operation.stepsUk.size,
                error = "Cancelled",
                cancelAvailable = false
            ),
            synchronous = true
        )
    }

    private fun publishError(operation: DbMaintenanceOperation, error: String) {
        val status = settings.dbMaintenanceStatus()
        settings.setDbMaintenanceStatus(
            status.copy(
                operation = operation,
                running = false,
                completed = false,
                stepCount = operation.stepsUk.size,
                error = error,
                cancelAvailable = false
            ),
            synchronous = true
        )
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    companion object {
        private const val PHASE_ARCHIVING = "ARCHIVING"
        private const val PHASE_CREATING = "CREATING"
        private const val PHASE_VERIFYING = "VERIFYING"
        private const val PHASE_ROLLBACK = "ROLLBACK"
    }
}

private class TerminalArchiveFailure(message: String) : RuntimeException(message)
private class DbMaintenanceCancelled(operation: DbMaintenanceOperation) :
    RuntimeException("Database maintenance cancelled: ${operation.key}")
