package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesSelectorTest {
    @Test
    fun selectsEnglishBlockAndExcludesSharedText() {
        assertEquals(
            "# English\n\n- Fixed the update flow.",
            ReleaseNotesSelector.select(bodyWithBothBlocks(), ukrainian = false)
        )
    }

    @Test
    fun selectsUkrainianBlockAndExcludesSharedFooter() {
        assertEquals(
            "# Українська\n\n- Виправлено оновлення.",
            ReleaseNotesSelector.select(bodyWithBothBlocks(), ukrainian = true)
        )
    }

    @Test
    fun ukrainianFallsBackToEnglishWhenUkrainianBlockIsMissing() {
        val body = """
            ## v2.6.2
            <!-- bydcollector:release-notes:en -->
            English notes
            <!-- /bydcollector:release-notes:en -->
            SHA-256: abc
        """.trimIndent()

        assertEquals("English notes", ReleaseNotesSelector.select(body, ukrainian = true))
    }

    @Test
    fun ukrainianFallsBackToEnglishWhenUkrainianBlockIsEmpty() {
        val body = """
            <!-- bydcollector:release-notes:uk -->

            <!-- /bydcollector:release-notes:uk -->
            <!-- bydcollector:release-notes:en -->
            English notes
            <!-- /bydcollector:release-notes:en -->
        """.trimIndent()

        assertEquals("English notes", ReleaseNotesSelector.select(body, ukrainian = true))
    }

    @Test
    fun ukrainianFallsBackToEnglishWhenUkrainianBlockIsMalformed() {
        val body = """
            <!-- bydcollector:release-notes:uk -->
            Ukrainian notes
            <!-- bydcollector:release-notes:en -->
            English notes
            <!-- /bydcollector:release-notes:en -->
            <!-- /bydcollector:release-notes:uk -->
        """.trimIndent()

        assertEquals("English notes", ReleaseNotesSelector.select(body, ukrainian = true))
    }

    @Test
    fun duplicateMarkersFallBackToLegacyBody() {
        val body = """
            <!-- bydcollector:release-notes:en -->
            First
            <!-- /bydcollector:release-notes:en -->
            <!-- bydcollector:release-notes:en -->
            Second
            <!-- /bydcollector:release-notes:en -->
        """.trimIndent()

        assertEquals(body, ReleaseNotesSelector.select(body, ukrainian = false))
    }

    @Test
    fun crossedMarkersFallBackToLegacyBody() {
        val body = """
            <!-- bydcollector:release-notes:en -->
            English notes
            <!-- bydcollector:release-notes:uk -->
            Ukrainian notes
            <!-- /bydcollector:release-notes:en -->
            <!-- /bydcollector:release-notes:uk -->
        """.trimIndent()

        assertEquals(body, ReleaseNotesSelector.select(body, ukrainian = false))
        assertEquals(body, ReleaseNotesSelector.select(body, ukrainian = true))
    }

    @Test
    fun markersMustOccupyAnExactLine() {
        val body = "prefix <!-- bydcollector:release-notes:en -->\nnotes\n<!-- /bydcollector:release-notes:en --> suffix"

        assertEquals(body, ReleaseNotesSelector.select(body, ukrainian = false))
    }

    @Test
    fun englishRequestWithOnlyUkrainianBlockReturnsLegacyBody() {
        val body = """
            ## v2.6.2
            <!-- bydcollector:release-notes:uk -->
            Українські нотатки
            <!-- /bydcollector:release-notes:uk -->
            SHA-256: abc
        """.trimIndent()

        assertEquals(body, ReleaseNotesSelector.select(body, ukrainian = false))
    }

    @Test
    fun legacyUnmarkedBodyIsUnchanged() {
        val body = "## v2.6.2\n\nLegacy notes\nSHA-256: abc"

        assertEquals(body, ReleaseNotesSelector.select(body, ukrainian = false))
        assertEquals(body, ReleaseNotesSelector.select(body, ukrainian = true))
    }

    @Test
    fun normalizesCrLfAndCrBeforeSelecting() {
        val body = "<!-- bydcollector:release-notes:en -->\rEnglish\r\nnotes\r<!-- /bydcollector:release-notes:en -->"

        assertEquals("English\nnotes", ReleaseNotesSelector.select(body, ukrainian = false))
    }

    @Test
    fun preservesOriginalLineEndingsForLegacyAndMalformedFallbacks() {
        val legacy = "## v2.6.2\r\n\r\nLegacy notes\r\nSHA-256: abc"
        val malformed = "## v2.6.2\r\n<!-- bydcollector:release-notes:en -->\r\nIncomplete"

        assertEquals(legacy, ReleaseNotesSelector.select(legacy, ukrainian = false))
        assertEquals(malformed, ReleaseNotesSelector.select(malformed, ukrainian = false))
    }

    private fun bodyWithBothBlocks(): String = """
        ## v2.6.2
        SHA-256: abc

        <!-- bydcollector:release-notes:en -->
        # English

        - Fixed the update flow.
        <!-- /bydcollector:release-notes:en -->

        <!-- bydcollector:release-notes:uk -->
        # Українська

        - Виправлено оновлення.
        <!-- /bydcollector:release-notes:uk -->

        SHA-256: footer
    """.trimIndent()
}
