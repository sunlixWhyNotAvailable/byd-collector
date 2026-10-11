package com.bydcollector.collector

import android.Manifest
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.StartupAdbPeer
import com.bydcollector.collector.data.normalized.*
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.service.KpiSourceUpdate
import com.bydcollector.collector.ui.compose.AppTab
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = BydCollectorApplication::class,
    qualifiers = "w1280dp-h480dp-mdpi", shadows = [StartupAdbPeer::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RuntimeUiBehaviorTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var app: BydCollectorApplication
    private lateinit var screen: ActivityController<MainActivity>
    private var kpiOwner: CollectorService? = null

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication() as BydCollectorApplication
        val settings = CollectorSettings(app)
        settings.setUiLanguageCode("en")
        settings.setUpdateAutoCheckEnabled(false)
        settings.setUpdateHintEnabled(false)
        app.getSharedPreferences("startup_setup", 0).edit()
            .putInt("background_settings_version", BuildConfig.VERSION_CODE)
            .putBoolean("location_permission_setup_consumed", true)
            .putBoolean("overlay_permission_setup_consumed", true).commit()
        StartupAdbPeer.reset()
        StartupAdbPeer.consent = true
        StartupAdbPeer.trusted = true
        AdbLocalClient(File(app.filesDir, "adb_keys")).keyFingerprint()
        screen = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        settle()
    }

    @After fun cleanup() {
        kpiOwner?.let { owner ->
            ReflectionHelpers.getField<AtomicBoolean>(owner, "running").set(false)
            ReflectionHelpers.getField<android.os.Handler>(owner, "mainHandler").removeCallbacksAndMessages(null)
            CollectorService::class.java.declaredFields.filter {
                java.util.concurrent.ExecutorService::class.java.isAssignableFrom(it.type)
            }.forEach { field ->
                field.isAccessible = true
                (field.get(owner) as? java.util.concurrent.ExecutorService)?.shutdownNow()
            }
            ReflectionHelpers.getField<com.bydcollector.collector.data.local.EcCurrentMeanProvider>(owner,
                "ecCurrentMeanProvider").close()
        }
        AdbAuthorizationManager.cancelObservation()
        screen.get().disposeTestComposition()
        screen.pause().stop().destroy()
        app.navigationSession.clear()
    }

    @Test fun tabsSwitchesAndViewportSurviveUiRecreation() {
        val row = toggle("Automatic start")
        row.assertIsOff()
        row.performClick()
        settle()
        row.assertIsOn()
        assertTrue(CollectorSettings(app).isAutoStartEnabled())
        val initialBounds = row.fetchSemanticsNode().boundsInRoot
        tab("All data")
        toggle("Automatic start").assertIsOff()
        tab("Main")
        toggle("Automatic start").assertIsOn()
        assertEquals(initialBounds, toggle("Automatic start").fetchSemanticsNode().boundsInRoot)
        tab("All data")
        ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), true)[0]
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 180f) }
        settle()
        val offset = app.navigationSession.scrollOffset(AppTab.ALL_PARAMETERS)
        assertTrue(offset > 0, "fixture must actually scroll")
        val scrolledBounds = toggle("Automatic start").fetchSemanticsNode().boundsInRoot
        tab("Main"); tab("All data")
        assertEquals(offset, app.navigationSession.scrollOffset(AppTab.ALL_PARAMETERS))
        assertEquals(scrolledBounds, toggle("Automatic start").fetchSemanticsNode().boundsInRoot)
        screen.pause().stop(); screen.start().resume().visible()
        settle()
        ui.mainClock.autoAdvance = false
        try {
            screen.recreate().visible()
            // Do not let a later animation/frame hide an initially incorrect viewport.
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            assertEquals(AppTab.ALL_PARAMETERS, app.navigationSession.selectedTab)
            assertEquals(offset, app.navigationSession.scrollOffset(AppTab.ALL_PARAMETERS))
            assertEquals(scrolledBounds, toggle("Automatic start").fetchSemanticsNode().boundsInRoot)
        } finally {
            ui.mainClock.autoAdvance = true
        }
        toggle("Automatic start").assertIsOff()
        tab("Main")
        toggle("Automatic start").assertIsOn()
    }

    @Test fun backgroundTelemetryReachesVisibleKpisWithoutRestart() {
        tab("All data")
        val owner = Robolectric.buildService(CollectorService::class.java).get().also { kpiOwner = it }
        // Attach an already-running owner at the vehicle input boundary; no live-car access.
        ReflectionHelpers.setField(owner, "settings", CollectorSettings(app))
        ReflectionHelpers.setField(owner, "dashboardUiStateStore", app.dashboardUiStateStore)
        ReflectionHelpers.setField(owner, "ecCurrentMeanProvider", com.bydcollector.collector.data.local.EcCurrentMeanProvider())
        ReflectionHelpers.setField(owner, "kpiPowerOn", true)
        ReflectionHelpers.setField(owner, "kpiInteractive", true)
        ReflectionHelpers.getField<AtomicBoolean>(owner, "running").set(true)
        while (shadowOf(app).nextStartedService != null) { /* Drain starts from opening the Activity. */ }
        screen.pause().stop()
        val field = NormalizedFieldCatalog.kpiFields.single { it.fieldKey == "soc" }
        val observation = NormalizedObservation(field, NormalizedValue(NormalizedValueType.NUMBER, number = 73.0),
            NormalizedQuality.OK, null, "soc", java.time.Instant.now().toString(), sourceStamp =
                NormalizedSourceStamp(NormalizedSourceKind.CALLBACK, "kpi-test-1",
                    com.bydcollector.collector.data.polling.LivePollSource.liveBootId, "fixture", 1L,
                    System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()))
        ReflectionHelpers.callInstanceMethod<Long>(owner, "applyKpiSourceUpdate",
            ReflectionHelpers.ClassParameter.from(KpiSourceUpdate::class.java,
                KpiSourceUpdate(observations = listOf(observation))))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        screen.start().resume().visible()
        settle()
        ui.onNodeWithText("73%").assertIsDisplayed()
        assertEquals("73%", app.dashboardUiStateStore.currentTab(AppTab.ALL_PARAMETERS)?.vehicleKpis?.socPercent)
        // Activity reattachment legitimately sends START; it is not a new collection run.
        assertEquals(CollectorService.ACTION_START, shadowOf(app).nextStartedService?.action)
        assertTrue(ReflectionHelpers.getField<AtomicBoolean>(owner, "running").get())
        ReflectionHelpers.getField<AtomicBoolean>(owner, "running").set(false)
    }

    @Test fun permissionReadbackUpdatesVisiblePillTextAndColour() {
        fun observe() {
            assertTrue(AdbAuthorizationManager.requestObservation(app))
            await { app.dashboardUiStateStore.currentChrome()?.permissionsGranted ==
                !com.bydcollector.collector.system.RequiredAccessChecker.hasMissingRequiredAccess(app) &&
                app.dashboardUiStateStore.currentChrome()?.adbAuthorized == true }
            settle()
        }
        fun colour(text: String): androidx.compose.ui.graphics.Color {
            val result = mutableListOf<TextLayoutResult>()
            ui.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
            return result.single().layoutInput.style.color
        }
        shadowOf(app).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "foreign.app/foreign.Listener")
        observe()
        ui.onNodeWithText("Permissions: missing").assertIsDisplayed()
        val missingColour = colour("Permissions: missing")
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFF8C8C), missingColour)
        screen.pause().stop()
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            "foreign.app/foreign.Listener:${app.packageName}/com.bydcollector.collector.system.CollectorNotificationListenerService")
        screen.start().resume().visible()
        observe()
        ui.onNodeWithText("Permissions: OK").assertIsDisplayed()
        assertEquals(androidx.compose.ui.graphics.Color(0xFF54D898), colour("Permissions: OK"))
        ui.onNodeWithText("ADB: OK").assertIsDisplayed()
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "foreign.app/foreign.Listener")
        observe()
        ui.onNodeWithText("Permissions: missing").assertIsDisplayed()
        assertEquals(missingColour, colour("Permissions: missing"))
        ui.onNodeWithText("ADB: OK").assertIsDisplayed()
    }

    private fun toggle(label: String) = ui.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
            hasAnyDescendant(hasText(label)), true)
    private fun tab(label: String) { ui.onNode(hasClickAction() and hasText(label)).performClick(); settle() }
    private fun settle() { shadowOf(Looper.getMainLooper()).idle(); ui.mainClock.advanceTimeBy(100); ui.waitForIdle() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline); shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
    }
}
