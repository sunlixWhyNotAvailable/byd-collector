package com.bydcollector.collector.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

interface GpsLocationSink {
    fun onLocation(sample: GpsLocationSample, isFinal: Boolean = false)
    fun onGap(reason: String, observedAt: String)
}

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
    private val listener = LocationListener { location ->
        val sample = GpsLocationSample.fromLocation(location, bootIdProvider(), segmentIdProvider(), wallClockMs()) ?: return@LocationListener
        gate.offer(sample)?.let { sink.onLocation(it) }
    }

    fun start(): Boolean {
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            sink.onGap("gps_provider_disabled", java.time.Instant.ofEpochMilli(wallClockMs()).toString())
            return false
        }
        return runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, looper)
            true
        }.getOrElse {
            sink.onGap("gps_request_failed:${it::class.java.simpleName}", java.time.Instant.ofEpochMilli(wallClockMs()).toString())
            false
        }
    }

    fun stop(markFinal: Boolean = false) {
        runCatching { locationManager.removeUpdates(listener) }
        gate.flushFinal()?.let { sink.onLocation(it, isFinal = markFinal) }
    }
}
