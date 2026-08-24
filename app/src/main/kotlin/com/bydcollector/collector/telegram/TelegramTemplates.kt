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

enum class TelegramTemplateLanguage {
    UK,
    EN
}

data class TelegramBuiltInTemplateMatch(
    val language: TelegramTemplateLanguage,
    val historic: Boolean
)

object TelegramBuiltInTemplates {
    const val CHARGING_STARTED_UK = "Заряджання розпочато\nSOC: {soc}%\nПотужність: {battery_power_kw} кВт"
    const val CHARGING_STARTED_EN = "Charging started\nSOC: {soc}%\nPower: {battery_power_kw} kW"
    const val CHARGING_PROGRESS_UK =
        "Заряд: {soc}%\nЗа крок: +{charge_step_added_percent}% / +{charge_step_added_kwh} кВт·год\nЧас кроку: {charge_step_duration}\nЗа сесію: +{charge_added_percent}% / +{charge_added_kwh} кВт·год\nЧас сесії: {charge_duration}"
    const val CHARGING_PROGRESS_EN =
        "Charge: {soc}%\nThis step: +{charge_step_added_percent}% / +{charge_step_added_kwh} kWh\nStep time: {charge_step_duration}\nSession total: +{charge_added_percent}% / +{charge_added_kwh} kWh\nSession time: {charge_duration}"
    const val CHARGED_TO_100_UK = "Авто заряджено до 100%\nЕнергія: {remaining_energy_kwh} кВт·год\nЗапас ходу: {range_km} км"
    const val CHARGED_TO_100_EN = "Vehicle charged to 100%\nEnergy: {remaining_energy_kwh} kWh\nRange: {range_km} km"
    const val CHARGING_STOPPED_UK = "Заряджання зупинено\nSOC: {soc}%\nТривалість: {charge_duration}"
    const val CHARGING_STOPPED_EN = "Charging stopped\nSOC: {soc}%\nDuration: {charge_duration}"
    const val CHARGE_GUN_CONNECTED_UK = "Зарядний кабель підключено\nSOC: {soc}%\nЧас: {time}"
    const val CHARGE_GUN_CONNECTED_EN = "Charge gun connected\nSOC: {soc}%\nTime: {time}"
    const val CHARGE_GUN_DISCONNECTED_UK = "Зарядний кабель відключено\nSOC: {soc}%\nЧас: {time}"
    const val CHARGE_GUN_DISCONNECTED_EN = "Charge gun disconnected\nSOC: {soc}%\nTime: {time}"
    const val LOW_12V_VOLTAGE_UK = "Низька напруга 12V\nНапруга: {battery_12v} В\nЧас: {time}"
    const val LOW_12V_VOLTAGE_EN = "Low 12V voltage\nVoltage: {battery_12v} V\nTime: {time}"
    const val TELEMETRY_UNAVAILABLE_UK = "Телеметрія недоступна\nОстанні дані: {last_data_time}\nПомилка: {error}"
    const val TELEMETRY_UNAVAILABLE_EN = "Telemetry unavailable\nLast data: {last_data_time}\nError: {error}"
    const val TRIP_SUMMARY_UK =
        "Поїздку завершено\nПоточна поїздка: {trip_distance_km} км / {trip_duration}\nВитрата: {trip_energy_kwh} кВт·год, SOC: {soc_start}% -> {soc_end}%\nЗагалом: {total_distance_km} км / {total_duration}\nВитрата: {total_energy_kwh} кВт·год, SOC: {soc_start}% -> {soc_end}%"
    const val TRIP_SUMMARY_EN =
        "Trip complete\nCurrent trip: {trip_distance_km} km / {trip_duration}\nEnergy used: {trip_energy_kwh} kWh, SOC: {soc_start}% -> {soc_end}%\nTotal: {total_distance_km} km / {total_duration}\nEnergy used: {total_energy_kwh} kWh, SOC: {soc_start}% -> {soc_end}%"

