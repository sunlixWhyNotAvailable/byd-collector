package com.bydcollector.collector.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.bydcollector.collector.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

class UpdateApkVerifier(private val context: Context) {
    fun validate(file: File): UpdateApkValidation {
        if (!file.isFile) return UpdateApkValidation.fail("downloaded APK is missing")
        val sha256 = fileSha256(file)
        val archive = archiveInfo(file) ?: return UpdateApkValidation.fail("downloaded file is not an APK")
        if (archive.packageName != context.packageName) {
            return UpdateApkValidation.fail("APK package mismatch: ${archive.packageName}")
        }
        val archiveVersion = archive.longVersion()
        if (archiveVersion <= BuildConfig.VERSION_CODE) {
            return UpdateApkValidation.fail("APK version is not newer: $archiveVersion")
        }
        val installed = installedInfo() ?: return UpdateApkValidation.fail("installed package info unavailable")
        val archiveDigests = certificateDigests(archive)
        val installedDigests = certificateDigests(installed)
        if (archiveDigests.isEmpty() || archiveDigests != installedDigests) {
            return UpdateApkValidation.fail("APK signing certificate mismatch")
        }
        return UpdateApkValidation.ok(sha256)
    }

    private companion object {
        private const val TAG = "UpdateApkVerifier"
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, flags)
        }.onFailure { error ->
            Log.w(TAG, "installed package info unavailable", error)
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersion(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            info.signatures?.toList().orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> String.format(Locale.US, "%02x", byte.toInt() and 0xff) }
        }.toSet()
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> String.format(Locale.US, "%02x", byte.toInt() and 0xff) }
    }
}

data class UpdateApkValidation(
    val ok: Boolean,
    val message: String,
    val sha256: String? = null
) {
    companion object {
        fun ok(sha256: String): UpdateApkValidation = UpdateApkValidation(true, "APK verified", sha256)
        fun fail(message: String): UpdateApkValidation = UpdateApkValidation(false, message)
    }
}
