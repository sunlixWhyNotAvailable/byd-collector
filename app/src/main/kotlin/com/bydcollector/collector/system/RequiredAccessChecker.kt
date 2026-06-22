package com.bydcollector.collector.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.adb.AdbAuthorizationManager

data class RequiredAccessRow(
    val key: String,
    val label: String,
    val enabled: Boolean,
    val detail: String
)

object RequiredAccessChecker {
    fun displayCheck(context: Context): List<RequiredAccessRow> {
        val appContext = context.applicationContext
        val storageEnabled = hasStorageReadAccess(appContext)
        val adbEnabled = AdbAuthorizationManager.isAdbGranted(context)
        return listOf(
            RequiredAccessRow(
                key = "storage",
                label = "Storage",
                enabled = storageEnabled,
                detail = compactGrantStatus(storageEnabled)
            ),
            RequiredAccessRow(
                key = "adb",
                label = "ADB",
                enabled = adbEnabled,
                detail = compactGrantStatus(adbEnabled)
            )
        )
    }

    fun check(context: Context): List<RequiredAccessRow> {
        val appContext = context.applicationContext
        val storageEnabled = hasStorageReadAccess(appContext)
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
            )
        )
    }

    fun hasMissingRequiredAccess(context: Context): Boolean {
        return check(context).any { !it.enabled }
    }

    fun missingShellGrantCommands(context: Context): List<String> {
        if (!hasMissingRequiredAccess(context)) return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf("appops set --uid ${BuildConfig.APPLICATION_ID} MANAGE_EXTERNAL_STORAGE allow")
        } else {
            listOf("pm grant ${BuildConfig.APPLICATION_ID} android.permission.READ_EXTERNAL_STORAGE")
        }
    }

    private fun hasStorageReadAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun compactGrantStatus(enabled: Boolean): String {
        return if (enabled) "granted" else "not granted"
    }
}
