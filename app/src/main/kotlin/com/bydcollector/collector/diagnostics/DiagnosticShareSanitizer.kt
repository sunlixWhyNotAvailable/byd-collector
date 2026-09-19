package com.bydcollector.collector.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * Output-only privacy policy for the text copied into a diagnostic share.
 * The identity aliases live only for this instance (one share bundle).
 */
internal class DiagnosticShareSanitizer {
    private enum class IdentityKind(val label: String) {
        VIN("VIN"),
        CHAT_ID("CHAT_ID"),
        ACCOUNT_ID("ACCOUNT_ID")
    }

    private val aliases = IdentityKind.entries.associateWith { LinkedHashMap<String, String>() }

    fun sanitizeText(text: String): String {
        var sanitized = replaceCookieHeaders(text)
        sanitized = replaceAuthHeaders(sanitized)
        // Android Wi-Fi dumps allow an unquoted SSID containing spaces. Match
        // only named properties, ending at the next property or record boundary.
        sanitized = WIFI_PROPERTY_PATTERN.replace(sanitized) { match ->
            match.groups["prefix"]!!.value + replaceMaskedToken(match.groups["value"]!!.value, REDACTED)
        }
        sanitized = VIN_RESULT_PATTERN.replace(sanitized) { match ->
            match.groups["prefix"]!!.value + replaceMaskedToken(match.groups["value"]!!.value,
                alias(IdentityKind.VIN, match.groups["value"]!!.value))
        }
        sanitized = ASSIGNMENT_PATTERN.replace(sanitized) { match ->
            val key = match.groups["key"]?.value.orEmpty()
            val token = match.groups["value"]?.value.orEmpty()
            val prefix = match.groups["prefix"]?.value.orEmpty()
            when (val identity = identityKind(key)) {
                null -> if (isCoordinateKey(key)) {
                    if (numericToken(token) != null) prefix + replaceMaskedToken(token, REDACTED_COORDINATE) else match.value
                } else prefix + replaceMaskedToken(token, REDACTED)
                else -> prefix + replaceMaskedToken(token, alias(identity, token))
            }
        }
        sanitized = URI_USERINFO_PATTERN.replace(sanitized) { match ->
            "${match.groups["scheme"]?.value.orEmpty()}$REDACTED@"
        }
        sanitized = TELEGRAM_HOST_BOT_PATTERN.replace(sanitized) { match ->
            "${match.groups["prefix"]?.value.orEmpty()}$REDACTED"
        }
        sanitized = TELEGRAM_BOT_PATH_PATTERN.replace(sanitized) { match ->
            "${match.groups["prefix"]?.value.orEmpty()}$REDACTED"
        }
        sanitized = GEO_COORDINATE_PATTERN.replace(sanitized) { match ->
            "${match.groups["prefix"]?.value.orEmpty()}$REDACTED_COORDINATE,$REDACTED_COORDINATE"
        }
        sanitized = ANDROID_LOCATION_PATTERN.replace(sanitized) { match ->
            "${match.groups["prefix"]?.value.orEmpty()}$REDACTED_COORDINATE,$REDACTED_COORDINATE"
        }
        return URL_PATTERN.replace(sanitized) { match ->
            val url = match.value
            if (isRecognizedMapUrl(url)) sanitizeMapUrl(url) else url
        }
    }

    /** Parses exactly one JSON value; trailing non-whitespace is an error. */
    fun sanitizeJsonLine(line: String): String {
        val tokener = JSONTokener(line)
        val value = tokener.nextValue()
        if (tokener.nextClean().code != 0) throw tokener.syntaxError("Trailing data")
        return jsonText(sanitizeJsonValue(value))
    }

