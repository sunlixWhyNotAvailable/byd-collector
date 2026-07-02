package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubReleaseParserTest {
    @Test
    fun parsesReleaseByTrustedDownloadUrlNotAssetName() {
        val json = """
            {
              "tag_name": "v1.3.4",
              "body": "Release notes",
              "assets": [
                {
                  "name": "notes.txt",
                  "browser_download_url": "https://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/v1.3.4/notes.txt"
                },
                {
                  "name": "whatever-user-named-it.bin",
                  "content_type": "application/vnd.android.package-archive",
                  "browser_download_url": "https://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/v1.3.4/whatever-user-named-it.bin"
                }
              ]
            }
        """.trimIndent()

        val info = GitHubReleaseParser.parseLatestRelease(json)

        assertEquals("v1.3.4", info.version)
        assertEquals("https://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/v1.3.4/whatever-user-named-it.bin", info.downloadUrl)
        assertEquals("application/vnd.android.package-archive", info.downloadContentType)
        assertEquals("Release notes", info.releaseNotes)
    }

    @Test
    fun skipsUntrustedDownloadUrlsAndSelectsTrustedCollectorReleaseUrl() {
        val json = """
            {
              "tag_name": "v1.1.1",
              "body": "Release notes",
              "assets": [
                {
                  "name": "app.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "browser_download_url": "https://github.com/other/byd-collector/releases/download/v1.1.1/app.apk"
                },
                {
                  "name": "release.apk",
                  "browser_download_url": "https://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/v1.1.1/release.apk"
                }
              ]
            }
        """.trimIndent()

        val info = GitHubReleaseParser.parseLatestRelease(json)

        assertEquals("https://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/v1.1.1/release.apk", info.downloadUrl)
    }

    @Test
    fun rejectsReleaseWithoutTrustedDownloadUrl() {
        val json = """
            {
              "tag_name": "v1.1.1",
              "body": "Release notes",
              "assets": [
                {
                  "name": "bydcollector-direct-1.1.1-debug.apk",
                  "browser_download_url": "http://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/v1.1.1/bydcollector-direct.apk"
                }
              ]
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            GitHubReleaseParser.parseLatestRelease(json)
        }
    }
}
