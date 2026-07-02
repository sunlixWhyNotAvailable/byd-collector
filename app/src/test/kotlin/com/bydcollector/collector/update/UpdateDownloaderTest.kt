package com.bydcollector.collector.update

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateDownloaderTest {
    @Test
    fun deletesOldCollectorApksBeforeNewDownload() {
        val dir = Files.createTempDirectory("bydcollector-update-test").toFile()
        val oldApk = dir.resolve("bydcollector-1.1.1.apk").apply { writeText("old") }
        val currentApk = dir.resolve("bydcollector-1.1.2.apk").apply { writeText("current") }
        val unrelated = dir.resolve("other.apk").apply { writeText("other") }

        UpdateDownloader.deleteOldUpdateApks(dir, keepFileName = "bydcollector-1.1.2.apk")

        assertFalse(oldApk.exists())
        assertTrue(currentApk.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun localApkNameSanitizesVersionToken() {
        assertFalse(UpdateDownloader.updateApkName("../v1.3.4").contains("/"))
        assertFalse(UpdateDownloader.updateApkName("..\\v1.3.4").contains("\\"))
        assertTrue(UpdateDownloader.updateApkName("v1.3.4").endsWith(".apk"))
    }
}
