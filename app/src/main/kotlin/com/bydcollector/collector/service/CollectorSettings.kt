package com.bydcollector.collector.service

import android.content.Context
import android.content.SharedPreferences
import com.bydcollector.collector.update.UpdateHintAppearance
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.telegram.TelegramBuiltInTemplates
import com.bydcollector.collector.telegram.TelegramEventType
import com.bydcollector.collector.telegram.TelegramNavigatorMask
import com.bydcollector.collector.telegram.TelegramPayloadLimitState
import com.bydcollector.collector.telegram.TelegramTemplateLanguage
import com.bydcollector.collector.ha.HaIntegrationCategories
import com.bydcollector.collector.influx.InfluxConfig
import com.bydcollector.collector.keepalive.KeepAliveConfig
import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import com.bydcollector.collector.maintenance.ArchiveStorageJobMode
import com.bydcollector.collector.maintenance.ArchiveStorageJobStatus
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.maintenance.DbMaintenanceRuntimeStatus
import com.bydcollector.collector.maintenance.StorageFormat
import com.bydcollector.collector.maintenance.StorageCutoverJournal
import com.bydcollector.collector.mqtt.HaMqttConfig
import com.bydcollector.collector.security.KeystoreSecretStore
import com.bydcollector.collector.util.dispatchOperationalEvent
import com.bydcollector.collector.util.sharedOperationalEventExecutor
import java.util.concurrent.Executor
import kotlin.math.round

