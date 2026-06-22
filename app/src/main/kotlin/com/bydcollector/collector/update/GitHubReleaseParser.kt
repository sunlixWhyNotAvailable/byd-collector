package com.bydcollector.collector.update

import org.json.JSONObject

object GitHubReleaseParser {
    fun parseLatestRelease(body: String): UpdateInfo {
        val json = JSONObject(body)
        val assets = json.getJSONArray("assets")

        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.getString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return UpdateInfo(
                    version = json.getString("tag_name"),
                    downloadUrl = asset.getString("browser_download_url"),
                    releaseNotes = json.optString("body", "")
                )
            }
        }

        throw IllegalArgumentException("release has no APK asset")
    }
}
