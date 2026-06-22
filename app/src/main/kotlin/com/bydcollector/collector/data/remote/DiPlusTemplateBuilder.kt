package com.bydcollector.collector.data.remote

import com.bydcollector.collector.data.local.CatalogParameter

data class DiPlusRequest(
    val template: String,
    val url: String,
    val parameterCount: Int,
    val requestCount: Int = 1
)

class DiPlusTemplateBuilder {
    fun build(parameters: List<CatalogParameter>): DiPlusRequest {
        val parts = mutableListOf<String>()
        parameters.forEach { parameter ->
            parts += "${parameter.key}:{${parameter.name}}"
            if (parameter.includeDesc) {
                parts += "${parameter.key}_desc:[${parameter.name}]"
            }
        }

        val template = parts.joinToString("|")
        return DiPlusRequest(
            template = template,
            url = "$DEFAULT_ENDPOINT?parameters=${parameters.size}",
            parameterCount = parameters.size
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT = "direct://autoservice/read"
    }
}
