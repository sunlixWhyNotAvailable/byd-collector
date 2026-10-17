package com.bydcollector.collector

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.bydcollector.collector.ui.compose.UiLanguage
import com.bydcollector.collector.ui.compose.runtimeJobMessage
import com.bydcollector.collector.ui.compose.strings
import com.bydcollector.collector.ui.compose.updateFailureMessage
import com.bydcollector.collector.update.UpdateApkVerifier
import com.bydcollector.collector.update.UpdateDownloader
import com.bydcollector.collector.update.UpdateFailureStage
import com.bydcollector.collector.update.UpdateInfo
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Real DownloadManager checks; the URL and release are supplied by the instrumentation caller. */
internal object UpdateDownloadGate {
    private const val DOWNLOAD_TIMEOUT_MS = 180_000L
    private const val POLL_INTERVAL_MS = 400L

    fun run(context: Context, url: String, version: String, contentType: String?): String {
        require(url.isNotBlank() && version.isNotBlank()) { "A public APK URL and release version are required" }
        val downloads = checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
            .canonicalFile
        val cache = context.cacheDir.canonicalFile
        val work = File(cache, "update_download_gate_${UUID.randomUUID()}")
        check(work.mkdirs() && work.canonicalFile.parentFile == cache) { "Could not create private gate directory" }
        val manager = context.getSystemService(DownloadManager::class.java)
        val downloader = UpdateDownloader(context)
        val info = UpdateInfo(version, url, "Real DownloadManager instrumentation", contentType)
        val ownedIds = linkedSetOf<Long>()
        val observations = mutableListOf<String>()
        var staleFile: File? = null
        var nonSuccessCopy: String? = null
        var failure: Throwable? = null
        var result: String? = null

        try {
            val firstJobId = UUID.randomUUID().toString()
            val retryJobId = UUID.randomUUID().toString()
            check(firstJobId != retryJobId)
            val staleJobId = UUID.randomUUID().toString()
            check(staleJobId != firstJobId && staleJobId != retryJobId)
            val stale = File(downloads, "bydcollector-$staleJobId.apk")
            check(stale.canonicalFile.parentFile == downloads && !stale.exists()) {
                "Refusing to overwrite a pre-existing DownloadManager destination"
            }
            staleFile = stale
            FileInputStream(context.applicationInfo.sourceDir).use { input ->
                stale.outputStream().use { output -> input.copyTo(output) }
            }
            val staleHash = sha256(stale)

            val firstId = downloader.enqueue(info, firstJobId)
            ownedIds += firstId
            nonSuccessCopy = observeEarlyCopy(downloader, firstId, File(work, "early-first.apk"))
            val firstSnapshot = awaitSuccessful(downloader, firstId)
            check(firstSnapshot.progress == 100) { "Successful DownloadManager row did not report 100%: ${firstSnapshot.detail}" }
            val firstDownloadHash = sha256(manager.openDownloadedFile(firstId))

            // A new downloader must recover the already-saved ID without queuing another request.
            val recoveredCopy = File(work, "saved-id-recovery.apk")
            val recoveredDownloader = UpdateDownloader(context)
            check(recoveredDownloader.snapshot(firstId).status == DownloadManager.STATUS_SUCCESSFUL)
            recoveredDownloader.copyDownloadedApk(firstId, recoveredCopy)
            check(sha256(recoveredCopy) == firstDownloadHash) { "Saved-ID recovery copy differs from DownloadManager bytes" }
            observations += "saved_id_recovery=same_id,new_downloader,no_enqueue"

            // The second real request is the same release but gets a different job UUID.
            val retryId = downloader.enqueue(info, retryJobId)
            ownedIds += retryId
            check(retryId != firstId) { "DownloadManager reused a live ID for a second request" }
            if (nonSuccessCopy == null) {
                nonSuccessCopy = observeEarlyCopy(downloader, retryId, File(work, "early-retry.apk"))
            }
            awaitSuccessful(downloader, retryId)
            val retryDownloadHash = sha256(manager.openDownloadedFile(retryId))
            val retryCopy = File(work, "same-release-retry.apk")
            UpdateDownloader(context).copyDownloadedApk(retryId, retryCopy)
            check(sha256(retryCopy) == retryDownloadHash) { "Retry copied bytes from a stale file instead of its DownloadManager ID" }
            check(stale.isFile && sha256(stale) == staleHash) { "The preserved stale APK changed during the different-job retry" }
            check(firstSnapshot.status == DownloadManager.STATUS_SUCCESSFUL && sha256(manager.openDownloadedFile(firstId)) == firstDownloadHash) {
                "The prior completed download changed during the retry"
            }
            observations += "same_release_new_job_id=download_bytes_match,stale_apk_preserved"

            // Removing the current ID must leave the other completed ID and its file usable.
            check(manager.remove(retryId) > 0) { "DownloadManager did not remove the current test ID" }
            val missing = downloader.snapshot(retryId)
            check(missing.status == null) { "Removed DownloadManager row is still visible" }
            val removedCopy = File(work, "removed-id.apk")
            val removedFailure = runCatching { downloader.copyDownloadedApk(retryId, removedCopy) }.exceptionOrNull()
            check(removedFailure != null) { "A removed DownloadManager record was copied" }
            val removedDetail = removedFailure.message.orEmpty()
            check("status=missing" in removedDetail) { "Removed-ID error lacks the actual missing-record detail: $removedDetail" }
            check(!removedCopy.exists() || removedCopy.length() == 0L) { "Removed-record copy produced bytes" }
            check(recoveredDownloader.snapshot(firstId).status == DownloadManager.STATUS_SUCCESSFUL)
            val survivorCopy = File(work, "surviving-id.apk")
            recoveredDownloader.copyDownloadedApk(firstId, survivorCopy)
            check(sha256(survivorCopy) == firstDownloadHash) { "Removing the current ID damaged the other completed job" }
            observations += "removed_id_copy=rejected,other_completed_id=preserved"

            val nonApk = File(work, "not-an-apk.bin").apply {
                writeBytes("local verifier input: not an APK".toByteArray(Charsets.UTF_8))
            }
            val validation = UpdateApkVerifier(context).validate(nonApk)
            check(!validation.ok && validation.message.contains("not an APK", ignoreCase = true)) {
                "Local non-APK did not fail at verification: ${validation.message}"
            }
            assertFailureFormatting(UpdateFailureStage.VERIFICATION, validation.message,
                "Помилка перевірки файлу оновлення:", "Update file verification error:")
            assertFailureFormatting(UpdateFailureStage.DOWNLOAD, removedDetail,
                "Помилка завантаження оновлення:", "Update download error:")
            assertFailureFormatting(UpdateFailureStage.UNKNOWN, removedDetail,
                "Помилка оновлення:", "Update error:")
            observations += "local_non_apk=verification_rejected:${validation.message}"
            observations += "localized_failure_stages=verification,removed_download,removed_unknown;uk,en"

            result = buildString {
                appendLine("UPDATE_DOWNLOAD_GATE_PASS")
                observations.forEach { appendLine(it) }
                append("non_success_copy=${nonSuccessCopy ?: "not_observed_before_success"}")
            }
        } catch (error: Throwable) {
            failure = error
        }

        val cleanupFailures = mutableListOf<Throwable>()
        ownedIds.forEach { id ->
            runCatching {
                if (downloader.snapshot(id).status != null) {
                    check(manager.remove(id) > 0 && downloader.snapshot(id).status == null) {
                        "Could not remove gate-owned DownloadManager ID $id"
                    }
                }
            }.onFailure { cleanupFailures += it }
        }
        staleFile?.let { file ->
            if (file.exists()) runCatching {
                check(file.canonicalFile.parentFile == downloads && file.delete()) { "Could not remove gate-owned stale APK: $file" }
            }.onFailure { cleanupFailures += it }
        }
        runCatching {
            check(work.canonicalFile.parentFile == cache && work.deleteRecursively()) { "Could not remove gate-owned cache: $work" }
        }.onFailure { cleanupFailures += it }

        failure?.let { primary ->
            cleanupFailures.forEach(primary::addSuppressed)
            throw primary
        }
        cleanupFailures.firstOrNull()?.let { throw it }
        return checkNotNull(result)
    }

