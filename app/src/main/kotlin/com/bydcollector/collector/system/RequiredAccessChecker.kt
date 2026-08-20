package com.bydcollector.collector.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.bydcollector.collector.BuildConfig

data class RequiredAccessRow(
    val key: String,
    val label: String,
    val enabled: Boolean,
    val detail: String
)

object RequiredAccessChecker {
    fun check(context: Context): List<RequiredAccessRow> {
        val appContext = context.applicationContext
        val storageEnabled = hasStorageReadAccess(appContext)
        val listenerEnabled = hasNotificationListenerAccess(appContext)
        val storageDetail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "MANAGE_EXTERNAL_STORAGE=${if (storageEnabled) "allow" else "deny"}"
        } else {
            "${Manifest.permission.READ_EXTERNAL_STORAGE}=${if (storageEnabled) "granted" else "denied"}"
        }
        return listOf(
            RequiredAccessRow(
                key = "storage",
                label = "Storage access",
                enabled = storageEnabled,
                detail = storageDetail
            ),
            RequiredAccessRow(
                key = "notification_listener",
                label = "Notification listener",
                enabled = listenerEnabled,
                detail = "${notificationListenerComponent(appContext).flattenToString()}=${if (listenerEnabled) "granted" else "denied"}"
            )
        )
    }

    fun hasMissingRequiredAccess(context: Context): Boolean {
        return check(context).any { !it.enabled }
    }

    fun missingShellGrantCommands(context: Context): List<String> {
        val commands = mutableListOf<String>()
        if (!hasStorageReadAccess(context)) {
            commands += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "appops set --uid ${BuildConfig.APPLICATION_ID} MANAGE_EXTERNAL_STORAGE allow"
            } else {
                "pm grant ${BuildConfig.APPLICATION_ID} android.permission.READ_EXTERNAL_STORAGE"
            }
        }
        if (!hasNotificationListenerAccess(context)) {
            commands += "cmd notification allow_listener ${notificationListenerComponent(context).flattenToString()}"
        }
        return commands
    }

    private fun hasStorageReadAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasNotificationListenerAccess(context: Context): Boolean {
        val enabledComponents = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                ENABLED_NOTIFICATION_LISTENERS
            )
        }.getOrNull() ?: return false
        val required = notificationListenerComponent(context)
        return enabledComponents.split(':').any { ComponentName.unflattenFromString(it) == required }
    }

    private fun notificationListenerComponent(context: Context): ComponentName {
        return ComponentName(context, CollectorNotificationListenerService::class.java)
    }

    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
}
