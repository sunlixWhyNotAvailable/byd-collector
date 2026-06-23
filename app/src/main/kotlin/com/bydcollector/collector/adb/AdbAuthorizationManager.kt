package com.bydcollector.collector.adb

import android.content.Context
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.remote.DirectBridgeManager
import com.bydcollector.collector.system.RequiredAccessChecker
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

//coordinates user-approved local adb setup before any direct autoservice helper work can run
object AdbAuthorizationManager {
    private val running = AtomicBoolean(false)

    fun isAdbGranted(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(ADB_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_AUTH_CATEGORY, null) == "adb_authorization_connected"
    }

    fun selfCheck(
        context: Context,
        store: TelemetryStore,
        allowAutoPrompt: Boolean,
        source: String
    ) {
        //serializes adb auth because concurrent rsa handshakes can confuse the system prompt state
        if (!running.compareAndSet(false, true)) {
            store.recordEvent(
                "adb_self_check_in_progress",
                "ADB self-check is already running",
                "source=$source"
            )
            return
        }

        val appContext = context.applicationContext
        store.recordEvent(
            "adb_self_check_started",
            "ADB authorization self-check started",
            "source=$source target=127.0.0.1:5555,127.0.0.1:1439"
        )

        Thread(
            {
                try {
                    val client = AdbLocalClient(
                        keyDir = File(appContext.filesDir, "adb_keys"),
                        eventSink = { category, message, detail ->
                            store.recordEvent(category, message, detail)
                        }
                    )
                    val checkResult = client.checkAuthorization()
                    rememberAuthorizationResult(appContext, checkResult.category)
                    store.recordEvent(
                        "adb_self_check_result",
                        checkResult.message,
                        "source=$source category=${checkResult.category} ${checkResult.detail.orEmpty()}".trim()
                    )

                    when (checkResult.category) {
                        "adb_authorization_connected" -> {
                            store.recordEvent(
                                "adb_self_check_authorized",
                                "ADB key is already authorized",
                                "source=$source ${checkResult.detail.orEmpty()}".trim()
                            )
                            completeShellSetup(appContext, store, client, source)
                        }
                        "adb_authorization_required" -> maybeSendOneShotAutoPrompt(
                            appContext = appContext,
                            store = store,
                            client = client,
                            source = source,
                            allowAutoPrompt = allowAutoPrompt
                        )
                    }
                } catch (error: Exception) {
                    store.recordEvent(
                        "adb_self_check_error",
                        "ADB authorization self-check failed",
                        "source=$source ${error::class.java.simpleName}: ${error.message ?: "no message"}"
                    )
                    rememberAuthorizationResult(appContext, "adb_self_check_error")
                } finally {
                    running.set(false)
                }
            },
            "bydcollector-adb-self-check"
        ).start()
    }

