package com.bydcollector.collector

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.runtime.RuntimeEndpoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.ObjectInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** T05: one real runtime snapshot, foreign-UID denial, and owner/job survival across UI death. */
internal object RuntimeBoundaryGate {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val pm = context.packageManager
        val adb = AdbLocalClient(File(context.filesDir, "adb_keys"))
        val authorization = adb.checkAuthorizationReadOnly()
        check(authorization.category == "adb_authorization_connected") {
            "T05 requires the owner's pre-authorized local ADB key: ${authorization.category}: ${authorization.message}"
        }
        val owner = pm.getApplicationInfo(RuntimeEndpoint.OWNER_PACKAGE, 0)
        val ui = pm.getApplicationInfo(RuntimeEndpoint.UI_PACKAGE, 0)
        check(owner.uid != ui.uid)
        check(pm.checkSignatures(owner.uid, ui.uid) == android.content.pm.PackageManager.SIGNATURE_MATCH)

        fun shell(command: String): String {
            val result = adb.execShell(command, allowAuthorizationPrompt = false)
            check(result.error == null) { "ADB shell command failed: ${result.error}; ${result.output}" }
            return result.output
        }

        val dashboardArgs = Bundle().apply {
            putInt("protocol", RuntimeEndpoint.PROTOCOL)
            putString("revision", BuildConfig.RUNTIME_REVISION)
            putString("operation", "dashboard")
            putString("profile", com.bydcollector.collector.ui.DashboardLoadProfile.ALL_PARAMETERS.name)
            putString("language", "UK")
        }
        context.contentResolver.openTypedAssetFileDescriptor(
            RuntimeEndpoint.uri, "application/vnd.bydcollector.query", dashboardArgs
        )!!.use { descriptor ->
            ObjectInputStream(descriptor.createInputStream()).use { input ->
                check(input.readObject() is com.bydcollector.collector.ui.DashboardState) {
                    "Owner did not return a real dashboard snapshot"
                }
                descriptor.parcelFileDescriptor.checkError()
            }
        }

        check(pm.checkPermission(RuntimeEndpoint.PERMISSION, "com.android.shell") ==
            android.content.pm.PackageManager.PERMISSION_DENIED)
        val foreignUidResult = adb.execShell(
            "content call --uri ${RuntimeEndpoint.uri} --method identity " +
                "--extra int:protocol:${RuntimeEndpoint.PROTOCOL} " +
                "--extra string:revision:${BuildConfig.RUNTIME_REVISION} 2>&1",
            allowAuthorizationPrompt = false
        )
        check(foreignUidResult.error == null || Regex("shell exit code \\d+").matches(foreignUidResult.error)) {
            "Could not capture the shell-UID provider response: ${foreignUidResult.error}; ${foreignUidResult.output}"
        }
        val foreignUidCall = foreignUidResult.output
        check(foreignUidCall.contains("SecurityException", ignoreCase = true) ||
            foreignUidCall.contains("Permission Denial", ignoreCase = true) ||
                (foreignUidCall.contains("requires", ignoreCase = true) &&
                    foreignUidCall.contains(RuntimeEndpoint.PERMISSION))) {
            "A real shell-UID ContentProvider call was not rejected: $foreignUidCall"
        }
        check(!foreignUidCall.contains("package=${RuntimeEndpoint.OWNER_PACKAGE}") &&
            !foreignUidCall.contains("uid=${owner.uid}")) {
            "Foreign UID received the owner identity response: $foreignUidCall"
        }

