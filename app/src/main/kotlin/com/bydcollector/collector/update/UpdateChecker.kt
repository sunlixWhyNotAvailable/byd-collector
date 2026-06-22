package com.bydcollector.collector.update

import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.service.CollectorSettings
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(
    private val settings: CollectorSettings,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    fun check(force: Boolean): UpdateCheckResult {
        val now = nowMs()
        val last = settings.lastUpdateCheckAtMs()
        if (!force && now - last < CHECK_INTERVAL_MS) {
            return UpdateCheckResult.UpToDate
        }
        settings.setLastUpdateCheckAtMs(now)

        return runCatching {
            val info = GitHubReleaseParser.parseLatestRelease(fetchLatestRelease())
            if (UpdateVersionComparator.isNewer(info.version, BuildConfig.VERSION_NAME)) {
                UpdateCheckResult.Available(info)
            } else {
                UpdateCheckResult.UpToDate
            }
        }.getOrElse { error ->
            UpdateCheckResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    private fun fetchLatestRelease(): String {
        //keep github url outside ui so release target is build-time configurable
        val connection = (URL(BuildConfig.UPDATE_RELEASES_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "BYDCollector-UpdateCheck")
        }
        return connection.useResponse()
    }

    private fun HttpURLConnection.useResponse(): String {
        return try {
            val code = responseCode
            if (code !in 200..299) error("GitHub API HTTP $code")
            inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            disconnect()
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L
    }
}
