package com.bydcollector.collector.ui.compose

import org.json.JSONObject

private fun UiStrings.feedback(uk: String, en: String) = if (step == "Крок") uk else en

internal fun runtimeJobMessage(
    strings: UiStrings,
    kind: String,
    completed: Boolean,
    result: JSONObject = JSONObject(),
    error: String = ""
): String {
    if (!completed) return operationFailureMessage(strings, kind, error)
    return when (kind) {
        "logClear" -> if ((result.optJSONArray("warnings")?.length() ?: 0) == 0) strings.logsCleared
            else strings.logsPartiallyCleared
        "logStart" -> strings.feedback("Запис logcat почато", "Logcat recording started")
        "logStop" -> strings.feedback("Запис logcat зупинено", "Logcat recording stopped")
        "mqttTest", "influxTest" -> connectionTestMessage(strings,
            if (kind == "mqttTest") "MQTT" else "InfluxDB", result.optBoolean("ok"), result.optString("message"))
        "mqttCleanup" -> strings.haIdentity.mqttCleanupSuccess
        else -> strings.feedback("Операцію виконано", "Operation completed")
    }
}

internal fun operationFailureMessage(strings: UiStrings, kind: String, detail: String): String {
    val title = when (kind) {
        "logStart" -> strings.feedback("Не вдалося почати запис logcat", "Could not start logcat recording")
        "logStop" -> strings.feedback("Не вдалося зупинити запис logcat", "Could not stop logcat recording")
        "logClear" -> strings.logsClearFailed
        "logShare" -> strings.logsShareFailed
        "archiveShare" -> strings.archiveShareFailed
        "updateDownload" -> strings.updateDownloadFailed
        "mqttCleanup" -> strings.haIdentity.mqttCleanupFailed
        "mqttTest" -> strings.feedback("MQTT: перевірка з’єднання невдала", "MQTT: connection test failed")
        "influxTest" -> strings.feedback("InfluxDB: перевірка з’єднання невдала", "InfluxDB: connection test failed")
        "mqttStart" -> strings.feedback("Не вдалося запустити MQTT", "Could not start MQTT")
        "mqttStop" -> strings.feedback("Не вдалося зупинити MQTT", "Could not stop MQTT")
        "influxStart" -> strings.feedback("Не вдалося запустити InfluxDB", "Could not start InfluxDB")
        "influxStop" -> strings.feedback("Не вдалося зупинити InfluxDB", "Could not stop InfluxDB")
        "mqttSave" -> strings.feedback("Не вдалося зберегти налаштування MQTT", "Could not save MQTT settings")
        "access" -> strings.feedback("Не вдалося перевірити дозволи", "Could not check permissions")
        else -> strings.feedback("Не вдалося виконати операцію", "Could not complete the operation")
    }
    return "$title\n${operationFailureDetail(strings, detail)}"
}

internal fun archivePreflightWarning(strings: UiStrings): String = strings.feedback(
    "Не вдалося перевірити базу та черги. Їхній стан невідомий; архівація може залишити ненадіслані дані лише в архіві. Подробиці — у логах.",
    "Could not check the database and queues. Their state is unknown; archiving may leave unsent data only in the archive. See the logs for details."
)

internal fun archiveCompletionWarning(strings: UiStrings, warning: String): String {
    val text = warning.lowercase()
    val summary = strings.feedback("Архівацію завершено з попередженнями. Подробиці — у логах.",
        "Archiving completed with warnings. See the logs for details.")
    return if ("archive verification could not be scheduled" in text) summary + "\n" + strings.feedback(
        "Сирий архів збережено, але його перевірку та пакування не запущено.",
        "The raw archive is preserved, but its verification and compression could not be started.")
    else summary
}

internal fun connectionTestMessage(strings: UiStrings, channel: String, ok: Boolean, detail: String): String {
    val code = httpStatusCode.find(detail)?.groupValues?.get(1)?.let { " (HTTP $it)" }.orEmpty()
    val result = if (ok) strings.feedback("з’єднання успішне", "connection successful")
        else strings.feedback("перевірка з’єднання невдала", "connection test failed")
    return "$channel: $result$code" + if (ok) "" else "\n${operationFailureDetail(strings, detail)}"
}

private val httpStatusCode = Regex("\\bhttp[ :]+([1-5][0-9]{2})\\b", RegexOption.IGNORE_CASE)

// Raw errors remain in the job/operation journal; UI copy never echoes backend payloads.
internal fun operationFailureDetail(strings: UiStrings, detail: String): String {
    val text = detail.lowercase()
    val httpCode = httpStatusCode.find(detail)?.groupValues?.get(1)
    return when {
        "insufficient free space" in text || "no space left" in text || "free-space reserve" in text ->
            strings.feedback("Недостатньо вільного місця. Звільніть місце та повторіть.",
                "Not enough free space. Free up space and retry.")
        "unauthorized" in text && "adb" in text || "adb_not_authorized" in text ->
            strings.feedback("Надайте доступ ADB та повторіть.", "Grant ADB access and retry.")
        httpCode == "401" || httpCode == "403" || "not authorized" in text || "bad user name or password" in text ->
            strings.feedback("Перевірте облікові дані та права доступу.", "Check credentials and access permissions.")
        "permission denied" in text || "securityexception" in text ->
            strings.feedback("Недостатньо прав доступу. Перевірте дозволи застосунку.",
                "Access denied. Check the app permissions.")
        "invalid or stale archive selection" in text ->
            strings.feedback("Оновіть список архівів і виберіть файли знову.", "Refresh the archive list and select the files again.")
        "already running" in text || "already active" in text || "maintenance is active" in text ->
            strings.feedback("Інша операція ще виконується. Дочекайтеся її завершення.",
                "Another operation is still running. Wait for it to finish.")
        "timeout" in text || "timed out" in text || "failed to connect" in text || "connection refused" in text ||
            "unknownhost" in text || "unable to resolve host" in text || "no address associated" in text ||
            "network is unreachable" in text || "connection lost" in text ->
            strings.feedback("Перевірте з’єднання, адресу сервера та VPN, якщо він потрібний, і повторіть.",
                "Check the connection, server address and VPN if required, then retry.")
        "not configured" in text || "host is blank" in text || "invalid port" in text ->
            strings.feedback("Перевірте налаштування з’єднання.", "Check connection settings.")
        else -> strings.feedback("Подробиці — у логах. Повторіть після усунення помилки.",
            "See the logs for details. Retry after resolving the error.")
    }
}
