package com.bydcollector.collector.update

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.MainActivity
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

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
    private lateinit var checksDelegate: java.lang.reflect.Field
    private lateinit var historyDelegate: java.lang.reflect.Field
    private var previousChecksDelegate: Any? = null
    private var previousHistoryDelegate: Any? = null
    private var previousUpdateAuto: Boolean? = null
    private var previousUpdateHint: Boolean? = null
    private var previousUiLanguage: String? = null
    private var screenStopped = false
    private var screenDestroyed = false

    @Before fun prepare() {
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
            if (::screen.isInitialized && !screenDestroyed) {
                screen.get().disposeTestComposition()
                if (!screenStopped) screen.pause().stop()
                screen.destroy()
            }
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

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        ui.mainClock.advanceTimeBy(100L)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
