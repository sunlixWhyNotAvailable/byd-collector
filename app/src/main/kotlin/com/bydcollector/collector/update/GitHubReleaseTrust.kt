package com.bydcollector.collector.update

import java.net.URI
import java.util.Locale

object GitHubReleaseTrust {
    private const val API_HOST = "api.github.com"
    private const val DOWNLOAD_HOST = "github.com"
    private const val API_PATH = "/repos/sunlixWhyNotAvailable/byd-collector/releases"
    private const val DOWNLOAD_PATH = "/sunlixWhyNotAvailable/byd-collector/releases/download/"
    private const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"

    fun requireTrustedApiUrl(url: String): String {
        require(isTrustedApiUrl(url)) { "Update API URL is not trusted" }
        return url
    }

    fun requireTrustedApkDownloadUrl(url: String, contentType: String?): String {
        require(isTrustedApkDownloadUrl(url, contentType)) { "APK download URL is not trusted" }
        return url
    }

    fun isTrustedApiUrl(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(API_HOST, ignoreCase = true) &&
            uri.path.startsWith(API_PATH)
    }

    fun isTrustedApkDownloadUrl(url: String, contentType: String?): Boolean {
        val uri = parse(url) ?: return false
        val apkByType = contentType.equals(APK_CONTENT_TYPE, ignoreCase = true)
        val apkByPath = uri.path.lowercase(Locale.US).endsWith(".apk")
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(DOWNLOAD_HOST, ignoreCase = true) &&
            uri.path.startsWith(DOWNLOAD_PATH) &&
            (apkByType || apkByPath)
    }

    fun sanitizeFileToken(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
    }

    private fun parse(url: String): URI? {
        return runCatching { URI(url) }.getOrNull()
    }
}
