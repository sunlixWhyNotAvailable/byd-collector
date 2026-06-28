package com.bydcollector.collector.update

import org.json.JSONObject

object GitHubReleaseParser {
    fun parseLatestRelease(body: String): UpdateInfo {
        val json = JSONObject(body)
        val assets = json.getJSONArray("assets")

        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.getString("name")
            if (isExpectedCollectorApk(name)) {
                return UpdateInfo(
                    version = json.getString("tag_name"),
                    downloadUrl = requireHttps(asset.getString("browser_download_url")),
                    releaseNotes = json.optString("body", "")
                )
            }
        }

        throw IllegalArgumentException("release has no expected BYD Collector APK asset")
    }

    private fun isExpectedCollectorApk(name: String): Boolean {
        val lowered = name.lowercase()
        return lowered.endsWith(".apk") &&
            (lowered.contains("bydcollector") || lowered.contains("byd-collector")) &&
            !lowered.contains("preview") &&
            !lowered.contains("uipreview")
    }

    private fun requireHttps(url: String): String {
        require(url.startsWith("https://", ignoreCase = true)) { "APK download URL must use HTTPS" }
        return url
    }
}