    private val current = mapOf(
        TelegramEventType.CHARGING_STARTED to mapOf(TelegramTemplateLanguage.UK to CHARGING_STARTED_UK, TelegramTemplateLanguage.EN to CHARGING_STARTED_EN),
        TelegramEventType.CHARGING_PROGRESS to mapOf(TelegramTemplateLanguage.UK to CHARGING_PROGRESS_UK, TelegramTemplateLanguage.EN to CHARGING_PROGRESS_EN),
        TelegramEventType.CHARGED_TO_100 to mapOf(TelegramTemplateLanguage.UK to CHARGED_TO_100_UK, TelegramTemplateLanguage.EN to CHARGED_TO_100_EN),
        TelegramEventType.CHARGING_STOPPED to mapOf(TelegramTemplateLanguage.UK to CHARGING_STOPPED_UK, TelegramTemplateLanguage.EN to CHARGING_STOPPED_EN),
        TelegramEventType.CHARGE_GUN_CONNECTED to mapOf(TelegramTemplateLanguage.UK to CHARGE_GUN_CONNECTED_UK, TelegramTemplateLanguage.EN to CHARGE_GUN_CONNECTED_EN),
        TelegramEventType.CHARGE_GUN_DISCONNECTED to mapOf(TelegramTemplateLanguage.UK to CHARGE_GUN_DISCONNECTED_UK, TelegramTemplateLanguage.EN to CHARGE_GUN_DISCONNECTED_EN),
        TelegramEventType.LOW_12V_VOLTAGE to mapOf(TelegramTemplateLanguage.UK to LOW_12V_VOLTAGE_UK, TelegramTemplateLanguage.EN to LOW_12V_VOLTAGE_EN),
        TelegramEventType.TELEMETRY_UNAVAILABLE to mapOf(TelegramTemplateLanguage.UK to TELEMETRY_UNAVAILABLE_UK, TelegramTemplateLanguage.EN to TELEMETRY_UNAVAILABLE_EN),
        TelegramEventType.TRIP_SUMMARY to mapOf(TelegramTemplateLanguage.UK to TRIP_SUMMARY_UK, TelegramTemplateLanguage.EN to TRIP_SUMMARY_EN)
    )

    private val historic = mapOf(
        TelegramEventType.CHARGING_STARTED to mapOf(
            TelegramTemplateLanguage.EN to setOf("Charging started at {soc}% ({battery_power_kw} kW) at {time}.")
        ),
        TelegramEventType.CHARGING_PROGRESS to mapOf(
            TelegramTemplateLanguage.UK to setOf(
                "Заряд: {soc}%\nДодано: {charge_added_percent}% / {charge_added_kwh} кВт·год",
                "Заряд: {soc}%\nЗа крок: +{charge_step_added_percent}% / +{charge_step_added_kwh} кВт·год\nЗа сесію: +{charge_added_percent}% / +{charge_added_kwh} кВт·год"
            ),
            TelegramTemplateLanguage.EN to setOf(
                "Charge: {soc}%\nAdded: {charge_added_percent}% / {charge_added_kwh} kWh",
                "Charging progress: {soc}% (+{charge_added_percent}%, {charge_added_kwh} kWh), {battery_power_kw} kW.",
                "Charge: {soc}%\nThis step: +{charge_step_added_percent}% / +{charge_step_added_kwh} kWh\nSession total: +{charge_added_percent}% / +{charge_added_kwh} kWh"
            )
        ),
        TelegramEventType.TRIP_SUMMARY to mapOf(
            TelegramTemplateLanguage.UK to setOf(
                "Поїздку завершено\nВідстань: {trip_distance_km} км за {trip_duration}\nSOC: {soc_start}% -> {soc_end}%\nЕнергія: {trip_energy_kwh} кВт·год",
                "Поїздку завершено\nПоточна поїздка: {trip_distance_km} км / {trip_duration}\nВитрата: {trip_energy_kwh} кВт·год, SOC: {soc_start}% -> {soc_end}%\nЗагалом: {total_distance_km} км / {total_duration}\nВитрата: {total_energy_kwh} кВт·год"
            ),
            TelegramTemplateLanguage.EN to setOf(
                "Trip complete\nDistance: {trip_distance_km} km in {trip_duration}\nSOC: {soc_start}% -> {soc_end}%\nEnergy: {trip_energy_kwh} kWh",
                "Trip complete: {trip_distance_km} km, {trip_energy_kwh} kWh at {time}.",
                "Trip complete\nCurrent trip: {trip_distance_km} km / {trip_duration}\nEnergy used: {trip_energy_kwh} kWh, SOC: {soc_start}% -> {soc_end}%\nTotal: {total_distance_km} km / {total_duration}\nEnergy used: {total_energy_kwh} kWh"
            )
        ),
        TelegramEventType.CHARGED_TO_100 to mapOf(
            TelegramTemplateLanguage.EN to setOf(
                "Charging complete: {soc}%, {remaining_energy_kwh} kWh remaining, range {range_km} km at {time}."
            )
        ),
        TelegramEventType.CHARGING_STOPPED to mapOf(
            TelegramTemplateLanguage.EN to setOf(
                "Charging stopped at {soc}% after {charge_duration}; added {charge_added_percent}% / {charge_added_kwh} kWh at {time}."
            )
        ),
        TelegramEventType.CHARGE_GUN_CONNECTED to mapOf(
            TelegramTemplateLanguage.EN to setOf("Charge gun connected at {soc}% at {time}.")
        ),
        TelegramEventType.CHARGE_GUN_DISCONNECTED to mapOf(
            TelegramTemplateLanguage.EN to setOf("Charge gun disconnected at {soc}% at {time}.")
        ),
        TelegramEventType.LOW_12V_VOLTAGE to mapOf(
            TelegramTemplateLanguage.EN to setOf("Low 12 V battery voltage: {battery_12v} V at {time}.")
        ),
        TelegramEventType.TELEMETRY_UNAVAILABLE to mapOf(
            TelegramTemplateLanguage.EN to setOf("Telemetry unavailable since {last_data_time}: {error} ({time}).")
        )
    )

