package com.bydcollector.collector.maintenance

import android.content.Context
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.debug.DirectDebugDatabaseResolver
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.service.CollectorSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

class DbMaintenanceCoordinator(
    private val context: Context,
    private val settings: CollectorSettings,
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
            // Wait before the timed runtime stop, so a Trips file copy cannot cause its 2s timeout.
            application.tripsFileOperationLock.withLock { stopRuntime(operation) }
            closeCancelWindowAndCheck(operation)
            publish(operation, 1, cancelAvailable = false)
            val result = application.tryWithExclusiveDatabaseMaintenance(FILE_MAINTENANCE_GATE_TIMEOUT_MS) {
                archive(operation)
            } ?: throw TerminalArchiveFailure("Database writers did not quiesce; refusing file maintenance")
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
        val databaseFile = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
        val warnings = mutableListOf<String>()
        application.telegramStorageError()?.let { warnings += telegramStorageWarning(it) }
        check(databaseFile.isFile) { "Main database source is not preservable" }
        val sourceFormat = runCatching {
            val sourceFormat = StorageFormatCutoverCoordinator.detectMain(databaseFile)
            sourceFormat
        }
            .getOrElse { warnings += inspectionWarning("format", it); StorageFormat.UNKNOWN }
        if (sourceFormat == StorageFormat.UNKNOWN) warnings += inspectionWarning("format")
        if (!runCatching { verifyWritableDatabaseFile(databaseFile) }.getOrDefault(false)) {
            warnings += inspectionWarning("quick_check")
        }
        if (!runCatching { StorageFormatCutoverCoordinator.checkpointDatabase(databaseFile) }.getOrDefault(false)) {
            warnings += inspectionWarning("checkpoint")
        }
        publish(operation, 2)
        application.closeTelemetryStoreForMaintenance()
        val sourceNames = sourceNames(databaseFile)
        check(databaseFile.name in sourceNames) { "Main database source is not preservable after close" }

        publish(operation, 3)
        val archiveRoot = File(context.filesDir, "db_archive")
        val archiveTimestamp = timestamp()
        val archiveDirectory = DatabaseArchiveManager.plannedArchiveDirectory(databaseFile, archiveRoot, archiveTimestamp)
        val journal = StorageCutoverJournal(
            TelemetryDatabaseHelper.SCHEMA_FAMILY,
            archiveDirectory.absolutePath,
            PHASE_ARCHIVING,
            sourceFormat,
            manual = true,
            sourceNames = sourceNames,
            sourceDatabaseName = databaseFile.name,
            targetDatabaseName = databaseFile.name
        )
        if (!runCatching { settings.setStorageCutoverJournal(journal) }.getOrDefault(false)) {
            reopenMainAndVerifyRestored(databaseFile)
            throw TerminalArchiveFailure("Cannot persist database cutover journal before archive")
        }
        val archive = DatabaseArchiveManager.archive(databaseFile, archiveRoot, archiveTimestamp)
        if (!archive.ok) {
            if (!archive.rollbackOk || !databaseFile.exists()) {
                throw TerminalArchiveFailure("Database archive failed and original database was not restored: ${archive.error ?: "unknown"}")
            }
            if (!sourceSetRestored(databaseFile, sourceNames)) {
                throw TerminalArchiveFailure("Database archive failed and original database was not restored: ${archive.error ?: "unknown"}")
            }
            reopenMainAndVerifyRestored(databaseFile, manual = true)
            if (!runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)) {
                throw TerminalArchiveFailure("Cannot clear database cutover journal after archive failure")
            }
            error(archive.error ?: "Database archive failed")
        }
        if (!exactArchiveSourceSet(databaseFile, sourceNames, archive)) {
            throw TerminalArchiveFailure("Database archive did not preserve the exact source file set")
        }
        if (!runCatching { StorageFormatCutoverCoordinator.verifyArchivedSource(
            TelemetryDatabaseHelper.SCHEMA_FAMILY,
            databaseFile,
            archiveDirectory,
            sourceFormat
        ) }.getOrDefault(false)) {
            warnings += inspectionWarning("archived source")
        }

        publish(operation, 4)
        val createFailure = runCatching {
            check(settings.setStorageCutoverJournal(
                journal.copy(phase = PHASE_CREATING)
            )) { "Cannot persist database creation journal" }
            val newStore = application.reopenTelemetryStoreForMaintenance()
            application.telegramStorageError()?.let { warnings += telegramStorageWarning(it) }
            onStoreReopened(newStore)
            publish(operation, 5)
            check(settings.setStorageCutoverJournal(
                journal.copy(phase = PHASE_VERIFYING)
            )) { "Cannot persist database verification journal" }
            check(newStore.verifyWritableDatabase()) { "New database quick_check failed" }
        }.exceptionOrNull()
        if (createFailure != null) rollbackMain(databaseFile, archive, createFailure, manual = true)

        if (!runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)) {
            throw RuntimeException("Cannot clear database cutover journal after successful archive")
        }
        return DbMaintenanceResult(
            ok = true,
            message = "Database archived",
            archivePath = archive.archiveDirectory.absolutePath,
            warning = warnings.takeIf { it.isNotEmpty() }?.distinct()?.joinToString("; ")
        )
    }

    private fun verifyWritableDatabaseFile(databaseFile: File): Boolean {
        return StorageFormatCutoverCoordinator.verifyDatabaseQuickCheck(databaseFile)
    }

    private fun rollbackMain(
        databaseFile: File,
        archive: DatabaseArchiveManager.ArchiveResult,
        cause: Throwable,
        manual: Boolean = false
    ): Nothing {
        runCatching { application.closeTelemetryStoreForMaintenance() }
        markRollbackPhase()
        if (!deleteExactNewDatabaseSet(databaseFile)) {
            throw TerminalArchiveFailure("Cannot remove failed new database before restoring archive: ${cause.message ?: cause::class.java.simpleName}")
        }
        if (!DatabaseArchiveManager.restore(databaseFile, archive.movedFiles)) {
            throw TerminalArchiveFailure("Database archive restore was incomplete after new database failure: ${cause.message ?: cause::class.java.simpleName}")
        }
        if (manual) reopenMainAndVerifyRestored(databaseFile) else reopenMainAndVerifyRestored(databaseFile, manual = false)
        if (!runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)) {
            throw TerminalArchiveFailure("Cannot clear database rollback journal after restore")
        }
        archive.archiveDirectory.takeIf { it.listFiles().orEmpty().isEmpty() }?.delete()
        throw RuntimeException(cause.message ?: "New database verification failed", cause)
    }

    private fun reopenMainAndVerifyRestored(databaseFile: File, manual: Boolean) {
        val format = runCatching { StorageFormatCutoverCoordinator.detectMain(databaseFile) }
            .getOrDefault(StorageFormat.UNKNOWN)
        if (!manual && format != StorageFormat.LEGACY_V1 && format != StorageFormat.COMPACT_V2) {
            throw TerminalArchiveFailure("Restored database format is not recognized")
        }
        val restoredStore = runCatching { application.reopenTelemetryStoreForMaintenance() }
            .getOrElse { error ->
                throw TerminalArchiveFailure("Restored database could not be reopened: ${error.message ?: error::class.java.simpleName}")
            }
        val verified = runCatching { restoredStore.verifyWritableDatabase() }.getOrDefault(false)
        if (!verified && !manual) {
            runCatching { application.closeTelemetryStoreForMaintenance() }
            throw TerminalArchiveFailure("Restored database quick_check failed")
        }
        onStoreReopened(restoredStore)
    }

    private fun reopenMainAndVerifyRestored(databaseFile: File) {
        reopenMainAndVerifyRestored(databaseFile, manual = true)
    }

    private fun archiveDebug(operation: DbMaintenanceOperation): DbMaintenanceResult {
        check(settings.storageCutoverJournal() == null) { "Database cutover recovery is pending" }
        val databaseFile = DirectDebugDatabaseResolver.databaseFile(context)
        val warnings = mutableListOf<String>()
        check(databaseFile.isFile) { "Debug database source is not preservable" }
        val sourceFormat = runCatching {
            val sourceFormat = StorageFormatCutoverCoordinator.detectDebug(databaseFile)
            sourceFormat
        }
            .getOrElse { warnings += inspectionWarning("format", it); StorageFormat.UNKNOWN }
        check(sourceFormat != StorageFormat.ABSENT) { "Debug database does not exist" }
        val quickCheck = runCatching { verifyWritableDatabaseFile(databaseFile) }.getOrDefault(false)
        if (!quickCheck) warnings += inspectionWarning("quick_check")
        if (!runCatching { StorageFormatCutoverCoordinator.checkpointDatabase(databaseFile) }.getOrDefault(false)) {
            warnings += inspectionWarning("checkpoint")
        }
        publish(operation, 2)
        closeDebugStore()
        val sourceNames = sourceNames(databaseFile)
        check(databaseFile.name in sourceNames) { "Debug database source is not preservable after close" }

        publish(operation, 3)
        val archiveRoot = File(context.filesDir, "db_archive")
        val archiveTimestamp = timestamp()
        val archiveDirectory = DatabaseArchiveManager.plannedArchiveDirectory(databaseFile, archiveRoot, archiveTimestamp)
        val journal = StorageCutoverJournal(
            DirectDebugDatabaseHelper.SCHEMA_FAMILY,
            archiveDirectory.absolutePath,
            PHASE_ARCHIVING,
            sourceFormat,
            manual = true,
            sourceNames = sourceNames,
            sourceDatabaseName = databaseFile.name,
            targetDatabaseName = DirectDebugDatabaseHelper.DATABASE_NAME
        )
        if (!runCatching { settings.setStorageCutoverJournal(journal) }.getOrDefault(false)) {
            reopenDebugAndVerifyRestored(databaseFile)
            throw TerminalArchiveFailure("Cannot persist debug database cutover journal before archive")
        }
        val archive = DatabaseArchiveManager.archive(databaseFile, archiveRoot, archiveTimestamp)
        if (!archive.ok) {
            if (!archive.rollbackOk || !databaseFile.exists()) {
                throw TerminalArchiveFailure("Debug database archive failed and original database was not restored: ${archive.error ?: "unknown"}")
            }
            if (!sourceSetRestored(databaseFile, sourceNames)) {
                throw TerminalArchiveFailure("Debug database archive failed and original database was not restored: ${archive.error ?: "unknown"}")
            }
            reopenDebugAndVerifyRestored(databaseFile, manual = true)
            if (!runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)) {
                throw TerminalArchiveFailure("Cannot clear debug database cutover journal after archive failure")
            }
            error(archive.error ?: "Debug database archive failed")
        }
        if (!exactArchiveSourceSet(databaseFile, sourceNames, archive)) {
            throw TerminalArchiveFailure("Debug database archive did not preserve the exact source file set")
        }
        if (!runCatching { StorageFormatCutoverCoordinator.verifyArchivedSource(
            DirectDebugDatabaseHelper.SCHEMA_FAMILY,
            databaseFile,
            archiveDirectory,
            sourceFormat
        ) }.getOrDefault(false)) {
            warnings += inspectionWarning("archived source")
        }

        publish(operation, 4)
        val createFailure = runCatching {
            check(settings.setStorageCutoverJournal(
                journal.copy(phase = PHASE_CREATING)
            )) { "Cannot persist debug database creation journal" }
            val newStore = reopenDebugAndRebind()
            publish(operation, 5)
            check(settings.setStorageCutoverJournal(
                journal.copy(phase = PHASE_VERIFYING)
            )) { "Cannot persist debug database verification journal" }
            check(newStore.verifyWritableDatabase()) { "New debug database quick_check failed" }
        }.exceptionOrNull()
        if (createFailure != null) {
            rollbackDebug(
                sourceDatabaseFile = databaseFile,
                createdDatabaseFile = context.getDatabasePath(journal.targetDatabaseName),
                archive = archive,
                cause = createFailure,
                manual = true
            )
        }

        if (!runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)) {
            throw RuntimeException("Cannot clear debug database cutover journal after successful archive")
        }
        return DbMaintenanceResult(
            ok = true,
            message = "Secondary database archived",
            archivePath = archive.archiveDirectory.absolutePath,
            warning = warnings.takeIf { it.isNotEmpty() }?.distinct()?.joinToString("; ")
        )
    }

    private fun rollbackDebug(
        sourceDatabaseFile: File,
        createdDatabaseFile: File,
        archive: DatabaseArchiveManager.ArchiveResult,
        cause: Throwable,
        manual: Boolean = false
    ): Nothing {
        runCatching { closeDebugStore() }
        markRollbackPhase()
        if (!deleteExactNewDatabaseSet(createdDatabaseFile)) {
            throw TerminalArchiveFailure("Cannot remove failed new debug database before restoring archive: ${cause.message ?: cause::class.java.simpleName}")
        }
        if (!DatabaseArchiveManager.restore(sourceDatabaseFile, archive.movedFiles)) {
            throw TerminalArchiveFailure("Debug database archive restore was incomplete after new database failure: ${cause.message ?: cause::class.java.simpleName}")
        }
        if (manual) reopenDebugAndVerifyRestored(sourceDatabaseFile) else reopenDebugAndVerifyRestored(sourceDatabaseFile, manual = false)
        if (!runCatching { settings.clearStorageCutoverJournal() }.getOrDefault(false)) {
            throw TerminalArchiveFailure("Cannot clear debug rollback journal after restore")
        }
        archive.archiveDirectory.takeIf { it.listFiles().orEmpty().isEmpty() }?.delete()
        throw RuntimeException(cause.message ?: "New debug database verification failed", cause)
    }

    private fun reopenDebugAndVerifyRestored(databaseFile: File, manual: Boolean) {
        val format = runCatching { StorageFormatCutoverCoordinator.detectDebug(databaseFile) }
            .getOrDefault(StorageFormat.UNKNOWN)
        if (!manual && (format == StorageFormat.ABSENT || !verifyWritableDatabaseFile(databaseFile))) {
            throw TerminalArchiveFailure("Restored debug database integrity check failed")
        }
        val restoredStore = runCatching { DirectDebugStore(context) }
            .getOrElse { error ->
                throw TerminalArchiveFailure("Restored debug database could not be reopened: ${error.message ?: error::class.java.simpleName}")
            }
        val verified = runCatching { restoredStore.verifyWritableDatabase() }.getOrDefault(false)
        if (!verified && !manual) {
            runCatching { restoredStore.close() }
            throw TerminalArchiveFailure("Restored debug database quick_check failed")
        }
        onDebugStoreReopened(restoredStore)
    }

    private fun reopenDebugAndVerifyRestored(databaseFile: File) {
        reopenDebugAndVerifyRestored(databaseFile, manual = true)
    }

    private fun reopenDebugAndRebind(): DirectDebugStore {
        return DirectDebugStore(context).also(onDebugStoreReopened)
    }

    private fun markRollbackPhase() {
        val journal = checkNotNull(settings.storageCutoverJournal()) { "Database rollback journal is missing" }
        check(runCatching { settings.setStorageCutoverJournal(journal.copy(phase = PHASE_ROLLBACK)) }.getOrDefault(false)) {
            "Cannot persist database rollback journal"
        }
    }

    private fun deleteExactNewDatabaseSet(databaseFile: File): Boolean {
        val allowedName = databaseFile.name == TelemetryDatabaseHelper.DATABASE_NAME ||
            databaseFile.name in DirectDebugDatabaseHelper.DATABASE_NAMES
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
                warning = result.warning,
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

    private fun sourceNames(databaseFile: File): Set<String> =
        DatabaseArchiveManager.sidecarFiles(databaseFile)
            .filter { it.isFile }
            .map { it.name }
            .toSet()

    private fun sourceSetRestored(databaseFile: File, expectedNames: Set<String>): Boolean {
        if (databaseFile.name !in expectedNames) return false
        val actualNames = DatabaseArchiveManager.sidecarFiles(databaseFile)
            .filter { it.isFile }
            .map { it.name }
            .toSet()
        return actualNames == expectedNames
    }

    private fun exactArchiveSourceSet(
        databaseFile: File,
        expectedNames: Set<String>,
        archive: DatabaseArchiveManager.ArchiveResult
    ): Boolean {
        if (databaseFile.name !in expectedNames || archive.movedFiles.map { it.name }.toSet() != expectedNames) return false
        val archiveDirectory = runCatching { archive.archiveDirectory.canonicalFile }.getOrNull() ?: return false
        val movedFiles = archive.movedFiles.map { runCatching { it.canonicalFile }.getOrNull() }
        if (movedFiles.any { it == null || it.parentFile != archiveDirectory || !it.isFile }) return false
        val archivedNames = archiveDirectory.listFiles().orEmpty().map { it.name }.toSet()
        if (archivedNames != expectedNames) return false
        return DatabaseArchiveManager.sidecarFiles(databaseFile).none { it.exists() }
    }

    private fun inspectionWarning(kind: String, error: Throwable? = null): String {
        val detail = error?.let { ": ${it::class.java.simpleName}" }.orEmpty()
        return "Manual archive warning: $kind inspection failed$detail"
    }

    private fun telegramStorageWarning(detail: String): String =
        "Telegram storage migration warning: ${detail.take(300)}"

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    companion object {
        private const val FILE_MAINTENANCE_GATE_TIMEOUT_MS = 2_000L
        private const val PHASE_ARCHIVING = "ARCHIVING"
        private const val PHASE_CREATING = "CREATING"
        private const val PHASE_VERIFYING = "VERIFYING"
        private const val PHASE_ROLLBACK = "ROLLBACK"
    }
}

private class TerminalArchiveFailure(message: String) : RuntimeException(message)
private class DbMaintenanceCancelled(operation: DbMaintenanceOperation) :
    RuntimeException("Database maintenance cancelled: ${operation.key}")
