package com.bydcollector.collector.update

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.MainActivity
import com.bydcollector.collector.runtime.RuntimeJobsService
import com.bydcollector.collector.runtime.RuntimeReads
import com.bydcollector.collector.runtime.RuntimeUpdateSnapshot
import com.bydcollector.collector.disposeTestComposition
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.StartupAdbPeer
import com.bydcollector.collector.service.CollectorSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = BydCollectorApplication::class,
    qualifiers = "w1280dp-h480dp-mdpi", shadows = [StartupAdbPeer::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateRuntimeBehaviorTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var app: BydCollectorApplication
    private lateinit var settings: CollectorSettings
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var screen: ActivityController<MainActivity>
    private var runtimeJobsController: ServiceController<RuntimeJobsService>? = null
    private lateinit var checksDelegate: java.lang.reflect.Field
    private lateinit var historyDelegate: java.lang.reflect.Field
    private var previousChecksDelegate: Any? = null
    private var previousHistoryDelegate: Any? = null
    private var previousUpdateAuto: Boolean? = null
    private var previousUpdateHint: Boolean? = null
    private var previousUiLanguage: String? = null
    private var screenStopped = false
    private var screenDestroyed = false
    private var syntheticRuntimeJobId: String? = null

    @Before fun prepare() {
        org.robolectric.shadows.ShadowProcess.setPid(12001)
        // Drive Compose frames explicitly; the live dashboard keeps scheduling background refreshes.
        ui.mainClock.autoAdvance = false
        app = RuntimeEnvironment.getApplication() as BydCollectorApplication
        settings = CollectorSettings(app)
        preferences = app.getSharedPreferences(CollectorSettings.PREFS_NAME, 0)
        previousUpdateAuto = preferences.all[CollectorSettings.KEY_UPDATE_AUTO_CHECK] as? Boolean
        previousUpdateHint = preferences.all[CollectorSettings.KEY_UPDATE_HINT_ENABLED] as? Boolean
        previousUiLanguage = preferences.all[CollectorSettings.KEY_UI_LANGUAGE_CODE] as? String
        checksDelegate = BydCollectorApplication::class.java.getDeclaredField("updateChecks\$delegate")
            .apply { isAccessible = true }
        historyDelegate = BydCollectorApplication::class.java.getDeclaredField("releaseNotesHistory\$delegate")
            .apply { isAccessible = true }
        previousChecksDelegate = checksDelegate.get(app)
        previousHistoryDelegate = historyDelegate.get(app)
        assertFalse((previousChecksDelegate as Lazy<*>).isInitialized(), "Use a fresh Application update session")
        assertFalse((previousHistoryDelegate as Lazy<*>).isInitialized(), "Use a fresh Application release-history session")

        settings.setUiLanguageCode("en")
        settings.setUpdateAutoCheckEnabled(true)
        settings.setUpdateHintEnabled(false)
        app.getSharedPreferences("startup_setup", 0).edit()
            .putInt("background_settings_version", BuildConfig.VERSION_CODE)
            .putBoolean("location_permission_setup_consumed", true)
            .putBoolean("overlay_permission_setup_consumed", true).commit()
        StartupAdbPeer.reset()
        StartupAdbPeer.consent = true
        StartupAdbPeer.trusted = true
        AdbLocalClient(File(app.filesDir, "adb_keys")).keyFingerprint()
    }

    @After fun cleanup() {
        if (::app.isInitialized) {
            runtimeJobsController?.let { runCatching { it.destroy() } }
            runtimeJobsController = null
            syntheticRuntimeJobId?.let { id ->
                runCatching {
                    val jobs = JSONArray(RuntimeJobsService.snapshot(app))
                    val index = (0 until jobs.length()).firstOrNull { jobs.getJSONObject(it).optString("id") == id }
                    if (index != null) {
                        jobs.getJSONObject(index).put("status", "complete")
                        replaceCachedJobs(jobs.toString())
                        RuntimeJobsService.presented(app, id)
                    }
                }
            }
            syntheticRuntimeJobId = null
            if (::screen.isInitialized && !screenDestroyed) {
                screen.get().disposeTestComposition()
                if (!screenStopped) screen.pause().stop()
                screen.destroy()
            }
            app.updateRuntime.shutdown()
            RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
            if (::checksDelegate.isInitialized) previousChecksDelegate?.let { checksDelegate.set(app, it) }
            if (::historyDelegate.isInitialized) previousHistoryDelegate?.let { historyDelegate.set(app, it) }
            if (::preferences.isInitialized) {
                val restore = preferences.edit()
                if (previousUpdateAuto == null) restore.remove(CollectorSettings.KEY_UPDATE_AUTO_CHECK)
                else restore.putBoolean(CollectorSettings.KEY_UPDATE_AUTO_CHECK, previousUpdateAuto!!)
                if (previousUpdateHint == null) restore.remove(CollectorSettings.KEY_UPDATE_HINT_ENABLED)
                else restore.putBoolean(CollectorSettings.KEY_UPDATE_HINT_ENABLED, previousUpdateHint!!)
                if (previousUiLanguage == null) restore.remove(CollectorSettings.KEY_UI_LANGUAGE_CODE)
                else restore.putString(CollectorSettings.KEY_UI_LANGUAGE_CODE, previousUiLanguage)
                restore.commit()
            }
            AdbAuthorizationManager.cancelObservation()
            app.navigationSession.clear()
        }
    }

    @Test fun offlineColdTimerRetriesThenActivityRecreationKeepsOfferAndReleaseHistory() {
        val pendingChecks = ArrayDeque<() -> Unit>()
        val pendingHistory = ArrayDeque<() -> Unit>()
        var response: UpdateCheckResult = UpdateCheckResult.Error("offline")
        var checkCount = 0
        val latest = UpdateInfo("3.2.3", "https://example.test/update.apk", "Latest patch notes")
        val history = ReleaseNotesHistory(
            entries = listOf(
                ReleaseNotesEntry("3.2.3", "Latest release improvements."),
                ReleaseNotesEntry("3.2.2", "Intermediate security and UI fixes.")
            )
        )
        val session = UpdateCheckSession(
            dispatch = { task -> pendingChecks.addLast(task) },
            checker = { checkCount++; response },
            elapsedRealtimeMs = { android.os.SystemClock.elapsedRealtime() }
        )
        val notesSession = ReleaseNotesHistorySession(
            dispatch = { task -> pendingHistory.addLast(task) },
            loader = { _, _ -> history }
        )
        checksDelegate.set(app, lazyOf(session))
        historyDelegate.set(app, lazyOf(notesSession))
        val runtime = app.updateRuntime

        try {
            // Start without an Activity and let UpdateRuntime's real Handler timer elapse.
            runtime.start("cold_service")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(1, pendingChecks.size)
            assertEquals(0, checkCount)
            pendingChecks.removeFirst().invoke()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(UpdateUiState.Hidden, session.snapshot().uiState)
            assertEquals(1, checkCount)

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(29_999))
            assertTrue(pendingChecks.isEmpty(), "Offline result must honor the runtime retry delay")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(1, pendingChecks.size)
            response = UpdateCheckResult.Available(latest)
            pendingChecks.removeFirst().invoke()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, checkCount)
            assertEquals(UpdateUiState.Available(latest), session.snapshot().uiState)
            val retainedResultId = assertNotNull(session.snapshot().availableResultId)

            screen = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
            settle()
            assertEquals(1, pendingHistory.size, "Activity should request the cached offer's release history")
            pendingHistory.removeFirst().invoke()
            shadowOf(Looper.getMainLooper()).idle()
            settle()
            assertEquals(2, checkCount, "Opening the Activity must not repeat the completed check")
            assertEquals(retainedResultId, session.snapshot().availableResultId)
            ui.onNodeWithText("Available version: 3.2.3").assertIsDisplayed()
            ui.onNodeWithText("v3.2.2").assertIsDisplayed()
            ui.onNodeWithText("Intermediate security and UI fixes.").assertIsDisplayed()

            screen.pause().stop()
            screen.start().resume().visible()
            screenStopped = false
            settle()
            screen.get().disposeTestComposition()
            screen.recreate().visible()
            settle()
            assertEquals(2, checkCount, "Recreated Activity must reuse the process-owned result")
            assertEquals(retainedResultId, session.snapshot().availableResultId)
            assertTrue(pendingHistory.isEmpty(), "Cached release history must survive Activity recreation")
            ui.onNodeWithText("Available version: 3.2.3").assertIsDisplayed()
            ui.onNodeWithText("Intermediate security and UI fixes.").assertIsDisplayed()

            ui.onNodeWithText("Close").performClick()
            settle()
            assertEquals(UpdateUiState.Hidden, session.snapshot().uiState)
            screen.pause().stop()
            screenStopped = true
            screen.get().disposeTestComposition()
            screen.destroy()
            screenDestroyed = true
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(30))
            assertEquals(2, checkCount, "Close suppression must hold for the current runtime session")
            assertTrue(pendingChecks.isEmpty())

            runtime.shutdown()
            response = UpdateCheckResult.UpToDate
            runtime.start("new_allowed_session")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(1, pendingChecks.size, "A new runtime session must be eligible for a fresh check")
            pendingChecks.removeFirst().invoke()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3, checkCount)
            assertTrue(session.snapshot().uiState is UpdateUiState.Hidden)
        } finally {
            runtime.shutdown()
        }
    }

    @Test fun closedDownloadStaysHiddenAcrossProgressAndRecreationWithoutCancellingJob() {
        settings.setUpdateAutoCheckEnabled(false)
        val session = UpdateCheckSession(dispatch = { it() }, checker = { error("No check expected") })
        checksDelegate.set(app, lazyOf(session))
        screen = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val id = RuntimeJobsService.submit(app, "updateDownload", JSONObject()
            .put("version", "3.2.3").put("url", "https://example.test/update.apk").put("notes", "Release notes"))
        val staleProgress = JSONArray(RuntimeJobsService.snapshot(app))
            .apply { getJSONObject(0).put("progress", 70) }.toString()
        consumeJobs(RuntimeJobsService.snapshot(app))
        ui.onNodeWithText("Close").assertIsDisplayed().performClick()
        settle()
        consumeJobs(staleProgress)
        ui.onNodeWithText("Close").assertDoesNotExist()

        // Reload the real persisted job, not a preconfigured dismissal mock.
        RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        val persisted = JSONArray(RuntimeJobsService.snapshot(app)).getJSONObject(0)
        assertEquals(id, persisted.getString("id"))
        assertEquals("queued", persisted.getString("status"))
        assertTrue(persisted.getBoolean("dialogDismissed"))
        assertFalse(persisted.optBoolean("presented"), "Close must not consume the eventual installer result")
        screen.get().disposeTestComposition()
        screen.recreate().visible()
        consumeJobs(RuntimeJobsService.snapshot(app))
        ui.onNodeWithText("Close").assertDoesNotExist()

        val failed = JSONArray().put(persisted.put("status", "error").put("error", "Download failed")).toString()
        consumeJobs(failed)
        ui.onNodeWithText("Close").assertDoesNotExist()
        // A later, distinct update must not inherit this job's dismissal.
        consumeJobs(JSONArray().put(JSONObject().put("id", "next-update").put("kind", "updateDownload")
            .put("status", "running").put("args", persisted.getJSONObject("args"))).toString())
        ui.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun explicitCancelPersistsAndCanBeReopenedWithoutAnOldResultOverridingRetry() {
        settings.setUpdateAutoCheckEnabled(false)
        var checkLoads = 0
        var historyLoads = 0
        checksDelegate.set(app, lazyOf(UpdateCheckSession(dispatch = { it() }, checker = { checkLoads++; error("No check expected") })))
        historyDelegate.set(app, lazyOf(ReleaseNotesHistorySession(dispatch = { it() }, loader = { _, _ ->
            historyLoads++
            ReleaseNotesHistory(emptyList())
        })))
        screen = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val info = UpdateInfo("3.2.5", "https://example.test/update.apk", "Current release notes")
        val id = RuntimeJobsService.submit(app, "updateDownload", JSONObject()
            .put("version", info.version).put("url", info.downloadUrl).put("notes", info.releaseNotes))
        syntheticRuntimeJobId = id
        consumeJobs(RuntimeJobsService.snapshot(app))
        ui.onNodeWithText("Downloading update...").assertIsDisplayed()

        clickAction("Cancel")
        var afterCancel = jobIn(RuntimeJobsService.snapshot(app), id)
        assertTrue(afterCancel.optBoolean("cancelRequested"), "Cancellation intent is persisted on its own job: $afterCancel")
        assertTrue(afterCancel.getString("status") in setOf("queued", "running", "cancelling", "cancelled"))
        assertEquals(1, JSONArray(RuntimeJobsService.snapshot(app)).length(), "Cancel must not enqueue a replacement job")
        startRuntimeJobs()
        val cleanupDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (afterCancel.getString("status") !in setOf("cancelled", "cancelError")) {
            check(System.nanoTime() < cleanupDeadline) { "Update cancellation did not reach a terminal cleanup result" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(10)
            afterCancel = jobIn(RuntimeJobsService.snapshot(app), id)
        }
        assertEquals("cancelled", afterCancel.getString("status"))
        stopRuntimeJobs()

        // Close remains a presentation-only action, including while cleanup is settling.
        ui.onNodeWithText("Close").performClick()
        settle()
        assertTrue(jobIn(RuntimeJobsService.snapshot(app), id).optBoolean("dialogDismissed"))
        screen.get().disposeTestComposition()
        screen.recreate().visible()
        consumeJobs(RuntimeJobsService.snapshot(app))
        ui.onNodeWithText("Close").assertDoesNotExist()

        // Check updates is the explicit reopen path; it does not issue a check or history request.
        selectTab("Options")
        clickAction("Check for updates")
        ui.onNodeWithText("Close").assertIsDisplayed()
        ui.onNode(hasClickAction() and hasText("Update") and !isToggleable()).assertIsDisplayed()
        assertEquals(0, checkLoads, "Reopening a cancelled job must not issue another update check")
        assertEquals(0, historyLoads, "Reopening a cancelled job must not issue another release-history request")

        // A terminal old cancellation must not mask a newer explicitly requested download.
        clickAction("Update")
        val retrySnapshot = JSONArray(RuntimeJobsService.snapshot(app))
        assertEquals(1, retrySnapshot.length())
        val retry = retrySnapshot.getJSONObject(0)
        val retryId = retry.getString("id")
        assertTrue(retryId != id, "Explicit Update creates a new job only after cancellation completed")
        assertEquals("queued", retry.getString("status"))
        assertEquals(info.version, retry.getJSONObject("args").getString("version"))
        val staleCancellation = JSONObject(cachedJob(id).toString()).apply { remove("presented") }
        val activeRetry = JSONObject(retry.toString()).put("progress", 12)
        consumeJobs(JSONArray().put(staleCancellation).put(activeRetry).toString())
        ui.onNodeWithText("12%").assertIsDisplayed()
        ui.onNodeWithText("Update download cancelled").assertDoesNotExist()
        syntheticRuntimeJobId = retryId
        assertEquals("REQUESTED", RuntimeJobsService.cancelUpdateDownload(app, retryId))
        startRuntimeJobs()
        val retryCleanupDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        var retryState = jobIn(RuntimeJobsService.snapshot(app), retryId)
        while (retryState.getString("status") !in setOf("cancelled", "cancelError")) {
            check(System.nanoTime() < retryCleanupDeadline) { "Explicit retry cancellation did not settle" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(10)
            retryState = jobIn(RuntimeJobsService.snapshot(app), retryId)
        }
        assertEquals("cancelled", retryState.getString("status"))
        stopRuntimeJobs()
        assertEquals(0, checkLoads)
        assertEquals(0, historyLoads)
        consumeJobs(RuntimeJobsService.snapshot(app))
        ui.onNodeWithText("Update download cancelled").assertIsDisplayed()
    }

    private fun startRuntimeJobs() {
        runtimeJobsController = Robolectric.buildService(RuntimeJobsService::class.java).create()
        runtimeJobsController!!.get().onStartCommand(null, 0, 1)
    }

    private fun stopRuntimeJobs() {
        runtimeJobsController?.let { runCatching { it.destroy() } }
        runtimeJobsController = null
    }

    private fun consumeJobs(json: String) {
        MainActivity::class.java.getDeclaredMethod("consumeRuntimeJobs", String::class.java)
            .apply { isAccessible = true }.invoke(screen.get(), json)
        settle()
    }

    private fun selectTab(label: String) {
        ui.onNode(hasClickAction() and hasText(label)).performClick()
        settle()
    }

    private fun clickAction(label: String) {
        ui.onNode(hasClickAction() and hasText(label) and !isToggleable()).performClick()
        settle()
    }

    private fun replaceCachedJobs(json: String) {
        val array = JSONArray(json)
        val jobs = (0 until array.length()).map { JSONObject(array.getJSONObject(it).toString()) }.toMutableList()
        RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, jobs)
    }

    private fun jobIn(json: String, id: String): JSONObject {
        val jobs = JSONArray(json)
        return (0 until jobs.length()).map(jobs::getJSONObject).single { it.getString("id") == id }
    }

    private fun cachedJob(id: String): JSONObject {
        val jobs = RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }
            .get(null) as MutableList<JSONObject>
        return jobs.single { it.getString("id") == id }
    }

    @Test fun downloadKeepsTargetAndIntermediateNotesAfterOfferClearsAndUiRecreates() {
        settings.setUpdateAutoCheckEnabled(false)
        val info = UpdateInfo("3.2.5", "https://example.test/update.apk", "Target release notes",
            "application/vnd.android.package-archive")
        val history = ReleaseNotesHistory(listOf(ReleaseNotesEntry(info.version, info.releaseNotes),
            ReleaseNotesEntry("3.2.4", "Intermediate release notes")))
        var checkLoads = 0
        var historyLoads = 0
        val session = UpdateCheckSession(dispatch = { it() }, checker = { checkLoads++; UpdateCheckResult.Available(info) })
        val notes = ReleaseNotesHistorySession(dispatch = { it() }, loader = { _, _ -> historyLoads++; history })
        checksDelegate.set(app, lazyOf(session))
        historyDelegate.set(app, lazyOf(notes))
        session.request(manual = false)
        screen = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        settle()
        assertEquals(1, checkLoads)
        ui.onNodeWithText("Target release notes").assertIsDisplayed()
        ui.onNodeWithText("Intermediate release notes").assertIsDisplayed()

        val id = RuntimeJobsService.submit(app, "updateDownload", JSONObject().put("version", info.version)
            .put("url", info.downloadUrl).put("notes", info.releaseNotes).put("contentType", info.downloadContentType))
        val unrelatedJobId = "unrelated-log-job"
        app.updateRuntime.onInstallStarted()
        settle()
        assertEquals(UpdateUiState.Hidden, session.snapshot().uiState)
        val transported = RuntimeReads::class.java.getDeclaredMethod("query", android.content.Context::class.java,
            String::class.java, android.os.Bundle::class.java).apply { isAccessible = true }
            .invoke(RuntimeReads, app, "updates", android.os.Bundle()) as RuntimeUpdateSnapshot
        assertEquals(info, transported.target)
        assertEquals(history, transported.history)
        val progressJobs = JSONArray(RuntimeJobsService.snapshot(app))
        val progressIndex = (0 until progressJobs.length()).single { progressJobs.getJSONObject(it).getString("id") == id }
        progressJobs.getJSONObject(progressIndex).put("progress", 73)
        val progress = progressJobs.toString()
        replaceCachedJobs(progress)
        consumeJobs(progress)
        ui.onNodeWithText("Downloading update...").assertIsDisplayed()
        ui.onNodeWithText("73%").assertIsDisplayed()
        ui.onNodeWithText("Target release notes").assertIsDisplayed()
        ui.onNodeWithText("Intermediate release notes").assertIsDisplayed()

        ui.onNodeWithText("Close").performClick()
        settle()
        RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        val closed = jobIn(RuntimeJobsService.snapshot(app), id)
        assertEquals(id, closed.getString("id"))
        assertTrue(closed.getBoolean("dialogDismissed"))
        assertEquals(73, closed.getInt("progress"))
        assertFalse(closed.optBoolean("presented"), "Close must not consume the eventual installer result")

        // Reopen from the same Activity to clear its local suppression as well as the saved dismissal.
        selectTab("Options")
        clickAction("Check for updates")
        ui.onNodeWithText("Downloading update...").assertIsDisplayed()
        ui.onNodeWithText("73%").assertIsDisplayed()
        assertFalse(jobIn(RuntimeJobsService.snapshot(app), id).optBoolean("dialogDismissed"))
        assertEquals(1, checkLoads)
        assertEquals(1, historyLoads)
        ui.onNodeWithText("Close").performClick()
        settle()
        RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        assertTrue(jobIn(RuntimeJobsService.snapshot(app), id).getBoolean("dialogDismissed"))

        screen.get().disposeTestComposition()
        screen.recreate().visible()
        consumeJobs(RuntimeJobsService.snapshot(app))
        ui.onNodeWithText("Close").assertDoesNotExist()

        // Reopening only the update job must not clear busy state for another owner task.
        syntheticRuntimeJobId = unrelatedJobId
        val withUnrelatedJob = JSONArray(RuntimeJobsService.snapshot(app)).put(JSONObject()
            .put("id", unrelatedJobId).put("kind", "logStart").put("args", JSONObject()).put("status", "running"))
        replaceCachedJobs(withUnrelatedJob.toString())
        RuntimeJobsService.dismissUpdateDialog(app, id)
        selectTab("Options")
        clickAction("Check for updates")
        ui.onNodeWithText("Downloading update...").assertIsDisplayed()
        ui.onNodeWithText("73%").assertIsDisplayed()
        ui.onNodeWithText("Target release notes").assertIsDisplayed()
        ui.onNodeWithText("Intermediate release notes").assertIsDisplayed()
        ui.onNodeWithText("Start logcat").assertIsNotEnabled()
        assertEquals(1, checkLoads, "Reopening a runtime job must not run another update check")
        assertEquals(1, historyLoads, "Download, dismissal and reopening reuse the accumulated release history")

        RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        val reopenedSnapshot = RuntimeJobsService.snapshot(app)
        val reopenedJobs = JSONArray(reopenedSnapshot)
        assertEquals(1, (0 until reopenedJobs.length()).count { reopenedJobs.getJSONObject(it).optString("kind") == "updateDownload" },
            "Reopening must reuse the current download job")
        val reopened = jobIn(reopenedSnapshot, id)
        assertFalse(reopened.optBoolean("dialogDismissed"), "Explicit reopen clears only this job's saved dismissal")
        assertEquals(73, reopened.getInt("progress"))
        assertFalse(reopened.optBoolean("presented"))

        screen.get().disposeTestComposition()
        screen.recreate().visible()
        consumeJobs(RuntimeJobsService.snapshot(app))
        ui.onNodeWithText("Downloading update...").assertIsDisplayed()
        ui.onNodeWithText("73%").assertIsDisplayed()
        ui.onNodeWithText("Intermediate release notes").assertIsDisplayed()
        ui.onNodeWithText("Close").performClick()
        settle()
        RuntimeJobsService::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        val closedAgain = jobIn(RuntimeJobsService.snapshot(app), id)
        assertTrue(closedAgain.getBoolean("dialogDismissed"))
        assertTrue(closedAgain.getString("status") in setOf("queued", "running"), "Close must not cancel the update job")
        assertEquals(1, checkLoads)
        assertEquals(1, historyLoads)

        // Simulate the worker finishing after Close; the explicit click must use its persisted result.
        cachedJob(id).put("status", "error").put("error", "Download failed")
        RuntimeJobsService.dismissUpdateDialog(app, id)
        selectTab("Options")
        clickAction("Check for updates")
        ui.onNodeWithText("Update check error: Could not download the update.").assertIsDisplayed()
        ui.onNodeWithText("73%").assertDoesNotExist()
        assertEquals(1, checkLoads, "A terminal update result must not start another check")
        assertEquals(1, historyLoads)
        val completed = cachedJob(id)
        assertEquals("error", completed.getString("status"))
        assertTrue(completed.optBoolean("presented"), "The authoritative terminal result is consumed once")
    }

    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            ui.mainClock.advanceTimeBy(100L)
            ui.waitForIdle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
