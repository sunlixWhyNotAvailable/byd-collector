package com.bydcollector.collector.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.bydcollector.collector.ha.HaConnectionOwnership
import com.bydcollector.collector.maintenance.DbMaintenanceOperation

object CollectorServiceController {
    fun start(context: Context, forceKeepAliveStatusCheck: Boolean = false) {
        val intent = CollectorService.startIntent(context, forceKeepAliveStatusCheck)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        context.startService(CollectorService.stopIntent(context))
    }

    fun shutdown(context: Context) {
        context.startService(CollectorService.shutdownIntent(context))
    }

    fun retryKeepAliveStop(context: Context, retryAttempt: Int) {
        val intent = CollectorService.keepAliveStopRetryIntent(context, retryAttempt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun startDebug(context: Context) {
        val intent = CollectorService.startDebugIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopDebug(context: Context) {
        context.startService(CollectorService.stopDebugIntent(context))
    }

    fun reconcileKeepAlive(context: Context, forceKeepAliveStatusCheck: Boolean = false) {
        val intent = CollectorService.keepAliveIntent(context, forceKeepAliveStatusCheck)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun startMqttExport(context: Context) {
        startOwnedChannel(context, CollectorService.mqttConnection, CollectorService.startMqttExportIntent(context))
    }

    fun reconcileDebug(context: Context) {
        val intent = CollectorService.reconcileDebugIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun reconcileMqttExport(context: Context) {
        startOwnedChannel(context, CollectorService.mqttConnection, CollectorService.reconcileMqttExportIntent(context))
    }

    fun stopMqttExport(context: Context) {
        stopOwnedChannel(context, CollectorService.mqttConnection, CollectorService.stopMqttExportIntent(context))
    }

    fun startInfluxExport(context: Context) {
        startOwnedChannel(context, CollectorService.influxConnection, CollectorService.startInfluxExportIntent(context))
    }

    fun reconcileInfluxExport(context: Context) {
        startOwnedChannel(context, CollectorService.influxConnection, CollectorService.reconcileInfluxExportIntent(context))
    }

    fun stopInfluxExport(context: Context) {
        stopOwnedChannel(context, CollectorService.influxConnection, CollectorService.stopInfluxExportIntent(context))
    }

    private fun startOwnedChannel(context: Context, owner: HaConnectionOwnership, intent: Intent) {
        if (owner.stopping) return
        val reserved = owner.reserve()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (error: RuntimeException) {
            if (reserved) owner.release()
            throw error
        }
    }

    private fun stopOwnedChannel(context: Context, owner: HaConnectionOwnership, intent: Intent) {
        owner.beginStop()
        try {
            context.startService(intent)
        } catch (error: RuntimeException) {
            owner.stopSubmissionFailed()
            throw error
        }
    }

    fun reconcileTelegram(context: Context) {
        val intent = CollectorService.reconcileTelegramIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun testTelegram(context: Context) {
        val intent = CollectorService.testTelegramIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun archiveDatabase(context: Context) {
        val intent = if (CollectorService.isRunning()) {
            CollectorService.archiveDatabaseIntent(context)
        } else {
            DatabaseMaintenanceService.archiveIntent(context, DbMaintenanceOperation.ARCHIVE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun archiveDebugDatabase(context: Context) {
        val intent = if (CollectorService.isRunning()) {
            CollectorService.archiveDebugDatabaseIntent(context)
        } else {
            DatabaseMaintenanceService.archiveIntent(context, DbMaintenanceOperation.DEBUG_ARCHIVE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun cancelDatabaseMaintenance(context: Context) {
        val intent = if (DatabaseMaintenanceService.isRunning()) {
            DatabaseMaintenanceService.cancelIntent(context)
        } else {
            CollectorService.cancelDatabaseMaintenanceIntent(context)
        }
        context.startService(intent)
    }

    fun reconcileArchiveStorage(context: Context) {
        val intent = CollectorService.reconcileArchiveStorageIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun deleteArchives(context: Context, ids: List<String>) {
        val intent = CollectorService.deleteArchivesIntent(context, ArrayList(ids))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