    fun defaultTemplate(type: TelegramEventType, language: TelegramTemplateLanguage): String =
        current.getValue(type).getValue(language)

    fun tripSummaryTemplate(language: TelegramTemplateLanguage, includeOverall: Boolean): String {
        if (includeOverall) return defaultTemplate(TelegramEventType.TRIP_SUMMARY, language)
        return when (language) {
            TelegramTemplateLanguage.UK ->
                "Поїздку завершено\nПоточна поїздка: {trip_distance_km} км / {trip_duration}\nВитрата: {trip_energy_kwh} кВт·год, SOC: {soc_start}% -> {soc_end}%"
            TelegramTemplateLanguage.EN ->
                "Trip complete\nCurrent trip: {trip_distance_km} km / {trip_duration}\nEnergy used: {trip_energy_kwh} kWh, SOC: {soc_start}% -> {soc_end}%"
        }
    }

    fun classify(type: TelegramEventType, template: String): TelegramBuiltInTemplateMatch? {
        val normalized = normalizeForBuiltInMatch(template)
        current[type]?.entries?.firstOrNull { normalizeForBuiltInMatch(it.value) == normalized }?.let {
            return TelegramBuiltInTemplateMatch(it.key, historic = false)
        }
        historic[type]?.entries?.firstNotNullOfOrNull { (language, values) ->
            values.firstOrNull { normalizeForBuiltInMatch(it) == normalized }?.let {
                TelegramBuiltInTemplateMatch(language, historic = true)
            }
        }?.let { return it }
        return null
    }

    fun isKnownBuiltIn(eventKey: String, template: String): Boolean =
        TelegramEventType.fromKey(eventKey)?.let { classify(it, template) != null } == true

    fun isKnownBuiltIn(event: TelegramEventType, template: String): Boolean =
        classify(event, template) != null

    fun isCurrentBuiltIn(eventKey: String, template: String): Boolean =
        TelegramEventType.fromKey(eventKey)?.let { classify(it, template)?.historic == false } == true

    fun isCurrentBuiltIn(event: TelegramEventType, template: String): Boolean =
        classify(event, template)?.historic == false

    fun isHistoricBuiltIn(eventKey: String, template: String): Boolean =
        TelegramEventType.fromKey(eventKey)?.let { classify(it, template)?.historic == true } == true

    fun isHistoricBuiltIn(event: TelegramEventType, template: String): Boolean =
        classify(event, template)?.historic == true

    fun classifyKnownSaved(eventKey: String, template: String): TelegramBuiltInTemplateMatch? =
        TelegramEventType.fromKey(eventKey)?.let { classify(it, template) }

    fun classifySaved(event: TelegramEventType, template: String): TelegramBuiltInTemplateMatch? =
        classify(event, template)