    private fun observeEarlyCopy(downloader: UpdateDownloader, id: Long, destination: File): String? {
        val before = downloader.snapshot(id)
        if (before.status == null || before.status == DownloadManager.STATUS_SUCCESSFUL) return null
        val copied = runCatching { downloader.copyDownloadedApk(id, destination) }
        val after = downloader.snapshot(id)
        if (copied.isSuccess) {
            check(after.status == DownloadManager.STATUS_SUCCESSFUL) {
                "A non-successful DownloadManager row supplied bytes: before=${before.detail} after=${after.detail}"
            }
            return "race_to_success"
        }
        if (after.status == DownloadManager.STATUS_SUCCESSFUL) return "race_to_success"
        check(!destination.exists() || destination.length() == 0L) {
            "A non-successful DownloadManager row left copied bytes: ${after.detail}"
        }
        return "non_success_copy=rejected_status_${after.status}_reason_${after.reason}"
    }

    private fun awaitSuccessful(downloader: UpdateDownloader, id: Long) = run {
        val deadline = SystemClock.elapsedRealtime() + DOWNLOAD_TIMEOUT_MS
        var snapshot = downloader.snapshot(id)
        while (SystemClock.elapsedRealtime() < deadline) {
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> return@run snapshot
                DownloadManager.STATUS_FAILED -> error("DownloadManager failed: ${snapshot.detail}")
                null -> error("DownloadManager record disappeared: ${snapshot.detail}")
            }
            Thread.sleep(POLL_INTERVAL_MS)
            snapshot = downloader.snapshot(id)
        }
        error("Timed out waiting for DownloadManager success: ${snapshot.detail}")
    }

    private fun sha256(file: File): String = FileInputStream(file).use { sha256(it) }

    private fun sha256(descriptor: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { sha256(it) }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
    }

    private fun assertFailureFormatting(stage: UpdateFailureStage, detail: String, ukPrefix: String, enPrefix: String) {
        listOf(UiLanguage.UK to ukPrefix, UiLanguage.EN to enPrefix).forEach { (language, prefix) ->
            val localized = strings(language)
            val direct = updateFailureMessage(localized, stage, detail)
            val runtime = runtimeJobMessage(localized, "updateDownload", false, error = detail, updateStage = stage)
            check(direct.startsWith(prefix) && runtime == direct) {
                "${language.code} update stage $stage rendered incorrectly: direct=$direct runtime=$runtime"
            }
            if (stage == UpdateFailureStage.VERIFICATION) {
                val downloadPrefix = if (language == UiLanguage.UK) "Помилка завантаження оновлення:" else "Update download error:"
                check(!direct.startsWith(localized.updateError) && !direct.startsWith(downloadPrefix)) {
                    "Verification failure was rendered as check/download feedback: $direct"
                }
            }
        }
    }
}
