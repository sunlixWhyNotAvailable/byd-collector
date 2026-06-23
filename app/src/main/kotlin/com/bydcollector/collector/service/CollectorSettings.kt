package com.bydcollector.collector.service

import android.content.Context
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.ha.HaIntegrationCategories
import com.bydcollector.collector.influx.InfluxConfig
import com.bydcollector.collector.keepalive.KeepAliveConfig
import com.bydcollector.collector.mqtt.HaMqttConfig

//persistent user settings facade that also records operational events for later diagnostics
class CollectorSettings(
    context: Context,
    private val store: TelemetryStore? = null
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_START, false)

    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "auto_start_enabled" else "auto_start_disabled",
            message = if (enabled) AUTO_START_ENABLED_UK else AUTO_START_DISABLED_UK
        )
    }

    fun isPollingEnabled(): Boolean = prefs.getBoolean(KEY_POLLING_ENABLED, false)

    fun setPollingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_POLLING_ENABLED, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "polling_enabled" else "polling_disabled",
            message = "Polling ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isDebugPollingEnabled(): Boolean = prefs.getBoolean(KEY_DEBUG_POLLING_ENABLED, false)

    fun setDebugPollingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_POLLING_ENABLED, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "debug_polling_enabled" else "debug_polling_disabled",
            message = "Debug polling ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isDebugAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_DEBUG_AUTO_START, false)

    fun setDebugAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_AUTO_START, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "debug_auto_start_enabled" else "debug_auto_start_disabled",
            message = "Debug autostart ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun debugBatchSize(): Int = prefs.getInt(KEY_DEBUG_BATCH_SIZE, DEFAULT_DEBUG_BATCH_SIZE)
        .coerceIn(1, MAX_DEBUG_BATCH_SIZE)

    //keeps debug autostart safer than manual debug because it may run without the user watching the tablet
    fun debugAutostartBatchSize(): Int = debugBatchSize().coerceAtMost(SAFE_DEBUG_AUTOSTART_BATCH_SIZE)

    fun setDebugBatchSize(value: Int) {
        val safeValue = value.coerceIn(1, MAX_DEBUG_BATCH_SIZE)
        prefs.edit().putInt(KEY_DEBUG_BATCH_SIZE, safeValue).apply()
        store?.recordEvent(
            category = "debug_batch_size_updated",
            message = "Debug batch size updated",
            detail = "batch_size=$safeValue"
        )
    }

    fun mqttConfig(): HaMqttConfig {
        //builds an immutable snapshot so async mqtt work uses one consistent set of settings
        return HaMqttConfig(
            enabled = isMqttEnabled(),
            discoveryEnabled = true,
            host = mqttHost(),
            port = mqttPort(),
            username = mqttUsername().takeIf { it.isNotBlank() },
            password = mqttPassword().takeIf { it.isNotBlank() },
            clientId = mqttClientId(),
            topicPrefix = mqttTopicPrefix(),
            discoveryPrefix = mqttDiscoveryPrefix(),
            enabledCategories = mqttEnabledCategories()
        )
    }

    fun isMqttEnabled(): Boolean = prefs.getBoolean(KEY_MQTT_ENABLED, false)

    fun setMqttEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MQTT_ENABLED, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "mqtt_enabled" else "mqtt_disabled",
            message = "MQTT ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isMqttAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_MQTT_AUTO_START, false)

    fun setMqttAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MQTT_AUTO_START, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "mqtt_auto_start_enabled" else "mqtt_auto_start_disabled",
            message = "MQTT auto-start ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isHaDiscoveryEnabled(): Boolean = prefs.getBoolean(KEY_HA_DISCOVERY_ENABLED, false)

    fun setHaDiscoveryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HA_DISCOVERY_ENABLED, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "ha_discovery_enabled" else "ha_discovery_disabled",
            message = "Home Assistant discovery ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun mqttHost(): String = prefs.getString(KEY_MQTT_HOST, "") ?: ""

    fun setMqttHost(host: String) {
        prefs.edit().putString(KEY_MQTT_HOST, host.trim()).apply()
    }

    fun mqttPort(): Int = prefs.getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT).coerceIn(1, 65535)

    fun setMqttPort(port: Int) {
        prefs.edit().putInt(KEY_MQTT_PORT, port.coerceIn(1, 65535)).apply()
    }

    fun mqttUsername(): String = prefs.getString(KEY_MQTT_USERNAME, "") ?: ""

    fun setMqttUsername(username: String) {
        prefs.edit().putString(KEY_MQTT_USERNAME, username).apply()
    }

    fun mqttPassword(): String = prefs.getString(KEY_MQTT_PASSWORD, "") ?: ""

    fun setMqttPassword(password: String) {
        prefs.edit().putString(KEY_MQTT_PASSWORD, password).apply()
    }

    fun mqttClientId(): String {
        return prefs.getString(KEY_MQTT_CLIENT_ID, HaMqttConfig.DEFAULT_CLIENT_ID)
            ?.ifBlank { HaMqttConfig.DEFAULT_CLIENT_ID }
            ?: HaMqttConfig.DEFAULT_CLIENT_ID
    }

    fun setMqttClientId(clientId: String) {
        prefs.edit().putString(
            KEY_MQTT_CLIENT_ID,
            clientId.ifBlank { HaMqttConfig.DEFAULT_CLIENT_ID }
        ).apply()
    }

    fun mqttTopicPrefix(): String {
        return prefs.getString(KEY_MQTT_TOPIC_PREFIX, HaMqttConfig.DEFAULT_TOPIC_PREFIX)
            ?: HaMqttConfig.DEFAULT_TOPIC_PREFIX
    }

    fun setMqttTopicPrefix(topicPrefix: String) {
        prefs.edit().putString(
            KEY_MQTT_TOPIC_PREFIX,
            topicPrefix.trim('/').ifBlank { HaMqttConfig.DEFAULT_TOPIC_PREFIX }
        ).apply()
    }

    fun mqttDiscoveryPrefix(): String {
        return prefs.getString(KEY_MQTT_DISCOVERY_PREFIX, HaMqttConfig.DEFAULT_DISCOVERY_PREFIX)
            ?: HaMqttConfig.DEFAULT_DISCOVERY_PREFIX
    }

    fun setMqttDiscoveryPrefix(discoveryPrefix: String) {
        prefs.edit().putString(
            KEY_MQTT_DISCOVERY_PREFIX,
            discoveryPrefix.trim('/').ifBlank { HaMqttConfig.DEFAULT_DISCOVERY_PREFIX }
        ).apply()
    }

    fun mqttEnabledCategories(): Set<String> {
        return prefs.getStringSet(KEY_MQTT_CATEGORIES, HaIntegrationCategories.defaults)
            ?.toSet()
            ?: HaIntegrationCategories.defaults
    }

    fun setMqttCategoryEnabled(category: String, enabled: Boolean) {
        val categories = mqttEnabledCategories().toMutableSet()
        if (enabled) {
            categories.add(category)
        } else {
            categories.remove(category)
        }
        prefs.edit().putStringSet(KEY_MQTT_CATEGORIES, categories).apply()
    }

    fun influxConfig(): InfluxConfig {
        //shares category selection with mqtt by default so ha live state and history stay aligned
        return InfluxConfig(
            enabled = isInfluxEnabled(),
            host = influxHost(),
            port = influxPort(),
            database = influxDatabase(),
            username = influxUsername().takeIf { it.isNotBlank() },
            password = influxPassword().takeIf { it.isNotBlank() },
            measurement = influxMeasurement(),
            enabledCategories = effectiveInfluxCategories()
        )
    }

    fun isInfluxEnabled(): Boolean = prefs.getBoolean(KEY_INFLUX_ENABLED, false)

    fun setInfluxEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INFLUX_ENABLED, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "influx_enabled" else "influx_disabled",
            message = "InfluxDB export ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isInfluxAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_INFLUX_AUTO_START, false)

    fun setInfluxAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INFLUX_AUTO_START, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "influx_auto_start_enabled" else "influx_auto_start_disabled",
            message = "InfluxDB auto-start ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isHaSharedCategoriesEnabled(): Boolean = prefs.getBoolean(KEY_HA_SHARED_CATEGORIES, true)

    fun setHaSharedCategoriesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HA_SHARED_CATEGORIES, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "ha_shared_categories_enabled" else "ha_shared_categories_disabled",
            message = "HA shared categories ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isUpdateAutoCheckEnabled(): Boolean = prefs.getBoolean(KEY_UPDATE_AUTO_CHECK, true)

    fun setUpdateAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_AUTO_CHECK, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "update_auto_check_enabled" else "update_auto_check_disabled",
            message = "Update auto-check ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun lastUpdateCheckAtMs(): Long = prefs.getLong(KEY_UPDATE_LAST_CHECK_AT_MS, 0L)

    fun setLastUpdateCheckAtMs(value: Long) {
        prefs.edit().putLong(KEY_UPDATE_LAST_CHECK_AT_MS, value).apply()
    }

    fun influxHost(): String = prefs.getString(KEY_INFLUX_HOST, "") ?: ""

    fun setInfluxHost(host: String) {
        prefs.edit().putString(KEY_INFLUX_HOST, host.trim()).apply()
    }

    fun influxPort(): Int = prefs.getInt(KEY_INFLUX_PORT, InfluxConfig.DEFAULT_PORT).coerceIn(1, 65535)

    fun setInfluxPort(port: Int) {
        prefs.edit().putInt(KEY_INFLUX_PORT, port.coerceIn(1, 65535)).apply()
    }

    fun influxDatabase(): String {
        return prefs.getString(KEY_INFLUX_DATABASE, InfluxConfig.DEFAULT_DATABASE)
            ?.ifBlank { InfluxConfig.DEFAULT_DATABASE }
            ?: InfluxConfig.DEFAULT_DATABASE
    }

    fun setInfluxDatabase(database: String) {
        prefs.edit().putString(KEY_INFLUX_DATABASE, database.ifBlank { InfluxConfig.DEFAULT_DATABASE }).apply()
    }

    fun influxUsername(): String = prefs.getString(KEY_INFLUX_USERNAME, "") ?: ""

    fun setInfluxUsername(username: String) {
        prefs.edit().putString(KEY_INFLUX_USERNAME, username).apply()
    }

    fun influxPassword(): String = prefs.getString(KEY_INFLUX_PASSWORD, "") ?: ""

    fun setInfluxPassword(password: String) {
        prefs.edit().putString(KEY_INFLUX_PASSWORD, password).apply()
    }

    fun influxMeasurement(): String {
        return prefs.getString(KEY_INFLUX_MEASUREMENT, InfluxConfig.DEFAULT_MEASUREMENT)
            ?.ifBlank { InfluxConfig.DEFAULT_MEASUREMENT }
            ?: InfluxConfig.DEFAULT_MEASUREMENT
    }

    fun setInfluxMeasurement(measurement: String) {
        prefs.edit().putString(KEY_INFLUX_MEASUREMENT, measurement.ifBlank { InfluxConfig.DEFAULT_MEASUREMENT }).apply()
    }

    fun influxEnabledCategories(): Set<String> {
        return prefs.getStringSet(KEY_INFLUX_CATEGORIES, HaIntegrationCategories.defaults)
            ?.toSet()
            ?: HaIntegrationCategories.defaults
    }

    fun effectiveInfluxCategories(): Set<String> {
        return if (isHaSharedCategoriesEnabled()) mqttEnabledCategories() else influxEnabledCategories()
    }

    fun setInfluxCategoryEnabled(category: String, enabled: Boolean) {
        val categories = influxEnabledCategories().toMutableSet()
        if (enabled) {
            categories.add(category)
        } else {
            categories.remove(category)
        }
        prefs.edit().putStringSet(KEY_INFLUX_CATEGORIES, categories).apply()
    }

    fun keepAliveConfig(): KeepAliveConfig {
        //groups radio/service recovery toggles for foreground-service reconciliation
        return KeepAliveConfig(
            keepWifi = isKeepWifiEnabled(),
            keepMobileData = isKeepMobileDataEnabled(),
            keepBluetooth = isKeepBluetoothEnabled(),
            recoverCollectorService = isRecoverCollectorServiceEnabled()
        )
    }

    fun isKeepWifiEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_WIFI, false)

    fun setKeepWifiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_WIFI, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "keep_alive_wifi_enabled" else "keep_alive_wifi_disabled",
            message = "Wi-Fi keep-alive ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isKeepMobileDataEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_MOBILE_DATA, false)

    fun setKeepMobileDataEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_MOBILE_DATA, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "keep_alive_mobile_data_enabled" else "keep_alive_mobile_data_disabled",
            message = "Mobile data keep-alive ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isKeepBluetoothEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_BLUETOOTH, false)

    fun setKeepBluetoothEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_BLUETOOTH, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "keep_alive_bluetooth_enabled" else "keep_alive_bluetooth_disabled",
            message = "Bluetooth keep-alive ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isRecoverCollectorServiceEnabled(): Boolean = prefs.getBoolean(KEY_RECOVER_COLLECTOR_SERVICE, false)

    fun setRecoverCollectorServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECOVER_COLLECTOR_SERVICE, enabled).apply()
        store?.recordEvent(
            category = if (enabled) "keep_alive_collector_recovery_enabled" else "keep_alive_collector_recovery_disabled",
            message = "Collector service recovery ${if (enabled) "enabled" else "disabled"}"
        )
    }

    companion object {
        const val PREFS_NAME = "collector_settings"
        const val KEY_AUTO_START = "autoStart"
        const val KEY_POLLING_ENABLED = "pollingEnabled"
        const val KEY_DEBUG_POLLING_ENABLED = "debugPollingEnabled"
        const val KEY_DEBUG_AUTO_START = "debugAutoStart"
        const val KEY_DEBUG_BATCH_SIZE = "debugBatchSize"
        const val KEY_KEEP_WIFI = "keepWifi"
        const val KEY_KEEP_MOBILE_DATA = "keepMobileData"
        const val KEY_KEEP_BLUETOOTH = "keepBluetooth"
        const val KEY_RECOVER_COLLECTOR_SERVICE = "recoverCollectorService"
        const val KEY_MQTT_ENABLED = "mqttEnabled"
        const val KEY_MQTT_AUTO_START = "mqttAutoStart"
        const val KEY_HA_DISCOVERY_ENABLED = "haDiscoveryEnabled"
        const val KEY_MQTT_HOST = "mqttHost"
        const val KEY_MQTT_PORT = "mqttPort"
        const val KEY_MQTT_USERNAME = "mqttUsername"
        const val KEY_MQTT_PASSWORD = "mqttPassword"
        const val KEY_MQTT_CLIENT_ID = "mqttClientId"
        const val KEY_MQTT_TOPIC_PREFIX = "mqttTopicPrefix"
        const val KEY_MQTT_DISCOVERY_PREFIX = "mqttDiscoveryPrefix"
        const val KEY_MQTT_CATEGORIES = "mqttCategories"
        const val KEY_INFLUX_ENABLED = "influxEnabled"
        const val KEY_INFLUX_AUTO_START = "influxAutoStart"
        const val KEY_HA_SHARED_CATEGORIES = "haSharedCategories"
        const val KEY_INFLUX_HOST = "influxHost"
        const val KEY_INFLUX_PORT = "influxPort"
        const val KEY_INFLUX_DATABASE = "influxDatabase"
        const val KEY_INFLUX_USERNAME = "influxUsername"
        const val KEY_INFLUX_PASSWORD = "influxPassword"
        const val KEY_INFLUX_MEASUREMENT = "influxMeasurement"
        const val KEY_INFLUX_CATEGORIES = "influxCategories"
        const val KEY_UPDATE_AUTO_CHECK = "updateAutoCheck"
        const val KEY_UPDATE_LAST_CHECK_AT_MS = "updateLastCheckAtMs"
        const val DEFAULT_DEBUG_BATCH_SIZE = 500
        const val SAFE_DEBUG_AUTOSTART_BATCH_SIZE = 500
        const val MAX_DEBUG_BATCH_SIZE = 6100
        const val DEFAULT_MQTT_PORT = 1883
        const val AUTO_START_ENABLED_UK = "Автозапуск активовано"
        const val AUTO_START_DISABLED_UK = "Автозапуск деактивовано"
    }
}