//persistent user settings facade that also records operational events for later diagnostics
class CollectorSettings(
    context: Context,
    private val store: TelemetryStore? = null,
    private val eventExecutor: Executor = sharedOperationalEventExecutor
) {
    data class LegacyLocationMigration(
        val mqttCategories: Set<String>,
        val influxCategories: Set<String>,
        val sharedCategories: Boolean
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secretStore = KeystoreSecretStore(context)

    init {
        migrateLegacyLocationCategories()
        migrateTripEndDelayToSeconds()
        migrateTelegramBuiltInTemplates()
        migrateLegacySecret(KEY_MQTT_USERNAME, SECRET_MQTT_USERNAME, KEY_MQTT_ENABLED)
        migrateLegacySecret(KEY_MQTT_PASSWORD, SECRET_MQTT_PASSWORD, KEY_MQTT_ENABLED)
        migrateLegacySecret(KEY_INFLUX_USERNAME, SECRET_INFLUX_USERNAME, KEY_INFLUX_ENABLED)
        migrateLegacySecret(KEY_INFLUX_PASSWORD, SECRET_INFLUX_PASSWORD, KEY_INFLUX_ENABLED)
    }

    fun isAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_START, false)

    fun runtimeDemand(includeEnabledExports: Boolean = false): RuntimeDemand {
        if (isUserShutdownRequested()) return RuntimeDemand()
        return RuntimeDemand(
            main = isAutoStartEnabled() && !isMainManuallyStopped(),
            debug = isDebugAutoStartEnabled() && !isDebugManuallyStopped(),
            mqtt = (isMqttAutoStartEnabled() || includeEnabledExports && isMqttEnabled()) &&
                !isMqttManuallyStopped(),
            influx = (isInfluxAutoStartEnabled() || includeEnabledExports && isInfluxEnabled()) &&
                !isInfluxManuallyStopped(),
            telegram = isTelegramEnabled(),
            keepAlive = keepAliveConfig().anyEnabled
        )
    }

    fun hasActiveAccessWork(): Boolean {
        return (isPollingEnabled() && !isMainManuallyStopped()) ||
            (isDebugPollingEnabled() && !isDebugManuallyStopped()) ||
            (isMqttEnabled() && !isMqttManuallyStopped()) ||
            (isInfluxEnabled() && !isInfluxManuallyStopped())
    }

    fun setAutoStartEnabled(enabled: Boolean, detail: String? = null) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
        recordEvent(
            category = if (enabled) "auto_start_enabled" else "auto_start_disabled",
            message = if (enabled) AUTO_START_ENABLED_UK else AUTO_START_DISABLED_UK,
            detail = detail
        )
    }

    fun isUserShutdownRequested(): Boolean = prefs.getBoolean(KEY_USER_SHUTDOWN, false)

    fun setUserShutdownRequested(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USER_SHUTDOWN, enabled).commit()
        recordEvent(
            category = if (enabled) "user_shutdown_enabled" else "user_shutdown_cleared",
            message = if (enabled) "User shutdown requested" else "User shutdown cleared"
        )
    }

    fun clearUserShutdownRequestIfSet(): Boolean {
        if (!isUserShutdownRequested()) return false
        setUserShutdownRequested(false)
        return true
    }

    fun isMainManuallyStopped(): Boolean = prefs.getBoolean(KEY_MAIN_MANUAL_STOP, false)

    fun setMainManuallyStopped(stopped: Boolean) {
        prefs.edit().putBoolean(KEY_MAIN_MANUAL_STOP, stopped).apply()
    }

    fun isDebugManuallyStopped(): Boolean = prefs.getBoolean(KEY_DEBUG_MANUAL_STOP, false)

    fun setDebugManuallyStopped(stopped: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_MANUAL_STOP, stopped).apply()
    }

    fun isMqttManuallyStopped(): Boolean = prefs.getBoolean(KEY_MQTT_MANUAL_STOP, false)

    fun setMqttManuallyStopped(stopped: Boolean) {
        prefs.edit().putBoolean(KEY_MQTT_MANUAL_STOP, stopped).apply()
    }

    fun isInfluxManuallyStopped(): Boolean = prefs.getBoolean(KEY_INFLUX_MANUAL_STOP, false)

    fun setInfluxManuallyStopped(stopped: Boolean) {
        prefs.edit().putBoolean(KEY_INFLUX_MANUAL_STOP, stopped).apply()
    }

    fun clearRuntimeManualStops() {
        prefs.edit()
            .putBoolean(KEY_MAIN_MANUAL_STOP, false)
            .putBoolean(KEY_DEBUG_MANUAL_STOP, false)
            .putBoolean(KEY_MQTT_MANUAL_STOP, false)
            .putBoolean(KEY_INFLUX_MANUAL_STOP, false)
            .apply()
    }

    fun isPollingEnabled(): Boolean = prefs.getBoolean(KEY_POLLING_ENABLED, false)

    fun setPollingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_POLLING_ENABLED, enabled).apply()
        recordEvent(
            category = if (enabled) "polling_enabled" else "polling_disabled",
            message = "Polling ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun mainHelperOwnerMode(): DirectHelperOwnerMode {
        return if (
            isPollingEnabled() &&
            !isMainManuallyStopped()
        ) {
            DirectHelperOwnerMode.APP_GAP_SPOOL
        } else {
            DirectHelperOwnerMode.APP
        }
    }

    fun isDebugPollingEnabled(): Boolean = prefs.getBoolean(KEY_DEBUG_POLLING_ENABLED, false)

    fun setDebugPollingEnabled(enabled: Boolean) {
        if (isDebugPollingEnabled() == enabled) return
        prefs.edit().putBoolean(KEY_DEBUG_POLLING_ENABLED, enabled).apply()
        recordEvent(
            category = if (enabled) "debug_polling_enabled" else "debug_polling_disabled",
            message = "Debug polling ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isDebugAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_DEBUG_AUTO_START, false)

    fun setDebugAutoStartEnabled(enabled: Boolean, detail: String? = null) {
        prefs.edit().putBoolean(KEY_DEBUG_AUTO_START, enabled).apply()
        recordEvent(
            category = if (enabled) "debug_auto_start_enabled" else "debug_auto_start_disabled",
            message = "Debug autostart ${if (enabled) "enabled" else "disabled"}",
            detail = detail
        )
    }

    fun mqttConfig(includeCredentials: Boolean = true): HaMqttConfig {
        //builds an immutable snapshot so async mqtt work uses one consistent set of settings
        return HaMqttConfig(
            enabled = isMqttEnabled(),
            discoveryEnabled = true,
            host = mqttHost(),
            port = mqttPort(),
            username = if (includeCredentials) mqttUsername().takeIf { it.isNotBlank() } else null,
            password = if (includeCredentials) mqttPassword().takeIf { it.isNotBlank() } else null,
            clientId = mqttClientId(),
            topicPrefix = mqttTopicPrefix(),
            discoveryPrefix = mqttDiscoveryPrefix(),
            enabledCategories = mqttEnabledCategories(),
            alternativeHost = mqttAlternativeHost(),
            alternativePort = mqttAlternativePort()
        )
    }

    fun isMqttEnabled(): Boolean = prefs.getBoolean(KEY_MQTT_ENABLED, false)

    fun setMqttEnabled(enabled: Boolean) {
        if (isMqttEnabled() == enabled) return
        prefs.edit().putBoolean(KEY_MQTT_ENABLED, enabled).apply()
        recordEvent(
            category = if (enabled) "mqtt_enabled" else "mqtt_disabled",
            message = "MQTT ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isMqttAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_MQTT_AUTO_START, false)

    fun setMqttAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MQTT_AUTO_START, enabled).apply()
        recordEvent(
            category = if (enabled) "mqtt_auto_start_enabled" else "mqtt_auto_start_disabled",
            message = "MQTT auto-start ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun mqttHost(): String = prefs.getString(KEY_MQTT_HOST, "") ?: ""

    fun setMqttHost(host: String) {
        prefs.edit().putString(KEY_MQTT_HOST, host.trim()).apply()
    }

    fun mqttPort(): Int = prefs.getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT)

    fun setMqttPort(port: Int) {
        prefs.edit().putInt(KEY_MQTT_PORT, port).apply()
    }

    fun mqttAlternativeHost(): String? {
        return prefs.getString(KEY_MQTT_ALTERNATIVE_HOST, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun setMqttAlternativeHost(host: String?) {
        prefs.edit().apply {
            if (host.isNullOrBlank()) remove(KEY_MQTT_ALTERNATIVE_HOST)
            else putString(KEY_MQTT_ALTERNATIVE_HOST, host.trim())
        }.apply()
    }

    fun mqttAlternativePort(): Int? {
        if (!prefs.contains(KEY_MQTT_ALTERNATIVE_PORT)) return null
        return prefs.getInt(KEY_MQTT_ALTERNATIVE_PORT, 0)
    }

    fun setMqttAlternativePort(port: Int?) {
        prefs.edit().apply {
            if (port == null) remove(KEY_MQTT_ALTERNATIVE_PORT)
            else putInt(KEY_MQTT_ALTERNATIVE_PORT, port)
        }.apply()
    }

    fun mqttUsername(): String = secretValue(SECRET_MQTT_USERNAME)

    fun setMqttUsername(username: String): Boolean =
        writeSecret(SECRET_MQTT_USERNAME, username, KEY_MQTT_USERNAME, KEY_MQTT_ENABLED)

    fun mqttPassword(): String = secretValue(SECRET_MQTT_PASSWORD)

    fun setMqttPassword(password: String): Boolean =
        writeSecret(SECRET_MQTT_PASSWORD, password, KEY_MQTT_PASSWORD, KEY_MQTT_ENABLED)

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

    fun influxConfig(includeCredentials: Boolean = true): InfluxConfig {
        //shares category selection with mqtt by default so ha live state and history stay aligned
        return InfluxConfig(
            enabled = isInfluxEnabled(),
            host = influxHost(),
            port = influxPort(),
            database = influxDatabase(),
            username = if (includeCredentials) influxUsername().takeIf { it.isNotBlank() } else null,
            password = if (includeCredentials) influxPassword().takeIf { it.isNotBlank() } else null,
            measurement = influxMeasurement(),
            enabledCategories = effectiveInfluxCategories(),
            alternativeHost = influxAlternativeHost(),
            alternativePort = influxAlternativePort()
        )
    }

    fun isInfluxEnabled(): Boolean = prefs.getBoolean(KEY_INFLUX_ENABLED, false)

    fun setInfluxEnabled(enabled: Boolean) {
        if (isInfluxEnabled() == enabled) return
        prefs.edit().putBoolean(KEY_INFLUX_ENABLED, enabled).apply()
        recordEvent(
            category = if (enabled) "influx_enabled" else "influx_disabled",
            message = "InfluxDB export ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isInfluxAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_INFLUX_AUTO_START, false)

    fun setInfluxAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INFLUX_AUTO_START, enabled).apply()
        recordEvent(
            category = if (enabled) "influx_auto_start_enabled" else "influx_auto_start_disabled",
            message = "InfluxDB auto-start ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isHaSharedCategoriesEnabled(): Boolean = prefs.getBoolean(KEY_HA_SHARED_CATEGORIES, true)

    fun setHaSharedCategoriesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HA_SHARED_CATEGORIES, enabled).apply()
        recordEvent(
            category = if (enabled) "ha_shared_categories_enabled" else "ha_shared_categories_disabled",
            message = "HA shared categories ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isUpdateAutoCheckEnabled(): Boolean = prefs.getBoolean(KEY_UPDATE_AUTO_CHECK, true)

    fun isUpdateHintEnabled(): Boolean = prefs.getBoolean(KEY_UPDATE_HINT_ENABLED, true)

    fun setUpdateHintEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_HINT_ENABLED, enabled).apply()
    }

    fun updateHintAppearance(): UpdateHintAppearance {
        val defaults = UpdateHintAppearance()
        return UpdateHintAppearance(
            prefs.getInt("updateHintTransparency", defaults.transparencyPercent),
            prefs.getInt("updateHintCorner", defaults.cornerRadiusDp),
            prefs.getInt("updateHintBorderWidth", defaults.borderWidthDp),
            prefs.getInt("updateHintBorderColor", defaults.borderArgb),
            prefs.getInt("updateHintSize", defaults.sizePercent)
        ).normalized()
    }

    fun setUpdateHintAppearance(appearance: UpdateHintAppearance) {
        val value = appearance.normalized()
        prefs.edit()
            .putInt("updateHintTransparency", value.transparencyPercent)
            .putInt("updateHintCorner", value.cornerRadiusDp)
            .putInt("updateHintBorderWidth", value.borderWidthDp)
            .putInt("updateHintBorderColor", value.borderArgb)
            .putInt("updateHintSize", value.sizePercent)
            .apply()
    }

    fun setUpdateAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_AUTO_CHECK, enabled).apply()
        recordEvent(
            category = if (enabled) "update_auto_check_enabled" else "update_auto_check_disabled",
            message = "Update auto-check ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isTailscaleActivationEnabled(): Boolean = prefs.getBoolean(KEY_TAILSCALE_ACTIVATION, false)

    fun setTailscaleActivationEnabled(enabled: Boolean) {
        if (enabled) clearTailscaleActivationAttempt()
        prefs.edit().putBoolean(KEY_TAILSCALE_ACTIVATION, enabled).apply()
        recordEvent(
            category = if (enabled) "tailscale_activation_enabled" else "tailscale_activation_disabled",
            message = "Tailscale activation ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun tailscaleActivationLastAttemptAtMs(): Long = prefs.getLong(KEY_TAILSCALE_ACTIVATION_LAST_ATTEMPT_AT_MS, 0L)

    fun setTailscaleActivationLastAttemptAtMs(value: Long) {
        prefs.edit().putLong(KEY_TAILSCALE_ACTIVATION_LAST_ATTEMPT_AT_MS, value).apply()
    }

    fun clearTailscaleActivationAttempt() {
        prefs.edit().remove(KEY_TAILSCALE_ACTIVATION_LAST_ATTEMPT_AT_MS).apply()
    }

    fun isTelegramEnabled(): Boolean = prefs.getBoolean(KEY_TELEGRAM_ENABLED, false)

    fun setTelegramEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TELEGRAM_ENABLED, enabled).apply()
        recordEvent(
            category = if (enabled) "telegram_enabled" else "telegram_disabled",
            message = "Telegram ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun telegramChatId(): String = prefs.getString(KEY_TELEGRAM_CHAT_ID, "") ?: ""

    fun setTelegramChatId(chatId: String) {
        prefs.edit().putString(KEY_TELEGRAM_CHAT_ID, chatId.trim()).apply()
    }

    fun telegramBotToken(): String = secretStore.read(SECRET_TELEGRAM_BOT_TOKEN).orEmpty()

    fun isTelegramBotTokenSet(): Boolean = telegramBotToken().isNotEmpty()

    fun setTelegramBotToken(token: String): Boolean = writeSecret(SECRET_TELEGRAM_BOT_TOKEN, token.trim())

    fun clearTelegramBotToken(): Boolean = secretStore.clear(SECRET_TELEGRAM_BOT_TOKEN)

    fun isTelegramSendLocationEnabled(): Boolean = prefs.getBoolean(KEY_TELEGRAM_SEND_LOCATION, false)

    fun setTelegramSendLocationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TELEGRAM_SEND_LOCATION, enabled).apply()
    }

    fun telegramNavigatorMask(): Int = TelegramNavigatorMask.sanitize(
        prefs.getInt(KEY_TELEGRAM_NAVIGATOR_MASK, DEFAULT_TELEGRAM_NAVIGATOR_MASK)
    )

    fun setTelegramNavigatorMask(mask: Int) {
        prefs.edit().putInt(KEY_TELEGRAM_NAVIGATOR_MASK, TelegramNavigatorMask.sanitize(mask)).apply()
    }

    fun isTripHistoryEnabled(): Boolean = prefs.getBoolean(KEY_TRIP_HISTORY, true)

    fun setTripHistoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRIP_HISTORY, enabled).apply()
    }

    fun tripSpeedGreenThreshold(): Int = prefs.getInt(KEY_TRIP_SPEED_GREEN, DEFAULT_TRIP_SPEED_GREEN).coerceIn(0, 300)
    fun tripSpeedYellowThreshold(): Int = prefs.getInt(KEY_TRIP_SPEED_YELLOW, DEFAULT_TRIP_SPEED_YELLOW).coerceIn(0, 300)
    fun tripConsumptionGreenThreshold(): Int = prefs.getInt(KEY_TRIP_CONSUMPTION_GREEN, DEFAULT_TRIP_CONSUMPTION_GREEN).coerceIn(0, 300)
    fun tripConsumptionYellowThreshold(): Int = prefs.getInt(KEY_TRIP_CONSUMPTION_YELLOW, DEFAULT_TRIP_CONSUMPTION_YELLOW).coerceIn(0, 300)

    fun setTripSpeedThresholds(green: Int, yellow: Int) {
        prefs.edit()
            .putInt(KEY_TRIP_SPEED_GREEN, green.coerceIn(0, 300))
            .putInt(KEY_TRIP_SPEED_YELLOW, yellow.coerceIn(0, 300))
            .apply()
    }

    fun setTripConsumptionThresholds(green: Int, yellow: Int) {
        prefs.edit()
            .putInt(KEY_TRIP_CONSUMPTION_GREEN, green.coerceIn(0, 300))
            .putInt(KEY_TRIP_CONSUMPTION_YELLOW, yellow.coerceIn(0, 300))
            .apply()
    }

    fun isTelegramEventEnabled(eventKey: String): Boolean {
        return prefs.getBoolean("$KEY_TELEGRAM_EVENT_PREFIX$eventKey", false)
    }

    fun setTelegramEventEnabled(eventKey: String, enabled: Boolean) {
        prefs.edit().putBoolean("$KEY_TELEGRAM_EVENT_PREFIX$eventKey", enabled).apply()
    }

    fun telegramTemplate(eventKey: String): String? {
        val saved = prefs.getString("$KEY_TELEGRAM_TEMPLATE_PREFIX$eventKey", null) ?: return null
        val event = TelegramEventType.fromKey(eventKey) ?: return saved
        if (!TelegramBuiltInTemplates.isKnownBuiltIn(event, saved)) return saved
        return TelegramBuiltInTemplates.defaultTemplate(
            event,
            if (uiLanguageCode() == "en") TelegramTemplateLanguage.EN else TelegramTemplateLanguage.UK
        )
    }

    fun setTelegramTemplate(eventKey: String, template: String) {
        prefs.edit().putString("$KEY_TELEGRAM_TEMPLATE_PREFIX$eventKey", template).apply()
    }

    fun clearTelegramTemplate(eventKey: String) {
        prefs.edit().remove("$KEY_TELEGRAM_TEMPLATE_PREFIX$eventKey").apply()
    }

    fun telegramTripTemplateLimitState(): TelegramPayloadLimitState {
        val saved = prefs.getString(KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_STATE, null)
        return TelegramPayloadLimitState.entries.firstOrNull { it.name == saved }
            ?: TelegramPayloadLimitState.NONE
    }

    fun telegramTripTemplateLimitRevision(): Long = synchronized(TELEGRAM_TRIP_TEMPLATE_LIMIT_LOCK) {
        prefs.getLong(KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_REVISION, 0L)
    }

    fun setTelegramTripTemplateLimitState(state: TelegramPayloadLimitState, expectedRevision: Long) {
        synchronized(TELEGRAM_TRIP_TEMPLATE_LIMIT_LOCK) {
            if (prefs.getLong(KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_REVISION, 0L) != expectedRevision) return
            if (telegramTripTemplateLimitState() == state) return
            prefs.edit().putString(KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_STATE, state.name).apply()
        }
    }

    fun resetTelegramTripTemplateLimitState() {
        synchronized(TELEGRAM_TRIP_TEMPLATE_LIMIT_LOCK) {
            val nextRevision = prefs.getLong(KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_REVISION, 0L) + 1L
            prefs.edit()
                .putLong(KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_REVISION, nextRevision)
                .putString(KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_STATE, TelegramPayloadLimitState.NONE.name)
                .apply()
        }
    }

    fun uiLanguageCode(): String = when (prefs.getString(KEY_UI_LANGUAGE_CODE, DEFAULT_UI_LANGUAGE_CODE)
        ?.trim()?.lowercase()) {
        "en" -> "en"
        else -> DEFAULT_UI_LANGUAGE_CODE
    }

    fun setUiLanguageCode(code: String) {
        prefs.edit().putString(
            KEY_UI_LANGUAGE_CODE,
            if (code.trim().lowercase() == "en") "en" else DEFAULT_UI_LANGUAGE_CODE
        ).apply()
    }

    fun telegramChargeStepPercent(): Int {
        return prefs.getInt(KEY_TELEGRAM_CHARGE_STEP, DEFAULT_TELEGRAM_CHARGE_STEP)
            .coerceIn(MIN_TELEGRAM_CHARGE_STEP, MAX_TELEGRAM_CHARGE_STEP)
    }

    fun setTelegramChargeStepPercent(value: Int) {
        prefs.edit().putInt(
            KEY_TELEGRAM_CHARGE_STEP,
            value.coerceIn(MIN_TELEGRAM_CHARGE_STEP, MAX_TELEGRAM_CHARGE_STEP)
        ).apply()
    }

    fun telegramLowVoltageThreshold(): Float {
        return (round(prefs.getFloat(KEY_TELEGRAM_LOW_VOLTAGE, DEFAULT_TELEGRAM_LOW_VOLTAGE) * 10f) / 10f)
            .coerceIn(MIN_TELEGRAM_LOW_VOLTAGE, MAX_TELEGRAM_LOW_VOLTAGE)
    }

    fun setTelegramLowVoltageThreshold(value: Float) {
        prefs.edit().putFloat(
            KEY_TELEGRAM_LOW_VOLTAGE,
            (round(value * 10f) / 10f).coerceIn(MIN_TELEGRAM_LOW_VOLTAGE, MAX_TELEGRAM_LOW_VOLTAGE)
        ).apply()
    }

    fun telegramUnavailableDelayMinutes(): Int {
        return prefs.getInt(KEY_TELEGRAM_UNAVAILABLE_DELAY, DEFAULT_TELEGRAM_UNAVAILABLE_DELAY)
            .coerceIn(MIN_TELEGRAM_DELAY_MINUTES, MAX_TELEGRAM_DELAY_MINUTES)
    }

    fun setTelegramUnavailableDelayMinutes(value: Int) {
        prefs.edit().putInt(
            KEY_TELEGRAM_UNAVAILABLE_DELAY,
            value.coerceIn(MIN_TELEGRAM_DELAY_MINUTES, MAX_TELEGRAM_DELAY_MINUTES)
        ).apply()
    }

    fun telegramTripEndDelaySeconds(): Int {
        return prefs.getInt(KEY_TELEGRAM_TRIP_END_DELAY_SECONDS, DEFAULT_TELEGRAM_TRIP_END_DELAY_SECONDS)
            .coerceIn(MIN_TELEGRAM_TRIP_END_DELAY_SECONDS, MAX_TELEGRAM_TRIP_END_DELAY_SECONDS)
    }

    fun setTelegramTripEndDelaySeconds(value: Int) {
        prefs.edit().putInt(
            KEY_TELEGRAM_TRIP_END_DELAY_SECONDS,
            value.coerceIn(MIN_TELEGRAM_TRIP_END_DELAY_SECONDS, MAX_TELEGRAM_TRIP_END_DELAY_SECONDS)
        ).apply()
    }

    fun telegramConnectionStatus(): String = prefs.getString(KEY_TELEGRAM_CONNECTION_STATUS, "not_tested")
        ?: "not_tested"

    fun telegramConnectionMessage(): String? = prefs.getString(KEY_TELEGRAM_CONNECTION_MESSAGE, null)

    fun setTelegramConnectionStatus(status: String, message: String?) {
        prefs.edit().apply {
            putString(KEY_TELEGRAM_CONNECTION_STATUS, status)
            if (message == null) remove(KEY_TELEGRAM_CONNECTION_MESSAGE)
            else putString(KEY_TELEGRAM_CONNECTION_MESSAGE, message.take(300))
        }.apply()
    }

    fun telegramLegacyMigrationRequired(): Boolean =
        prefs.getBoolean(KEY_TELEGRAM_LEGACY_MIGRATION_REQUIRED, false)

    fun setTelegramLegacyMigrationRequired(required: Boolean): Boolean = prefs.edit().apply {
        if (required) putBoolean(KEY_TELEGRAM_LEGACY_MIGRATION_REQUIRED, true)
        else remove(KEY_TELEGRAM_LEGACY_MIGRATION_REQUIRED)
    }.commit()

    fun archiveStorageLimitGb(): Int = prefs.getInt(KEY_ARCHIVE_STORAGE_LIMIT_GB, DEFAULT_ARCHIVE_STORAGE_LIMIT_GB)
        .coerceIn(MIN_ARCHIVE_STORAGE_LIMIT_GB, MAX_ARCHIVE_STORAGE_LIMIT_GB)

    fun setArchiveStorageLimitGb(value: Int) {
        val safeValue = value.coerceIn(MIN_ARCHIVE_STORAGE_LIMIT_GB, MAX_ARCHIVE_STORAGE_LIMIT_GB)
        prefs.edit().putInt(KEY_ARCHIVE_STORAGE_LIMIT_GB, safeValue).apply()
        recordEvent(
            category = "archive_storage_limit_changed",
            message = "Archive storage limit changed",
            detail = "limit_gb=$safeValue"
        )
    }

    fun archiveStorageJobStatus(): ArchiveStorageJobStatus {
        val mode = prefs.getString(KEY_ARCHIVE_STORAGE_JOB_MODE, null)
            ?.let { runCatching { ArchiveStorageJobMode.valueOf(it) }.getOrNull() }
        return ArchiveStorageJobStatus(
            mode = mode,
            running = prefs.getBoolean(KEY_ARCHIVE_STORAGE_JOB_RUNNING, false),
            stepIndex = prefs.getInt(KEY_ARCHIVE_STORAGE_JOB_STEP_INDEX, 0),
            stepCount = prefs.getInt(KEY_ARCHIVE_STORAGE_JOB_STEP_COUNT, 0),
            messageUk = prefs.getString(KEY_ARCHIVE_STORAGE_JOB_MESSAGE_UK, "") ?: "",
            messageEn = prefs.getString(KEY_ARCHIVE_STORAGE_JOB_MESSAGE_EN, "") ?: "",
            itemId = prefs.getString(KEY_ARCHIVE_STORAGE_JOB_ITEM_ID, null),
            error = prefs.getString(KEY_ARCHIVE_STORAGE_JOB_ERROR, null),
            updatedAtMs = prefs.getLong(KEY_ARCHIVE_STORAGE_JOB_UPDATED_AT_MS, 0L)
        )
    }

    fun setArchiveStorageJobStatus(status: ArchiveStorageJobStatus, synchronous: Boolean = false) {
        val now = System.currentTimeMillis()
        val editor = prefs.edit().apply {
            status.mode?.let { putString(KEY_ARCHIVE_STORAGE_JOB_MODE, it.name) }
                ?: remove(KEY_ARCHIVE_STORAGE_JOB_MODE)
            putBoolean(KEY_ARCHIVE_STORAGE_JOB_RUNNING, status.running)
            putInt(KEY_ARCHIVE_STORAGE_JOB_STEP_INDEX, status.stepIndex)
            putInt(KEY_ARCHIVE_STORAGE_JOB_STEP_COUNT, status.stepCount)
            putString(KEY_ARCHIVE_STORAGE_JOB_MESSAGE_UK, status.messageUk)
            putString(KEY_ARCHIVE_STORAGE_JOB_MESSAGE_EN, status.messageEn)
            status.itemId?.let { putString(KEY_ARCHIVE_STORAGE_JOB_ITEM_ID, it) }
                ?: remove(KEY_ARCHIVE_STORAGE_JOB_ITEM_ID)
            status.error?.let { putString(KEY_ARCHIVE_STORAGE_JOB_ERROR, it) }
                ?: remove(KEY_ARCHIVE_STORAGE_JOB_ERROR)
            putLong(KEY_ARCHIVE_STORAGE_JOB_UPDATED_AT_MS, status.updatedAtMs.takeIf { it > 0L } ?: now)
        }
        if (synchronous) editor.commit() else editor.apply()
    }

    fun clearArchiveStorageJobStatus() {
        prefs.edit()
            .remove(KEY_ARCHIVE_STORAGE_JOB_MODE)
            .remove(KEY_ARCHIVE_STORAGE_JOB_RUNNING)
            .remove(KEY_ARCHIVE_STORAGE_JOB_STEP_INDEX)
            .remove(KEY_ARCHIVE_STORAGE_JOB_STEP_COUNT)
            .remove(KEY_ARCHIVE_STORAGE_JOB_MESSAGE_UK)
            .remove(KEY_ARCHIVE_STORAGE_JOB_MESSAGE_EN)
            .remove(KEY_ARCHIVE_STORAGE_JOB_ITEM_ID)
            .remove(KEY_ARCHIVE_STORAGE_JOB_ERROR)
            .remove(KEY_ARCHIVE_STORAGE_JOB_UPDATED_AT_MS)
            .apply()
    }

    fun dbMaintenanceStatus(): DbMaintenanceRuntimeStatus {
        return DbMaintenanceRuntimeStatus(
            operation = DbMaintenanceOperation.fromKey(prefs.getString(KEY_DB_MAINTENANCE_OPERATION, null)),
            running = prefs.getBoolean(KEY_DB_MAINTENANCE_RUNNING, false),
            completed = prefs.getBoolean(KEY_DB_MAINTENANCE_COMPLETED, false),
            stepIndex = prefs.getInt(KEY_DB_MAINTENANCE_STEP_INDEX, 0),
            stepCount = prefs.getInt(KEY_DB_MAINTENANCE_STEP_COUNT, 0),
            messageUk = prefs.getString(KEY_DB_MAINTENANCE_MESSAGE_UK, "") ?: "",
            messageEn = prefs.getString(KEY_DB_MAINTENANCE_MESSAGE_EN, "") ?: "",
            error = prefs.getString(KEY_DB_MAINTENANCE_ERROR, null),
            warning = prefs.getString(KEY_DB_MAINTENANCE_WARNING, null),
            archivePath = prefs.getString(KEY_DB_MAINTENANCE_ARCHIVE_PATH, null),
            startedAtMs = prefs.getLong(KEY_DB_MAINTENANCE_STARTED_AT_MS, 0L),
            updatedAtMs = prefs.getLong(KEY_DB_MAINTENANCE_UPDATED_AT_MS, 0L),
            cancelAvailable = prefs.getBoolean(KEY_DB_MAINTENANCE_CANCEL_AVAILABLE, false)
        )
    }

    fun setDbMaintenanceStatus(status: DbMaintenanceRuntimeStatus, synchronous: Boolean = false): Boolean {
        val previous = dbMaintenanceStatus()
        val now = System.currentTimeMillis()
        val startedAt = when {
            status.startedAtMs > 0L -> status.startedAtMs
            previous.running && previous.operation == status.operation && previous.startedAtMs > 0L -> previous.startedAtMs
            status.running -> now
            else -> 0L
        }
        val editor = prefs.edit().apply {
            status.operation?.let { putString(KEY_DB_MAINTENANCE_OPERATION, it.key) }
                ?: remove(KEY_DB_MAINTENANCE_OPERATION)
            putBoolean(KEY_DB_MAINTENANCE_RUNNING, status.running)
            putBoolean(KEY_DB_MAINTENANCE_COMPLETED, status.completed)
            putInt(KEY_DB_MAINTENANCE_STEP_INDEX, status.stepIndex)
            putInt(KEY_DB_MAINTENANCE_STEP_COUNT, status.stepCount)
            putString(KEY_DB_MAINTENANCE_MESSAGE_UK, status.messageUk)
            putString(KEY_DB_MAINTENANCE_MESSAGE_EN, status.messageEn)
            status.error?.let { putString(KEY_DB_MAINTENANCE_ERROR, it) } ?: remove(KEY_DB_MAINTENANCE_ERROR)
            status.warning?.let { putString(KEY_DB_MAINTENANCE_WARNING, it) } ?: remove(KEY_DB_MAINTENANCE_WARNING)
            status.archivePath?.let { putString(KEY_DB_MAINTENANCE_ARCHIVE_PATH, it) }
                ?: remove(KEY_DB_MAINTENANCE_ARCHIVE_PATH)
            putLong(KEY_DB_MAINTENANCE_STARTED_AT_MS, startedAt)
            putLong(KEY_DB_MAINTENANCE_UPDATED_AT_MS, now)
            putBoolean(KEY_DB_MAINTENANCE_CANCEL_AVAILABLE, status.cancelAvailable)
        }
        return if (synchronous) editor.commit() else {
            editor.apply()
            true
        }
    }

    fun recoverInterruptedDbMaintenanceIfNeeded(source: String): Boolean {
        val operationKey = prefs.getString(KEY_DB_MAINTENANCE_OPERATION, null)
        if (prefs.getBoolean(KEY_DB_MAINTENANCE_RUNNING, false) && DbMaintenanceOperation.fromKey(operationKey) == null) {
            clearDbMaintenanceStatus(synchronous = true)
            recordEvent(
                category = "database_maintenance_unknown_cleared",
                message = "Unknown database maintenance state cleared",
                detail = "source=$source operation=$operationKey"
            )
            return true
        }
        val status = dbMaintenanceStatus()
        val operation = status.operation ?: return false
        if (!status.running) return false
        val now = System.currentTimeMillis()
        if (status.updatedAtMs > 0L && now - status.updatedAtMs < DB_MAINTENANCE_RECOVERY_GRACE_MS) {
            return false
        }
        setDbMaintenanceStatus(
            status.copy(
                running = false,
                completed = false,
                stepCount = status.stepCount.takeIf { it > 0 } ?: operation.stepsUk.size,
                error = "Interrupted before completion"
            ),
            synchronous = true
        )
        recordEvent(
            category = "database_maintenance_interrupted",
            message = "Database maintenance interrupted before completion",
            detail = "source=$source operation=${operation.key} step=${status.stepIndex}/${status.stepCount} updated_at_ms=${status.updatedAtMs}"
        )
        return true
    }

    fun clearDbMaintenanceStatus(synchronous: Boolean = false) {
        val editor = prefs.edit()
            .remove(KEY_DB_MAINTENANCE_OPERATION)
            .remove(KEY_DB_MAINTENANCE_RUNNING)
            .remove(KEY_DB_MAINTENANCE_COMPLETED)
            .remove(KEY_DB_MAINTENANCE_STEP_INDEX)
            .remove(KEY_DB_MAINTENANCE_STEP_COUNT)
            .remove(KEY_DB_MAINTENANCE_MESSAGE_UK)
            .remove(KEY_DB_MAINTENANCE_MESSAGE_EN)
            .remove(KEY_DB_MAINTENANCE_ERROR)
            .remove(KEY_DB_MAINTENANCE_WARNING)
            .remove(KEY_DB_MAINTENANCE_ARCHIVE_PATH)
            .remove(KEY_DB_MAINTENANCE_STARTED_AT_MS)
            .remove(KEY_DB_MAINTENANCE_UPDATED_AT_MS)
            .remove(KEY_DB_MAINTENANCE_CANCEL_AVAILABLE)
        if (synchronous) editor.commit() else editor.apply()
    }

    fun mainStorageCutoverDeferredReason(): String? =
        prefs.getString(KEY_MAIN_STORAGE_CUTOVER_DEFERRED_REASON, null)

    fun mainStorageCutoverError(): String? =
        prefs.getString(KEY_MAIN_STORAGE_CUTOVER_ERROR, null)

    fun deferMainStorageCutover(reason: String) {
        prefs.edit()
            .putString(KEY_MAIN_STORAGE_CUTOVER_DEFERRED_REASON, reason)
            .remove(KEY_MAIN_STORAGE_CUTOVER_ERROR)
            .commit()
    }

    fun setMainStorageCutoverError(error: String?) {
        prefs.edit().apply {
            error?.let { putString(KEY_MAIN_STORAGE_CUTOVER_ERROR, it) }
                ?: remove(KEY_MAIN_STORAGE_CUTOVER_ERROR)
        }.commit()
    }

    fun clearMainStorageCutoverStatus() {
        prefs.edit()
            .remove(KEY_MAIN_STORAGE_CUTOVER_DEFERRED_REASON)
            .remove(KEY_MAIN_STORAGE_CUTOVER_ERROR)
            .commit()
    }

    fun debugStorageCutoverError(): String? =
        prefs.getString(KEY_DEBUG_STORAGE_CUTOVER_ERROR, null)

    fun setDebugStorageCutoverError(error: String?) {
        prefs.edit().apply {
            error?.let { putString(KEY_DEBUG_STORAGE_CUTOVER_ERROR, it) }
                ?: remove(KEY_DEBUG_STORAGE_CUTOVER_ERROR)
        }.commit()
    }

    fun storageCutoverJournal(): StorageCutoverJournal? = readStorageCutoverJournal(prefs)

    private fun readStorageCutoverJournal(prefs: SharedPreferences): StorageCutoverJournal? {
        val journalKeys = listOf(
            KEY_STORAGE_CUTOVER_JOURNAL_FAMILY,
            KEY_STORAGE_CUTOVER_JOURNAL_ARCHIVE_PATH,
            KEY_STORAGE_CUTOVER_JOURNAL_PHASE,
            KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_FORMAT,
            KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_NAMES,
            KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_DATABASE_NAME,
            KEY_STORAGE_CUTOVER_JOURNAL_TARGET_DATABASE_NAME
        )
        if (runCatching { journalKeys.none(prefs::contains) }.getOrDefault(false)) return null
        fun journalString(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()
        val family = journalString(KEY_STORAGE_CUTOVER_JOURNAL_FAMILY).orEmpty()
        val phase = journalString(KEY_STORAGE_CUTOVER_JOURNAL_PHASE).orEmpty()
        val sourceFormat = journalString(KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_FORMAT)
            ?.let { runCatching { StorageFormat.valueOf(it) }.getOrNull() }
            ?: StorageFormat.UNKNOWN
        return StorageCutoverJournal(
            family = family,
            archivePath = journalString(KEY_STORAGE_CUTOVER_JOURNAL_ARCHIVE_PATH),
            phase = phase,
            sourceFormat = sourceFormat,
            manual = runCatching { prefs.getBoolean(KEY_STORAGE_CUTOVER_JOURNAL_MANUAL, false) }
                .getOrDefault(false),
            sourceNames = runCatching {
                prefs.getStringSet(KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_NAMES, emptySet())
                    .orEmpty()
                    .filter(String::isNotBlank)
                    .toSet()
            }.getOrDefault(emptySet()),
            sourceDatabaseName = journalString(KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_DATABASE_NAME).orEmpty(),
            targetDatabaseName = journalString(KEY_STORAGE_CUTOVER_JOURNAL_TARGET_DATABASE_NAME).orEmpty()
        )
    }

    fun setStorageCutoverJournal(journal: StorageCutoverJournal): Boolean =
        prefs.edit().apply {
            putString(KEY_STORAGE_CUTOVER_JOURNAL_FAMILY, journal.family)
            putString(KEY_STORAGE_CUTOVER_JOURNAL_PHASE, journal.phase)
            putString(KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_FORMAT, journal.sourceFormat.name)
            putBoolean(KEY_STORAGE_CUTOVER_JOURNAL_MANUAL, journal.manual)
            putStringSet(KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_NAMES, journal.sourceNames.toSet())
            putString(KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_DATABASE_NAME, journal.sourceDatabaseName)
            putString(KEY_STORAGE_CUTOVER_JOURNAL_TARGET_DATABASE_NAME, journal.targetDatabaseName)
            journal.archivePath?.let { putString(KEY_STORAGE_CUTOVER_JOURNAL_ARCHIVE_PATH, it) }
                ?: remove(KEY_STORAGE_CUTOVER_JOURNAL_ARCHIVE_PATH)
        }.commit()

    fun clearStorageCutoverJournal(): Boolean =
        prefs.edit()
            .remove(KEY_STORAGE_CUTOVER_JOURNAL_FAMILY)
            .remove(KEY_STORAGE_CUTOVER_JOURNAL_ARCHIVE_PATH)
            .remove(KEY_STORAGE_CUTOVER_JOURNAL_PHASE)
            .remove(KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_FORMAT)
            .remove(KEY_STORAGE_CUTOVER_JOURNAL_MANUAL)
            .remove(KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_NAMES)
            .remove(KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_DATABASE_NAME)
            .remove(KEY_STORAGE_CUTOVER_JOURNAL_TARGET_DATABASE_NAME)
            .commit()

    fun isCutoverArchiveStoragePending(): Boolean =
        prefs.getBoolean(KEY_STORAGE_CUTOVER_ARCHIVE_PENDING, false)

    fun setCutoverArchiveStoragePending(pending: Boolean) {
        check(prefs.edit().putBoolean(KEY_STORAGE_CUTOVER_ARCHIVE_PENDING, pending).commit()) {
            "Cannot persist archive-storage cutover state"
        }
    }

    fun influxHost(): String = prefs.getString(KEY_INFLUX_HOST, "") ?: ""

    fun setInfluxHost(host: String) {
        prefs.edit().putString(KEY_INFLUX_HOST, host.trim()).apply()
    }

    fun influxPort(): Int = prefs.getInt(KEY_INFLUX_PORT, InfluxConfig.DEFAULT_PORT)

    fun setInfluxPort(port: Int) {
        prefs.edit().putInt(KEY_INFLUX_PORT, port).apply()
    }

    fun influxAlternativeHost(): String? {
        return prefs.getString(KEY_INFLUX_ALTERNATIVE_HOST, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun setInfluxAlternativeHost(host: String?) {
        prefs.edit().apply {
            if (host.isNullOrBlank()) remove(KEY_INFLUX_ALTERNATIVE_HOST)
            else putString(KEY_INFLUX_ALTERNATIVE_HOST, host.trim())
        }.apply()
    }

    fun influxAlternativePort(): Int? {
        if (!prefs.contains(KEY_INFLUX_ALTERNATIVE_PORT)) return null
        return prefs.getInt(KEY_INFLUX_ALTERNATIVE_PORT, 0)
    }

    fun setInfluxAlternativePort(port: Int?) {
        prefs.edit().apply {
            if (port == null) remove(KEY_INFLUX_ALTERNATIVE_PORT)
            else putInt(KEY_INFLUX_ALTERNATIVE_PORT, port)
        }.apply()
    }

    fun influxDatabase(): String {
        return prefs.getString(KEY_INFLUX_DATABASE, InfluxConfig.DEFAULT_DATABASE)
            ?.ifBlank { InfluxConfig.DEFAULT_DATABASE }
            ?: InfluxConfig.DEFAULT_DATABASE
    }

    fun setInfluxDatabase(database: String) {
        prefs.edit().putString(KEY_INFLUX_DATABASE, database.ifBlank { InfluxConfig.DEFAULT_DATABASE }).apply()
    }

    fun influxUsername(): String = secretValue(SECRET_INFLUX_USERNAME)

    fun setInfluxUsername(username: String): Boolean =
        writeSecret(SECRET_INFLUX_USERNAME, username, KEY_INFLUX_USERNAME, KEY_INFLUX_ENABLED)

    fun influxPassword(): String = secretValue(SECRET_INFLUX_PASSWORD)

    fun setInfluxPassword(password: String): Boolean =
        writeSecret(SECRET_INFLUX_PASSWORD, password, KEY_INFLUX_PASSWORD, KEY_INFLUX_ENABLED)

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
        val recoverConnectivity = isKeepWifiEnabled() || isKeepMobileDataEnabled()
        return KeepAliveConfig(
            keepWifi = recoverConnectivity,
            keepMobileData = recoverConnectivity,
            keepBluetooth = isKeepBluetoothEnabled(),
            recoverCollectorService = isRecoverCollectorServiceEnabled()
        )
    }

    fun isKeepWifiEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_WIFI, false)

    fun isKeepMobileDataEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_MOBILE_DATA, false)

    fun setConnectivityRecoveryEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_KEEP_WIFI, enabled)
            .putBoolean(KEY_KEEP_MOBILE_DATA, enabled)
            .apply()
        recordEvent(
            category = if (enabled) "connectivity_recovery_enabled" else "connectivity_recovery_disabled",
            message = "Wi-Fi and cellular recovery ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isKeepBluetoothEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_BLUETOOTH, false)

    fun setKeepBluetoothEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_BLUETOOTH, enabled).apply()
        recordEvent(
            category = if (enabled) "keep_alive_bluetooth_enabled" else "keep_alive_bluetooth_disabled",
            message = "Bluetooth recovery ${if (enabled) "enabled" else "disabled"}"
        )
    }

    fun isRecoverCollectorServiceEnabled(): Boolean = prefs.getBoolean(KEY_RECOVER_COLLECTOR_SERVICE, false)

    fun setRecoverCollectorServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECOVER_COLLECTOR_SERVICE, enabled).apply()
        recordEvent(
            category = if (enabled) "keep_alive_collector_recovery_enabled" else "keep_alive_collector_recovery_disabled",
            message = "Collector service recovery ${if (enabled) "enabled" else "disabled"}"
        )
    }

    private fun recordEvent(category: String, message: String, detail: String? = null) {
        val eventStore = store ?: return
        dispatchOperationalEvent(eventExecutor) {
            eventStore.recordEvent(category, message, detail)
        }
    }

    private fun secretValue(name: String): String = secretStore.read(name).orEmpty()

    private fun writeSecret(
        name: String,
        value: String,
        legacyPreferenceKey: String? = null,
        integrationEnabledKey: String? = null
    ): Boolean {
        val written = if (value.isBlank()) {
            secretStore.clear(name)
        } else {
            secretStore.write(name, value) && secretStore.read(name) == value
        }
        val editor = prefs.edit()
        legacyPreferenceKey?.let(editor::remove)
        if (!written) integrationEnabledKey?.let { editor.putBoolean(it, false) }
        editor.commit()
        if (!written) {
            recordEvent(
                category = "keystore_secret_write_failed",
                message = "Credential could not be stored in Android Keystore",
                detail = "secret=$name"
            )
        }
        return written
    }

    private fun migrateLegacySecret(preferenceKey: String, secretName: String, integrationEnabledKey: String) {
        if (!prefs.contains(preferenceKey)) return
        val legacyResult = runCatching { prefs.getString(preferenceKey, null) }
        val legacy = legacyResult.getOrNull()
        val existing = secretStore.read(secretName)
        val migrated = when {
            !existing.isNullOrBlank() -> true
            legacyResult.isFailure -> false
            legacy.isNullOrBlank() -> true
            else -> secretStore.write(secretName, legacy) && secretStore.read(secretName) == legacy
        }
        prefs.edit().apply {
            remove(preferenceKey)
            if (!migrated) putBoolean(integrationEnabledKey, false)
        }.commit()
        if (!migrated) {
            recordEvent(
                category = "keystore_secret_migration_failed",
                message = "Legacy credential migration failed closed",
                detail = "secret=$secretName"
            )
        }
    }

    private fun migrateTripEndDelayToSeconds() {
        if (prefs.contains(KEY_TELEGRAM_TRIP_END_DELAY_SECONDS)) {
            if (prefs.contains(KEY_TELEGRAM_TRIP_END_DELAY)) {
                prefs.edit().remove(KEY_TELEGRAM_TRIP_END_DELAY).commit()
            }
            return
        }
        if (!prefs.contains(KEY_TELEGRAM_TRIP_END_DELAY)) return
        val legacyMinutes = runCatching {
            prefs.getInt(KEY_TELEGRAM_TRIP_END_DELAY, DEFAULT_TELEGRAM_UNAVAILABLE_DELAY)
        }.getOrDefault(DEFAULT_TELEGRAM_UNAVAILABLE_DELAY)
        val seconds = legacyTripDelaySeconds(legacyMinutes)
        prefs.edit()
            .putInt(KEY_TELEGRAM_TRIP_END_DELAY_SECONDS, seconds)
            .remove(KEY_TELEGRAM_TRIP_END_DELAY)
            .commit()
    }

    private fun migrateLegacyLocationCategories() {
        val mqttLegacy = prefs.getBooleanOrNull(KEY_MQTT_LOCATION)
        val influxLegacy = prefs.getBooleanOrNull(KEY_INFLUX_LOCATION)
        if (mqttLegacy == null && influxLegacy == null) return

        val migrated = migrateLegacyLocationCategories(
            mqttCategories = mqttEnabledCategories(),
            influxCategories = influxEnabledCategories(),
            sharedCategories = isHaSharedCategoriesEnabled(),
            mqttLocation = mqttLegacy,
            influxLocation = influxLegacy
        )
        prefs.edit().apply {
            putStringSet(KEY_MQTT_CATEGORIES, migrated.mqttCategories)
            putStringSet(KEY_INFLUX_CATEGORIES, migrated.influxCategories)
            putBoolean(KEY_HA_SHARED_CATEGORIES, migrated.sharedCategories)
            remove(KEY_MQTT_LOCATION)
            remove(KEY_INFLUX_LOCATION)
        }.commit()
    }

    private fun migrateTelegramBuiltInTemplates() = migrateTelegramBuiltInTemplates(prefs)

    companion object {
        internal fun migrateTelegramBuiltInTemplates(prefs: SharedPreferences) {
            val editor = prefs.edit()
            var changed = false
            val tripMigrationDone = prefs.getBoolean(KEY_TELEGRAM_TRIP_SUMMARY_MIGRATION_DONE, false)
            if (!tripMigrationDone) {
                val tripKey = "$KEY_TELEGRAM_TEMPLATE_PREFIX${TelegramEventType.TRIP_SUMMARY.key}"
                val saved = runCatching { prefs.getString(tripKey, null) }.getOrNull()
                if (saved != null && TelegramBuiltInTemplates.isKnownBuiltIn(TelegramEventType.TRIP_SUMMARY, saved)) {
                    editor.remove(tripKey)
                    changed = true
                }
                editor.putBoolean(KEY_TELEGRAM_TRIP_SUMMARY_MIGRATION_DONE, true)
                changed = true
            }

            if (!prefs.getBoolean(KEY_TELEGRAM_CHARGED_TO_100_MIGRATION_DONE, false)) {
                val chargedKey = "$KEY_TELEGRAM_TEMPLATE_PREFIX${TelegramEventType.CHARGED_TO_100.key}"
                val saved = runCatching { prefs.getString(chargedKey, null) }.getOrNull()
                if (saved != null && TelegramBuiltInTemplates.isKnownBuiltIn(TelegramEventType.CHARGED_TO_100, saved)) {
                    editor.remove(chargedKey)
                    changed = true
                }
                editor.putBoolean(KEY_TELEGRAM_CHARGED_TO_100_MIGRATION_DONE, true)
                changed = true
            }

            if (!prefs.getBoolean(KEY_TELEGRAM_BUILTIN_DEFAULTS_MIGRATION_DONE, false)) {
                TelegramEventType.entries.forEach { event ->
                    val key = "$KEY_TELEGRAM_TEMPLATE_PREFIX${event.key}"
                    val saved = runCatching { prefs.getString(key, null) }.getOrNull()
                    if (saved != null && TelegramBuiltInTemplates.isKnownBuiltIn(event.key, saved)) {
                        editor.remove(key)
                        changed = true
                    }
                }
                editor.putBoolean(KEY_TELEGRAM_BUILTIN_DEFAULTS_MIGRATION_DONE, true)
                changed = true
            }
            if (changed) editor.commit()
        }

        const val PREFS_NAME = "collector_settings"
        const val KEY_AUTO_START = "autoStart"
        const val KEY_USER_SHUTDOWN = "userShutdown"
        const val KEY_MAIN_MANUAL_STOP = "mainManualStop"
        const val KEY_DEBUG_MANUAL_STOP = "debugManualStop"
        const val KEY_MQTT_MANUAL_STOP = "mqttManualStop"
        const val KEY_INFLUX_MANUAL_STOP = "influxManualStop"
        const val KEY_POLLING_ENABLED = "pollingEnabled"
        const val KEY_DEBUG_POLLING_ENABLED = "debugPollingEnabled"
        const val KEY_DEBUG_AUTO_START = "debugAutoStart"
        const val KEY_KEEP_WIFI = "keepWifi"
        const val KEY_KEEP_MOBILE_DATA = "keepMobileData"
        const val KEY_KEEP_BLUETOOTH = "keepBluetooth"
        const val KEY_RECOVER_COLLECTOR_SERVICE = "recoverCollectorService"
        const val KEY_MQTT_ENABLED = "mqttEnabled"
        const val KEY_MQTT_AUTO_START = "mqttAutoStart"
        const val KEY_MQTT_HOST = "mqttHost"
        const val KEY_MQTT_PORT = "mqttPort"
        const val KEY_MQTT_ALTERNATIVE_HOST = "mqttAlternativeHost"
        const val KEY_MQTT_ALTERNATIVE_PORT = "mqttAlternativePort"
        const val KEY_MQTT_USERNAME = "mqttUsername"
        const val KEY_MQTT_PASSWORD = "mqttPassword"
        const val KEY_MQTT_CLIENT_ID = "mqttClientId"
        const val KEY_MQTT_TOPIC_PREFIX = "mqttTopicPrefix"
        const val KEY_MQTT_DISCOVERY_PREFIX = "mqttDiscoveryPrefix"
        const val KEY_MQTT_CATEGORIES = "mqttCategories"
        const val KEY_MQTT_LOCATION = "mqttLocation"
        const val KEY_INFLUX_ENABLED = "influxEnabled"
        const val KEY_INFLUX_AUTO_START = "influxAutoStart"
        const val KEY_HA_SHARED_CATEGORIES = "haSharedCategories"
        const val KEY_INFLUX_HOST = "influxHost"
        const val KEY_INFLUX_PORT = "influxPort"
        const val KEY_INFLUX_ALTERNATIVE_HOST = "influxAlternativeHost"
        const val KEY_INFLUX_ALTERNATIVE_PORT = "influxAlternativePort"
        const val KEY_INFLUX_DATABASE = "influxDatabase"
        const val KEY_INFLUX_USERNAME = "influxUsername"
        const val KEY_INFLUX_PASSWORD = "influxPassword"
        const val KEY_INFLUX_MEASUREMENT = "influxMeasurement"
        const val KEY_INFLUX_CATEGORIES = "influxCategories"
        const val KEY_INFLUX_LOCATION = "influxLocation"
        const val KEY_TELEGRAM_ENABLED = "telegramEnabled"
        const val KEY_TELEGRAM_CHAT_ID = "telegramChatId"
        const val KEY_TELEGRAM_EVENT_PREFIX = "telegramEvent."
        const val KEY_TELEGRAM_TEMPLATE_PREFIX = "telegramTemplate."
        const val KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_STATE = "telegramTripTemplateLimitState"
        private const val KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_REVISION = "telegramTripTemplateLimitRevision"
        const val KEY_TELEGRAM_TRIP_SUMMARY_MIGRATION_DONE = "telegramTripSummaryMigrationDone"
        const val KEY_TELEGRAM_BUILTIN_DEFAULTS_MIGRATION_DONE = "telegramBuiltInDefaultsMigrationDone"
        const val KEY_TELEGRAM_CHARGED_TO_100_MIGRATION_DONE = "telegramChargedTo100MigrationDone"
        const val KEY_TELEGRAM_CHARGE_STEP = "telegramChargeStep"
        const val KEY_TELEGRAM_LOW_VOLTAGE = "telegramLowVoltage"
        const val KEY_TELEGRAM_UNAVAILABLE_DELAY = "telegramUnavailableDelay"
        const val KEY_TELEGRAM_TRIP_END_DELAY = "telegramTripEndDelay"
        const val KEY_TELEGRAM_TRIP_END_DELAY_SECONDS = "telegramTripEndDelaySeconds"
        const val KEY_TELEGRAM_CONNECTION_STATUS = "telegramConnectionStatus"
        const val KEY_TELEGRAM_CONNECTION_MESSAGE = "telegramConnectionMessage"
        const val KEY_TELEGRAM_LEGACY_MIGRATION_REQUIRED = "telegramLegacyMigrationRequired"
        const val KEY_TELEGRAM_SEND_LOCATION = "telegramSendLocation"
        const val KEY_TELEGRAM_NAVIGATOR_MASK = "telegramNavigatorMask"
        const val KEY_UI_LANGUAGE_CODE = "uiLanguageCode"
        const val KEY_TRIP_HISTORY = "tripHistory"
        const val KEY_TRIP_SPEED_GREEN = "tripSpeedGreen"
        const val KEY_TRIP_SPEED_YELLOW = "tripSpeedYellow"
        const val KEY_TRIP_CONSUMPTION_GREEN = "tripConsumptionGreen"
        const val KEY_TRIP_CONSUMPTION_YELLOW = "tripConsumptionYellow"
        const val SECRET_MQTT_USERNAME = "mqtt.username"
        const val SECRET_MQTT_PASSWORD = "mqtt.password"
        const val SECRET_INFLUX_USERNAME = "influx.username"
        const val SECRET_INFLUX_PASSWORD = "influx.password"

        private val TELEGRAM_TRIP_TEMPLATE_LIMIT_LOCK = Any()
        const val SECRET_TELEGRAM_BOT_TOKEN = "telegram.bot_token"
        const val KEY_UPDATE_AUTO_CHECK = "updateAutoCheck"
        const val KEY_UPDATE_HINT_ENABLED = "updateHintEnabled"
        const val KEY_TAILSCALE_ACTIVATION = "tailscaleActivation"
        const val KEY_TAILSCALE_ACTIVATION_LAST_ATTEMPT_AT_MS = "tailscaleActivationLastAttemptAtMs"
        const val KEY_ARCHIVE_STORAGE_LIMIT_GB = "archiveStorageLimitGb"
        const val KEY_ARCHIVE_STORAGE_JOB_MODE = "archiveStorageJobMode"
        const val KEY_ARCHIVE_STORAGE_JOB_RUNNING = "archiveStorageJobRunning"
        const val KEY_ARCHIVE_STORAGE_JOB_STEP_INDEX = "archiveStorageJobStepIndex"
        const val KEY_ARCHIVE_STORAGE_JOB_STEP_COUNT = "archiveStorageJobStepCount"
        const val KEY_ARCHIVE_STORAGE_JOB_MESSAGE_UK = "archiveStorageJobMessageUk"
        const val KEY_ARCHIVE_STORAGE_JOB_MESSAGE_EN = "archiveStorageJobMessageEn"
        const val KEY_ARCHIVE_STORAGE_JOB_ITEM_ID = "archiveStorageJobItemId"
        const val KEY_ARCHIVE_STORAGE_JOB_ERROR = "archiveStorageJobError"
        const val KEY_ARCHIVE_STORAGE_JOB_UPDATED_AT_MS = "archiveStorageJobUpdatedAtMs"
        const val KEY_DB_MAINTENANCE_OPERATION = "dbMaintenanceOperation"
        const val KEY_DB_MAINTENANCE_RUNNING = "dbMaintenanceRunning"
        const val KEY_DB_MAINTENANCE_COMPLETED = "dbMaintenanceCompleted"
        const val KEY_DB_MAINTENANCE_STEP_INDEX = "dbMaintenanceStepIndex"
        const val KEY_DB_MAINTENANCE_STEP_COUNT = "dbMaintenanceStepCount"
        const val KEY_DB_MAINTENANCE_MESSAGE_UK = "dbMaintenanceMessageUk"
        const val KEY_DB_MAINTENANCE_MESSAGE_EN = "dbMaintenanceMessageEn"
        const val KEY_DB_MAINTENANCE_ERROR = "dbMaintenanceError"
        const val KEY_DB_MAINTENANCE_WARNING = "dbMaintenanceWarning"
        const val KEY_DB_MAINTENANCE_ARCHIVE_PATH = "dbMaintenanceArchivePath"
        const val KEY_DB_MAINTENANCE_STARTED_AT_MS = "dbMaintenanceStartedAtMs"
        const val KEY_DB_MAINTENANCE_UPDATED_AT_MS = "dbMaintenanceUpdatedAtMs"
        const val KEY_DB_MAINTENANCE_CANCEL_AVAILABLE = "dbMaintenanceCancelAvailable"
        const val KEY_MAIN_STORAGE_CUTOVER_DEFERRED_REASON = "mainStorageCutoverDeferredReason"
        const val KEY_MAIN_STORAGE_CUTOVER_ERROR = "mainStorageCutoverError"
        const val KEY_DEBUG_STORAGE_CUTOVER_ERROR = "debugStorageCutoverError"
        const val KEY_STORAGE_CUTOVER_JOURNAL_FAMILY = "storageCutoverJournalFamily"
        const val KEY_STORAGE_CUTOVER_JOURNAL_ARCHIVE_PATH = "storageCutoverJournalArchivePath"
        const val KEY_STORAGE_CUTOVER_JOURNAL_PHASE = "storageCutoverJournalPhase"
        const val KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_FORMAT = "storageCutoverJournalSourceFormat"
        const val KEY_STORAGE_CUTOVER_JOURNAL_MANUAL = "storageCutoverJournalManual"
        const val KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_NAMES = "storageCutoverJournalSourceNames"
        const val KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_DATABASE_NAME = "storageCutoverJournalSourceDatabaseName"
        const val KEY_STORAGE_CUTOVER_JOURNAL_TARGET_DATABASE_NAME = "storageCutoverJournalTargetDatabaseName"
        const val KEY_STORAGE_CUTOVER_ARCHIVE_PENDING = "storageCutoverArchivePending"
        const val DB_MAINTENANCE_RECOVERY_GRACE_MS = 15_000L
        const val DEFAULT_ARCHIVE_STORAGE_LIMIT_GB = 2
        const val MIN_ARCHIVE_STORAGE_LIMIT_GB = 1
        const val MAX_ARCHIVE_STORAGE_LIMIT_GB = 10
        const val DEFAULT_MQTT_PORT = 1883
        const val DEFAULT_TELEGRAM_CHARGE_STEP = 5
        const val DEFAULT_TELEGRAM_NAVIGATOR_MASK = TelegramNavigatorMask.NONE
        const val DEFAULT_UI_LANGUAGE_CODE = "uk"
        const val MIN_TELEGRAM_CHARGE_STEP = 1
        const val MAX_TELEGRAM_CHARGE_STEP = 99
        const val DEFAULT_TELEGRAM_LOW_VOLTAGE = 12.5f
        const val MIN_TELEGRAM_LOW_VOLTAGE = 9.0f
        const val MAX_TELEGRAM_LOW_VOLTAGE = 15.0f
        const val DEFAULT_TELEGRAM_UNAVAILABLE_DELAY = 1
        const val DEFAULT_TELEGRAM_TRIP_END_DELAY_SECONDS = 10
        const val DEFAULT_TRIP_SPEED_GREEN = 90
        const val DEFAULT_TRIP_SPEED_YELLOW = 30
        const val DEFAULT_TRIP_CONSUMPTION_GREEN = 15
        const val DEFAULT_TRIP_CONSUMPTION_YELLOW = 20
        const val MIN_TELEGRAM_TRIP_END_DELAY_SECONDS = 5
        const val MAX_TELEGRAM_TRIP_END_DELAY_SECONDS = 300
        const val MIN_TELEGRAM_DELAY_MINUTES = 1
        const val MAX_TELEGRAM_DELAY_MINUTES = 60
        const val AUTO_START_ENABLED_UK = "Автозапуск активовано"
        const val AUTO_START_DISABLED_UK = "Автозапуск деактивовано"

        internal fun migrateLegacyLocationCategories(
            mqttCategories: Set<String>,
            influxCategories: Set<String>,
            sharedCategories: Boolean,
            mqttLocation: Boolean?,
            influxLocation: Boolean?
        ): LegacyLocationMigration {
            if (mqttLocation == null && influxLocation == null) {
                return LegacyLocationMigration(mqttCategories, influxCategories, sharedCategories)
            }
            val resolvedMqtt = mqttLocation ?: false
            val resolvedInflux = influxLocation ?: false
            val splitSharedSelection = sharedCategories && resolvedMqtt != resolvedInflux
            val mqtt = mqttCategories.toMutableSet()
            val influx = if (splitSharedSelection) {
                mqttCategories.toMutableSet()
            } else {
                influxCategories.toMutableSet()
            }
            if (resolvedMqtt) mqtt.add("location") else mqtt.remove("location")
            if (resolvedInflux) influx.add("location") else influx.remove("location")
            val preserveShared = sharedCategories && resolvedMqtt == resolvedInflux
            return LegacyLocationMigration(mqtt, influx, preserveShared)
        }

        private fun SharedPreferences.getBooleanOrNull(key: String): Boolean? {
            if (!contains(key)) return null
            return runCatching { getBoolean(key, false) }.getOrNull()
        }

        internal fun legacyTripDelaySeconds(minutes: Int): Int {
            return (minutes.toLong() * 60L)
                .coerceIn(
                    MIN_TELEGRAM_TRIP_END_DELAY_SECONDS.toLong(),
                    MAX_TELEGRAM_TRIP_END_DELAY_SECONDS.toLong()
                )
                .toInt()
        }

        fun isDbMaintenanceRunning(context: Context): Boolean {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val operation = DbMaintenanceOperation.fromKey(prefs.getString(KEY_DB_MAINTENANCE_OPERATION, null))
            return operation != null && prefs.getBoolean(KEY_DB_MAINTENANCE_RUNNING, false)
        }
    }
}
