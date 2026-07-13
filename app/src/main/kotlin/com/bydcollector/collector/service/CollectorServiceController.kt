package com.bydcollector.collector.service

import android.content.Context
import android.os.Build

object CollectorServiceController {
    fun start(context: Context) {
        val intent = CollectorService.startIntent(context)
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

    fun reconcileKeepAlive(context: Context) {
        val intent = CollectorService.keepAliveIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun startMqttExport(context: Context) {
        val intent = CollectorService.startMqttExportIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopMqttExport(context: Context) {
        context.startService(CollectorService.stopMqttExportIntent(context))
    }

    fun startInfluxExport(context: Context) {
        val intent = CollectorService.startInfluxExportIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopInfluxExport(context: Context) {
        context.startService(CollectorService.stopInfluxExportIntent(context))
    }

    fun archiveDatabase(context: Context) {
        val intent = CollectorService.archiveDatabaseIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun archiveDebugDatabase(context: Context) {
        val intent = CollectorService.archiveDebugDatabaseIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun cancelDatabaseMaintenance(context: Context) {
        context.startService(CollectorService.cancelDatabaseMaintenanceIntent(context))
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
