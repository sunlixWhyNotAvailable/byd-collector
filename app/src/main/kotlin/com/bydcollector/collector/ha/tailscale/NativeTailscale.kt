package com.bydcollector.collector.ha.tailscale

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import com.bydcollector.collector.BuildConfig
import org.json.JSONObject
import org.json.JSONArray
import com.bydcollector.collector.util.diagnosticDetail
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.net.NetworkInterface

internal data class TailscaleNativeStatus(val id: String, val state: String, val loginUrl: String?, val error: String?) {
    companion object {
        fun parse(value: JSONObject) = TailscaleNativeStatus(value.optString("id"), value.getString("state"),
            value.optString("loginUrl").takeIf(String::isNotBlank), value.optString("error").takeIf(String::isNotBlank))
    }
}

internal class TailscaleTransportException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal object NativeTailscale {
    @Volatile var listener: ((TailscaleNativeStatus) -> Unit)? = null
    private lateinit var context: Context
    private var loaded = false
    @Synchronized fun load(owner: Context) {
        check(!BuildConfig.RUNTIME_CLIENT && Process.myUid() != 2000) { "Tailscale belongs to the application runtime UID" }
        context = owner.applicationContext
        if (!loaded) { System.loadLibrary("collector_tailscale"); loaded = true }
    }
    @JvmStatic external fun call(operation: String, request: String): String
    // Android permits this API while denying Go's netlink interface enumeration.
    @JvmStatic fun interfaces(): String = try {
        val entries = JSONArray()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces != null && interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            val addresses = JSONArray()
            network.interfaceAddresses.forEach { address ->
                addresses.put("${address.address.hostAddress!!.substringBefore('%')}/${address.networkPrefixLength}")
            }
            var flags = 0
            if (network.isUp) flags = flags or 1
            if (network.interfaceAddresses.any { it.broadcast != null }) flags = flags or 2
            if (network.isLoopback) flags = flags or 4
            if (network.isPointToPoint) flags = flags or 8
            if (network.supportsMulticast()) flags = flags or 16
            entries.put(JSONObject().put("name", network.name).put("index", network.index)
                .put("mtu", network.mtu).put("flags", flags).put("addresses", addresses))
        }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val route = manager.activeNetwork?.let(manager::getLinkProperties)?.interfaceName.orEmpty()
        JSONObject().put("interfaces", entries).put("defaultInterface", route).toString()
    } catch (error: Throwable) { JSONObject().put("error", error.diagnosticDetail()).toString() }
    @JvmStatic fun onEvent(json: String) {
        try { listener?.invoke(TailscaleNativeStatus.parse(JSONObject(json))) }
        catch (error: Throwable) { android.util.Log.e("CollectorTailscale", "Native event delivery failed", error) }
    }
}

/** Calls are owned/serialized by HaTailscaleRuntime; callbacks never execute UI work. */
internal class TailscaleNativeSession(private val context: Context, val id: String = UUID.randomUUID().toString()) : Closeable {
    fun start(): TailscaleNativeStatus {
        NativeTailscale.load(context)
        val key = TailscaleStateKey.readOrCreate(context)
        val prefs = context.getSharedPreferences("ha_tailscale_identity", Context.MODE_PRIVATE)
        val name = prefs.getString("name", null) ?: ("byd-collector-" + UUID.randomUUID().toString().take(8)).also {
            check(prefs.edit().putString("name", it).commit()) { "Cannot persist Tailscale node name" }
        }
        try {
            return TailscaleNativeStatus.parse(request("start", JSONObject()
                .put("directory", File(context.noBackupFilesDir, "ha-tailscale").absolutePath)
                .put("name", name).put("key", Base64.getEncoder().encodeToString(key))))
        } finally { key.fill(0) }
    }
    fun status() = TailscaleNativeStatus.parse(request("status"))
    fun networkChanged() { request("network") }
    fun lease(host: String, port: Int): TailscaleBridgeLease {
        val response = request("lease", JSONObject().put("host", host).put("port", port))
        if (response.optString("state") != "ready") throw TailscaleTransportException("Tailscale is ${response.optString("state")}")
        return TailscaleBridgeLease(response.getInt("port"), response.getString("token")) { token ->
            request("release", JSONObject().put("token", token))
        }
    }
    private fun request(operation: String, arguments: JSONObject = JSONObject()): JSONObject {
        val result = JSONObject(NativeTailscale.call(operation, arguments.put("id", id).toString()))
        if (result.optString("state") == "error") throw TailscaleTransportException(result.getString("error"))
        return result
    }
    override fun close() { request("close") }
}

internal class TailscaleBridgeLease(val port: Int, val token: String, private val release: (String) -> Unit) : Closeable {
    private var closed = false
    @Synchronized override fun close() { if (!closed) { release(token); closed = true } }
}
