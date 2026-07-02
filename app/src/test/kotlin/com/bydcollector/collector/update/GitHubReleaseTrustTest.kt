package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubReleaseTrustTest {
    @Test
    fun acceptsOnlyCollectorGitHubApiEndpoint() {
        assertTrue(GitHubReleaseTrust.isTrustedApiUrl("https://api.github.com/repos/sunlixWhyNotAvailable/byd-collector/releases/latest"))
        assertFalse(GitHubReleaseTrust.isTrustedApiUrl("http://api.github.com/repos/sunlixWhyNotAvailable/byd-collector/releases/latest"))
        assertFalse(GitHubReleaseTrust.isTrustedApiUrl("https://github.com/repos/sunlixWhyNotAvailable/byd-collector/releases/latest"))
        assertFalse(GitHubReleaseTrust.isTrustedApiUrl("https://api.github.com/repos/other/byd-collector/releases/latest"))
    }

    @Test
    fun acceptsOnlyCollectorReleaseDownloadApkCandidates() {
        assertTrue(
            GitHubReleaseTrust.isTrustedApkDownloadUrl(
                url = "https://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/v1.3.4/random-name.apk",
                contentType = null
            )
        )
        assertTrue(
            GitHubReleaseTrust.isTrustedApkDownloadUrl(
                url = "https://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/v1.3.4/random-name.bin",
                contentType = "application/vnd.android.package-archive"
            )
        )
        assertFalse(
            GitHubReleaseTrust.isTrustedApkDownloadUrl(
                url = "http://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/v1.3.4/app.apk",
                contentType = "application/vnd.android.package-archive"
            )
        )
        assertFalse(
            GitHubReleaseTrust.isTrustedApkDownloadUrl(
                url = "https://github.com/other/byd-collector/releases/download/v1.3.4/app.apk",
                contentType = "application/vnd.android.package-archive"
            )
        )
    }

    @Test
    fun sanitizesReleaseTagForLocalFilenameOnly() {
        assertTrue(GitHubReleaseTrust.sanitizeFileToken("v1.3.4").contains("v1.3.4"))
        assertFalse(GitHubReleaseTrust.sanitizeFileToken("../v1.3.4").contains("/"))
        assertFalse(GitHubReleaseTrust.sanitizeFileToken("..\\v1.3.4").contains("\\"))
    }
}
