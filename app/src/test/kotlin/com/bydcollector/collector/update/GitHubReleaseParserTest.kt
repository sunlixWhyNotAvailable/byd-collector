package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubReleaseParserTest {
    @Test
    fun parsesLatestReleaseWithFirstApkAsset() {
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
}
