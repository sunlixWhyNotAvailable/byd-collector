package com.bydcollector.collector.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateApkVerifierContractTest {
    @Test
    fun verifierUsesCurrentPackageVersionAndSigningCertificates() {
        val source = sourceFile("com/bydcollector/collector/update/UpdateApkVerifier.kt").readText()

        assertTrue(source.contains("context.packageName"))
        assertTrue(source.contains("BuildConfig.VERSION_CODE"))
        assertTrue(source.contains("PackageManager.GET_SIGNING_CERTIFICATES"))
        assertTrue(source.contains("PackageManager.GET_SIGNATURES"))
        assertTrue(source.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(source.contains("archiveVersion <= BuildConfig.VERSION_CODE"))
        assertFalse(source.contains("com.bydcollector.app"))
    }

    @Test
    fun activityValidatesPrivateApkCopyAndChecksDigestBeforeInstall() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        assertTrue(source.contains("copyDownloadedApkForInstall("))
        assertTrue(source.contains("val validation = updateApkVerifier.validate(verifiedFile)"))
        assertTrue(source.contains("finalValidation.sha256 != verified.sha256"))
        assertInOrder(source, "copyDownloadedApkForInstall(", "updateApkVerifier.validate(verifiedFile)")
        assertInOrder(source, "finalValidation.sha256 != verified.sha256", "updateDownloader.install(")
    }

    @Test
    fun verifierReturnsFileShaAndLogsInstalledPackageInfoFailure() {
        val source = sourceFile("com/bydcollector/collector/update/UpdateApkVerifier.kt").readText()

        assertTrue(source.contains("val sha256: String?"))
        assertTrue(source.contains("private fun fileSha256(file: File): String"))
        assertTrue(source.contains("Log.w(TAG, \"installed package info unavailable\", error)"))
        assertTrue(source.contains("UpdateApkValidation.ok(sha256)"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing first token: $first")
        assertTrue(secondIndex >= 0, "Missing second token: $second")
        assertTrue(firstIndex < secondIndex, "Expected `$first` before `$second`")
    }
}