    fun requestAuthorization(context: Context, store: TelemetryStore) {
        if (!running.compareAndSet(false, true)) {
            store.recordEvent(
                "adb_authorization_in_progress",
                "ADB authorization request is already running"
            )
            return
        }

        val appContext = context.applicationContext
        store.recordEvent(
            "adb_authorization_started",
            "ADB authorization request started",
            "target=127.0.0.1:5555"
        )

        Thread(
            {
                try {
                    val client = AdbLocalClient(
                        keyDir = File(appContext.filesDir, "adb_keys"),
                        eventSink = { category, message, detail ->
                            store.recordEvent(category, message, detail)
                        }
                    )
                    val result = client.requestAuthorization()
                    rememberAuthorizationResult(appContext, result.category)
                    store.recordEvent(result.category, result.message, result.detail)
                    if (result.category == "adb_authorization_connected") {
                        completeShellSetup(appContext, store, client, "grant_button")
                    }
                } catch (error: Exception) {
                    store.recordEvent(
                        "adb_authorization_error",
                        "ADB authorization request failed",
                        "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                    )
                    rememberAuthorizationResult(appContext, "adb_authorization_error")
                } finally {
                    running.set(false)
                }
            },
            "bydcollector-adb-auth"
        ).start()
    }

    private fun maybeSendOneShotAutoPrompt(
        appContext: Context,
        store: TelemetryStore,
        client: AdbLocalClient,
        source: String,
        allowAutoPrompt: Boolean
    ) {
        val fingerprint = client.keyFingerprint()
        val prefs = appContext.getSharedPreferences(ADB_PREFS, Context.MODE_PRIVATE)
        val promptedFingerprint = prefs.getString(KEY_AUTO_PROMPTED_KEY_FINGERPRINT, null)
        val promptedVersion = prefs.getInt(KEY_AUTO_PROMPTED_APP_VERSION, -1)
        val alreadyPromptedForCurrentKeyAndVersion =
            promptedFingerprint == fingerprint && promptedVersion == BuildConfig.VERSION_CODE
        //limits automatic rsa prompts to one per app version/key; manual grant stays available afterwards
        if (!allowAutoPrompt || alreadyPromptedForCurrentKeyAndVersion) {
            store.recordEvent(
                "adb_auto_prompt_skipped",
                "ADB key is not authorized; waiting for manual Grant ADB",
                "source=$source key=${fingerprint.take(16)} prompted=$alreadyPromptedForCurrentKeyAndVersion version=${BuildConfig.VERSION_CODE}"
            )
            return
        }

        prefs.edit()
            .putString(KEY_AUTO_PROMPTED_KEY_FINGERPRINT, fingerprint)
            .putInt(KEY_AUTO_PROMPTED_APP_VERSION, BuildConfig.VERSION_CODE)
            .apply()
        store.recordEvent(
            "adb_auto_prompt_once_started",
            "ADB key is not authorized; sending one automatic RSA public key prompt",
            "source=$source key=${fingerprint.take(16)}"
        )

        val promptResult = client.requestAuthorization()
        rememberAuthorizationResult(appContext, promptResult.category)
        store.recordEvent(
            promptResult.category,
            promptResult.message,
            "source=$source ${promptResult.detail.orEmpty()}".trim()
        )
        if (promptResult.category == "adb_authorization_connected") {
            completeShellSetup(appContext, store, client, source)
        }
    }

    private fun completeShellSetup(
        appContext: Context,
        store: TelemetryStore,
        client: AdbLocalClient,
        source: String
    ) {
        //uses local adb only for app-required grants and helper startup; vehicle reads stay read-only
        RequiredAccessChecker.missingShellGrantCommands(appContext).forEachIndexed { index, command ->
            store.recordEvent(
                "adb_permission_grant_started",
                "Granting missing required access through local ADB",
                "source=$source index=$index command=$command"
            )
            val result = client.execShell(command, timeoutMs = 10_000)
            val detail = buildString {
                append("source=").append(source)
                append(" elapsed_ms=").append(result.elapsedMs)
                result.error?.let { append(" error=").append(it) }
                if (result.output.isNotBlank()) append(" output=").append(result.output.take(500))
            }
            if (result.ok) {
                store.recordEvent(
                    "adb_permission_grant_success",
                    "Required access grant command completed",
                    detail
                )
            } else {
                store.recordEvent(
                    "adb_permission_grant_failed",
                    "Required access grant command failed",
                    detail
                )
            }
        }

        val accessRows = RequiredAccessChecker.check(appContext)
        store.recordEvent(
            "required_access_self_check_result",
            "Required access self-check completed after Grant ADB",
            accessRows.joinToString(separator = "; ") { row ->
                "${row.key}=${if (row.enabled) "enabled" else "disabled"} ${row.detail}"
            }
        )

        val bridgeResult = DirectBridgeManager.ensureRunning(appContext, client)
        if (bridgeResult.ok) {
            store.recordEvent(
                "direct_helper_ready",
                "Direct autoservice helper is ready",
                "source=$source ${bridgeResult.message}"
            )
        } else {
            store.recordEvent(
                "direct_helper_unavailable",
                "Direct autoservice helper is unavailable",
                "source=$source ${bridgeResult.message}"
            )
        }
    }

    private fun rememberAuthorizationResult(appContext: Context, category: String) {
        appContext.getSharedPreferences(ADB_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_AUTH_CATEGORY, category)
            .putLong(KEY_LAST_AUTH_CHECK_AT_MS, System.currentTimeMillis())
            .apply()
    }

    private const val ADB_PREFS = "adb_authorization"
    private const val KEY_AUTO_PROMPTED_KEY_FINGERPRINT = "adb_auto_prompt_key_fingerprint"
    private const val KEY_AUTO_PROMPTED_APP_VERSION = "adb_auto_prompt_app_version"
    private const val KEY_LAST_AUTH_CATEGORY = "adb_last_auth_category"
    private const val KEY_LAST_AUTH_CHECK_AT_MS = "adb_last_auth_check_at_ms"
}
