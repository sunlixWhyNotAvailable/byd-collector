package com.bydcollector.collector.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.Looper
import android.util.AtomicFile
import androidx.lifecycle.Lifecycle
import com.bydcollector.collector.disposeTestComposition
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.MainActivity
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.StartupAdbPeer
import com.bydcollector.collector.runtime.RuntimeJobsService
import com.bydcollector.collector.service.CollectorSettings
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [29],
    application = BydCollectorApplication::class,
    qualifiers = "w1280dp-h480dp-mdpi",
    shadows = [
        StartupAdbPeer::class,
        CompletedDownloadManagerShadow::class,
        VerifiedArchivePackageManagerShadow::class,
        WindowsFileProviderShadow::class
    ]
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateApkVerifierContractTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var app: BydCollectorApplication
    private lateinit var screen: ActivityController<MainActivity>
    private var runtimeJobsController: ServiceController<RuntimeJobsService>? = null

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as BydCollectorApplication
        ShadowProcess.setPid(12001)
        resetRuntimeJobStore()
        val settings = CollectorSettings(app)
        settings.setUiLanguageCode("en")
        settings.setUpdateAutoCheckEnabled(false)
        settings.setUpdateHintEnabled(false)
        app.getSharedPreferences("startup_setup", 0).edit()
            .putInt("background_settings_version", BuildConfig.VERSION_CODE)
            .putBoolean("location_permission_setup_consumed", true)
            .putBoolean("overlay_permission_setup_consumed", true).commit()
        StartupAdbPeer.reset()
        StartupAdbPeer.consent = true
        StartupAdbPeer.trusted = true
        AdbLocalClient(File(app.filesDir, "adb_keys")).keyFingerprint()
        CompletedDownloadManagerShadow.reset()
        screen = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
        ui.waitForIdle()
    }

    @After fun cleanup() {
        stopRuntimeJobs()
        if (::app.isInitialized) app.updateRuntime.shutdown()
        if (::app.isInitialized) RuntimeJobsService::class.java.getDeclaredField("cached")
            .apply { isAccessible = true }.set(null, null)
        if (::screen.isInitialized) {
            try {
                screen.get().disposeTestComposition()
            } finally {
                screen.pause().stop().destroy()
            }
        }
        AdbAuthorizationManager.cancelObservation()
        if (::app.isInitialized) app.navigationSession.clear()
        CompletedDownloadManagerShadow.reset()
    }

    @Test fun actualApkArchiveReadbackReachesInstallerOnlyAfterPackageVersionAndSignerValidation() {
        val packageManager = app.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installedArchive = registerArchive("installed.apk", app.packageName, 1L, flags)
        val parsedInstalledSigners = signerDigests(installedArchive)
        assertTrue(parsedInstalledSigners.isNotEmpty(), "Installed fixture must carry a parsed signing certificate")

        // Preserve the test application's own provider and activity metadata; only its signer baseline
        // comes from the separately parsed, verified installed APK fixture.
        val installedPackageInfo = assertNotNull(
            shadowOf(packageManager).getInternalMutablePackageInfo(app.packageName)
        )
        installedPackageInfo.signatures = installedArchive.signatures
        installedPackageInfo.signingInfo = installedArchive.signingInfo
        assertEquals(parsedInstalledSigners, signerDigests(packageManager.getPackageInfo(app.packageName, flags)))
        assertTrue(
            packageManager.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS)
                .providers.orEmpty().any { it.authority == "${app.packageName}.fileprovider" },
            "Signer setup must preserve the application's FileProvider metadata"
        )

        val downloader = UpdateDownloader(app)
        val verifier = UpdateApkVerifier(app)
        val acceptedInfo = updateInfo("3.2.2")
        val acceptedBytes = fixture("valid.apk").readBytes()
        assertEquals(parsedInstalledSigners, signerDigests(registerArchive("valid.apk", app.packageName, 2_000_000_000L, flags)))

        // The shared runtime job copies and validates the private staging file before claiming installer handoff.
        writeDownloadedApk(downloader, acceptedInfo, acceptedBytes)
        invokeDownload(acceptedInfo)
        awaitInstaller()

        val installerIntent = assertNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, installerIntent.action)
        assertEquals("application/vnd.android.package-archive", installerIntent.type)
        assertTrue(installerIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val apkUri = assertNotNull(installerIntent.data)
        assertEquals("${app.packageName}.fileprovider", apkUri.authority)
        assertEquals("verified_updates", apkUri.pathSegments.first())
        val cachedApk = File(File(app.cacheDir, "updates"), apkUri.lastPathSegment!!)
        assertTrue(cachedApk.isFile)
        assertContentEquals(acceptedBytes, cachedApk.readBytes())
        val finalValidation = verifier.validate(cachedApk)
        assertTrue(finalValidation.ok, finalValidation.message)
        mapInstallerUriToPrivateCopy(apkUri, cachedApk)
        val readBack = app.contentResolver.openInputStream(apkUri).use { stream ->
            assertNotNull(stream).readBytes()
        }
        assertContentEquals(acceptedBytes, readBack)
        assertEquals(sha256(acceptedBytes), finalValidation.sha256)
        assertEquals(sha256(readBack), finalValidation.sha256)
        assertEquals(1, CompletedDownloadManagerShadow.enqueueCount)
        val handedOffId = MainActivity::class.java.getDeclaredField("updateDownloadJobId")
            .apply { isAccessible = true }.get(screen.get()) as String
        assertEquals("INSTALLER_CLAIMED", RuntimeJobsService.cancelUpdateDownload(app, handedOffId),
            "Cancellation must lose once the verified installer handoff is claimed")

        // Exercise the shared job's installer-claim boundary: changing the exact staged file
        // after worker verification must fail the saved SHA check before any installer claim.
        screen.pause().stop()
        val tamperedJobId = RuntimeJobsService.submit(app, "updateDownload", org.json.JSONObject()
            .put("version", acceptedInfo.version).put("url", acceptedInfo.downloadUrl)
            .put("notes", acceptedInfo.releaseNotes).put("contentType", acceptedInfo.downloadContentType))
        RuntimeJobsService.dismissUpdateDialog(app, tamperedJobId)
        writeDownloadedApk(downloader, acceptedInfo, acceptedBytes)
        startRuntimeJobs()
        val stagedApk = File(File(app.cacheDir, "updates"), "runtime-$tamperedJobId.apk")
        await {
            stagedApk.isFile && jobIn(RuntimeJobsService.snapshot(app), tamperedJobId).optString("status") == "complete"
        }
        val verifiedResult = jobIn(RuntimeJobsService.snapshot(app), tamperedJobId).getJSONObject("result")
        val verifiedSha = verifiedResult.getString("sha256")
        stagedApk.writeBytes(acceptedBytes.copyOf().apply { this[0] = (this[0].toInt() xor 0xff).toByte() })
        assertTrue(UpdateApkVerifier(app).sha256(stagedApk) != verifiedSha)
        screen.start().resume().visible()
        val reopened = assertNotNull(RuntimeJobsService.reopenUpdateDialog(app, tamperedJobId))
        consumeJobs(reopened.second)
        awaitUpdateError()
        val tamperError = activityUpdateState() as UpdateUiState.Error
        assertTrue(tamperError.message.contains("changed", ignoreCase = true), tamperError.message)
        assertFalse(cachedJob(tamperedJobId).optBoolean("installerClaimed"))
        assertNull(shadowOf(app).peekNextStartedActivity(), "Tampered staged APK must not reach the installer")

        val rejected = listOf(
            ApkCandidate("downgrade.apk", "3.2.1", app.packageName, 1L, "version is not newer", sameSigner = true),
            ApkCandidate("foreign-package.apk", "3.2.4", "com.bydcollector.updatefixture.foreign", 2_000_000_000L, "package mismatch", sameSigner = true),
            ApkCandidate("wrong-signer.apk", "3.2.3", app.packageName, 2_000_000_001L, "signing certificate mismatch", sameSigner = false),
            ApkCandidate("corrupt", "3.2.5", app.packageName, 0L, "not an APK", sameSigner = false)
        )
        rejected.forEach { candidate ->
            val bytes = if (candidate.fixture == "corrupt") {
                acceptedBytes.copyOf().apply { this[0] = (this[0].toInt() xor 0xff).toByte() }
            } else {
                val archiveInfo = registerArchive(candidate.fixture, candidate.packageName, candidate.versionCode, flags)
                val candidateSigners = signerDigests(archiveInfo)
                if (candidate.sameSigner) assertEquals(parsedInstalledSigners, candidateSigners)
                else assertTrue(candidateSigners != parsedInstalledSigners, "${candidate.fixture} must use a different real signer")
                fixture(candidate.fixture).readBytes()
            }
            val info = updateInfo(candidate.downloadVersion)
            writeDownloadedApk(downloader, info, bytes)
            invokeDownload(info)
            awaitUpdateError()
            val error = activityUpdateState() as UpdateUiState.Error
            assertTrue(error.message.contains(candidate.failureText, ignoreCase = true), error.message)
            assertNull(shadowOf(app).peekNextStartedActivity(), "Rejected ${candidate.fixture} must not launch an installer")
        }
        assertEquals(6, CompletedDownloadManagerShadow.enqueueCount)
    }

    @Test fun cancellationRemovesItsDownloadAndFencesStaleCompletionAcrossRecoveryAndRetry() {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installedArchive = registerArchive("installed.apk", app.packageName, 1L, flags)
        val installedPackageInfo = assertNotNull(
            shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName)
        )
        installedPackageInfo.signatures = installedArchive.signatures
        installedPackageInfo.signingInfo = installedArchive.signingInfo

        screen.pause().stop()
        val info = updateInfo("3.2.6")
        val downloader = UpdateDownloader(app)
        val validApk = fixture("valid.apk").readBytes()
        val unrelatedStaging = File(app.cacheDir, "updates/runtime-unrelated.apk").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(7, 8, 9))
        }
        val cancelledIds = mutableListOf<String>()
        val downloadIds = mutableListOf<Long>()

        listOf(false, true).forEach { completeBeforeCancel ->
            CompletedDownloadManagerShadow.queryStatus = if (completeBeforeCancel) {
                DownloadManager.STATUS_SUCCESSFUL
            } else {
                DownloadManager.STATUS_RUNNING
            }
            val queryEntered = if (completeBeforeCancel) null else CountDownLatch(1)
            val queryRelease = if (completeBeforeCancel) null else CountDownLatch(1)
            CompletedDownloadManagerShadow.queryEntered = queryEntered
            CompletedDownloadManagerShadow.queryRelease = queryRelease
            val id = RuntimeJobsService.submit(app, "updateDownload", org.json.JSONObject()
                .put("version", info.version).put("url", info.downloadUrl)
                .put("notes", info.releaseNotes).put("contentType", info.downloadContentType))
            cancelledIds += id
            if (completeBeforeCancel) writeDownloadedApk(downloader, info, validApk)

            startRuntimeJobs()
            await {
                val current = jobIn(RuntimeJobsService.snapshot(app), id)
                current.optLong("downloadId", -1L) > 0L && if (completeBeforeCancel) {
                    current.optString("status") == "complete"
                } else {
                    queryEntered?.count == 0L
                }
            }

            val beforeCancel = jobIn(RuntimeJobsService.snapshot(app), id)
            val downloadId = beforeCancel.getLong("downloadId")
            downloadIds += downloadId
            val staleCompleteSnapshot = if (completeBeforeCancel) {
                RuntimeJobsService.snapshot(app).also { snapshot ->
                    assertEquals("complete", jobIn(snapshot, id).getString("status"))
                }
            } else {
                writeDownloadedApk(downloader, info, validApk)
                File(app.cacheDir, "updates/runtime-$id.apk").apply {
                    parentFile?.mkdirs()
                    writeBytes(byteArrayOf(1, 2, 3))
                }
                null
            }
            val downloadedFile = downloader.downloadedFile(info)
            val stagedFile = File(app.cacheDir, "updates/runtime-$id.apk")
            assertTrue(downloadedFile.isFile, "Fixture must have this job's exact DownloadManager destination")
            assertTrue(stagedFile.isFile, "Fixture must have this job's exact staged APK")

            assertEquals("REQUESTED", RuntimeJobsService.cancelUpdateDownload(app, id))
            assertTrue(CompletedDownloadManagerShadow.removedIds.contains(downloadId),
                "Cancellation must remove DownloadManager ID $downloadId")
            val shutdownRecovery = !completeBeforeCancel
            if (shutdownRecovery) {
                val settings = CollectorSettings(app)
                assertTrue(settings.setUserShutdownRequested(true))
                RuntimeJobsService.cancelForShutdown(app)
                assertEquals("cancelling", jobIn(RuntimeJobsService.snapshot(app), id).getString("status"),
                    "Shutdown must preserve requested update cleanup as pending")
                stopRuntimeJobs()
                queryRelease?.countDown()
                CompletedDownloadManagerShadow.queryEntered = null
                CompletedDownloadManagerShadow.queryRelease = null
                RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
                assertTrue(settings.clearUserShutdownRequestIfSet())
                val blockedRetryError = runCatching {
                    RuntimeJobsService.submit(app, "updateDownload", org.json.JSONObject()
                        .put("version", info.version).put("url", info.downloadUrl)
                        .put("notes", info.releaseNotes).put("contentType", info.downloadContentType))
                }.exceptionOrNull()
                assertEquals("Update cancellation cleanup is still pending", blockedRetryError?.message,
                    "A new same-version job must remain fenced across shutdown until cleanup finishes")
                val enqueuesBeforeRecovery = CompletedDownloadManagerShadow.enqueueCount
                RuntimeJobsService.recover(app)
                startRuntimeJobs()
                assertEquals(enqueuesBeforeRecovery, CompletedDownloadManagerShadow.enqueueCount,
                    "Shutdown recovery must not enqueue a second update download")
            } else {
                runtimeJobsController?.get()?.onStartCommand(null, 0, 2) ?: startRuntimeJobs()
            }
            val cancellationDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
            var cancellationStatus = jobIn(RuntimeJobsService.snapshot(app), id).optString("status")
            while (cancellationStatus !in setOf("cancelled", "cancelError") &&
                System.nanoTime() < cancellationDeadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
                Thread.sleep(10)
                cancellationStatus = jobIn(RuntimeJobsService.snapshot(app), id).optString("status")
            }
            if (completeBeforeCancel && cancellationStatus !in setOf("cancelled", "cancelError")) startRuntimeJobs()
            await {
                jobIn(RuntimeJobsService.snapshot(app), id).optString("status") in setOf("cancelled", "cancelError")
            }
            val cancellationResult = jobIn(RuntimeJobsService.snapshot(app), id)
            assertEquals("cancelled", cancellationResult.getString("status"),
                "Cancellation cleanup failed at completeBeforeCancel=$completeBeforeCancel: " +
                    cancellationResult.optString("cancelCleanupError"))

            assertFalse(downloadedFile.exists(), "Cancellation must delete only this update's downloaded APK")
            assertFalse(stagedFile.exists(), "Cancellation must delete only this job's staged APK")
            assertTrue(unrelatedStaging.isFile, "Cancellation must leave an unrelated staged job untouched")
            assertFalse(RuntimeJobsService.prepareResult(app, id),
                "A stale completed snapshot must not claim the installer after cancellation")
            assertNull(shadowOf(app).peekNextStartedActivity(), "Cancelled work must not launch an installer")
            staleCompleteSnapshot?.let { stale ->
                assertEquals("complete", jobIn(stale, id).getString("status"),
                    "The saved pre-cancel view should remain stale while the owner rejects its handoff")
            }

            if (!shutdownRecovery) {
                stopRuntimeJobs()
                RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
                val enqueuesBeforeRecovery = CompletedDownloadManagerShadow.enqueueCount
                RuntimeJobsService.recover(app)
                startRuntimeJobs()
                assertEquals(enqueuesBeforeRecovery, CompletedDownloadManagerShadow.enqueueCount,
                    "Service/cache recovery must not re-enqueue a terminally cancelled update")
            }
            assertEquals("cancelled", jobIn(RuntimeJobsService.snapshot(app), id).getString("status"))
            stopRuntimeJobs()
        }

        assertEquals(2, cancelledIds.distinct().size)
        assertEquals(downloadIds.toSet(), CompletedDownloadManagerShadow.removedIds.toSet(),
            "Cancellation cleanup must remove only the two cancelled jobs' DownloadManager IDs")

        CompletedDownloadManagerShadow.queryStatus = DownloadManager.STATUS_SUCCESSFUL
        val retryId = RuntimeJobsService.submit(app, "updateDownload", org.json.JSONObject()
            .put("version", info.version).put("url", info.downloadUrl)
            .put("notes", info.releaseNotes).put("contentType", info.downloadContentType))
        assertFalse(retryId in cancelledIds, "An explicit retry must create a fresh job")
        writeDownloadedApk(downloader, info, validApk)
        startRuntimeJobs()
        await { jobIn(RuntimeJobsService.snapshot(app), retryId).optString("status") == "complete" }
        assertEquals(3, CompletedDownloadManagerShadow.enqueueCount,
            "Only the two original jobs and the explicit retry may enqueue downloads")
        assertNull(shadowOf(app).peekNextStartedActivity(), "A stopped UI must not auto-offer the completed retry")
        unrelatedStaging.delete()
    }

    @Test fun missingDownloadManagerRowIsTerminalInsteadOfStallingAtZeroProgress() {
        CompletedDownloadManagerShadow.missingRow = true
        assertEquals(-1, UpdateDownloader(app).progress(42L))
    }

    private fun invokeDownload(info: UpdateInfo) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            screen.get(),
            "startUpdateDownload",
            ReflectionHelpers.ClassParameter.from(UpdateInfo::class.java, info)
        )
        startRuntimeJobs()
    }

    private fun startRuntimeJobs() {
        stopRuntimeJobs()
        runtimeJobsController = Robolectric.buildService(RuntimeJobsService::class.java).create()
        runtimeJobsController!!.get().onStartCommand(null, 0, 1)
    }

    private fun stopRuntimeJobs() {
        runtimeJobsController?.let { runCatching { it.destroy() } }
        runtimeJobsController = null
    }

    private fun resetRuntimeJobStore() {
        RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        AtomicFile(File(app.filesDir, "runtime_jobs.json")).delete()
        RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
    }

    private fun consumeJobs(json: String) {
        MainActivity::class.java.getDeclaredMethod("consumeRuntimeJobs", String::class.java)
            .apply { isAccessible = true }.invoke(screen.get(), json)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun jobIn(json: String, id: String): org.json.JSONObject {
        val jobs = org.json.JSONArray(json)
        return (0 until jobs.length()).map(jobs::getJSONObject).single { it.getString("id") == id }
    }

    private fun cachedJob(id: String): org.json.JSONObject {
        val jobs = RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }
            .get(null) as MutableList<org.json.JSONObject>
        return jobs.single { it.getString("id") == id }
    }

    private fun activityUpdateState(): UpdateUiState = ReflectionHelpers.callInstanceMethod(screen.get(), "getUpdateUiState")

    private fun writeDownloadedApk(downloader: UpdateDownloader, info: UpdateInfo, bytes: ByteArray) {
        val destination = downloader.downloadedFile(info)
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
    }

    private fun mapInstallerUriToPrivateCopy(uri: Uri, file: File) {
        val privateRoot = File(app.cacheDir, "updates").canonicalFile
        val privateCopy = file.canonicalFile
        assertEquals(privateRoot, privateCopy.parentFile, "Installer URI must reference the verified private cache copy")
        assertTrue(privateCopy.isFile)
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { privateCopy.inputStream() }
    }

    private fun archive(file: File, flags: Int): PackageInfo = assertNotNull(
        app.packageManager.getPackageArchiveInfo(file.absolutePath, flags),
        "PackageManager must parse ${file.name} as an APK"
    )

    private fun registerArchive(
        name: String,
        expectedPackage: String,
        expectedVersion: Long,
        flags: Int
    ): PackageInfo {
        val file = fixture(name)
        val parsedInfo = archive(file, flags)
        assertEquals(expectedPackage, parsedInfo.packageName)
        assertEquals(expectedVersion, parsedInfo.longVersion())

        assertTrue(signerDigests(parsedInfo).isNotEmpty(), "$name must carry the verified signer")
        return parsedInfo
    }

    private fun fixture(name: String): File {
        val url = assertNotNull(javaClass.classLoader?.getResource("update-apk-fixtures/$name"), "Missing APK fixture $name")
        return File(url.toURI())
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersion(): Long = if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signers = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else info.signatures?.toList().orEmpty()
        return signers.map { signature -> sha256(signature.toByteArray()) }.toSet()
    }

    private fun updateInfo(version: String) = UpdateInfo(
        version = version,
        downloadUrl = "https://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/v$version/bydcollector-v$version.apk",
        releaseNotes = "Test-only signed APK fixture",
        downloadContentType = "application/vnd.android.package-archive"
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) {
                "Timed out waiting for update download/install boundary: ${updateBoundaryDiagnostics()}"
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitInstaller() {
        await {
            if (screen.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                consumeJobs(RuntimeJobsService.snapshot(app))
            }
            shadowOf(app).peekNextStartedActivity() != null
        }
    }

    private fun awaitUpdateError() {
        await {
            if (screen.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                consumeJobs(RuntimeJobsService.snapshot(app))
            }
            activityUpdateState() is UpdateUiState.Error
        }
    }

    private fun updateBoundaryDiagnostics(): String {
        val uiState = runCatching { activityUpdateState().toString() }.getOrDefault("<unavailable>")
        val startedActivity = runCatching { shadowOf(app).peekNextStartedActivity()?.toString() }
            .getOrNull() ?: "<none>"
        val cacheFiles = File(app.cacheDir, "updates").listFiles()
            ?.joinToString { "${it.name}:${it.length()}" } ?: "<none>"
        val downloadFiles = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()
            ?.joinToString { "${it.name}:${it.length()}" } ?: "<none>"
        val jobs = runCatching {
            val all = org.json.JSONArray(RuntimeJobsService.snapshot(app))
            (0 until all.length()).joinToString { index ->
                val job = all.getJSONObject(index)
                "${job.optString("id")}:${job.optString("status")}:" +
                    job.optString("cancelCleanupError", job.optString("error"))
            }
        }.getOrElse { "<${it.javaClass.simpleName}>" }
        return "ui=$uiState enqueues=${CompletedDownloadManagerShadow.enqueueCount} " +
            "cache=[$cacheFiles] downloads=[$downloadFiles] jobs=[$jobs] installer=$startedActivity"
    }

    private data class ApkCandidate(
        val fixture: String,
        val downloadVersion: String,
        val packageName: String,
        val versionCode: Long,
        val failureText: String,
        val sameSigner: Boolean
    )
}