        val ownerPid = android.os.Process.myPid()
        context.startActivity(Intent().setClassName(RuntimeEndpoint.UI_PACKAGE,
            "com.bydcollector.collector.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(2_000L)
        val firstUiPid = shell("pidof ${RuntimeEndpoint.UI_PACKAGE}").trim()
        check(firstUiPid.isNotEmpty()) { "UI failed to start; inspect logcat" }
        val settings = CollectorSettings(context)
        val previousHost = settings.influxHost()
        val previousPort = settings.influxPort()
        val preferences = context.getSharedPreferences(CollectorSettings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val hadHostPreference = preferences.contains(CollectorSettings.KEY_INFLUX_HOST)
        val hadPortPreference = preferences.contains(CollectorSettings.KEY_INFLUX_PORT)
        val endpoint = HeldInfluxEndpoint()
        fun restoreEndpointSettings() {
            val editor = preferences.edit()
            if (hadHostPreference) editor.putString(CollectorSettings.KEY_INFLUX_HOST, previousHost)
            else editor.remove(CollectorSettings.KEY_INFLUX_HOST)
            if (hadPortPreference) editor.putInt(CollectorSettings.KEY_INFLUX_PORT, previousPort)
            else editor.remove(CollectorSettings.KEY_INFLUX_PORT)
            check(editor.commit()) { "Could not restore temporary Influx endpoint settings" }
        }
        try {
            settings.setInfluxHost("127.0.0.1")
            settings.setInfluxPort(endpoint.port)
            val jobId = com.bydcollector.collector.runtime.RuntimeJobsService.submit(
                context, "influxTest", JSONObject().put("profile", "PRIMARY")
            )
            check(endpoint.writeRequestReceived.await(5, TimeUnit.SECONDS)) {
                "Runtime job never reached its real InfluxDB write request: ${endpoint.failure() ?: "no endpoint error"}"
            }
            check(endpoint.failure() == null) { "Local InfluxDB endpoint failed: ${endpoint.failure()}" }
            awaitPersistedJob(context, jobId, "running", 3_000L)

            shell("am force-stop ${RuntimeEndpoint.UI_PACKAGE}")
            SystemClock.sleep(500L)
            val stoppedUi = adb.execShell("pidof ${RuntimeEndpoint.UI_PACKAGE}", allowAuthorizationPrompt = false)
            check(stoppedUi.error == "shell exit code 1" && stoppedUi.output.isBlank()) {
                "UI process survived force-stop or pidof failed unexpectedly: ${stoppedUi.error}; ${stoppedUi.output}"
            }
            check(shell("pidof ${RuntimeEndpoint.OWNER_PACKAGE}").trim().split(' ').contains(ownerPid.toString())) {
                "UI force-stop killed the distinct runtime owner"
            }
            check(persistedJob(context, jobId)?.optString("status") == "running") {
                "Accepted Influx test did not remain running after UI force-stop"
            }

            endpoint.releaseWriteResponse()
            val completed = awaitPersistedJob(context, jobId, "complete", 8_000L)
            check(completed.optJSONObject("result")?.optBoolean("ok") == true) {
                "Accepted Influx test did not complete successfully: ${completed.optJSONObject("result")}"
            }

            restoreEndpointSettings()
            endpoint.close()
            context.startActivity(Intent().setClassName(RuntimeEndpoint.UI_PACKAGE,
                "com.bydcollector.collector.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(2_000L)
            val relaunchedUiPid = shell("pidof ${RuntimeEndpoint.UI_PACKAGE}").trim()
            check(relaunchedUiPid.isNotEmpty() && relaunchedUiPid != firstUiPid) {
                "UI did not restart as a fresh process after force-stop"
            }
            com.bydcollector.collector.runtime.RuntimeJobsService.presented(context, jobId)
            return "PASS: owner snapshot read; real shell-UID provider call denied; held Influx HTTP job remained running after UI force-stop and completed successfully; owner survived and UI relaunched"
        } finally {
            endpoint.releaseWriteResponse()
            endpoint.close()
            restoreEndpointSettings()
        }
    }

    private fun persistedJob(context: android.content.Context, id: String): JSONObject? {
        val file = java.io.File(context.filesDir, "runtime_jobs.json")
        if (!file.isFile) return null
        return runCatching {
            val jobs = JSONArray(file.readText(Charsets.UTF_8))
            (0 until jobs.length()).map(jobs::getJSONObject).firstOrNull { it.optString("id") == id }
        }.getOrNull()
    }

    private fun awaitPersistedJob(
        context: android.content.Context,
        id: String,
        status: String,
        timeoutMs: Long
    ): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var current: JSONObject? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            current = persistedJob(context, id)
            current?.takeIf { it.optString("status") == status }?.let { return it }
            SystemClock.sleep(25L)
        }
        error("Job $id did not persist status '$status'; last=${current?.optString("status") ?: "missing"}")
    }

    private class HeldInfluxEndpoint : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val closed = AtomicBoolean(false)
        private val serverFailure = AtomicReference<Throwable?>(null)
        val writeRequestReceived = CountDownLatch(1)
        private val releaseWrite = CountDownLatch(1)
        @Volatile private var activeSocket: Socket? = null
        private val thread = Thread(::serve, "runtime-influx-test-endpoint").apply {
            isDaemon = true
            start()
        }

        val port: Int get() = server.localPort
        fun failure(): Throwable? = serverFailure.get()
        fun releaseWriteResponse() { releaseWrite.countDown() }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            releaseWrite.countDown()
            runCatching { activeSocket?.close() }
            runCatching { server.close() }
            runCatching { thread.join(2_000L) }
        }

        private fun serve() {
            try {
                repeat(2) {
                    val client = server.accept()
                    activeSocket = client
                    try {
                        client.soTimeout = 8_000
                        val input = BufferedInputStream(client.getInputStream())
                        val requestLine = readLine(input)
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val header = readLine(input)
                            if (header.isEmpty()) break
                            val separator = header.indexOf(':')
                            if (separator > 0) {
                                headers[header.substring(0, separator).trim().lowercase()] =
                                    header.substring(separator + 1).trim()
                            }
                        }
                        val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
                        check(bodyLength in 0..4_096) { "Unexpected Influx test request body length: $bodyLength" }
                        var remaining = bodyLength
                        val buffer = ByteArray(512)
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                            check(read > 0) { "Influx HTTP request ended before its declared body length" }
                            remaining -= read
                        }
                        when {
                            requestLine.startsWith("GET /ping ") -> respond(client)
                            requestLine.startsWith("POST /write?") -> {
                                writeRequestReceived.countDown()
                                check(releaseWrite.await(20, TimeUnit.SECONDS)) {
                                    "Timed out waiting to release the held Influx write response"
                                }
                                respond(client)
                            }
                            else -> error("Unexpected Influx test request: $requestLine")
                        }
                    } finally {
                        activeSocket = null
                        client.close()
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) serverFailure.compareAndSet(null, error)
                writeRequestReceived.countDown()
            }
        }

        private fun respond(client: Socket) {
            client.getOutputStream().apply {
                write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII))
                flush()
            }
        }

        private fun readLine(input: BufferedInputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0 || next == '\n'.code) break
                if (next != '\r'.code) bytes.write(next)
            }
            return String(bytes.toByteArray(), Charsets.US_ASCII)
        }
    }
}
