package com.bydcollector.collector.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import java.time.Instant

interface GpsLocationSink {
    fun onLocation(sample: GpsLocationSample, isFinal: Boolean = false)
    fun onUntrusted(sample: GpsLocationSample, reason: String) = Unit
    fun onGap(reason: String, observedAt: String)
    fun onProviderChanged(enabled: Boolean) = Unit
}

data class GpsStartResult(
    val started: Boolean,
    val failureReason: String? = null,
    val recordGap: Boolean = false
)

/** Native Android GPS source. Permission and foreground-service ownership remain with the caller. */
class AndroidGpsLocationSource(
    context: Context,
    private val sink: GpsLocationSink,
    private val bootIdProvider: () -> String,
    private val segmentIdProvider: () -> String = { "gps" },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val looper: Looper = Looper.getMainLooper()
) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val gate = GpsSampleGate()
    private val trustGate = GpsTrustGate()
    private var providerReceiverRegistered = false
    private var lastGpsEnabled: Boolean? = null
    private val listener = LocationListener { location ->
        val sample = GpsLocationSample.fromLocation(location, bootIdProvider(), segmentIdProvider(), wallClockMs()) ?: return@LocationListener
        gate.offer(sample)?.let(::emit)
    }
    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocationManager.PROVIDERS_CHANGED_ACTION) return
            val provider = intent.getStringExtra(LocationManager.EXTRA_PROVIDER_NAME)
            if (provider != null && provider != LocationManager.GPS_PROVIDER) return
            val enabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
            if (enabled == lastGpsEnabled) return
            lastGpsEnabled = enabled
            if (!enabled) runCatching { locationManager.removeUpdates(listener) }
            sink.onProviderChanged(enabled)
        }
    }

    fun start(anchor: GpsLocationSample? = null): GpsStartResult {
        trustGate.beginRecovery(anchor)
        registerProviderReceiver()
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return GpsStartResult(false, "gps_permission_missing")
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return GpsStartResult(false, "gps_provider_disabled", recordGap = true)
        }
        return runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, looper)
            GpsStartResult(true)
        }.getOrElse {
            GpsStartResult(false, "gps_request_failed:${it::class.java.simpleName}", recordGap = true)
        }
    }

    fun stop(markFinal: Boolean = false) {
        runCatching { locationManager.removeUpdates(listener) }
        unregisterProviderReceiver()
        gate.flushFinal()?.let { emit(it, markFinal) }
    }

    private fun registerProviderReceiver() {
        if (providerReceiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                providerReceiver,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onSuccess {
            providerReceiverRegistered = true
            lastGpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
        }
    }

    private fun unregisterProviderReceiver() {
        if (!providerReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(providerReceiver) }
        providerReceiverRegistered = false
        lastGpsEnabled = null
    }

    private fun emit(sample: GpsLocationSample, isFinal: Boolean = false) {
        val decision = trustGate.offer(sample)
        if (decision.callbackGap) {
            val receivedAt = runCatching { Instant.ofEpochMilli(sample.receiveWallTimeMs).toString() }.getOrElse { sample.observedAt }
            sink.onGap("gps_callback_gap", receivedAt)
        }
        if (decision.trusted) sink.onLocation(sample, isFinal)
        else sink.onUntrusted(sample, decision.reason ?: "untrusted")
    }
}
