package com.bydcollector.collector.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.mqtt.HaMqttConfig
import com.bydcollector.collector.mqtt.MqttDestinationIdentity
import com.bydcollector.collector.mqtt.MqttPublicationJournal
import com.bydcollector.collector.mqtt.HaDiscoveryBuilder
import com.bydcollector.collector.security.KeystoreSecretStore
import com.bydcollector.collector.service.CollectorSettings
import java.util.concurrent.CopyOnWriteArraySet

/** UI cache only. A successful edit means the owner durably committed it. */
class RuntimePreferences private constructor(private val context: Context) : SharedPreferences {
    @Volatile private var values = emptyMap<String, Any?>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val main = Handler(Looper.getMainLooper())

    init { refresh() }

    @Synchronized fun refresh() = replace(RuntimeEndpoint.call(context, "preferences"))

    private fun replace(snapshot: Bundle) {
        val next = snapshot.keySet().associateWith { key ->
            @Suppress("DEPRECATION") val value = snapshot.get(key)
            if (value is ArrayList<*>) value.filterIsInstance<String>().toSet() else value
        }
        val previous = values
        values = next
        RuntimeUiAppearance.save(context, com.bydcollector.collector.ui.compose.UiLanguage.fromCode(
            next[CollectorSettings.KEY_UI_LANGUAGE_CODE] as? String ?: CollectorSettings.DEFAULT_UI_LANGUAGE_CODE))
        val changed = (previous.keys + next.keys).filter { previous[it] != next[it] }
        if (changed.isNotEmpty()) main.post {
            changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this, key) } }
        }
    }

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues?.toMutableSet()
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(listener) }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(listener) }
    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val changes = Bundle()
        private val removals = arrayListOf<String>()
        private fun key(key: String): String { removals.remove(key); return key }
        override fun putString(key: String, value: String?) = apply { if (value == null) remove(key) else changes.putString(key(key), value) }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply {
            if (values == null) remove(key) else changes.putStringArrayList(key(key), ArrayList(values))
        }
        override fun putInt(key: String, value: Int) = apply { changes.putInt(key(key), value) }
        override fun putLong(key: String, value: Long) = apply { changes.putLong(key(key), value) }
        override fun putFloat(key: String, value: Float) = apply { changes.putFloat(key(key), value) }
        override fun putBoolean(key: String, value: Boolean) = apply { changes.putBoolean(key(key), value) }
        override fun remove(key: String) = apply { changes.remove(key); if (key !in removals) removals.add(key) }
        override fun clear(): SharedPreferences.Editor = error("The UI cannot clear runtime settings")
        override fun commit(): Boolean = synchronized(this@RuntimePreferences) {
            val response = RuntimeEndpoint.call(context, "preferences.edit", Bundle().apply {
                putBundle("changes", changes)
                putStringArrayList("remove", removals)
            })
            val committed = response.getBoolean("committed")
            if (committed) replace(requireNotNull(response.getBundle("values")))
            committed && response.getBoolean("credentialsStored", true)
        }
        override fun apply() { check(commit()) { "Runtime settings were not persisted" } }
    }

    companion object {
        @Volatile private var client: RuntimePreferences? = null
        fun isInitialized(): Boolean = client != null
        fun get(context: Context): SharedPreferences {
            if (!BuildConfig.RUNTIME_CLIENT) return context.getSharedPreferences(CollectorSettings.PREFS_NAME, Context.MODE_PRIVATE)
            return client ?: synchronized(this) {
                client ?: RuntimePreferences(context.applicationContext).also { client = it }
            }
        }
        fun refresh(context: Context) { if (BuildConfig.RUNTIME_CLIENT) (get(context) as RuntimePreferences).refresh() }

        fun snapshot(preferences: SharedPreferences): Bundle = Bundle().apply {
            preferences.all.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
                }
            }
        }

        fun editOwner(context: Context, preferences: SharedPreferences, arguments: Bundle): Bundle {
            val incomingChanges = arguments.getBundle("changes") ?: Bundle()
            val removals = arguments.getStringArrayList("remove").orEmpty().toMutableList()
            val usernameUpdateRequested = incomingChanges.containsKey(MQTT_USERNAME_EDIT_KEY)
            val passwordUpdateRequested = incomingChanges.containsKey(MQTT_PASSWORD_EDIT_KEY)
            @Suppress("DEPRECATION") val usernameUpdate = incomingChanges.get(MQTT_USERNAME_EDIT_KEY)
            @Suppress("DEPRECATION") val passwordUpdate = incomingChanges.get(MQTT_PASSWORD_EDIT_KEY)
            val changes = Bundle(incomingChanges).apply {
                remove(MQTT_USERNAME_EDIT_KEY)
                remove(MQTT_PASSWORD_EDIT_KEY)
            }
            val guardedKeys = changes.keySet() + removals + buildSet {
                if (usernameUpdateRequested) add(CollectorSettings.KEY_MQTT_USERNAME)
                if (passwordUpdateRequested) add(CollectorSettings.KEY_MQTT_PASSWORD)
            }
            return RuntimeJobsService.withMqttConfigurationEdit(context, guardedKeys) {
                val secretUpdates = usernameUpdateRequested || passwordUpdateRequested
                require(changes.size() + removals.size + (if (secretUpdates) 1 else 0) <= 256) { "Too many setting changes" }
                require(!usernameUpdateRequested || usernameUpdate is String) { "Invalid MQTT username" }
                require(!passwordUpdateRequested || passwordUpdate is String) { "Invalid MQTT password" }
                val keyPattern = Regex("[a-zA-Z0-9_.-]{1,160}")
                require((changes.keySet() + removals).all(keyPattern::matches)) { "Invalid setting key" }
                var characters = (usernameUpdate as? String).orEmpty().length + (passwordUpdate as? String).orEmpty().length
                changes.keySet().forEach { key ->
                    @Suppress("DEPRECATION") val value = changes.get(key)
                    when (value) {
                        is String -> characters += value.length
                        is Boolean, is Int, is Long -> Unit
                        is Float -> require(value.isFinite())
                        is ArrayList<*> -> {
                            require(value.size <= 128 && value.all { it is String })
                            characters += value.filterIsInstance<String>().sumOf { it.length }
                        }
                        else -> error("Unsupported setting type")
                    }
                }
                require(characters <= 128 * 1024) { "Settings payload is too large" }
                if (RuntimeJobsService.isMqttConfigurationEdit(guardedKeys)) {
                    rejectPendingMqttIdentity(context, preferences, changes, removals.toSet(), usernameUpdate as? String)
                }

                val secretStore = if (secretUpdates) KeystoreSecretStore(context) else null
                var credentialsStored = true
                if (usernameUpdateRequested) {
                    val value = usernameUpdate as String
                    credentialsStored = storeSecret(secretStore!!, CollectorSettings.SECRET_MQTT_USERNAME, value)
                    if (CollectorSettings.KEY_MQTT_USERNAME !in removals) removals.add(CollectorSettings.KEY_MQTT_USERNAME)
                }
                if (passwordUpdateRequested) {
                    val value = passwordUpdate as String
                    credentialsStored = storeSecret(secretStore!!, CollectorSettings.SECRET_MQTT_PASSWORD, value) && credentialsStored
                    if (CollectorSettings.KEY_MQTT_PASSWORD !in removals) removals.add(CollectorSettings.KEY_MQTT_PASSWORD)
                }

                val editor = preferences.edit()
                removals.forEach(editor::remove)
                changes.keySet().forEach { key ->
                    @Suppress("DEPRECATION") val value = changes.get(key)
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is ArrayList<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> error("Unsupported setting type")
                    }
                }
                if (!credentialsStored) editor.putBoolean(CollectorSettings.KEY_MQTT_ENABLED, false)
                val committed = editor.commit()
                Bundle().apply {
                    putBoolean("committed", committed)
                    putBoolean("credentialsStored", credentialsStored)
                    putBundle("values", snapshot(preferences))
                }
            }
        }

        internal const val MQTT_USERNAME_EDIT_KEY = "__runtimeMqttUsernameEdit"
        internal const val MQTT_PASSWORD_EDIT_KEY = "__runtimeMqttPasswordEdit"

        private fun storeSecret(store: KeystoreSecretStore, name: String, value: String): Boolean =
            if (value.isBlank()) store.clear(name) else store.write(name, value) && store.read(name) == value

        private fun rejectPendingMqttIdentity(
            context: Context,
            preferences: SharedPreferences,
            changes: Bundle,
            removals: Set<String>,
            usernameUpdate: String?
        ) {
            fun string(key: String, default: String): String = when {
                key in removals -> default
                changes.containsKey(key) -> changes.getString(key) ?: default
                else -> preferences.getString(key, default) ?: default
            }
            fun integer(key: String, default: Int): Int = when {
                key in removals -> default
                changes.containsKey(key) -> changes.getInt(key, default)
                else -> preferences.getInt(key, default)
            }
            val alternativePort = when {
                CollectorSettings.KEY_MQTT_ALTERNATIVE_PORT in removals -> null
                changes.containsKey(CollectorSettings.KEY_MQTT_ALTERNATIVE_PORT) ->
                    changes.getInt(CollectorSettings.KEY_MQTT_ALTERNATIVE_PORT)
                preferences.contains(CollectorSettings.KEY_MQTT_ALTERNATIVE_PORT) ->
                    preferences.getInt(CollectorSettings.KEY_MQTT_ALTERNATIVE_PORT, 0)
                else -> null
            }
            val username = usernameUpdate ?: KeystoreSecretStore(context)
                .read(CollectorSettings.SECRET_MQTT_USERNAME).orEmpty()
            val config = HaMqttConfig(
                enabled = false,
                discoveryEnabled = true,
                host = string(CollectorSettings.KEY_MQTT_HOST, ""),
                port = integer(CollectorSettings.KEY_MQTT_PORT, CollectorSettings.DEFAULT_MQTT_PORT),
                username = username.takeIf(String::isNotBlank),
                password = null,
                clientId = "",
                topicPrefix = "",
                discoveryPrefix = "",
                deviceId = string(CollectorSettings.KEY_MQTT_DEVICE_ID, HaDiscoveryBuilder.DEVICE_ID),
                enabledCategories = emptySet(),
                alternativeHost = string(CollectorSettings.KEY_MQTT_ALTERNATIVE_HOST, "").takeIf(String::isNotBlank),
                alternativePort = alternativePort
            )
            val scopes = MqttDestinationIdentity.configuredScopes(config).mapTo(linkedSetOf()) { it.key }
            val journal = MqttPublicationJournal(context)
            try {
                check(!journal.isCleanupPending(config.normalizedDeviceId(), scopes)) { "mqtt_cleanup_pending_identity" }
            } finally {
                journal.close()
            }
        }
    }
}
