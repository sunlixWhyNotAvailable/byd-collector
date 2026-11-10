package com.bydcollector.collector.ha.tailscale

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.SystemClock
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.mqtt.MqttClientFacade
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.util.diagnosticDetail
import com.bydcollector.collector.util.dispatchOperationalEvent
import com.bydcollector.collector.util.sharedOperationalEventExecutor
import java.time.Instant
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One owner in the runtime APK. The UI sees a snapshot, never native credentials. */
internal class HaTailscaleRuntime private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("ha_tailscale_transport", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "collector-tailnet-owner") }
    private val generation = AtomicLong()
    private val nativeLock = Any()
    private var native: TailscaleNativeSession? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mqttClients = Collections.newSetFromMap(WeakHashMap<MqttClientFacade, Boolean>())
    private val readyListeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()
    @Volatile var enabled = preferences.getBoolean("enabled", false)
        private set
    @Volatile private var pending = false
    @Volatile private var phase = if (enabled) "starting" else "off"
    @Volatile private var loginUrl: String? = null
    @Volatile private var detail: String? = null
    @Volatile private var activation = 0L
    @Volatile private var requestedEnabled = false

    init {
        check(!BuildConfig.RUNTIME_CLIENT)
        NativeTailscale.listener = { state -> worker.execute { onNativeState(state) } }
    }

    @Synchronized fun snapshot() = Bundle().apply {
        putBoolean("enabled", enabled); putBoolean("pending", pending)
        putString("phase", phase); putString("loginUrl", loginUrl); putString("error", detail)
        putLong("operation", activation)
    }

    @Synchronized fun setEnabled(value: Boolean) {
        if (pending || enabled == value) return
        if (CollectorSettings(context).isUserShutdownRequested()) return
        activation = generation.incrementAndGet()
        val operation = activation
        requestedEnabled = value
        pending = true; detail = null; loginUrl = null; phase = if (value) "starting" else "switching"
        worker.execute {
            try {
                if (operation != generation.get()) return@execute
                if (!value) { commitMode(false, operation); return@execute }
                val state = synchronized(nativeLock) { ensureNative().status() }
                onNativeState(state)
            } catch (error: Throwable) { failActivation(operation, error) }
        }
    }

    @Synchronized fun cancel(operation: Long) {
        if (!pending || operation != activation) return
        generation.incrementAndGet()
        pending = false; loginUrl = null; detail = null; phase = if (enabled) "ready" else "off"
        worker.execute { if (!enabled) closeNativeReported("cancel") }
    }

    private fun onNativeState(state: TailscaleNativeStatus) {
        if (synchronized(nativeLock) { native?.id } != state.id) return
        if (state.state == "transport_error") {
            record("transport_error", "runtime_id=${state.id} request_id=${state.requestId ?: "none"} " +
                "profile=${state.profile ?: "unknown"} ${state.error.orEmpty()}")
            return
        }
        if (!pending && !enabled) return
        detail = state.error
        if (state.state == "ready") {
            loginUrl = null
            if (pending && requestedEnabled && activation == generation.get()) {
                try { commitMode(true, activation) } catch (error: Throwable) { failActivation(activation, error) }
                return
            }
        }
        if (pending && state.state == "error") {
            failActivation(activation, TailscaleTransportException(state.error ?: "Native authorization failed")); return
        }
        if (pending || enabled) {
            val previous = phase
            phase = state.state
            // Login URLs are only delivered to the requesting UI, never to diagnostics.
            loginUrl = state.loginUrl?.takeIf { pending && it.startsWith("https://") }
            if (previous != phase || state.error != null) record("native_state", "state=$phase" + state.error?.let { " error=$it" }.orEmpty())
            if (previous != "ready" && phase == "ready") notifyReady(state.id)
        }
    }

    private fun commitMode(value: Boolean, operation: Long) {
        if (operation != generation.get()) return
        phase = "switching"
        HaTransportGate.switch {
            if (operation != generation.get()) return@switch
            val clients = synchronized(mqttClients) { mqttClients.toList() }
            clients.forEach { client ->
                val result = client.disconnect(null)
                if (!result.ok) throw TailscaleTransportException("MQTT handover close: ${result.category}: ${result.message}")
            }
            synchronized(this) {
                if (operation != generation.get()) return@switch
                check(preferences.edit().putBoolean("enabled", value).commit()) { "Cannot persist HA transport selection" }
                enabled = value
                pending = false; loginUrl = null; detail = null; phase = if (value) "ready" else "off"
            }
        }
        if (operation != generation.get()) return
        record("transport_selected", "transport=${if(value) "tailscale" else "android"} operation=$operation")
        if (value) synchronized(nativeLock) { native?.id }?.let(::notifyReady)
        if (!value) closeNativeReported("disable") else releaseIfIdle()
    }

    private fun failActivation(operation: Long, error: Throwable) {
        record("activation_failed", error.diagnosticDetail("operation=$operation"))
        synchronized(this) {
            if (operation != generation.get()) return
            pending = false; loginUrl = null
            detail = "${error::class.java.simpleName}: ${error.message?.lineSequence()?.firstOrNull()}"; phase = "error"
        }
        if (!enabled) closeNativeReported("activation_failure")
    }

    private fun ensureNative(): TailscaleNativeSession {
        native?.let { return it }
        check(!CollectorSettings(context).isUserShutdownRequested()) { "Collector was shut down" }
        val session = TailscaleNativeSession(context)
        native = session
        try {
            session.start()
            worker.execute {
                try {
                    val status = synchronized(nativeLock) { if (native === session) session.status() else null }
                    status?.let(::onNativeState)
                }
                catch (error: Throwable) { record("initial_status_failed", error.diagnosticDetail()) }
            }
            registerNetworkCallback()
            return session
        } catch (error: Throwable) {
            native = null
            try { session.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun bridge(host: String, port: Int, requestId: String? = null, profile: String? = null): TailscaleBridgeLease? {
        if (!enabled) return null
        return synchronized(nativeLock) { ensureNative().lease(host, port, requestId, profile) }
    }

    fun register(client: MqttClientFacade) { synchronized(mqttClients) { mqttClients.add(client) } }

    fun isReady(id: String? = null): Boolean = enabled && !pending && phase == "ready" &&
        synchronized(nativeLock) { native?.let { id == null || id == it.id } == true }

    fun addReadyListener(listener: (String) -> Unit) { readyListeners.add(listener) }
    fun removeReadyListener(listener: (String) -> Unit) { readyListeners.remove(listener) }
    private fun notifyReady(id: String) {
        readyListeners.forEach { listener ->
            try { listener(id) }
            catch (error: Throwable) { record("ready_notification_failed", error.diagnosticDetail()) }
        }
    }

    fun releaseIfIdle() {
        worker.execute {
            val settings = CollectorSettings(context)
            if (!pending && !settings.isMqttEnabled() && !settings.isInfluxEnabled()) {
                try { HaTransportGate.switch {
                    if (!pending && !settings.isMqttEnabled() && !settings.isInfluxEnabled()) closeNativeReported("idle")
                } }
                catch (error: Throwable) { record("idle_close_deferred", error.diagnosticDetail()) }
            }
        }
    }

    fun shutdown() {
        generation.incrementAndGet(); pending = false; loginUrl = null
        worker.execute { closeNativeReported("shutdown"); phase = if(enabled) "stopped" else "off" }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed()
            private fun changed() { worker.execute {
                try { synchronized(nativeLock) { native?.networkChanged() } }
                catch (error: Throwable) { record("network_notification_failed", error.diagnosticDetail()) }
            } }
        }
        // Observe all Internet networks; do not bind sockets or alter VPN policy.
        manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        networkCallback = callback
    }

    private fun closeNativeReported(reason: String) {
        synchronized(nativeLock) {
            networkCallback?.let { callback ->
                try { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
                catch (error: Throwable) { record("network_unregister_failed", error.diagnosticDetail()) }
            }
            networkCallback = null
            val session = native; native = null
            try { session?.close() } catch (error: Throwable) { record("native_close_failed", error.diagnosticDetail("reason=$reason")) }
        }
    }

    private fun record(event: String, detail: String? = null) {
        com.bydcollector.collector.runtime.RuntimeEndpoint.changed(context)
        val app = context.applicationContext as BydCollectorApplication
        val time = Instant.now().toString(); val elapsed = SystemClock.elapsedRealtime()
        dispatchOperationalEvent(sharedOperationalEventExecutor) {
            app.operationalEventJournal.append(time, elapsed, "ha_tailscale", event, detail)
        }
    }

    companion object {
        @Volatile private var owner: HaTailscaleRuntime? = null
        @Synchronized fun initialize(context: Context): HaTailscaleRuntime {
            check(!BuildConfig.RUNTIME_CLIENT)
            return owner ?: HaTailscaleRuntime(context.applicationContext).also { owner = it }
        }
        fun current(): HaTailscaleRuntime? = owner
    }
}
