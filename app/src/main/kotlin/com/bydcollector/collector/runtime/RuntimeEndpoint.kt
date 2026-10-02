package com.bydcollector.collector.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.security.KeystoreSecretStore
import com.bydcollector.collector.service.CollectorSettings

/** The existing UID retains its private databases, keys, helpers and foreground workers. */
object RuntimeEndpoint {
    const val OWNER_PACKAGE = "com.bydcollector.collector"
    const val UI_PACKAGE = "com.bydcollector.collector.ui"
    const val AUTHORITY = "$OWNER_PACKAGE.runtime"
    const val PERMISSION = "$OWNER_PACKAGE.permission.RUNTIME"
    const val PROTOCOL = 1
    val uri: Uri = Uri.parse("content://$AUTHORITY")
    private val changePending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val changeHandler = android.os.Handler(android.os.Looper.getMainLooper())

    internal fun changed(context: Context) {
        if (!changePending.compareAndSet(false, true)) return
        changeHandler.postDelayed({
            changePending.set(false)
            context.contentResolver.notifyChange(uri, null)
        }, 50L)
    }

    fun forward(context: Context, operation: String, arguments: Bundle = Bundle()): Boolean {
        if (!BuildConfig.RUNTIME_CLIENT) return false
        call(context, "command.$operation", arguments)
        RuntimeClient.refresh()
        return true
    }

    fun call(context: Context, operation: String, arguments: Bundle = Bundle()): Bundle {
        verifyOwner(context)
        arguments.putInt("protocol", PROTOCOL)
        arguments.putString("revision", BuildConfig.RUNTIME_REVISION)
        return context.contentResolver.call(uri, operation, null, arguments)
            ?: error("Collector runtime returned no response")
    }

    internal fun verifyOwner(context: Context) {
        check(BuildConfig.RUNTIME_CLIENT) { "Runtime IPC is only for the UI client" }
        val pm = context.packageManager
        val provider = pm.resolveContentProvider(AUTHORITY, 0)
            ?: error("Collector runtime is not installed")
        check(provider.packageName == OWNER_PACKAGE && provider.readPermission == PERMISSION &&
            provider.writePermission == PERMISSION &&
            pm.checkSignatures(context.packageName, OWNER_PACKAGE) == PackageManager.SIGNATURE_MATCH) {
            "Collector runtime identity mismatch"
        }
    }
}

class RuntimeEndpointProvider : ContentProvider() {
    override fun onCreate(): Boolean = !BuildConfig.RUNTIME_CLIENT

