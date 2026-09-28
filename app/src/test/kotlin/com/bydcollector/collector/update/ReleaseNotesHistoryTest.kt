package com.bydcollector.collector.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.*

class ReleaseNotesHistoryTest {
    private val target = UpdateInfo("v3.1.0", "https://github.com/sunlixWhyNotAvailable/byd-collector/releases/download/v3.1.0/app.apk", "target notes")

    @Test fun paginatesAllPublishedVersionsWithoutUsingListOrderOrRequiringAssets() {
        val calls = mutableListOf<Int>()
        val result = loadReleaseNotesHistory("2.7.9", target) { page ->
            calls += page
            when (page) {
                1 -> page(true, "v3.2.0", "v2.7.9", "v2.7.8", "v2.7.10")
                2 -> page(true, "v3.0.0", "v3.1.0", "2.7.10")
                else -> ReleaseNotesPage(JSONArray().put(release("v2.8.0")).put(release("v2.9.0").put("draft", true)).toString(), false)
            }
        }
        assertEquals(listOf(1, 2, 3), calls)
        assertEquals(listOf("v3.1.0", "v3.0.0", "v2.8.0", "v2.7.10"), result.entries.map { it.version })
        assertEquals("target notes", result.entries.first().body)
        assertFalse(result.incomplete)
    }

    @Test fun oneReleaseEmptyBodyAndLongNotesRemainExplicitAndUntruncated() {
        val longNotes = "1. **Change** with `code`\n".repeat(5000)
        val info = target.copy(releaseNotes = longNotes)
        val result = loadReleaseNotesHistory("3.0.0", info) { page(false, "v3.1.0", "v3.0.0") }
        assertEquals(listOf(ReleaseNotesEntry(info.version, longNotes)), result.entries)
        assertFalse(result.incomplete)
        assertEquals("", loadReleaseNotesHistory("3.0.0", target.copy(releaseNotes = "")) { page(false) }.entries.single().body)
    }

    @Test fun failedLaterPageRetainsTargetAndDownloadedNotesButReportsPartialHistory() {
        val result = loadReleaseNotesHistory("2.7.9", target) { number ->
            if (number == 1) page(true, "v3.0.0") else error("offline")
        }
        assertEquals(listOf("v3.1.0", "v3.0.0"), result.entries.map { it.version })
        assertTrue(result.incomplete)
    }

    @Test fun supersededWorkStopsBeforeAnotherPage() {
        var current = true
        var calls = 0
        val result = loadReleaseNotesHistory("2.7.9", target, isCurrent = { current }) {
            calls++
            current = false
            page(true)
        }
        assertEquals(1, calls)
        assertTrue(result.incomplete)
    }

    @Test fun eachReleaseSelectsItsOwnLanguageAndLegacyHeaderIsNotDuplicated() {
        val bilingual = """
            ## v3.0.0
            <!-- bydcollector:release-notes:en -->
            English
            <!-- /bydcollector:release-notes:en -->
            <!-- bydcollector:release-notes:uk -->
            Українська
            <!-- /bydcollector:release-notes:uk -->
        """.trimIndent()
        assertEquals("Українська", ReleaseNotesSelector.forVersion(ReleaseNotesEntry("v3.0.0", bilingual), true))
        assertEquals("English", ReleaseNotesSelector.forVersion(ReleaseNotesEntry("v3.0.0", bilingual), false))
        assertEquals("legacy", ReleaseNotesSelector.forVersion(ReleaseNotesEntry("v2.8.0", "## v2.8.0\nlegacy"), true))
        assertEquals("## Features\nlegacy", ReleaseNotesSelector.forVersion(ReleaseNotesEntry("v2.8.0", "## Features\nlegacy"), false))
    }

