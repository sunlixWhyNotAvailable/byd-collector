package com.bydcollector.collector.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesMarkdownTest {
    @Test
    fun parsesSupportedReleaseNotesSubset() {
        val blocks = ReleaseNotesMarkdown.parse(
            """
            # v1.3.3

            ## Fixed:
            - **mqtt** reconnect uses `retry`;
            * plain item
            ---
            ### Changed
            regular paragraph
            """.trimIndent()
        )

        assertEquals(
            listOf(
                ReleaseNotesMarkdownBlock.Heading(1, listOf(ReleaseNotesMarkdownSpan.Text("v1.3.3"))),
                ReleaseNotesMarkdownBlock.Blank,
                ReleaseNotesMarkdownBlock.Heading(2, listOf(ReleaseNotesMarkdownSpan.Text("Fixed:"))),
                ReleaseNotesMarkdownBlock.Bullet(
                    listOf(
                        ReleaseNotesMarkdownSpan.Bold("mqtt"),
                        ReleaseNotesMarkdownSpan.Text(" reconnect uses "),
                        ReleaseNotesMarkdownSpan.Code("retry"),
                        ReleaseNotesMarkdownSpan.Text(";")
                    )
                ),
                ReleaseNotesMarkdownBlock.Bullet(listOf(ReleaseNotesMarkdownSpan.Text("plain item"))),
                ReleaseNotesMarkdownBlock.Separator,
                ReleaseNotesMarkdownBlock.Heading(3, listOf(ReleaseNotesMarkdownSpan.Text("Changed"))),
                ReleaseNotesMarkdownBlock.Paragraph(listOf(ReleaseNotesMarkdownSpan.Text("regular paragraph")))
            ),
            blocks
        )
    }

    @Test
    fun keepsUnsupportedMarkdownReadableAsPlainText() {
        val blocks = ReleaseNotesMarkdown.parse(
            """
            #### too deep
            1.text
            12.item
            **unclosed
            `also unclosed
            """.trimIndent()
        )

        assertEquals(
            listOf(
                ReleaseNotesMarkdownBlock.Paragraph(listOf(ReleaseNotesMarkdownSpan.Text("#### too deep"))),
                ReleaseNotesMarkdownBlock.Paragraph(listOf(ReleaseNotesMarkdownSpan.Text("1.text"))),
                ReleaseNotesMarkdownBlock.Paragraph(listOf(ReleaseNotesMarkdownSpan.Text("12.item"))),
                ReleaseNotesMarkdownBlock.Paragraph(listOf(ReleaseNotesMarkdownSpan.Text("**unclosed"))),
                ReleaseNotesMarkdownBlock.Paragraph(listOf(ReleaseNotesMarkdownSpan.Text("`also unclosed")))
            ),
            blocks
        )
    }

    @Test
    fun parsesOrderedItemsWithOriginalNumbersAndInlineSpans() {
        val blocks = ReleaseNotesMarkdown.parse(
            """
            1. **bold** uses `retry`
            12. plain item
            """.trimIndent()
        )

        assertEquals(
            listOf(
                ReleaseNotesMarkdownBlock.Ordered(
                    "1",
                    listOf(
                        ReleaseNotesMarkdownSpan.Bold("bold"),
                        ReleaseNotesMarkdownSpan.Text(" uses "),
                        ReleaseNotesMarkdownSpan.Code("retry")
                    )
                ),
                ReleaseNotesMarkdownBlock.Ordered(
                    "12",
                    listOf(ReleaseNotesMarkdownSpan.Text("plain item"))
                )
            ),
            blocks
        )
    }
}
