package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubReleaseParserTest {
    @Test
    fun parsesLatestReleaseWithExpectedCollectorApkAsset() {
        val json = """
            {
              "tag_name": "v1.0.6",
              "body": "Release notes",
              "assets": [
                {
                  "name": "notes.txt",
                  "browser_download_url": "https://example.test/notes.txt"
                },
                {
                  "name": "bydcollector-direct-1.0.6-debug.apk",
                  "browser_download_url": "https://example.test/bydcollector.apk"
                }
              ]
            }
        """.trimIndent()

        val info = GitHubReleaseParser.parseLatestRelease(json)

        assertEquals("v1.0.6", info.version)
        assertEquals("https://example.test/bydcollector.apk", info.downloadUrl)
        assertEquals("Release notes", info.releaseNotes)
    }

    @Test
    fun skipsPreviewApkAndSelectsCollectorApk() {
        val json = """
            {
              "tag_name": "v1.1.1",
              "body": "Release notes",
              "assets": [
                {
                  "name": "bydcollector-compose-ui-preview-0.1.0-debug.apk",
                  "browser_download_url": "https://example.test/preview.apk"
                },
                {
                  "name": "bydcollector-direct-1.1.1-debug.apk",
                  "browser_download_url": "https://example.test/bydcollector-direct.apk"
                }
              ]
            }
        """.trimIndent()

        val info = GitHubReleaseParser.parseLatestRelease(json)

        assertEquals("https://example.test/bydcollector-direct.apk", info.downloadUrl)
    }

    @Test
    fun rejectsNonHttpsApkDownloadUrl() {
        val json = """
            {
              "tag_name": "v1.1.1",
              "body": "Release notes",
              "assets": [
                {
                  "name": "bydcollector-direct-1.1.1-debug.apk",
                  "browser_download_url": "http://example.test/bydcollector-direct.apk"
                }
              ]
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            GitHubReleaseParser.parseLatestRelease(json)
        }
    }
}
