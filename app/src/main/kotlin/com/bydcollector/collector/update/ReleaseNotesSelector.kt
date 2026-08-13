package com.bydcollector.collector.update

object ReleaseNotesSelector {
    private const val EN_OPEN = "<!-- bydcollector:release-notes:en -->"
    private const val EN_CLOSE = "<!-- /bydcollector:release-notes:en -->"
    private const val UK_OPEN = "<!-- bydcollector:release-notes:uk -->"
    private const val UK_CLOSE = "<!-- /bydcollector:release-notes:uk -->"
    private val MARKERS = setOf(EN_OPEN, EN_CLOSE, UK_OPEN, UK_CLOSE)

    fun select(rawBody: String, ukrainian: Boolean): String {
        val requested = if (ukrainian) {
            block(rawBody, UK_OPEN, UK_CLOSE)
        } else {
            block(rawBody, EN_OPEN, EN_CLOSE)
        }
        return requested ?: block(rawBody, EN_OPEN, EN_CLOSE) ?: rawBody
    }

    private fun block(body: String, open: String, close: String): String? {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').lines()
        val opens = lines.indices.filter { lines[it] == open }
        val closes = lines.indices.filter { lines[it] == close }
        if (opens.size != 1 || closes.size != 1 || closes[0] <= opens[0]) return null
        val content = lines.subList(opens[0] + 1, closes[0])
        if (content.any { it in MARKERS }) return null
        return content.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }
}
