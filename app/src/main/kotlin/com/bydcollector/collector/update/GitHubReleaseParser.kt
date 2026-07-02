package com.bydcollector.collector.update

import org.json.JSONObject

object GitHubReleaseParser {
    fun parseLatestRelease(body: String): UpdateInfo {
        val json = JSONObject(body)
        val assets = json.getJSONArray("assets")

        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val downloadUrl = asset.getString("browser_download_url")
            val contentType = asset.optString("content_type").takeIf { it.isNotBlank() }
            if (GitHubReleaseTrust.isTrustedApkDownloadUrl(downloadUrl, contentType)) {
                return UpdateInfo(
                    version = json.getString("tag_name"),
                    downloadUrl = GitHubReleaseTrust.requireTrustedApkDownloadUrl(downloadUrl, contentType),
                    downloadContentType = contentType,
                    releaseNotes = json.optString("body", "")
                )
            }
        }

        throw IllegalArgumentException("release has no trusted BYD Collector APK asset")
    }
}