    @Test fun processSessionRetainsLoadingAndResultAcrossUiDetachAndDoesNotRepeatRequests() {
        val tasks = mutableListOf<() -> Unit>()
        val session = ReleaseNotesHistorySession(tasks::add) { info, _ -> ReleaseNotesHistory(listOf(ReleaseNotesEntry(info.version, "loaded"))) }
        var notifications = 0
        val listener = { notifications++; Unit }
        session.addListener(listener)
        session.request(target)
        assertTrue(session.snapshot(target)!!.loading)
        session.removeListener(listener)
        session.request(target) // Activity recreated or download begun with the same offer.
        assertEquals(1, tasks.size)
        tasks.removeAt(0)()
        session.addListener(listener)
        assertEquals("loaded", session.snapshot(target)!!.entries.single().body)
        session.request(target)
        assertTrue(tasks.isEmpty())
        assertEquals(1, notifications)
    }

    @Test fun freshManualCheckRetriesAndOldCompletionCannotReplaceNewTarget() {
        val tasks = mutableListOf<() -> Unit>()
        val session = ReleaseNotesHistorySession(tasks::add) { info, _ -> ReleaseNotesHistory(listOf(ReleaseNotesEntry(info.version, "loaded"))) }
        session.request(target)
        session.reset()
        val newer = target.copy(version = "v3.2.0")
        session.request(newer)
        tasks.removeAt(0)()
        assertNull(session.snapshot(target))
        assertTrue(session.snapshot(newer)!!.loading)
        tasks.removeAt(0)()
        assertFalse(session.snapshot(newer)!!.loading)
    }

    @Test fun failedLoadOrExecutorLeavesAnExplicitRetryablePartialState() {
        val session = ReleaseNotesHistorySession({ it() }) { _, _ -> error("offline") }
        session.request(target)
        assertTrue(session.snapshot(target)!!.incomplete)
        session.reset()
        session.request(target)
        assertFalse(session.snapshot(target)!!.loading)
        val rejected = ReleaseNotesHistorySession({ error("executor stopped") }) { _, _ -> error("must not run") }
        rejected.request(target)
        assertTrue(rejected.snapshot(target)!!.incomplete)
    }

    @Test fun manualJoinRetriesHistoryEvenWhenCheckSettlesBeforeCallerReturns() {
        val tasks = ArrayDeque<() -> Unit>()
        val checks = UpdateCheckSession(tasks::addLast, { UpdateCheckResult.Available(target) })
        var attempts = 0
        val history = ReleaseNotesHistorySession({ it() }) { info, _ ->
            ReleaseNotesHistory(listOf(ReleaseNotesEntry(info.version, "loaded")), incomplete = ++attempts == 1)
        }
        history.request(target)
        assertTrue(history.snapshot(target)!!.incomplete)
        assertTrue(checks.request(manual = false))
        history.reset() // Admitted manual intent invalidates before starting/joining HTTP.
        val accepted = checks.request(manual = true)
        assertFalse(accepted) // Joins the physical request instead of creating a second one.
        assertTrue(checks.hasCurrentManualRequest())
        tasks.removeFirst()() // The background worker may finish before the caller resumes.
        assertFalse(checks.hasCurrentManualRequest())
        history.request(assertIs<UpdateUiState.Available>(checks.snapshot().uiState).info)
        assertEquals(2, attempts)
        assertFalse(history.snapshot(target)!!.incomplete)

        // Bind the host session check to the runtime's Android-only admission/dispatch seam.
        val runtime = listOf("src/main", "app/src/main").map {
            File("$it/kotlin/com/bydcollector/collector/update/UpdateRuntime.kt")
        }.first { it.isFile }.readText().substringAfter("fun request(manual: Boolean)")
            .substringBefore("fun dismissOffer()")
        val reset = runtime.indexOf("if (manual) app.releaseNotesHistory.reset()")
        assertTrue(reset > runtime.indexOf("if (!started || installing"))
        assertTrue(reset >= 0 && reset < runtime.indexOf("val accepted = app.updateChecks.request(manual)"))
    }

    private fun release(version: String) = JSONObject().put("tag_name", version).put("body", "notes $version")
    private fun page(next: Boolean, vararg versions: String) = ReleaseNotesPage(JSONArray().also { array -> versions.forEach { array.put(release(it)) } }.toString(), next)
}
