package com.bydcollector.collector.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

data class UpdateDownloadSnapshot(
    val status: Int?,
    val reason: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long
) {
    val progress: Int
        get() = if (status == DownloadManager.STATUS_SUCCESSFUL) 100
        else if (totalBytes <= 0L) 0
        else ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)

    val detail: String
        get() = "status=${status ?: "missing"} reason=$reason bytes=$bytesDownloaded/$totalBytes"
}

class UpdateDownloader(private val context: Context) {
    fun enqueue(info: UpdateInfo, jobId: String): Long {
        GitHubReleaseTrust.requireTrustedApkDownloadUrl(info.downloadUrl, info.downloadContentType)
        require(jobId.length in 1..64)
        val apkFileName = "bydcollector-${GitHubReleaseTrust.sanitizeFileToken(jobId)}.apk"
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("BYD Collector ${info.version}")
            .setDescription("Downloading update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                apkFileName
            )
        val manager = context.getSystemService(DownloadManager::class.java)
        //bridge downloadmanager progress into compose state by returning stable id
        return manager.enqueue(request)
    }

    fun snapshot(downloadId: Long): UpdateDownloadSnapshot {
        val manager = context.getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return UpdateDownloadSnapshot(null, -1, 0L, 0L)
            val status = cursor.intValue(DownloadManager.COLUMN_STATUS)
            val reason = cursor.intValue(DownloadManager.COLUMN_REASON, -1)
            val downloaded = cursor.longValue(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = cursor.longValue(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            return UpdateDownloadSnapshot(status, reason, downloaded, total)
        }
    }

    fun copyDownloadedApk(downloadId: Long, destination: File) {
        val state = snapshot(downloadId)
        check(state.status == DownloadManager.STATUS_SUCCESSFUL) {
            "DownloadManager download $downloadId is not successful (${state.detail})"
        }
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) error("Cannot create update staging directory")
        }
        val source = context.getSystemService(DownloadManager::class.java).openDownloadedFile(downloadId)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    fun install(info: UpdateInfo, file: File) {
        GitHubReleaseTrust.requireTrustedApkDownloadUrl(info.downloadUrl, info.downloadContentType)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        //open package installer for the verified private apk copy
        context.startActivity(intent)
    }

    private fun Cursor.longValue(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0) getLong(index) else 0L
    }

    private fun Cursor.intValue(column: String, missingValue: Int = 0): Int {
        val index = getColumnIndex(column)
        return if (index >= 0) getInt(index) else missingValue
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}
