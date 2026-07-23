package com.bydcollector.collector.telegram

const val TELEGRAM_MESSAGE_MAX_CHARS = 4_096

enum class TelegramEventType(val key: String) {
    CHARGING_STARTED("charging-started"),
    CHARGING_PROGRESS("charging-progress"),
    CHARGED_TO_100("charged-full"),
    CHARGING_STOPPED("charging-stopped"),
    CHARGE_GUN_CONNECTED("charge-gun-connected"),
    CHARGE_GUN_DISCONNECTED("charge-gun-disconnected"),
    LOW_12V_VOLTAGE("low-12v"),
    TELEMETRY_UNAVAILABLE("telemetry-unavailable"),
    TRIP_SUMMARY("trip-summary");

    companion object {
        fun fromKey(value: String): TelegramEventType? = entries.firstOrNull { it.key == value }
    }
}

data class TelegramTemplateSpec(
    val defaultTemplate: String,
    val allowedVariables: Set<String>
)

object TelegramTemplateCatalog {
    private val specs = mapOf(
        TelegramEventType.CHARGING_STARTED to TelegramTemplateSpec(
            "Charging started at {soc}% ({battery_power_kw} kW) at {time}.",
            setOf("soc", "battery_power_kw", "time")
        ),
        TelegramEventType.CHARGING_PROGRESS to TelegramTemplateSpec(
            "Charging progress: {soc}% (+{charge_added_percent}%, {charge_added_kwh} kWh), {battery_power_kw} kW.",
            setOf("soc", "charge_added_percent", "charge_added_kwh", "battery_power_kw")
        ),
        TelegramEventType.CHARGED_TO_100 to TelegramTemplateSpec(
            "Charging complete: {soc}%, {remaining_energy_kwh} kWh remaining, range {range_km} km at {time}.",
            setOf("soc", "remaining_energy_kwh", "range_km", "time")
        ),
        TelegramEventType.CHARGING_STOPPED to TelegramTemplateSpec(
            "Charging stopped at {soc}% after {charge_duration}; added {charge_added_percent}% / {charge_added_kwh} kWh at {time}.",
            setOf("soc", "charge_duration", "charge_added_percent", "charge_added_kwh", "time")
        ),
        TelegramEventType.CHARGE_GUN_CONNECTED to TelegramTemplateSpec(
            "Charge gun connected at {soc}% at {time}.",
            setOf("soc", "time")
        ),
        TelegramEventType.CHARGE_GUN_DISCONNECTED to TelegramTemplateSpec(
            "Charge gun disconnected at {soc}% at {time}.",
            setOf("soc", "time")
        ),
        TelegramEventType.LOW_12V_VOLTAGE to TelegramTemplateSpec(
            "Low 12 V battery voltage: {battery_12v} V at {time}.",
            setOf("battery_12v", "time")
        ),
        TelegramEventType.TELEMETRY_UNAVAILABLE to TelegramTemplateSpec(
            "Telemetry unavailable since {last_data_time}: {error} ({time}).",
            setOf("last_data_time", "error", "time")
        ),
        TelegramEventType.TRIP_SUMMARY to TelegramTemplateSpec(
            "Trip complete: {trip_distance_km} km, {trip_energy_kwh} kWh at {time}.",
            setOf("trip_distance_km", "trip_energy_kwh", "trip_duration", "soc_start", "soc_end", "time")
        )
    )

    val events: Set<TelegramEventType> = specs.keys

    fun spec(event: TelegramEventType): TelegramTemplateSpec = specs.getValue(event)
}

sealed interface TelegramTemplateToken {
    data class Text(val value: String) : TelegramTemplateToken
    data class Variable(val name: String, val position: Int) : TelegramTemplateToken
}

enum class TelegramTemplateErrorKind {
    EMPTY,
    TOO_LONG,
    MALFORMED_PLACEHOLDER,
    VARIABLE_NOT_ALLOWED,
    VALUE_MISSING
}

data class TelegramTemplateError(
    val kind: TelegramTemplateErrorKind,
    val position: Int? = null,
    val variable: String? = null,
    val actualLength: Int? = null
)

data class TelegramTemplateParseResult(
    val tokens: List<TelegramTemplateToken>,
    val errors: List<TelegramTemplateError>
) {
    val isValid: Boolean get() = errors.isEmpty()
}

