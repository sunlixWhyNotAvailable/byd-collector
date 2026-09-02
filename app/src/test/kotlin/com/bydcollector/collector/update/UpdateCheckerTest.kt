package com.bydcollector.collector.update

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun networkErrorDoesNotSuppressTheNextFreshCheck() {
        var calls = 0
        val checker = UpdateChecker(currentVersion = "v1.0.0") {
            calls++
            if (calls == 1) throw IOException("offline")
            releaseJson("v1.0.1")
        }

        assertEquals(UpdateCheckResult.Error("offline"), checker.check())
        assertEquals(UpdateCheckResult.Available(releaseInfo("v1.0.1")), checker.check())
        assertEquals(2, calls)
    }

    @Test
    fun successfulChecksFetchTheCurrentLatestReleaseAgain() {
        var latest = "v1.0.1"
        var calls = 0
        val checker = UpdateChecker(currentVersion = "v1.0.0") {
            calls++
            releaseJson(latest)
        }

        assertEquals(UpdateCheckResult.Available(releaseInfo("v1.0.1")), checker.check())
        latest = "v1.0.2"
        assertEquals(UpdateCheckResult.Available(releaseInfo("v1.0.2")), checker.check())
        assertEquals(2, calls)
    }

    @Test
    fun equalLatestVersionIsUpToDate() {
        val checker = UpdateChecker(currentVersion = "v1.0.1") { releaseJson("v1.0.1") }

        assertEquals(UpdateCheckResult.UpToDate, checker.check())
    }

    @Test
    fun malformedLatestReleaseIsAnError() {
        val checker = UpdateChecker(currentVersion = "v1.0.0") { "{}" }

        val result = checker.check()

        assertTrue(result is UpdateCheckResult.Error)
    }

    private fun releaseJson(version: String): String = """
        {
          "tag_name": "$version",
          "assets": [
            {
              "content_type": "application/vnd.android.package-archive",
              "browser_download_url": "https://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/$version/bydcollector.apk"
            }
          ]
        }
    """.trimIndent()

    private fun releaseInfo(version: String): UpdateInfo = UpdateInfo(
        version = version,
        downloadUrl = "https://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/$version/bydcollector.apk",
        releaseNotes = "",
        downloadContentType = "application/vnd.android.package-archive"
    )
}