    private fun sanitizeJsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> {
            val output = JSONObject()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val original = value.get(key)
                val replacement = when {
                    isSensitiveKey(key) -> if (original === JSONObject.NULL) JSONObject.NULL else REDACTED
                    identityKind(key) != null -> identityValue(identityKind(key)!!, original)
                    isCoordinateKey(key) -> coordinateValue(original)
                    else -> sanitizeJsonValue(original)
                }
                output.put(key, replacement)
            }
            output
        }
        is JSONArray -> {
            val output = JSONArray()
            for (index in 0 until value.length()) output.put(sanitizeJsonValue(value.get(index)))
            output
        }
        is String -> sanitizeText(value)
        else -> value
    }

    private fun identityValue(kind: IdentityKind, value: Any?): Any? {
        if (value === JSONObject.NULL) return JSONObject.NULL
        val raw = when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> return REDACTED
        }
        return raw.takeIf(String::isNotBlank)?.let { alias(kind, it) } ?: value
    }

    private fun coordinateValue(value: Any?): Any? {
        if (value === JSONObject.NULL) return JSONObject.NULL
        return when (value) {
            is Number -> if (value.toDouble().isFinite()) REDACTED_COORDINATE else value
            is String -> if (numericToken(value) != null) REDACTED_COORDINATE else value
            else -> value
        }
    }

    private fun alias(kind: IdentityKind, token: String): String {
        val quoted = token.length >= 2 &&
            token.first() == token.last() && token.first() in charArrayOf('\'', '"')
        val raw = if (quoted) token.substring(1, token.length - 1) else token
        if (raw.isBlank()) return ""
        val values = aliases.getValue(kind)
        return values.getOrPut(raw) { "[${kind.label}-${values.size + 1}]" }
    }

    private fun replaceCookieHeaders(text: String): String = COOKIE_HEADER_PATTERN.replace(text) { match ->
        "${match.groups["prefix"]?.value.orEmpty()}${match.groups["separator"]?.value.orEmpty()}$REDACTED"
    }

    private fun replaceAuthHeaders(text: String): String = AUTH_HEADER_PATTERN.replace(text) { match ->
        match.groups["prefix"]?.value.orEmpty() + replaceMaskedToken(match.groups["value"]?.value.orEmpty(), REDACTED)
    }

    private fun replaceMaskedToken(token: String, replacement: String): String {
        if (token.length >= 2 && token.first() == token.last() && token.first() in charArrayOf('\'', '"')) {
            return "${token.first()}$replacement${token.last()}"
        }
        return replacement
    }

    private fun numericToken(token: String): Double? {
        val raw = if (token.length >= 2 && token.first() == token.last() && token.first() in charArrayOf('\'', '"')) {
            token.substring(1, token.length - 1)
        } else token
        return raw.toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    private fun isSensitiveKey(key: String): Boolean = normalize(key) in SENSITIVE_KEYS

    private fun identityKind(key: String): IdentityKind? = when (normalize(key)) {
        in VIN_KEYS -> IdentityKind.VIN
        in CHAT_ID_KEYS -> IdentityKind.CHAT_ID
        in ACCOUNT_ID_KEYS -> IdentityKind.ACCOUNT_ID
        else -> null
    }

    private fun isCoordinateKey(key: String): Boolean = normalize(key) in COORDINATE_KEYS

    private fun normalize(key: String): String = key.lowercase(Locale.US).filter(Char::isLetterOrDigit)

    private fun isRecognizedMapUrl(url: String): Boolean = MAP_HOST_PATTERN.containsMatchIn(url)

    private fun sanitizeMapUrl(url: String): String {
        var output = MAP_FRAGMENT_PATTERN.replace(url) { match ->
            "${match.groups["prefix"]?.value.orEmpty()}${match.groups["zoom"]?.value.orEmpty()}/$REDACTED_COORDINATE/$REDACTED_COORDINATE"
        }
        output = MAP_QUERY_COORDINATE_PATTERN.replace(output) { match ->
            "${match.groups["prefix"]?.value.orEmpty()}$REDACTED_COORDINATE"
        }
        return MAP_PAIR_PATTERN.replace(output) { match ->
            "${match.groups["prefix"]?.value.orEmpty()}$REDACTED_COORDINATE${match.groups["separator"]?.value.orEmpty()}$REDACTED_COORDINATE"
        }
    }

    private fun jsonText(value: Any?): String = when (value) {
        is JSONObject, is JSONArray -> value.toString()
        is String -> JSONObject.quote(value)
        JSONObject.NULL, null -> "null"
        else -> value.toString()
    }

    companion object {
        private const val REDACTED = "[redacted]"
        private const val REDACTED_COORDINATE = "[redacted-coordinate]"
        private val SENSITIVE_KEYS = setOf(
            "password", "passwd", "mqttpassword", "influxpassword",
            "bottoken", "telegrambottoken", "apikey", "accesstoken",
            "refreshtoken", "clientsecret", "authorization", "proxyauthorization",
            "cookie", "setcookie", "xapikey",
            "ssid", "bssid", "mssid", "mbssid", "wifissid", "wifibssid"
        )
        private val VIN_KEYS = setOf("vin", "infovin", "vehiclevin", "autovin", "realautovin", "bodyworkautovin", "bodyworkrealautovin")
        private val CHAT_ID_KEYS = setOf("chatid", "telegramchatid")
        private val ACCOUNT_ID_KEYS = setOf("accountid", "telegramaccountid")
        private val COORDINATE_KEYS = setOf(
            "latitude", "longitude", "lat", "lon", "lng", "locationlatitude", "locationlongitude"
        )
        private val ASSIGNMENT_KEYS =
            "(?:password|passwd|mqtt[-_.]?password|influx[-_.]?password|(?:telegram[-_.]?)?bot[-_.]?token|" +
                "api[-_.]?key|access[-_.]?token|refresh[-_.]?token|client[-_.]?secret|" +
                "authorization|proxy[-_.]?authorization|cookie|set[-_.]?cookie|x[-_.]?api[-_.]?key|" +
                "vin|info[-_.]?vin|vehicle[-_.]?vin|(?:bodywork[-_.]?)?(?:real[-_.]?)?auto[-_.]?vin|" +
                "(?:telegram[-_.]?)?chat[-_.]?id|(?:telegram[-_.]?)?account[-_.]?id|" +
                "latitude|longitude|location[-_.]?(?:latitude|longitude)|lat|lon|lng)"
        private val ASSIGNMENT_PATTERN = Regex(
            "(?i)(?<![A-Za-z0-9_.-])(?<prefix>(?<key>$ASSIGNMENT_KEYS)(?<separator>[ \\t]*(?:[\\\"'][ \\t]*)?[:=][ \\t]*))(?<value>\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|\\[redacted(?:-coordinate)?\\]|[^\\s,;}&\\]]+)"
        )
        private val WIFI_PROPERTY_PATTERN = Regex(
            """(?i)(?<![A-Za-z0-9_.-])(?<prefix>(?:m?(?:ssid|bssid)|wifi[-_.]?(?:ssid|bssid))[ \t]*(?:["'][ \t]*)?[:=][ \t]*)(?<value>"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^,;\r\n}\]]+?)(?=[ \t]+[A-Za-z][A-Za-z0-9_.-]*[ \t]*[:=]|[,;\r\n}\]]|$)"""
        )
        // VIN-valued API/property results, not arbitrary 17-character IDs or MACs.
        private val VIN_RESULT_PATTERN = Regex(
            """(?i)(?<![A-Za-z0-9_.-])(?<prefix>(?:get(?:Vehicle)?Vin\(\)[ \t]*(?:[:=]|->|returns?)[ \t]*|INFO_VIN[ \t]+(?:value|result)[ \t]*[:=][ \t]*))(?<value>"[A-HJ-NPR-Z0-9]{17}"|'[A-HJ-NPR-Z0-9]{17}'|[A-HJ-NPR-Z0-9]{17})(?![A-Za-z0-9])"""
        )
        private val AUTH_HEADER_PATTERN = Regex(
            "(?i)(?<![A-Za-z0-9_.-])(?<prefix>(?:Authorization|Proxy-Authorization)[ \\t]*(?:[\\\"'][ \\t]*)?[:=][ \\t]*)(?<value>\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|(?:(?:Bearer|Basic)[ \\t]+)?[^\\s,;&}\\]]+)"
        )
        private val COOKIE_HEADER_PATTERN = Regex(
            "(?im)^(?<prefix>[ \\t]*(?:Cookie|Set-Cookie))(?<separator>[ \\t]*:[ \\t]*)[^\\r\\n]+"
        )
        private val URI_USERINFO_PATTERN = Regex(
            "(?i)(?<scheme>\\b[a-z][a-z0-9+.-]*://)[^/?#\\s@]+@"
        )
        private val TELEGRAM_HOST_BOT_PATTERN = Regex(
            "(?i)(?<prefix>https?://(?:api\\.)?telegram\\.org/(?:[^\\s?#]*/)?bot)[^/\\s?#]+"
        )
        private val TELEGRAM_BOT_PATH_PATTERN = Regex(
            "(?i)(?<prefix>(?<![A-Za-z0-9])/bot)\\d{5,}:[A-Za-z0-9_-]{5,}(?=/|[?#]|$)"
        )
        private val GEO_COORDINATE_PATTERN = Regex(
            "(?i)(?<prefix>\\bgeo:)[+-]?(?:\\d{1,3}(?:\\.\\d+)?|\\.\\d+),\\s*[+-]?(?:\\d{1,3}(?:\\.\\d+)?|\\.\\d+)"
        )
        private val ANDROID_LOCATION_PATTERN = Regex(
            "(?i)(?<prefix>\\bLocation\\[[^\\]\\r\\n]*?)[+-]?(?:\\d{1,3}(?:\\.\\d+)?|\\.\\d+),\\s*[+-]?(?:\\d{1,3}(?:\\.\\d+)?|\\.\\d+)(?=[^\\]\\r\\n]*])"
        )
        private val URL_PATTERN = Regex("(?i)\\bhttps?://[^\\s<>\\\"']+")
        private val MAP_HOST_PATTERN = Regex(
            "(?i)^https?://(?:(?:www\\.)?(?:google\\.com|waze\\.com|openstreetmap\\.org)|maps\\.(?:google|apple)\\.com)(?::\\d+)?(?=[/?#]|$)"
        )
        private val MAP_FRAGMENT_PATTERN = Regex(
            "(?i)(?<prefix>#map=)(?<zoom>[+-]?(?:\\d{1,3}(?:\\.\\d+)?|\\.\\d+))/[+-]?(?:\\d{1,3}(?:\\.\\d+)?|\\.\\d+)/[+-]?(?:\\d{1,3}(?:\\.\\d+)?|\\.\\d+)"
        )
        private val MAP_QUERY_COORDINATE_PATTERN = Regex(
            "(?i)(?<prefix>[?&#](?:mlat|mlon)=)[+-]?(?:\\d{1,3}(?:\\.\\d+)?|\\.\\d+)"
        )
        private val MAP_PAIR_PATTERN = Regex(
            "(?i)(?<prefix>[?&#](?:query|q|ll|sll|center|destination|origin)=|/maps/@)[+-]?(?:\\d{1,3}(?:\\.\\d+)?|\\.\\d+)(?<separator>,|%2c)[+-]?(?:\\d{1,3}(?:\\.\\d+)?|\\.\\d+)(?=$|[&#/,)])"
        )
    }
}