    private fun authorize(extras: Bundle?): Context {
        check(!BuildConfig.RUNTIME_CLIENT)
        val ctx = requireNotNull(context)
        val caller = Binder.getCallingUid()
        val uiUid = runCatching { ctx.packageManager.getApplicationInfo(RuntimeEndpoint.UI_PACKAGE, 0).uid }.getOrNull()
        // A signature alone would also admit other applications signed with our key.
        if (caller != Process.myUid() && (caller != uiUid ||
                ctx.packageManager.checkSignatures(caller, Process.myUid()) != PackageManager.SIGNATURE_MATCH)) {
            throw SecurityException("Not the Collector UI")
        }
        require(extras?.getInt("protocol") == RuntimeEndpoint.PROTOCOL) { "Runtime protocol mismatch" }
        require(extras.getString("revision") == BuildConfig.RUNTIME_REVISION) { "Runtime/client build mismatch; open BYD Collector from its launcher" }
        return ctx
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): android.content.res.AssetFileDescriptor {
        val ctx = authorize(opts)
        require(uri == RuntimeEndpoint.uri && mimeTypeFilter == "application/vnd.bydcollector.query")
        val identity = Binder.clearCallingIdentity()
        return try {
            android.content.res.AssetFileDescriptor(RuntimeReads.open(ctx, requireNotNull(opts?.getString("operation")),
                requireNotNull(opts)), 0, android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = authorize(extras)
        requireNotNull(extras)
        val identity = Binder.clearCallingIdentity()
        return try {
            when (method) {
                "reopen" -> {
                    val app = ctx.applicationContext as com.bydcollector.collector.BydCollectorApplication
                    val settings = CollectorSettings(ctx)
                    val reopened = com.bydcollector.collector.service.CollectorService.clearShutdownForExplicitReopen(ctx)
                    if (reopened) {
                        check(app.ensureDebugStorageReady()) { "Secondary storage recovery failed" }
                        val store = com.bydcollector.collector.BydCollectorApplication.store(ctx)
                        check(settings.storageCutoverJournal() == null) { "Storage cutover still pending" }
                        com.bydcollector.collector.system.CollectorAutoStart.recoverFromForeground(ctx, settings, store)
                    }
                    Bundle().apply { putBoolean("reopened", reopened) }
                }
                "control" -> RuntimeControl.call(ctx, requireNotNull(extras.getString("operation")), extras)
                "state" -> RuntimeClient.snapshotOwner(ctx.applicationContext as com.bydcollector.collector.BydCollectorApplication)
                "job.presented" -> {
                    RuntimeJobsService.presented(ctx, requireNotNull(extras.getString("id")))
                    Bundle()
                }
                "job.prepare" -> {
                    RuntimeJobsService.prepareResult(ctx, requireNotNull(extras.getString("id")))
                    Bundle()
                }
                "job.submit" -> Bundle().apply {
                    val json = extras.getString("args") ?: "{}"
                    require(json.length <= 64 * 1024)
                    putString("id", RuntimeJobsService.submit(ctx, requireNotNull(extras.getString("kind")), org.json.JSONObject(json)))
                }
                "event" -> {
                    val category = requireNotNull(extras.getString("category"))
                    val message = requireNotNull(extras.getString("message"))
                    val detail = extras.getString("detail")
                    require(category.length <= 160 && message.length <= 4096 && (detail?.length ?: 0) <= 8192)
                    (ctx.applicationContext as com.bydcollector.collector.BydCollectorApplication)
                        .withTelemetryStoreRead { it.recordEvent(category, message, detail) }
                    Bundle()
                }
                "preferences" -> RuntimePreferences.snapshot(RuntimePreferences.get(ctx))
                "preferences.edit" -> RuntimePreferences.editOwner(RuntimePreferences.get(ctx), requireNotNull(extras))
                "secret.read", "secret.write", "secret.clear" -> {
                    val name = extras.getString("name")
                    require(name in setOf(CollectorSettings.SECRET_MQTT_USERNAME, CollectorSettings.SECRET_MQTT_PASSWORD,
                        CollectorSettings.SECRET_INFLUX_USERNAME, CollectorSettings.SECRET_INFLUX_PASSWORD,
                        CollectorSettings.SECRET_TELEGRAM_BOT_TOKEN)) { "Unknown credential" }
                    val store = KeystoreSecretStore(ctx)
                    Bundle().apply {
                        when (method) {
                            "secret.read" -> putString("value", store.read(name!!))
                            "secret.clear" -> putBoolean("ok", store.clear(name!!))
                            else -> {
                                val value = requireNotNull(extras.getString("value"))
                                require(value.length <= 16_384) { "Credential too large" }
                                putBoolean("ok", store.write(name!!, value))
                            }
                        }
                    }
                }
                "identity" -> Bundle().apply {
                    putInt("protocol", RuntimeEndpoint.PROTOCOL)
                    putInt("uid", Process.myUid())
                    putInt("pid", Process.myPid())
                    putString("package", ctx.packageName)
                    putInt("version", BuildConfig.VERSION_CODE)
                }
                else -> {
                    require(method.startsWith("command.")) { "Unknown runtime operation" }
                    RuntimeCommands.dispatch(ctx, method.removePrefix("command."), requireNotNull(extras))
                    Bundle()
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("No SQL interface")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
}