object TelegramTemplateParser {
    private val variableName = Regex("[a-z][a-z0-9_]*")

    fun parse(template: String): TelegramTemplateParseResult {
        val tokens = mutableListOf<TelegramTemplateToken>()
        val errors = mutableListOf<TelegramTemplateError>()
        var textStart = 0
        var index = 0

        while (index < template.length) {
            when (template[index]) {
                '{' -> {
                    if (textStart < index) tokens += TelegramTemplateToken.Text(template.substring(textStart, index))
                    val close = template.indexOf('}', startIndex = index + 1)
                    if (close < 0) {
                        errors += TelegramTemplateError(TelegramTemplateErrorKind.MALFORMED_PLACEHOLDER, index)
                        textStart = template.length
                        break
                    }
                    val name = template.substring(index + 1, close)
                    if (!variableName.matches(name)) {
                        errors += TelegramTemplateError(
                            TelegramTemplateErrorKind.MALFORMED_PLACEHOLDER,
                            position = index,
                            variable = name.ifEmpty { null }
                        )
                    } else {
                        tokens += TelegramTemplateToken.Variable(name, index)
                    }
                    index = close + 1
                    textStart = index
                }
                '}' -> {
                    errors += TelegramTemplateError(TelegramTemplateErrorKind.MALFORMED_PLACEHOLDER, index)
                    index++
                }
                else -> index++
            }
        }
        if (textStart < template.length) tokens += TelegramTemplateToken.Text(template.substring(textStart))
        return TelegramTemplateParseResult(tokens, errors)
    }
}

data class TelegramTemplateRenderResult(
    val text: String?,
    val errors: List<TelegramTemplateError>
) {
    val isSuccess: Boolean get() = errors.isEmpty()
}

object TelegramTemplateRenderer {
    fun validate(event: TelegramEventType, template: String): List<TelegramTemplateError> {
        val parsed = TelegramTemplateParser.parse(template)
        val errors = parsed.errors.toMutableList()
        val length = template.codePointCount(0, template.length)
        if (template.isBlank()) errors += TelegramTemplateError(TelegramTemplateErrorKind.EMPTY)
        if (length > TELEGRAM_MESSAGE_MAX_CHARS) {
            errors += TelegramTemplateError(TelegramTemplateErrorKind.TOO_LONG, actualLength = length)
        }
        val allowed = TelegramTemplateCatalog.spec(event).allowedVariables
        parsed.tokens.filterIsInstance<TelegramTemplateToken.Variable>().forEach { token ->
            if (token.name !in allowed) {
                errors += TelegramTemplateError(
                    TelegramTemplateErrorKind.VARIABLE_NOT_ALLOWED,
                    position = token.position,
                    variable = token.name
                )
            }
        }
        return errors
    }

    fun render(
        event: TelegramEventType,
        template: String,
        values: Map<String, String>
    ): TelegramTemplateRenderResult {
        val parsed = TelegramTemplateParser.parse(template)
        val errors = validate(event, template).toMutableList()
        parsed.tokens.filterIsInstance<TelegramTemplateToken.Variable>().forEach { token ->
            if (token.name !in values) {
                errors += TelegramTemplateError(
                    TelegramTemplateErrorKind.VALUE_MISSING,
                    position = token.position,
                    variable = token.name
                )
            }
        }
        if (errors.isNotEmpty()) return TelegramTemplateRenderResult(null, errors)

        val rendered = buildString {
            parsed.tokens.forEach { token ->
                when (token) {
                    is TelegramTemplateToken.Text -> append(token.value)
                    is TelegramTemplateToken.Variable -> append(values.getValue(token.name))
                }
            }
        }
        val renderedLength = rendered.codePointCount(0, rendered.length)
        if (rendered.isBlank()) errors += TelegramTemplateError(TelegramTemplateErrorKind.EMPTY)
        if (renderedLength > TELEGRAM_MESSAGE_MAX_CHARS) {
            errors += TelegramTemplateError(TelegramTemplateErrorKind.TOO_LONG, actualLength = renderedLength)
        }
        return TelegramTemplateRenderResult(rendered.takeIf { errors.isEmpty() }, errors)
    }
}
