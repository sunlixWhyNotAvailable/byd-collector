package com.bydcollector.collector.update

import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.util.readBoundedUtf8
import java.net.HttpURLConnection
import java.net.URL

//checks github releases from a background executor and returns ui-safe update states
class UpdateChecker(
    private val settings: CollectorSettings,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    fun check(force: Boolean): UpdateCheckResult {
        val now = nowMs()
        val last = settings.lastUpdateCheckAtMs()
        //throttles automatic checks so normal app launches do not hammer the github api
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
        val trustedUrl = GitHubReleaseTrust.requireTrustedApiUrl(BuildConfig.UPDATE_RELEASES_API_URL)
        val connection = (URL(trustedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "BYDCollector-UpdateCheck")
        }
        return readUpdateResponse(connection)
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L
    }
}

internal fun readUpdateResponse(connection: HttpURLConnection): String {
    return try {
        val code = connection.responseCode
        if (code !in 200..299) error("GitHub API HTTP $code")
        val response = readBoundedUtf8(connection.inputStream, UPDATE_RESPONSE_MAX_CHARS)
            ?: error("GitHub API response is empty")
        check(!response.truncated) { "GitHub API response exceeds $UPDATE_RESPONSE_MAX_CHARS characters" }
        response.text
    } finally {
        connection.disconnect()
    }
}

internal const val UPDATE_RESPONSE_MAX_CHARS = 262_144
