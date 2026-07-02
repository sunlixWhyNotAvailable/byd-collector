package com.bydcollector.collector.ui.compose

internal sealed interface ReleaseNotesMarkdownBlock {
    data class Heading(val level: Int, val spans: List<ReleaseNotesMarkdownSpan>) : ReleaseNotesMarkdownBlock
    data class Bullet(val spans: List<ReleaseNotesMarkdownSpan>) : ReleaseNotesMarkdownBlock
    data class Paragraph(val spans: List<ReleaseNotesMarkdownSpan>) : ReleaseNotesMarkdownBlock
    data object Blank : ReleaseNotesMarkdownBlock
    data object Separator : ReleaseNotesMarkdownBlock
}

internal sealed interface ReleaseNotesMarkdownSpan {
    data class Text(val value: String) : ReleaseNotesMarkdownSpan
    data class Bold(val value: String) : ReleaseNotesMarkdownSpan
    data class Code(val value: String) : ReleaseNotesMarkdownSpan
}

internal object ReleaseNotesMarkdown {
    fun parse(text: String): List<ReleaseNotesMarkdownBlock> {
        return text.lines()
            .map { it.trimEnd() }
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .map { line ->
                val trimmed = line.trim()
                when {
                    line.isBlank() -> ReleaseNotesMarkdownBlock.Blank
                    trimmed == "---" -> ReleaseNotesMarkdownBlock.Separator
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                        ReleaseNotesMarkdownBlock.Bullet(parseInline(trimmed.drop(2)))
                    else -> headingBlock(trimmed) ?: ReleaseNotesMarkdownBlock.Paragraph(parseInline(trimmed))
                }
            }
    }

    private fun headingBlock(line: String): ReleaseNotesMarkdownBlock.Heading? {
        val markerCount = line.takeWhile { it == '#' }.length
        if (markerCount !in 1..3 || line.getOrNull(markerCount) != ' ') return null
        return ReleaseNotesMarkdownBlock.Heading(markerCount, parseInline(line.drop(markerCount + 1).trim()))
    }

    private fun parseInline(text: String): List<ReleaseNotesMarkdownSpan> {
        val spans = mutableListOf<ReleaseNotesMarkdownSpan>()
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("**", index) -> {
                    val end = text.indexOf("**", startIndex = index + 2)
                    if (end < 0) {
                        spans += ReleaseNotesMarkdownSpan.Text(text.substring(index))
                        break
                    }
                    spans += ReleaseNotesMarkdownSpan.Bold(text.substring(index + 2, end))
                    index = end + 2
                }
                text[index] == '`' -> {
                    val end = text.indexOf('`', startIndex = index + 1)
                    if (end < 0) {
                        spans += ReleaseNotesMarkdownSpan.Text(text.substring(index))
                        break
                    }
                    spans += ReleaseNotesMarkdownSpan.Code(text.substring(index + 1, end))
                    index = end + 1
                }
                else -> {
                    val nextBold = text.indexOf("**", startIndex = index).takeIf { it >= 0 } ?: text.length
                    val nextCode = text.indexOf('`', startIndex = index).takeIf { it >= 0 } ?: text.length
                    val next = minOf(nextBold, nextCode)
                    spans += ReleaseNotesMarkdownSpan.Text(text.substring(index, next))
                    index = next
                }
            }
        }
        return spans.filterNot { it is ReleaseNotesMarkdownSpan.Text && it.value.isEmpty() }
    }
}
