package com.bydcollector.collector.data.direct

import android.content.Context
import com.bydcollector.collector.BuildConfig
import java.io.File

object DirectWideDiscoveryArtifacts {
    const val DIRECTORY_NAME = "direct_discovery"
    const val LATEST_FILE_NAME = "wide_discovery_latest.jsonl"
    private const val WINDOWS_PULL_ROOT = "D:\\Work_folder\\!test\\byd web\\manual_pulls"

    fun root(context: Context): File {
        return File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    }

    fun latestFile(context: Context): File {
        return File(root(context), LATEST_FILE_NAME)
    }

    fun latestPath(context: Context): String {
        return latestFile(context).absolutePath
    }

    fun pullCommand(): String {
        return "adb exec-out run-as ${BuildConfig.APPLICATION_ID} cat files/direct_discovery/$LATEST_FILE_NAME > \"$WINDOWS_PULL_ROOT\\bydcollector_discovery\\$LATEST_FILE_NAME\""
    }
}
