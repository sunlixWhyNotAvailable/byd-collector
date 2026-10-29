package com.bydcollector.collector.keepalive

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.util.diagnosticDetail

/** SettingsProvider is accessed with the real APP identity, never a shell-labelled Context. */
object WakeDiscoveryStatus {
    private const val GLOBAL = "bydcollector_wake_discovery_status"
    private const val CACHE = "wakeDiscoveryStatus"
    private const val FIRMWARE = "wakeDiscoveryStatusFirmware"

    fun start(context: Context) {
        val app = context.applicationContext
        val worker = HandlerThread("byd-wake-status").apply { start() }
        val handler = Handler(worker.looper)
        fun read() {
            try {
                val value = Settings.Global.getString(app.contentResolver, GLOBAL).orEmpty()
                if (value.isNotEmpty() && !value.matches(Regex("[0-9]{1,18}:(pending|found|failed):[0-9a-f]{64}:[0-9]+")))
                    error("Invalid OEM wake discovery status: $value")
                val parts = value.split(':')
                val firmware = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(android.os.Build.FINGERPRINT.toByteArray()).joinToString("") { "%02x".format(it) }
                val current = parts.size == 4 && parts[2] == firmware && parts[3] == OemWakeAnalyzer.VERSION.toString()
                app.getSharedPreferences(CollectorSettings.PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(CACHE, if (current) parts.take(2).joinToString(":") else "")
                    .putString(FIRMWARE, android.os.Build.FINGERPRINT).apply()
            } catch (error: Exception) {
                android.util.Log.e("WakeDiscoveryStatus", "Cannot read discovery status", error)
                try {
                    (app as? com.bydcollector.collector.BydCollectorApplication)?.operationalEventJournal?.tryAppend(
                        java.time.Instant.now().toString(), android.os.SystemClock.elapsedRealtime(),
                        "wake_discovery_status_error", "Could not read wake discovery status",
                        error.diagnosticDetail("stage=settings_status_read"))
                } catch (storageError: Exception) {
                    error.addSuppressed(storageError)
                    android.util.Log.e("WakeDiscoveryStatus", "Could not retain original status read failure", error)
                }
            }
        }
        handler.post {
            try {
                app.contentResolver.registerContentObserver(Settings.Global.getUriFor(GLOBAL), false,
                    object : ContentObserver(handler) { override fun onChange(selfChange: Boolean) = read() })
                read()
            } catch (error: Exception) {
                android.util.Log.e("WakeDiscoveryStatus", "Cannot observe discovery status", error)
            }
        }
    }

    fun cached(prefs: android.content.SharedPreferences, enabled: Boolean): String {
        if (prefs.getString(FIRMWARE, "") != android.os.Build.FINGERPRINT) return if (enabled) "pending" else ""
        val value = prefs.getString(CACHE, "").orEmpty().split(':')
        if (value.size != 2) return if (enabled) "pending" else ""
        val generation = value[0].toLongOrNull() ?: return if (enabled) "pending" else ""
        // A successful result survives re-enabling. A failed result is explicitly retried.
        if (enabled && value[1] != "found" && generation != prefs.getLong(WakeDiscoveryRequest.KEY, 0L)) return "pending"
        return value[1]
    }
}
