package com.bydcollector.collector.maintenance

import android.content.Context
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.data.local.TelemetryStore
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
    private val application: BydCollectorApplication,
    private val stopRuntime: () -> Unit,
    private val onStoreReopened: (TelemetryStore) -> Unit
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
            stopRuntime()
            closeCancelWindowAndCheck(operation)
            publish(operation, 1, cancelAvailable = false)
            val result = when (operation) {
                DbMaintenanceOperation.COMPACT -> compact(operation)
                DbMaintenanceOperation.ARCHIVE -> archive(operation)
            }
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

    private fun compact(operation: DbMaintenanceOperation): DbMaintenanceResult {
        return storeProvider().compactRawHistory { step -> publish(operation, step) }
    }

    private fun archive(operation: DbMaintenanceOperation): DbMaintenanceResult {
        val store = storeProvider()
        val databaseFile = store.databaseFile()
        var reopened = false
        store.checkpointForArchive()
        publish(operation, 2)
        application.closeTelemetryStoreForMaintenance()
        try {
            publish(operation, 3)
            val archive = DatabaseArchiveManager.archive(
                databaseFile = databaseFile,
                archiveRoot = File(context.filesDir, "db_archive"),
                timestamp = timestamp()
            )
            if (!archive.ok) {
                if (!databaseFile.exists()) {
                    throw TerminalArchiveFailure("Database archive failed and original database was not restored: ${archive.error ?: "unknown"}")
                }
                reopenAndRebind()
                reopened = true
                error(archive.error ?: "Database archive failed")
            }
            publish(operation, 4)
            val newStore = application.reopenTelemetryStoreForMaintenance()
            reopened = true
            onStoreReopened(newStore)
            publish(operation, 5)
            check(newStore.verifyWritableDatabase()) { "New database quick_check failed" }
            return DbMaintenanceResult(true, "Database archived", archive.archiveDirectory.absolutePath)
        } finally {
            if (!reopened && databaseFile.exists()) {
                runCatching { reopenAndRebind() }
            }
        }
    }

    private fun reopenAndRebind() {
        val newStore = application.reopenTelemetryStoreForMaintenance()
        onStoreReopened(newStore)
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
}

private class TerminalArchiveFailure(message: String) : RuntimeException(message)
private class DbMaintenanceCancelled(operation: DbMaintenanceOperation) :
    RuntimeException("Database maintenance cancelled: ${operation.key}")
