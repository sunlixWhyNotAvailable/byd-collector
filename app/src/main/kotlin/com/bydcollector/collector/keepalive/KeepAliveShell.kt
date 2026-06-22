package com.bydcollector.collector.keepalive

import com.bydcollector.collector.adb.AdbLocalClient

data class KeepAliveShellResult(
    val ok: Boolean,
    val output: String,
    val error: String?,
    val elapsedMs: Long
)

interface KeepAliveShell {
    fun exec(command: String, timeoutMs: Int): KeepAliveShellResult
}

class AdbKeepAliveShell(
    private val adbClient: AdbLocalClient
) : KeepAliveShell {
    override fun exec(command: String, timeoutMs: Int): KeepAliveShellResult {
        val result = adbClient.execShell(command, timeoutMs = timeoutMs)
        return KeepAliveShellResult(
            ok = result.ok,
            output = result.output,
            error = result.error,
            elapsedMs = result.elapsedMs
        )
    }
}
