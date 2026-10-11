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
import com.bydcollector.collector.disposeTestComposition
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.MainActivity
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.StartupAdbPeer
import com.bydcollector.collector.service.CollectorSettings
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Duration
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
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

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as BydCollectorApplication
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
        if (::app.isInitialized) app.updateRuntime.shutdown()
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

        // MainActivity validates the private install copy twice. Hold its main-thread continuation after
        // the first successful validation, change that copy, then prove the final validation blocks install.
        writeDownloadedApk(downloader, acceptedInfo, acceptedBytes)
        val mainLooper = shadowOf(Looper.getMainLooper())
        val wasMainLooperPaused = mainLooper.isPaused
        val enqueuesBeforeTamper = CompletedDownloadManagerShadow.enqueueCount
        val cachedApk = File(app.cacheDir, "updates/bydcollector-3.2.2.apk")
        mainLooper.pause()
        try {
            invokeDownload(acceptedInfo)
            awaitPreflightValidation(cachedApk, enqueuesBeforeTamper + 1)
            assertContentEquals(acceptedBytes, cachedApk.readBytes(), "The private copy should pass preflight before mutation")
            cachedApk.writeBytes(acceptedBytes.copyOf().apply { this[0] = (this[0].toInt() xor 0xff).toByte() })
        } finally {
            if (!wasMainLooperPaused) mainLooper.unPause()
        }
        mainLooper.idle()
        await { activityUpdateState() is UpdateUiState.Error }
        val tamperError = activityUpdateState() as UpdateUiState.Error
        assertTrue(tamperError.message.contains("not an APK", ignoreCase = true), tamperError.message)
        assertFalse(verifier.validate(File(app.cacheDir, "updates/bydcollector-3.2.2.apk")).ok)
        assertNull(shadowOf(app).peekNextStartedActivity(), "Changed private copy must not launch an installer")

        invokeDownload(acceptedInfo)
        await { shadowOf(app).peekNextStartedActivity() != null }

        val installerIntent = assertNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, installerIntent.action)
        assertEquals("application/vnd.android.package-archive", installerIntent.type)
        assertTrue(installerIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(installerIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val apkUri = assertNotNull(installerIntent.data)
        assertEquals("${app.packageName}.fileprovider", apkUri.authority)
        assertEquals(listOf("verified_updates", cachedApk.name), apkUri.pathSegments)
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
        assertEquals(2, CompletedDownloadManagerShadow.enqueueCount)

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
            await { activityUpdateState() is UpdateUiState.Error }
            val error = activityUpdateState() as UpdateUiState.Error
            assertTrue(error.message.contains(candidate.failureText, ignoreCase = true), error.message)
            assertNull(shadowOf(app).peekNextStartedActivity(), "Rejected ${candidate.fixture} must not launch an installer")
        }
        assertEquals(6, CompletedDownloadManagerShadow.enqueueCount)
    }

    private fun invokeDownload(info: UpdateInfo) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            screen.get(),
            "startUpdateDownload",
            ReflectionHelpers.ClassParameter.from(UpdateInfo::class.java, info)
        )
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

    private fun updateBoundaryDiagnostics(): String {
        val uiState = runCatching { activityUpdateState().toString() }.getOrDefault("<unavailable>")
        val startedActivity = runCatching { shadowOf(app).peekNextStartedActivity()?.toString() }
            .getOrNull() ?: "<none>"
        val cacheFiles = File(app.cacheDir, "updates").listFiles()
            ?.joinToString { "${it.name}:${it.length()}" } ?: "<none>"
        val downloadFiles = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()
            ?.joinToString { "${it.name}:${it.length()}" } ?: "<none>"
        return "ui=$uiState worker=${updateWorkerState()} enqueues=${CompletedDownloadManagerShadow.enqueueCount} " +
            "cache=[$cacheFiles] downloads=[$downloadFiles] installer=$startedActivity"
    }

    private fun awaitPreflightValidation(privateCopy: File, requiredEnqueueCount: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (CompletedDownloadManagerShadow.enqueueCount < requiredEnqueueCount ||
            !privateCopy.isFile || updateWorkerState() != Thread.State.WAITING
        ) {
            check(System.nanoTime() < deadline) { "Timed out waiting for update preflight validation" }
            Thread.sleep(10)
        }
    }

    private fun updateWorkerState(): Thread.State? = Thread.getAllStackTraces().keys
        .firstOrNull { it.name == "byd-update" }?.state

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
        addRow(arrayOf(DownloadManager.STATUS_SUCCESSFUL, 1L, 1L))
    }

    companion object {
        @Volatile var enqueueCount = 0
        fun reset() { enqueueCount = 0 }
    }
}

@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class VerifiedArchivePackageManagerShadow : ShadowApplicationPackageManager() {
    @Implementation
    override fun getPackageArchiveInfo(archiveFilePath: String, flags: Int): PackageInfo? {
        val parsedInfo = super.getPackageArchiveInfo(archiveFilePath, flags) ?: return null
        val verifiedSignatures = verifiedJarSignatures(File(archiveFilePath)) ?: return null
        parsedInfo.signatures = verifiedSignatures.toTypedArray()
        parsedInfo.signingInfo = SigningInfo().apply {
            shadowOf(this).setSignatures(verifiedSignatures.toTypedArray())
        }
        return parsedInfo
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
