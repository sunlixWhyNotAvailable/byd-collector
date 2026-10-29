package com.bydcollector.collector.keepalive

import android.content.SharedPreferences
import com.bydcollector.collector.service.CollectorSettings

/** Only the APP settings owner increments; a quick off/on cannot disappear between heartbeats. */
object WakeDiscoveryRequest {
    const val KEY = "wakeDiscoveryRequest"
    fun stage(prefs: SharedPreferences, editor: SharedPreferences.Editor, enabled: Boolean) {
        val wasEnabled = prefs.getBoolean(CollectorSettings.KEY_KEEP_WIFI, false) ||
            prefs.getBoolean(CollectorSettings.KEY_KEEP_MOBILE_DATA, false)
        if (enabled && !wasEnabled) editor.putLong(KEY, Math.addExact(prefs.getLong(KEY, 0L), 1L))
    }
}