@Implements(DownloadManager::class)
class CompletedDownloadManagerShadow {
    @Implementation
    fun enqueue(request: DownloadManager.Request): Long {
        enqueueCount += 1
        return enqueueCount.toLong()
    }

    @Implementation
    fun query(query: DownloadManager.Query): Cursor = MatrixCursor(
        arrayOf(
            DownloadManager.COLUMN_STATUS,
            DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
        )
    ).apply {
        queryCount += 1
        queryEntered?.countDown()
        queryRelease?.let { check(it.await(10, TimeUnit.SECONDS)) { "Timed out releasing DownloadManager query" } }
        if (!missingRow) {
            val downloaded = if (queryStatus == DownloadManager.STATUS_SUCCESSFUL) 1L else 0L
            addRow(arrayOf(queryStatus, 1L, downloaded))
        }
    }

    @Implementation
    fun remove(vararg ids: Long): Int {
        removedIds.addAll(ids.toList())
        return ids.size
    }

    companion object {
        @Volatile var enqueueCount = 0
        @Volatile var missingRow = false
        @Volatile var queryCount = 0
        @Volatile var queryStatus = DownloadManager.STATUS_SUCCESSFUL
        @Volatile var queryEntered: CountDownLatch? = null
        @Volatile var queryRelease: CountDownLatch? = null
        val removedIds = java.util.Collections.synchronizedList(mutableListOf<Long>())
        fun reset() {
            queryRelease?.countDown()
            queryEntered = null
            queryRelease = null
            enqueueCount = 0
            missingRow = false
            queryCount = 0
            queryStatus = DownloadManager.STATUS_SUCCESSFUL
            removedIds.clear()
        }
    }
}

