package com.bydcollector.collector.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class UpdateDownloader(private val context: Context) {
    fun enqueue(info: UpdateInfo): Long {
        GitHubReleaseTrust.requireTrustedApkDownloadUrl(info.downloadUrl, info.downloadContentType)
        val apkFileName = updateApkName(info.version)
        deleteOldUpdateApks(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            keepFileName = apkFileName
        )
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

    fun downloadedFile(info: UpdateInfo): File {
        return File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            updateApkName(info.version)
        )
    }

    fun progress(downloadId: Long): Int {
        val manager = context.getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return 0
            when (cursor.intValue(DownloadManager.COLUMN_STATUS)) {
                DownloadManager.STATUS_SUCCESSFUL -> return 100
                DownloadManager.STATUS_FAILED -> return -1
            }
            val total = cursor.longValue(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val downloaded = cursor.longValue(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            if (total <= 0L) return 0
            return ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
        }
    }

    fun copyDownloadedApkForInstall(info: UpdateInfo): File {
        return copyToVerifiedCache(
            sourceFile = downloadedFile(info),
            cacheDir = File(context.cacheDir, "updates"),
            version = info.version
        )
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

    private fun Cursor.intValue(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0) getInt(index) else 0
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"

        internal fun updateApkName(version: String): String = "bydcollector-${GitHubReleaseTrust.sanitizeFileToken(version)}.apk"

        internal fun copyToVerifiedCache(sourceFile: File, cacheDir: File, version: String): File {
            require(sourceFile.isFile) { "downloaded APK is missing" }
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                error("Cannot create verified update cache")
            }
            val target = cacheDir.resolve(updateApkName(version))
            cacheDir.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        file.name.startsWith("bydcollector-") &&
                        file.name.endsWith(".apk") &&
                        file.name != target.name
                }
                ?.forEach { file -> runCatching { file.delete() } }
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output)
                }
            }
            return target
        }

        internal fun deleteOldUpdateApks(downloadDir: File?, keepFileName: String) {
            if (downloadDir == null || !downloadDir.exists()) return
            downloadDir.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        file.name.startsWith("bydcollector-") &&
                        file.name.endsWith(".apk") &&
                        file.name != keepFileName
                }
                ?.forEach { file -> runCatching { file.delete() } }
        }
    }
}
