package com.bydcollector.collector

import android.content.Context
import android.os.Environment
import com.bydcollector.collector.runtime.RuntimeJobsService
import com.bydcollector.collector.update.UpdateApkVerifier
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/** Exercises update cleanup with real installed and optional older signed APKs. */
internal object UpdateApkCleanupGate {
    fun run(context: Context, olderApkPath: String?): String {
        val verifier = UpdateApkVerifier(context)
        val installed = checkNotNull(verifier.installedIdentity()) { "Installed package identity unavailable" }
        check(installed.packageName == context.packageName && installed.signerDigests.isNotEmpty()) {
            "Installed package identity is incomplete"
        }
        val installedApk = File(context.applicationInfo.sourceDir)
        val installedArchive = checkNotNull(verifier.archiveIdentity(installedApk)) { "Installed source APK metadata unavailable" }
        check(installedArchive == installed) { "Installed source APK metadata differs from PackageManager" }

        val older = olderApkPath?.let { path ->
            val archive = checkNotNull(verifier.archiveIdentity(File(path))) { "Older APK metadata unavailable: $path" }
            check(archive.packageName == installed.packageName && archive.signerDigests == installed.signerDigests) {
                "Older APK package or signer differs from the installed app"
            }
            check(archive.versionCode in 1 until installed.versionCode) {
                "Older APK must have a lower actual version code than the installed app"
            }
            archive
        }

        val cacheRoot = File(context.cacheDir, "updates")
        val cacheParent = context.cacheDir.canonicalFile
        val externalParent = checkNotNull(context.getExternalFilesDir(null)) { "App-specific external files unavailable" }
        val externalRoot = checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
            "App-specific Download directory unavailable"
        }
        ensureRoot(cacheRoot, cacheParent)
        ensureRoot(externalRoot, externalParent.canonicalFile)

        val owned = mutableListOf<File>()
        fun addApk(root: File, prefix: String, source: File): File {
            val file = File(root, "$prefix${UUID.randomUUID()}.apk")
            check(file.absoluteFile.parentFile?.canonicalFile == root.canonicalFile && !file.exists())
            owned += file
            FileInputStream(source).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            return file
        }
        fun addUnknown(root: File): File {
            val file = File(root, "runtime-${UUID.randomUUID()}.apk")
            check(file.absoluteFile.parentFile?.canonicalFile == root.canonicalFile && !file.exists())
            owned += file
            file.writeBytes("not an APK".toByteArray(Charsets.UTF_8))
            return file
        }

        try {
            val equalStage = addApk(cacheRoot, "runtime-", installedApk)
            val equalDownload = addApk(externalRoot, "bydcollector-", installedApk)
            val olderStage = older?.let { addApk(cacheRoot, "runtime-", File(olderApkPath!!)) }
            val olderDownload = older?.let { addApk(externalRoot, "bydcollector-", File(olderApkPath!!)) }
            val unknown = addUnknown(cacheRoot)
            val unrecognized = addApk(externalRoot, "not-bydcollector-", installedApk)

            RuntimeJobsService.recover(context)

            check(!equalStage.exists() && !equalDownload.exists()) {
                "Installed-version APKs were not removed from both owned directories"
            }
            check(olderStage?.exists() != true && olderDownload?.exists() != true) {
                "Older signed APKs were not removed"
            }
            check(unknown.exists()) { "Unverified APK was removed" }
            check(unrecognized.exists()) { "Unrecognized app-specific file was removed" }

            return buildString {
                appendLine("UPDATE_APK_CLEANUP_GATE_PASS")
                appendLine("installed_version_code=${installed.versionCode}")
                appendLine("equal_version_cleanup=cache,app_specific_downloads")
                if (older != null) appendLine("older_version_cleanup=cache,app_specific_downloads version_code=${older.versionCode}")
                appendLine("unknown_and_unrecognized=preserved")
                append("signer_match=dynamic_package_manager_and_archive")
            }
        } finally {
            owned.forEach { file ->
                if (file.exists()) {
                    check(file.canonicalFile.parentFile == file.parentFile?.canonicalFile && file.delete()) {
                        "Could not remove gate-owned file ${file.name}"
                    }
                }
            }
        }
    }

    private fun ensureRoot(root: File, expectedParent: File) {
        check(root.mkdirs() || root.isDirectory) { "Could not create ${root.name}" }
        val canonical = root.canonicalFile
        check(canonical.parentFile == expectedParent && canonical.name == root.name) {
            "Unexpected cleanup directory ${root.absolutePath}"
        }
    }
}