@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class VerifiedArchivePackageManagerShadow : ShadowApplicationPackageManager() {
    @Implementation
    override fun getPackageArchiveInfo(archiveFilePath: String, flags: Int): PackageInfo? {
        // Robolectric's native PackageManager can keep the parsed archive open on Windows.
        // Parse a byte-identical host-temp copy so cancellation can still assert deletion of
        // the production staging path after verification.
        val source = File(archiveFilePath)
        val parserCopy = File.createTempFile("update-apk-archive-", ".apk").apply {
            writeBytes(source.readBytes())
            deleteOnExit()
        }
        return try {
            val parsedInfo = super.getPackageArchiveInfo(parserCopy.absolutePath, flags) ?: return null
            val verifiedSignatures = verifiedJarSignatures(parserCopy) ?: return null
            parsedInfo.signatures = verifiedSignatures.toTypedArray()
            parsedInfo.signingInfo = SigningInfo().apply {
                shadowOf(this).setSignatures(verifiedSignatures.toTypedArray())
            }
            parsedInfo
        } finally {
            parserCopy.delete()
        }
    }
}

@Implements(FileProvider::class, isInAndroidSdk = false)
class WindowsFileProviderShadow {
    companion object {
        @Implementation
        @JvmStatic
        fun getUriForFile(context: Context, authority: String, file: File): Uri {
            val privateRoot = File(context.cacheDir, "updates").canonicalFile
            val privateCopy = file.canonicalFile
            check(privateCopy.isFile && privateCopy.parentFile == privateRoot) {
                "Only a real APK directly inside cacheDir/updates may be shared"
            }
            return Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath("verified_updates")
                .appendPath(privateCopy.name)
                .build()
        }
    }
}

private fun verifiedJarSignatures(file: File): List<Signature>? = runCatching {
    val signatures = linkedMapOf<String, Signature>()
    var expectedPayloadSigners: Set<String>? = null
    JarFile(file, true).use { jar ->
        val buffer = ByteArray(16 * 1024)
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || entry.name.startsWith("META-INF/", ignoreCase = true)) continue
            jar.getInputStream(entry).use { input ->
                while (input.read(buffer) != -1) Unit
            }
            val entrySigners = entry.codeSigners
                ?: error("${file.name}:${entry.name} is an unsigned APK payload entry")
            val entrySignerDigests = entrySigners.map { signer ->
                val certificate = signer.signerCertPath.certificates.firstOrNull() as? X509Certificate
                    ?: error("${file.name}:${entry.name} has no X.509 signer certificate")
                val signature = Signature(certificate.encoded)
                val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                signatures[digest] = signature
                digest
            }.toSet()
            val previousSigners = expectedPayloadSigners
            check(previousSigners == null || previousSigners == entrySignerDigests) {
                "${file.name}:${entry.name} has a different APK payload signer set"
            }
            expectedPayloadSigners = entrySignerDigests
        }
    }
    signatures.values.toList().takeIf { it.isNotEmpty() }
}.getOrNull()
