package com.bydcollector.collector.data.local

object CsvCatalogParser {
    fun parse(content: String): List<CatalogSeedRow> {
        val rows = content.removePrefix("\uFEFF").lineSequence()
            .filter { it.isNotBlank() }
            .map { parseLine(it) }
            .toList()
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { it.trim() }
        val index = header.withIndex().associate { it.value to it.index }

        fun List<String>.field(name: String): String {
            val position = index[name] ?: return ""
            return getOrNull(position).orEmpty().trim()
        }

        return rows.drop(1).mapNotNull { row ->
            val key = row.field("key")
            val name = row.field("name")
            if (key.isBlank() || name.isBlank()) return@mapNotNull null
            CatalogSeedRow(
                sourceId = row.field("id").ifBlank { null },
                key = key,
                name = name,
                groupName = row.field("group").ifBlank { null },
                includeDesc = row.field("include_desc").lowercase() !in setOf("false", "0", "no", "n", "off"),
                note = row.field("note").ifBlank { null }
            )
        }
    }

    private fun parseLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    cells += current.toString()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            i++
        }
        cells += current.toString()
        return cells
    }
}
