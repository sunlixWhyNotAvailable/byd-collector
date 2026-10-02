package com.bydcollector.collector.runtime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.service.CollectorSettings
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Retains the public package, data and launcher identity, but never owns the Recents task. */
class RuntimeLauncherActivity : ComponentActivity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(!BuildConfig.RUNTIME_CLIENT)
        val uk = CollectorSettings(this).uiLanguageCode() != "en"
        status = TextView(this).apply {
            text = if (uk) "BYD Collector — запуск…" else "BYD Collector — opening…"
            gravity = android.view.Gravity.CENTER
            textSize = 18f
        }
        setContentView(status)
        val onboarding = getSharedPreferences("runtime_launcher", MODE_PRIVATE)
        if (!onboarding.getBoolean("location_requested", false) &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onboarding.edit().putBoolean("location_requested", true).commit()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        } else launchClient()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) launchClient()
    }

    private fun launchClient() {
        // The operation belongs to the owner, not the client being installed/replaced.
        Thread({
            val result = runCatching { synchronized(INSTALL_LOCK) { ensureClient() } }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    startActivity(Intent(intent.action).setClassName(RuntimeEndpoint.UI_PACKAGE,
                        "com.bydcollector.collector.MainActivity").apply {
                        intent.categories?.forEach(::addCategory)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    })
                    finishAndRemoveTask()
                }.onFailure {
                    status.text = "BYD Collector\n${it.message}\n${if (CollectorSettings(this).uiLanguageCode() == "en") "Tap to retry" else "Натисніть, щоб повторити"}"
                    status.setOnClickListener { status.setOnClickListener(null); launchClient() }
                }
            }
        }, "collector-client-bootstrap").start()
    }

    private fun ensureClient() {
        val expected = assets.open("collector-ui.apk").use(::sha256)
        fun matches(): Boolean = runCatching {
            val pm = packageManager
            val client = pm.getApplicationInfo(RuntimeEndpoint.UI_PACKAGE, 0)
            client.uid != applicationInfo.uid && pm.checkSignatures(packageName, RuntimeEndpoint.UI_PACKAGE) == PackageManager.SIGNATURE_MATCH &&
                File(client.sourceDir).inputStream().use(::sha256) == expected
        }.getOrDefault(false)
        if (matches()) return
        val client = AdbLocalClient(File(filesDir, "adb_keys"))
        val authorization = client.requestAuthorization()
        check(authorization.category == "adb_authorization_connected") { authorization.message }
        val apk = applicationInfo.sourceDir
        val quote = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
        val result = client.execShell("CLASSPATH=${quote(apk)} app_process / ${RuntimeUiInstallerMain::class.java.name} ${quote(apk)} $expected",
            timeoutMs = 120_000)
        check(result.ok && result.output.contains("COLLECTOR_UI_INSTALLED")) {
            "UI installation failed: ${result.error ?: result.output.take(300)}"
        }
        check(matches()) { "Installed UI identity or digest mismatch" }
    }

    companion object { private val INSTALL_LOCK = Any() }
}

/** Shell UID extracts only our embedded, digest-checked APK; no private app data is exposed. */
object RuntimeUiInstallerMain {
    @JvmStatic fun main(args: Array<String>) {
        try {
            require(args.size == 2 && args[1].matches(Regex("[a-f0-9]{64}")))
            val target = File("/data/local/tmp/bydcollector-ui-${args[1]}.apk")
            try {
                ZipFile(args[0]).use { zip ->
                    val entry = requireNotNull(zip.getEntry("assets/collector-ui.apk"))
                    require(entry.size in 1..200_000_000)
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
                check(target.inputStream().use(::sha256) == args[1]) { "Embedded UI digest mismatch" }
                val process = ProcessBuilder("/system/bin/pm", "install", "-r", target.absolutePath).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                check(process.waitFor() == 0 && output.trim() == "Success") { output.take(300) }
                println("COLLECTOR_UI_INSTALLED")
            } finally { target.delete() }
            kotlin.system.exitProcess(0)
        } catch (error: Exception) {
            System.err.println("UI_INSTALL_ERROR: ${error.javaClass.simpleName}: ${error.message}")
            kotlin.system.exitProcess(1)
        }
    }
}

private fun sha256(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
