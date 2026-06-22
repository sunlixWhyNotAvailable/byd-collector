package com.bydcollector.collector.data.local

internal object PollValueColumns {
    private val SAFE_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun raw(parameterKey: String): String = "${safeParameterKey(parameterKey)}_raw"

    fun desc(parameterKey: String): String = "${safeParameterKey(parameterKey)}_desc"

    fun forParameter(parameter: CatalogParameter): List<String> {
        val columns = mutableListOf(raw(parameter.key))
        if (parameter.includeDesc) columns += desc(parameter.key)
        return columns
    }

    fun isSafeIdentifier(value: String): Boolean = SAFE_IDENTIFIER.matches(value)

    private fun safeParameterKey(parameterKey: String): String {
        val mapped = buildString {
            parameterKey.forEach { char ->
                when {
                    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' -> append(char)
                    char == '-' -> {
                        if (isNotEmpty() && last() == '_') {
                            append("neg_")
                        } else {
                            append("_neg_")
                        }
                    }
                    else -> append("_u").append(char.code.toString(16)).append("_")
                }
            }
        }
        return when {
            mapped.isBlank() -> "_empty"
            mapped.first() in '0'..'9' -> "p_$mapped"
            else -> mapped
        }
    }
}
