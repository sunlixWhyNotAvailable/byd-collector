package com.bydcollector.collector.runtime

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.ui.compose.*
import com.bydcollector.collector.runtime.StartupAccessSequence.Step
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Retains the public package, data and launcher identity, but never owns the Recents task. */
class RuntimeLauncherActivity : ComponentActivity() {
    private lateinit var model: RuntimeAccessModel
    private val handler = Handler(Looper.getMainLooper())
    private var resumed by mutableStateOf(false)
    private var focused by mutableStateOf(false)
    private var adbPosted = false
    private val adbStart = Runnable {
        adbPosted = false
        if (model.flow.step != Step.ADB || !model.flow.begin(resumed, focused)) return@Runnable
        model.revision++
        val pending = model
        val app = applicationContext
        event("adb_started", "manual=${pending.manualAdb}")
        AdbAuthorizationManager.requestStartupAccess(app, pending.manualAdb) {
            Handler(Looper.getMainLooper()).post { pending.complete(Step.ADB) }
        }
    }
    private val backgroundSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        completeBackground()
    }
    private val overlaySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        event("overlay_returned", "granted=${Settings.canDrawOverlays(this)}")
        (application as BydCollectorApplication).updateRuntime.onPresentationAccessChanged()
        model.complete(Step.OVERLAY)
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        event("location_result", "granted=${grants.values.any { it }}")
        model.complete(Step.LOCATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(!BuildConfig.RUNTIME_CLIENT)
        model = ViewModelProvider(this)[RuntimeAccessModel::class.java]
        if (!model.initialized) model.reset(request(intent))
        setContent {
            LaunchedEffect(model.revision, resumed, focused) { continueAccess() }
            BydCollectorTheme(true) {
                Box(Modifier.fillMaxSize().background(LocalBydPalette.current.background)) {
                    if (model.installing) {
                        RuntimeRecoveryPanel(
                            language = launcherLanguage(),
                            phase = RuntimeRecoveryUiPhase.INSTALLING,
                            onRetry = {},
                            onClose = ::finishAndRemoveTask
                        )
                    } else if (model.error != null) {
                        RuntimeRecoveryPanel(
                            language = launcherLanguage(),
                            phase = RuntimeRecoveryUiPhase.FAILED,
                            onRetry = ::retryClientInstall,
                            onClose = ::finishAndRemoveTask
                        )
                    }
                    if (model.backgroundVisible) {
                        BackgroundAppsSetupPrompt(strings(launcherLanguage()),
                            onOpenSettings = ::openBackgroundSettings, onDismiss = ::completeBackground)
                    }
                }
                BackHandler(enabled = model.flow.inFlight) {
                    if (model.backgroundVisible) completeBackground()
                }
            }
        }
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); focused = hasFocus }
    override fun onDestroy() { handler.removeCallbacks(adbStart); super.onDestroy() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask coalesces repeated launcher/manual requests while any access screen is active.
        if (model.flow.step != Step.COMPLETE || model.installing) return
        setIntent(intent)
        model.reset(request(intent))
    }

    private fun request(intent: Intent): String =
        if (intent.component?.className == RuntimeLauncherActivity::class.java.name)
            intent.getStringExtra("accessRequest")?.takeIf { it in setOf("adb", "overlay", "background") } ?: "startup"
        else "startup" // Exported launcher alias cannot inject a manual access request.

    private fun launcherLanguage(): UiLanguage =
        UiLanguage.fromCode(CollectorSettings(this).uiLanguageCode())

    private fun retryClientInstall() {
        if (model.installing) return
        model.reset("startup")
        model.flow.complete(Step.BACKGROUND)
        model.manualAdb = true
    }

    private fun continueAccess() {
        if (!resumed || !focused || isFinishing || model.error != null || model.flow.inFlight) return
        val flow = model.flow
        val prefs = getSharedPreferences("startup_setup", MODE_PRIVATE)
        when (flow.step) {
            Step.BACKGROUND -> {
                if (flow.request == "startup" && automaticAccessCompleted) { launchClient(); return }
                if (flow.request == "startup" && (prefs.getInt("background_settings_version", -1) == BuildConfig.VERSION_CODE ||
                    prefs.getBoolean("background_settings_pending_return", false))) {
                    completeBackground()
                } else if (flow.begin(resumed, focused)) {
                    model.backgroundVisible = true
                    event("background_prompt", "request=${flow.request}")
                }
            }
            Step.ADB -> if (!adbPosted) {
                adbPosted = true
                handler.postDelayed(adbStart, 600L)
            }
            Step.OVERLAY -> {
                if (!CollectorSettings(this).isUpdateHintEnabled() || Settings.canDrawOverlays(this) ||
                    (flow.request == "startup" && prefs.getBoolean("overlay_permission_setup_consumed", false))) {
                    model.complete(Step.OVERLAY)
                } else if (flow.begin(resumed, focused)) {
                    prefs.edit().putBoolean("overlay_permission_setup_consumed", true).apply()
                    event("overlay_requested", "request=${flow.request}")
                    try { overlaySettings.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    catch (error: RuntimeException) { event("overlay_failed", error.toString()); model.complete(Step.OVERLAY) }
                }
            }
            Step.LOCATION -> {
                val legacy = getSharedPreferences("runtime_launcher", MODE_PRIVATE)
                if (legacy.getBoolean("location_requested", false) || prefs.getBoolean("location_permission_setup_consumed", false) ||
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    model.complete(Step.LOCATION)
                } else if (flow.begin(resumed, focused)) {
                    prefs.edit().putBoolean("location_permission_setup_consumed", true).apply()
                    event("location_requested", "owner=$packageName")
                    try { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                    catch (error: RuntimeException) { event("location_failed", error.toString()); model.complete(Step.LOCATION) }
                }
            }
            Step.COMPLETE -> {
                if (flow.request == "startup") { automaticAccessCompleted = true; launchClient() }
                else finishAndRemoveTask()
            }
        }
    }

    private fun completeBackground() {
        if (model.flow.step != Step.BACKGROUND) return
        getSharedPreferences("startup_setup", MODE_PRIVATE).edit()
            .putInt("background_settings_version", BuildConfig.VERSION_CODE)
            .remove("background_settings_pending_return").apply()
        model.backgroundVisible = false
        event("background_handled", "version=${BuildConfig.VERSION_CODE}; OEM toggle not verified")
        model.complete(Step.BACKGROUND)
    }

    private fun openBackgroundSettings() {
        model.backgroundVisible = false
        getSharedPreferences("startup_setup", MODE_PRIVATE).edit().putBoolean("background_settings_pending_return", true).apply()
        val intents = listOf(Intent(Intent.ACTION_MAIN).setClassName("com.byd.appstartmanagement",
            "com.byd.appstartmanagement.frame.AppStartManagement"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")), Intent(Settings.ACTION_SETTINGS))
        for (target in intents) {
            try { backgroundSettings.launch(target); event("background_settings_opened", target.toString()); return }
            catch (error: RuntimeException) { event("background_settings_failed", error.toString()) }
        }
        completeBackground()
    }

    private fun event(name: String, detail: String) {
        (application as BydCollectorApplication).recordUpdateEvent("startup_access_$name", detail)
    }

    private fun launchClient() {
        if (model.installing) return
        if (model.clientReady) {
            startActivity(Intent(intent.action).setClassName(RuntimeEndpoint.UI_PACKAGE,
                "com.bydcollector.collector.MainActivity").apply {
                intent.categories?.forEach(::addCategory)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finishAndRemoveTask()
            return
        }
        model.installing = true
        model.revision++
        val pending = model
        val app = applicationContext
        // The operation belongs to the owner, not the client being installed/replaced.
        Thread({
            val result = runCatching { synchronized(INSTALL_LOCK) { ensureClient(app) } }
            Handler(Looper.getMainLooper()).post {
                pending.installing = false
                pending.clientReady = result.isSuccess
                pending.error = result.exceptionOrNull()?.let {
                    event("client_install_failed", "${it.javaClass.simpleName}: ${it.message}")
                    "${it.javaClass.simpleName}: ${it.message}"
                }
                pending.revision++
            }
        }, "collector-client-bootstrap").start()
    }

    companion object {
        private val INSTALL_LOCK = Any()
        private var verifiedClient: Pair<String, Long>? = null
        private var automaticAccessCompleted = false

    private fun ensureClient(context: Context) = with(context) {
        fun installedIdentity(): Pair<String, Long>? = runCatching {
            val pm = packageManager
            val info = pm.getPackageInfo(RuntimeEndpoint.UI_PACKAGE, 0)
            val client = pm.getApplicationInfo(RuntimeEndpoint.UI_PACKAGE, 0)
            check(client.uid != applicationInfo.uid &&
                pm.checkSignatures(packageName, RuntimeEndpoint.UI_PACKAGE) == PackageManager.SIGNATURE_MATCH)
            client.sourceDir to info.lastUpdateTime
        }.getOrNull()
        // Installed APK paths are immutable; a replacement invalidates this process-local proof.
        val installed = installedIdentity()
        if (installed != null && installed == verifiedClient) return
        val expected = assets.open("collector-ui.apk").use(::sha256)
        fun matches(): Boolean = runCatching {
            val identity = installedIdentity() ?: return@runCatching false
            (File(identity.first).inputStream().use(::sha256) == expected).also { matches ->
                if (matches) verifiedClient = identity
            }
        }.getOrDefault(false)
        if (matches()) return
        val client = AdbLocalClient(File(filesDir, "adb_keys"))
        val authorization = client.checkAuthorizationReadOnly()
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

    }
}

/** Retained across Activity recreation, including outstanding RSA and Android permission results. */
class RuntimeAccessModel : ViewModel() {
    internal lateinit var flow: StartupAccessSequence
    internal var initialized = false
    internal var revision by mutableIntStateOf(0)
    internal var backgroundVisible by mutableStateOf(false)
    internal var error by mutableStateOf<String?>(null)
    internal var manualAdb = false
    internal var installing = false
    internal var clientReady = false

    internal fun reset(request: String) {
        flow = StartupAccessSequence(request)
        initialized = true
        manualAdb = request == "adb"
        backgroundVisible = false
        error = null
        clientReady = false
        revision++
    }

    internal fun complete(step: Step) { flow.complete(step); revision++ }
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
