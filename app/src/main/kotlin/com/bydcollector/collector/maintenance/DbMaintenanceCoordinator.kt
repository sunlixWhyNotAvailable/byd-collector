package com.bydcollector.collector.maintenance

import android.content.Context
import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

class DbMaintenanceCoordinator(
    private val context: Context,
    private val settings: CollectorSettings,
    private val application: BydCollectorApplication,
    private val stopRuntime: (DbMaintenanceOperation) -> Unit,
    private val onStoreReopened: (TelemetryStore) -> Unit,
    private val closeDebugStore: () -> Unit,
    private val onDebugStoreReopened: (DirectDebugStore) -> Unit,
    private val beforeFileMaintenance: () -> Unit = {},
    private val drainRuntime: (DbMaintenanceOperation) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val cancelAllowed = AtomicBoolean(false)
    private val cancelLock = Any()
    private val drainProgressLock = Any()
    private val drainWarningsLock = Any()
    private val drainWarnings = mutableListOf<String>()
    private var activeDrainOperation: DbMaintenanceOperation? = null
    private var drainStartedAtMs = 0L
    private var drainStatusPublishedAtMs = 0L

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

    /** Cancellation checkpoint for the bounded drain work owned by the runtime callback. */
    fun checkDrainCancelled(operation: DbMaintenanceOperation) {
        checkCancelled(operation)
    }

    /** Reports actual drain progress without throwing into replay acknowledgement callbacks. */
    fun reportDrainProgress(
        operation: DbMaintenanceOperation,
        remaining: Long?,
        unitUk: String,
        unitEn: String,
        madeProgress: Boolean
    ) {
        val safeRemaining = remaining?.takeIf { it >= 0L }
        val safeUnitUk = unitUk.trim().take(MAX_DRAIN_UNIT_CHARS).ifBlank { "записів" }
        val safeUnitEn = unitEn.trim().take(MAX_DRAIN_UNIT_CHARS).ifBlank { "records" }
        runCatching {
            application.maintenanceDiagnostics.reportDrainProgress(safeRemaining, safeUnitEn, madeProgress)
        }
        persistDrainProgressStatus(operation, safeUnitUk, safeUnitEn)
    }

    /** Adds a visible warning for data intentionally left untouched by a compatibility drain. */
    fun recordDrainWarning(message: String) {
        val warning = message.trim().take(MAX_DRAIN_WARNING_CHARS)
        if (warning.isEmpty()) return
        val added = synchronized(drainWarningsLock) {
            if (running.get() && warning !in drainWarnings) {
                drainWarnings += warning
                true
            } else false
        }
        if (added) application.recordMaintenanceEvent("database_maintenance_warning", warning)
    }

    fun run(operation: DbMaintenanceOperation, restoreRuntime: () -> Unit): DbMaintenanceResult {
        val diagnostics = application.maintenanceDiagnostics
        if (!running.compareAndSet(false, true)) {
            application.recordMaintenanceEvent("database_maintenance_rejected", "operation=${operation.key} reason=already_running")
            return DbMaintenanceResult(false, "Database maintenance already running")
        }
        if (!tryClaim(operation)) {
            running.set(false)
            application.recordMaintenanceEvent("database_maintenance_rejected", "operation=${operation.key} reason=another_entrypoint")
            return DbMaintenanceResult(false, "Database maintenance already running in another entrypoint")
        }

        cancelRequested.set(false)
        synchronized(drainWarningsLock) { drainWarnings.clear() }
        var restored = false
        var skipRestore = false
        var terminalStatus = "error"
        runCatching { diagnostics.begin(operation.key, databasePathForDiagnostics(operation)) }
        return try {
            publish(operation, 1, cancelAvailable = true)
            stopAndDrainRuntime(operation, diagnostics)
            closeCancelWindowAndCheck(operation)
            publish(operation, 3, cancelAvailable = false)
            diagnostics.beginPhase("database_gate_wait")
            var gateEntered = false
            val result = try {
                application.tryWithExclusiveDatabaseMaintenance(operation, FILE_MAINTENANCE_GATE_TIMEOUT_MS) {
                    gateEntered = true
                    diagnostics.endPhase()
                    beforeFileMaintenance()
                    archive(operation)
                } ?: throw TerminalArchiveFailure("Database writers did not quiesce; refusing file maintenance")
            } catch (error: Throwable) {
                if (!gateEntered) diagnostics.failPhase(error)
                throw error
            }
            val resultWithDrainWarnings = mergeDrainWarnings(result)
            val resultWithAuditPending = markArchiveVerificationPending(resultWithDrainWarnings)
            resultWithAuditPending.warning?.let { warning ->
                application.recordMaintenanceEvent("database_maintenance_warning", "operation=${operation.key} warning=$warning")
            }
            publishRestoring(operation, resultWithAuditPending)
            diagnostics.runPhase("runtime_restore") { restoreRuntime() }
            restored = true
            publishComplete(operation, resultWithAuditPending)
            terminalStatus = if (resultWithAuditPending.ok) "success" else "failed"
            resultWithAuditPending
        } catch (cancelled: DbMaintenanceCancelled) {
            terminalStatus = "cancelled"
            publishCancelled(operation)
            DbMaintenanceResult(false, "Cancelled")
        } catch (error: TerminalArchiveFailure) {
            terminalStatus = "failed"
            skipRestore = true
            val message = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            diagnostics.error(error)
            publishError(operation, message)
            DbMaintenanceResult(false, message)
        } catch (error: RuntimeException) {
            terminalStatus = "failed"
            val message = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            diagnostics.error(error)
            publishError(operation, message)
            DbMaintenanceResult(false, message)
        } catch (error: Throwable) {
            terminalStatus = "fatal"
            diagnostics.error(error)
            throw error
        } finally {
            try {
                if (!restored && !skipRestore) {
                    runCatching { diagnostics.runPhase("runtime_restore") { restoreRuntime() } }
                        .onFailure {
                            terminalStatus = "restore_failed"
                            diagnostics.error(it, "runtime restore request")
                            publishError(operation, "${it::class.java.simpleName}: ${it.message ?: "restore failed"}")
                        }
                }
            } finally {
                setCancelAvailable(false)
                running.set(false)
                runCatching { diagnostics.finish(terminalStatus) }
                releaseClaim(operation)
            }
        }
    }

    private fun stopAndDrainRuntime(
        operation: DbMaintenanceOperation,
        diagnostics: MaintenanceDiagnostics
    ) {
        if (operation == DbMaintenanceOperation.ARCHIVE) {
            // Main replay can write Trips; hold the file barrier through its final drain.
            diagnostics.beginPhase("trips_file_barrier_wait")
            var tripsBarrierEntered = false
            try {
                application.tripsFileOperationLock.withLock {
                    tripsBarrierEntered = true
                    diagnostics.endPhase()
                    diagnostics.runPhase("runtime_stop_callback") { stopRuntime(operation) }
                    drainRuntimeWithProgress(operation, diagnostics)
                }
            } catch (error: Throwable) {
                if (!tripsBarrierEntered) diagnostics.failPhase(error)
                throw error
            }
        } else {
            diagnostics.runPhase("runtime_stop_callback") { stopRuntime(operation) }
            drainRuntimeWithProgress(operation, diagnostics)
        }
    }

    private fun drainRuntimeWithProgress(
        operation: DbMaintenanceOperation,
        diagnostics: MaintenanceDiagnostics
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        synchronized(drainProgressLock) {
            activeDrainOperation = operation
            drainStartedAtMs = startedAt
            drainStatusPublishedAtMs = startedAt
        }
        diagnostics.beginPhase("runtime_tail_drain")
        try {
            publish(
                operation,
                DRAIN_STEP_INDEX,
                cancelAvailable = true,
                messageUk = "${operation.stepsUk[DRAIN_STEP_INDEX - 1]}: залишилося невідомо; минуло 0 с",
                messageEn = "${operation.stepsEn[DRAIN_STEP_INDEX - 1]}: remaining unknown; elapsed 0s"
            )
            synchronized(drainProgressLock) { drainStatusPublishedAtMs = SystemClock.elapsedRealtime() }
            checkDrainCancelled(operation)
            drainRuntime(operation)
            checkDrainCancelled(operation)
            diagnostics.endPhase()
        } catch (cancelled: DbMaintenanceCancelled) {
            diagnostics.endPhase("cancelled")
            throw cancelled
        } catch (error: Throwable) {
            diagnostics.failPhase(error)
            throw error
        } finally {
            synchronized(drainProgressLock) {
                if (activeDrainOperation == operation) activeDrainOperation = null
            }
        }
    }

    private fun persistDrainProgressStatus(
        operation: DbMaintenanceOperation,
        unitUk: String,
        unitEn: String
    ) {
        val now = SystemClock.elapsedRealtime()
        synchronized(drainProgressLock) {
            if (activeDrainOperation != operation) return
            if (now - drainStatusPublishedAtMs < DRAIN_STATUS_MIN_INTERVAL_MS) return

            val status = runCatching { settings.dbMaintenanceStatus() }.getOrNull() ?: return
            if (!status.running || status.operation != operation || status.stepIndex != DRAIN_STEP_INDEX) return

            val snapshot = runCatching { application.maintenanceDiagnostics.cachedSnapshot() }.getOrNull()
            val remaining = snapshot?.remainingCount
            val elapsedSeconds = ((now - drainStartedAtMs).coerceAtLeast(0L) / 1_000L)
            val ukRemaining = remaining?.let { "$it ${unitUk.ifBlank { "записів" }}" } ?: "невідомо"
            val enRemaining = remaining?.let { "$it ${unitEn.ifBlank { "records" }}" } ?: "unknown"
            val messageUk = "${operation.stepsUk[DRAIN_STEP_INDEX - 1]}: залишилося $ukRemaining; минуло $elapsedSeconds с"
            val messageEn = "${operation.stepsEn[DRAIN_STEP_INDEX - 1]}: remaining $enRemaining; elapsed ${elapsedSeconds}s"
            drainStatusPublishedAtMs = now
            runCatching {
                settings.setDbMaintenanceStatus(
                    status.copy(messageUk = messageUk, messageEn = messageEn),
                    synchronous = true
                )
            }
        }
    }

    private fun mergeDrainWarnings(result: DbMaintenanceResult): DbMaintenanceResult {
        val warnings = synchronized(drainWarningsLock) {
            (listOfNotNull(result.warning?.takeIf { it.isNotBlank() }) + drainWarnings).distinct()
        }
        return if (warnings.isEmpty()) result else result.copy(warning = warnings.joinToString("; "))
    }

    private fun drainWarningMessage(): String? = synchronized(drainWarningsLock) {
        drainWarnings.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun markArchiveVerificationPending(result: DbMaintenanceResult): DbMaintenanceResult {
        if (!result.ok || result.archivePath.isNullOrBlank()) return result
        return try {
            settings.setCutoverArchiveStoragePending(true)
            result
        } catch (error: RuntimeException) {
            application.maintenanceDiagnostics.error(error, "archive verification pending flag")
            val warning = "Archive verification could not be scheduled; raw archive preserved"
            result.copy(warning = listOfNotNull(result.warning, warning).distinct().joinToString("; "))
        }
    }

    private fun databasePathForDiagnostics(operation: DbMaintenanceOperation): String? = runCatching {
        when (operation) {
            DbMaintenanceOperation.ARCHIVE -> context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
            DbMaintenanceOperation.DEBUG_ARCHIVE -> DirectDebugDatabaseResolver.databaseFile(context)
        }.absolutePath
    }.getOrNull()

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
            application.maintenanceDiagnostics.runPhase("source_format_detection") {
                StorageFormatCutoverCoordinator.detectMain(databaseFile)
            }
        }
            .getOrElse { warnings += inspectionWarning("format", it); StorageFormat.UNKNOWN }
        if (sourceFormat == StorageFormat.UNKNOWN) warnings += inspectionWarning("format")
        publish(operation, 3)
        if (!runCatching {
                application.maintenanceDiagnostics.runPhase("old_database_checkpoint") {
                    StorageFormatCutoverCoordinator.checkpointDatabase(databaseFile)
                }
            }.getOrDefault(false)) {
            warnings += inspectionWarning("checkpoint")
        }
        application.maintenanceDiagnostics.runPhase("old_database_close") {
            application.closeTelemetryStoreForMaintenance()
        }
        val sourceNames = sourceNames(databaseFile)
        check(databaseFile.name in sourceNames) { "Main database source is not preservable after close" }

        publish(operation, 4)
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
        if (!runCatching {
                application.maintenanceDiagnostics.runPhase("persist_cutover_journal") {
                    settings.setStorageCutoverJournal(journal)
                }
            }.getOrDefault(false)) {
            reopenMainAndVerifyRestored(databaseFile)
            throw TerminalArchiveFailure("Cannot persist database cutover journal before archive")
        }
        val archive = application.maintenanceDiagnostics.runPhase("archive_move_source_files") {
            DatabaseArchiveManager.archive(databaseFile, archiveRoot, archiveTimestamp).also {
                if (!it.ok) application.maintenanceDiagnostics.endPhase("negative_result")
            }
        }
        if (!archive.ok) {
            if (!archive.rollbackOk || !databaseFile.exists()) {
                throw TerminalArchiveFailure("Database archive failed and original database was not restored: ${archive.error ?: "unknown"}")
            }
            if (!application.maintenanceDiagnostics.runPhase("rollback_verify_source_files_restored") {
                    sourceSetRestored(databaseFile, sourceNames)
                }) {
                throw TerminalArchiveFailure("Database archive failed and original database was not restored: ${archive.error ?: "unknown"}")
            }
            reopenMainAndVerifyRestored(databaseFile, manual = true)
            if (!runCatching {
                    application.maintenanceDiagnostics.runPhase("rollback_clear_cutover_journal") {
                        settings.clearStorageCutoverJournal()
                    }
                }.getOrDefault(false)) {
                throw TerminalArchiveFailure("Cannot clear database cutover journal after archive failure")
            }
            error(archive.error ?: "Database archive failed")
        }
        if (!application.maintenanceDiagnostics.runPhase("verify_archived_source_set") {
                exactArchiveSourceSet(databaseFile, sourceNames, archive)
            }) {
            throw TerminalArchiveFailure("Database archive did not preserve the exact source file set")
        }

        publish(operation, 5)
        val createFailure = runCatching {
            val newStore = application.maintenanceDiagnostics.runPhase("new_database_create") {
                check(settings.setStorageCutoverJournal(
                    journal.copy(phase = PHASE_CREATING)
                )) { "Cannot persist database creation journal" }
                val created = application.reopenTelemetryStoreForMaintenance()
                application.telegramStorageError()?.let { warnings += telegramStorageWarning(it) }
                onStoreReopened(created)
                created
            }
            application.maintenanceDiagnostics.runPhase("new_database_quick_check") {
                publish(operation, 6)
                check(settings.setStorageCutoverJournal(
                    journal.copy(phase = PHASE_VERIFYING)
                )) { "Cannot persist database verification journal" }
                check(newStore.verifyWritableDatabase()) { "New database quick_check failed" }
            }
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
        runCatching {
            application.maintenanceDiagnostics.runPhase("rollback_close_failed_database") {
                application.closeTelemetryStoreForMaintenance()
            }
        }
        application.maintenanceDiagnostics.runPhase("rollback_mark_journal") { markRollbackPhase() }
        if (!application.maintenanceDiagnostics.runPhase("rollback_delete_new_database") {
                deleteExactNewDatabaseSet(databaseFile)
            }) {
            throw TerminalArchiveFailure("Cannot remove failed new database before restoring archive: ${cause.message ?: cause::class.java.simpleName}")
        }
        if (!application.maintenanceDiagnostics.runPhase("rollback_restore_archived_files") {
                DatabaseArchiveManager.restore(databaseFile, archive.movedFiles)
            }) {
            throw TerminalArchiveFailure("Database archive restore was incomplete after new database failure: ${cause.message ?: cause::class.java.simpleName}")
        }
        if (manual) reopenMainAndVerifyRestored(databaseFile) else reopenMainAndVerifyRestored(databaseFile, manual = false)
        if (!runCatching {
                application.maintenanceDiagnostics.runPhase("rollback_clear_journal") {
                    settings.clearStorageCutoverJournal()
                }
            }.getOrDefault(false)) {
            throw TerminalArchiveFailure("Cannot clear database rollback journal after restore")
        }
        application.maintenanceDiagnostics.runPhase("rollback_remove_empty_archive_directory") {
            archive.archiveDirectory.takeIf { it.listFiles().orEmpty().isEmpty() }?.delete()
        }
        throw RuntimeException(cause.message ?: "New database verification failed", cause)
    }

    private fun reopenMainAndVerifyRestored(databaseFile: File, manual: Boolean) {
        val format = runCatching {
            application.maintenanceDiagnostics.runPhase("rollback_detect_restored_database_format") {
                StorageFormatCutoverCoordinator.detectMain(databaseFile)
            }
        }
            .getOrDefault(StorageFormat.UNKNOWN)
        if (!manual && format != StorageFormat.LEGACY_V1 && format != StorageFormat.COMPACT_V2) {
            throw TerminalArchiveFailure("Restored database format is not recognized")
        }
        val restoredStore = runCatching {
            application.maintenanceDiagnostics.runPhase("rollback_reopen_main_database") {
                application.reopenTelemetryStoreForMaintenance()
            }
        }
            .getOrElse { error ->
                throw TerminalArchiveFailure("Restored database could not be reopened: ${error.message ?: error::class.java.simpleName}")
            }
        val verified = runCatching {
            application.maintenanceDiagnostics.runPhase("rollback_verify_main_database") {
                restoredStore.verifyWritableDatabase()
            }
        }.getOrDefault(false)
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
            application.maintenanceDiagnostics.runPhase("source_format_detection") {
                StorageFormatCutoverCoordinator.detectDebug(databaseFile)
            }
        }
            .getOrElse { warnings += inspectionWarning("format", it); StorageFormat.UNKNOWN }
        check(sourceFormat != StorageFormat.ABSENT) { "Debug database does not exist" }
        publish(operation, 3)
        if (!runCatching {
                application.maintenanceDiagnostics.runPhase("old_database_checkpoint") {
                    StorageFormatCutoverCoordinator.checkpointDatabase(databaseFile)
                }
            }.getOrDefault(false)) {
            warnings += inspectionWarning("checkpoint")
        }
        application.maintenanceDiagnostics.runPhase("old_database_close") { closeDebugStore() }
        val sourceNames = sourceNames(databaseFile)
        check(databaseFile.name in sourceNames) { "Debug database source is not preservable after close" }

        publish(operation, 4)
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
        if (!runCatching {
                application.maintenanceDiagnostics.runPhase("persist_cutover_journal") {
                    settings.setStorageCutoverJournal(journal)
                }
            }.getOrDefault(false)) {
            reopenDebugAndVerifyRestored(databaseFile)
            throw TerminalArchiveFailure("Cannot persist debug database cutover journal before archive")
        }
        val archive = application.maintenanceDiagnostics.runPhase("archive_move_source_files") {
            DatabaseArchiveManager.archive(databaseFile, archiveRoot, archiveTimestamp).also {
                if (!it.ok) application.maintenanceDiagnostics.endPhase("negative_result")
            }
        }
        if (!archive.ok) {
            if (!archive.rollbackOk || !databaseFile.exists()) {
                throw TerminalArchiveFailure("Debug database archive failed and original database was not restored: ${archive.error ?: "unknown"}")
            }
            if (!application.maintenanceDiagnostics.runPhase("rollback_verify_source_files_restored") {
                    sourceSetRestored(databaseFile, sourceNames)
                }) {
                throw TerminalArchiveFailure("Debug database archive failed and original database was not restored: ${archive.error ?: "unknown"}")
            }
            reopenDebugAndVerifyRestored(databaseFile, manual = true)
            if (!runCatching {
                    application.maintenanceDiagnostics.runPhase("rollback_clear_cutover_journal") {
                        settings.clearStorageCutoverJournal()
                    }
                }.getOrDefault(false)) {
                throw TerminalArchiveFailure("Cannot clear debug database cutover journal after archive failure")
            }
            error(archive.error ?: "Debug database archive failed")
        }
        if (!application.maintenanceDiagnostics.runPhase("verify_archived_source_set") {
                exactArchiveSourceSet(databaseFile, sourceNames, archive)
            }) {
            throw TerminalArchiveFailure("Debug database archive did not preserve the exact source file set")
        }

        publish(operation, 5)
        val createFailure = runCatching {
            val newStore = application.maintenanceDiagnostics.runPhase("new_database_create") {
                check(settings.setStorageCutoverJournal(
                    journal.copy(phase = PHASE_CREATING)
                )) { "Cannot persist debug database creation journal" }
                reopenDebugAndRebind()
            }
            application.maintenanceDiagnostics.runPhase("new_database_quick_check") {
                publish(operation, 6)
                check(settings.setStorageCutoverJournal(
                    journal.copy(phase = PHASE_VERIFYING)
                )) { "Cannot persist debug database verification journal" }
                check(newStore.verifyWritableDatabase()) { "New debug database quick_check failed" }
            }
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
        runCatching {
            application.maintenanceDiagnostics.runPhase("rollback_close_failed_database") { closeDebugStore() }
        }
        application.maintenanceDiagnostics.runPhase("rollback_mark_journal") { markRollbackPhase() }
        if (!application.maintenanceDiagnostics.runPhase("rollback_delete_new_database") {
                deleteExactNewDatabaseSet(createdDatabaseFile)
            }) {
            throw TerminalArchiveFailure("Cannot remove failed new debug database before restoring archive: ${cause.message ?: cause::class.java.simpleName}")
        }
        if (!application.maintenanceDiagnostics.runPhase("rollback_restore_archived_files") {
                DatabaseArchiveManager.restore(sourceDatabaseFile, archive.movedFiles)
            }) {
            throw TerminalArchiveFailure("Debug database archive restore was incomplete after new database failure: ${cause.message ?: cause::class.java.simpleName}")
        }
        if (manual) reopenDebugAndVerifyRestored(sourceDatabaseFile) else reopenDebugAndVerifyRestored(sourceDatabaseFile, manual = false)
        if (!runCatching {
                application.maintenanceDiagnostics.runPhase("rollback_clear_journal") {
                    settings.clearStorageCutoverJournal()
                }
            }.getOrDefault(false)) {
            throw TerminalArchiveFailure("Cannot clear debug rollback journal after restore")
        }
        application.maintenanceDiagnostics.runPhase("rollback_remove_empty_archive_directory") {
            archive.archiveDirectory.takeIf { it.listFiles().orEmpty().isEmpty() }?.delete()
        }
        throw RuntimeException(cause.message ?: "New debug database verification failed", cause)
    }

    private fun reopenDebugAndVerifyRestored(databaseFile: File, manual: Boolean) {
        val format = runCatching {
            application.maintenanceDiagnostics.runPhase("rollback_detect_restored_database_format") {
                StorageFormatCutoverCoordinator.detectDebug(databaseFile)
            }
        }
            .getOrDefault(StorageFormat.UNKNOWN)
        if (!manual && (format == StorageFormat.ABSENT || !application.maintenanceDiagnostics.runPhase("rollback_verify_restored_database_file") {
                verifyWritableDatabaseFile(databaseFile)
            })) {
            throw TerminalArchiveFailure("Restored debug database integrity check failed")
        }
        val restoredStore = runCatching {
            application.maintenanceDiagnostics.runPhase("rollback_reopen_debug_database") {
                DirectDebugStore(context)
            }
        }
            .getOrElse { error ->
                throw TerminalArchiveFailure("Restored debug database could not be reopened: ${error.message ?: error::class.java.simpleName}")
            }
        val verified = runCatching {
            application.maintenanceDiagnostics.runPhase("rollback_verify_debug_database") {
                restoredStore.verifyWritableDatabase()
            }
        }.getOrDefault(false)
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

    private fun publish(
        operation: DbMaintenanceOperation,
        step: Int,
        cancelAvailable: Boolean = false,
        messageUk: String? = null,
        messageEn: String? = null
    ) {
        setCancelAvailable(cancelAvailable)
        settings.setDbMaintenanceStatus(
            DbMaintenanceRuntimeStatus(
                operation = operation,
                running = true,
                completed = false,
                stepIndex = step,
                stepCount = operation.stepsUk.size,
                messageUk = messageUk ?: operation.stepsUk.getOrElse(step - 1) { "" },
                messageEn = messageEn ?: operation.stepsEn.getOrElse(step - 1) { "" },
                cancelAvailable = cancelAvailable
            ),
            synchronous = true
        )
    }

    private fun publishRestoring(operation: DbMaintenanceOperation, result: DbMaintenanceResult) {
        settings.setDbMaintenanceStatus(
            DbMaintenanceRuntimeStatus(
                operation = operation,
                running = true,
                completed = false,
                stepIndex = operation.stepsUk.size,
                stepCount = operation.stepsUk.size,
                messageUk = operation.stepsUk.last(),
                messageEn = operation.stepsEn.last(),
                warning = result.warning,
                archivePath = result.archivePath,
                cancelAvailable = false
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
                warning = status.warning ?: drainWarningMessage(),
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
                warning = status.warning ?: drainWarningMessage(),
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
        private val activeOperationRef = AtomicReference<DbMaintenanceOperation?>(null)
        internal fun currentOperation(): DbMaintenanceOperation? = activeOperationRef.get()
        internal fun tryClaim(operation: DbMaintenanceOperation): Boolean = activeOperationRef.compareAndSet(null, operation)
        internal fun releaseClaim(operation: DbMaintenanceOperation) { activeOperationRef.compareAndSet(operation, null) }
        private const val FILE_MAINTENANCE_GATE_TIMEOUT_MS = 2_000L
        private const val DRAIN_STEP_INDEX = 2
        private const val DRAIN_STATUS_MIN_INTERVAL_MS = 1_000L
        private const val MAX_DRAIN_UNIT_CHARS = 48
        private const val MAX_DRAIN_WARNING_CHARS = 512
        private const val PHASE_ARCHIVING = "ARCHIVING"
        private const val PHASE_CREATING = "CREATING"
        private const val PHASE_VERIFYING = "VERIFYING"
        private const val PHASE_ROLLBACK = "ROLLBACK"
    }
}

private class TerminalArchiveFailure(message: String) : RuntimeException(message)
private class DbMaintenanceCancelled(operation: DbMaintenanceOperation) :
    InterruptedException("Database maintenance cancelled: ${operation.key}")