    fun migrateKnownSaved(eventKey: String, template: String): String {
        val type = TelegramEventType.fromKey(eventKey) ?: return template
        val match = classify(type, template) ?: return template
        return if (match.historic) defaultTemplate(type, match.language) else template
    }

    private fun normalizeForBuiltInMatch(template: String): String =
        template.replace("\r\n", "\n").split('\n').joinToString("\n") { it.trimEnd() }.trimEnd()

}

object TelegramTemplateCatalog {
    private val specs = mapOf(
        TelegramEventType.CHARGING_STARTED to TelegramTemplateSpec(
            TelegramBuiltInTemplates.CHARGING_STARTED_EN,
            setOf("soc", "battery_power_kw", "time")
        ),
        TelegramEventType.CHARGING_PROGRESS to TelegramTemplateSpec(
            TelegramBuiltInTemplates.CHARGING_PROGRESS_EN,
            setOf(
                "soc",
                "charge_step_added_percent",
                "charge_step_added_kwh",
                "charge_step_duration",
                "charge_added_percent",
                "charge_added_kwh",
                "charge_duration",
                "battery_power_kw"
            )
        ),
        TelegramEventType.CHARGED_TO_100 to TelegramTemplateSpec(
            TelegramBuiltInTemplates.CHARGED_TO_100_EN,
            setOf("soc", "remaining_energy_kwh", "range_km", "time")
        ),
        TelegramEventType.CHARGING_STOPPED to TelegramTemplateSpec(
            TelegramBuiltInTemplates.CHARGING_STOPPED_EN,
            setOf("soc", "charge_duration", "charge_added_percent", "charge_added_kwh", "time")
        ),
        TelegramEventType.CHARGE_GUN_CONNECTED to TelegramTemplateSpec(
            TelegramBuiltInTemplates.CHARGE_GUN_CONNECTED_EN,
            setOf("soc", "time")
        ),
        TelegramEventType.CHARGE_GUN_DISCONNECTED to TelegramTemplateSpec(
            TelegramBuiltInTemplates.CHARGE_GUN_DISCONNECTED_EN,
            setOf("soc", "time")
        ),
        TelegramEventType.LOW_12V_VOLTAGE to TelegramTemplateSpec(
            TelegramBuiltInTemplates.LOW_12V_VOLTAGE_EN,
            setOf("battery_12v", "time")
        ),
        TelegramEventType.TELEMETRY_UNAVAILABLE to TelegramTemplateSpec(
            TelegramBuiltInTemplates.TELEMETRY_UNAVAILABLE_EN,
            setOf("last_data_time", "error", "time")
        ),
        TelegramEventType.TRIP_SUMMARY to TelegramTemplateSpec(
            TelegramBuiltInTemplates.TRIP_SUMMARY_EN,
            setOf(
                "trip_distance_km",
                "trip_energy_kwh",
                "trip_duration",
                "soc_start",
                "soc_end",
                "total_distance_km",
                "total_energy_kwh",
                "total_duration",
                "time"
            )
        )
    )

    val events: Set<TelegramEventType> = specs.keys

    fun spec(event: TelegramEventType): TelegramTemplateSpec = specs.getValue(event)

    fun defaultTemplate(event: TelegramEventType, language: TelegramTemplateLanguage): String =
        TelegramBuiltInTemplates.defaultTemplate(event, language)

    fun templateForRendering(
        event: TelegramEventType,
        template: String,
        language: TelegramTemplateLanguage,
        omitOverall: Boolean
    ): String {
        if (event != TelegramEventType.TRIP_SUMMARY || !omitOverall) return template
        val match = TelegramBuiltInTemplates.classify(event, template) ?: return template
        return TelegramBuiltInTemplates.tripSummaryTemplate(match.language, includeOverall = false)
    }

    fun defaultTemplate(event: TelegramEventType): String = spec(event).defaultTemplate

    fun isKnownBuiltIn(eventKey: String, template: String): Boolean =
        TelegramBuiltInTemplates.isKnownBuiltIn(eventKey, template)

    fun isKnownBuiltIn(event: TelegramEventType, template: String): Boolean =
        TelegramBuiltInTemplates.isKnownBuiltIn(event, template)
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
