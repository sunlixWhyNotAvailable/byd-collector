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
    fun activityValidatesDownloadedApkBeforeInstall() {
        val source = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        assertTrue(source.contains("updateApkVerifier.validate("))
        assertInOrder(source, "updateApkVerifier.validate(", "updateDownloader.install(")
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
