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

    @Test fun completedRuntimeJobsShowLocalizedOutcomesNotTechnicalPayloads() {
        val actions = ReflectionHelpers.getField<com.bydcollector.collector.ui.compose.BydCollectorActions>(screen.get(), "uiActions")
        val consume = MainActivity::class.java.getDeclaredMethod("consumeRuntimeJobs", String::class.java)
            .apply { isAccessible = true }
        fun deliver(kind: String, result: org.json.JSONObject = org.json.JSONObject(), error: String? = null): String {
            val job = org.json.JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("kind", kind)
                .put("status", if (error == null) "complete" else "failed").put("result", result)
                .put("error", error.orEmpty())
            consume.invoke(screen.get(), org.json.JSONArray().put(job).toString())
            return org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
        }
        for (language in com.bydcollector.collector.ui.compose.UiLanguage.entries) {
            actions.onLanguageSelected(language)
            val uk = language == com.bydcollector.collector.ui.compose.UiLanguage.UK
            assertEquals(if (uk) "Старі логи очищено" else "Old logs cleared", deliver("logClear"))
            val warnings = org.json.JSONObject().put("removed", 2).put("warnings",
                org.json.JSONArray().put("diagnostic_files=IOException: denied"))
            assertEquals(if (uk) "Логи очищено частково. Подробиці — у логах" else
                "Logs partially cleared. See the logs for details", deliver("logClear", warnings))
            assertEquals(if (uk) "Запис logcat почато" else "Logcat recording started", deliver("logStart"))
            assertEquals(if (uk) "Запис logcat зупинено" else "Logcat recording stopped", deliver("logStop"))
            assertEquals(if (uk) "InfluxDB: з’єднання успішне (HTTP 204)" else
                "InfluxDB: connection successful (HTTP 204)", deliver("influxTest",
                org.json.JSONObject().put("ok", true).put("message", "http 204")))
            // A completed worker can still report a failed connection test.
            val failed = deliver("influxTest", org.json.JSONObject().put("ok", false)
                .put("message", "http 401: backend response"))
            assertTrue(failed.contains(if (uk) "перевірка з’єднання невдала" else "connection test failed"))
            assertTrue(failed.contains("HTTP 401"))
            assertFalse(failed.contains("backend response"))
            val error = deliver("logClear", error = "IllegalStateException: internal detail")
            assertTrue(error.startsWith(if (uk) "Не вдалося очистити логи" else "Could not clear logs"))
            assertFalse(error.contains("IllegalStateException"))
            assertEquals("diagnostic_files=IOException: denied", warnings.getJSONArray("warnings").getString(0))
        }
    }

    @Test fun modalFailuresExplainSpaceAndUnknownQueuesWithoutHidingDiagnosticsOrBlockingArchive() {
        val actions = ReflectionHelpers.getField<com.bydcollector.collector.ui.compose.BydCollectorActions>(screen.get(), "uiActions")
        val compression = ReflectionHelpers.getStaticField<kotlinx.coroutines.flow.MutableStateFlow<com.bydcollector.collector.service.TripCompressionState>>(
            com.bydcollector.collector.service.TripCompressionService::class.java, "_state")
        val pending = ReflectionHelpers.getField<androidx.compose.runtime.MutableState<com.bydcollector.collector.maintenance.DbMaintenanceOperation?>>(
            screen.get(), "pendingMaintenanceOperation\$delegate")
        val preflight = ReflectionHelpers.getField<androidx.compose.runtime.MutableState<com.bydcollector.collector.maintenance.MainArchivePreflight?>>(
            screen.get(), "pendingMainArchivePreflight\$delegate")
        val error = "IllegalStateException: Insufficient free space for Trips compression"
        try {
            for (language in com.bydcollector.collector.ui.compose.UiLanguage.entries) {
                actions.onLanguageSelected(language)
                val uk = language == com.bydcollector.collector.ui.compose.UiLanguage.UK
                compression.value = com.bydcollector.collector.service.TripCompressionState(error = error)
                settle()
                ui.onNodeWithText(if (uk) "Недостатньо вільного місця. Звільніть місце та повторіть." else
                    "Not enough free space. Free up space and retry.").assertIsDisplayed()
                ui.onNodeWithText(error).assertDoesNotExist()
                assertEquals(error, compression.value.error)
                compression.value = com.bydcollector.collector.service.TripCompressionState()
                preflight.value = com.bydcollector.collector.maintenance.MainArchivePreflight(warning = "SQLiteException: corrupt")
                pending.value = com.bydcollector.collector.maintenance.DbMaintenanceOperation.ARCHIVE
                settle()
                ui.onNodeWithText(if (uk) "Не вдалося перевірити базу та черги." else
                    "Could not check the database and queues.", substring = true).assertExists()
                ui.onNodeWithText("SQLiteException: corrupt").assertDoesNotExist()
                ui.onNodeWithText(if (uk) "Так" else "Yes").assertIsEnabled()
                assertEquals("SQLiteException: corrupt", preflight.value?.warning)
                pending.value = null
                preflight.value = null
                settle()
            }
        } finally {
            compression.value = com.bydcollector.collector.service.TripCompressionState()
            pending.value = null
            preflight.value = null
        }
    }

    @Test fun runtimeJobWarningsAndFailuresRemainInSharedJournalAfterPresentation() {
        val jobs = com.bydcollector.collector.runtime.RuntimeJobsService
        val owner = Robolectric.buildService(com.bydcollector.collector.runtime.RuntimeJobsService::class.java).create()
        fun run(kind: String, args: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject {
            val id = jobs.submit(app, kind, args)
            owner.get().onStartCommand(null, 0, 1)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (true) {
                val snapshot = org.json.JSONArray(jobs.snapshot(app))
                val job = (0 until snapshot.length()).map(snapshot::getJSONObject).single { it.getString("id") == id }
                if (job.getString("status") in setOf("complete", "error")) {
                    jobs.presented(app, id)
                    return job
                }
                check(System.nanoTime() < deadline)
                Thread.sleep(10)
            }
        }
        try {
            // The ADB peer acknowledges shell commands but provides no helper JSON: a real partial clear.
            val cleared = run("logClear")
            assertEquals("complete", cleared.getString("status"))
            val warnings = cleared.getJSONObject("result").getJSONArray("warnings")
            assertTrue(warnings.length() > 0)
            shadowOf(Looper.getMainLooper()).idle()
            val failed = run("archiveShare", org.json.JSONObject().put("ids", org.json.JSONArray().put("missing-archive")))
            assertEquals("error", failed.getString("status"))
            val snapshot = File(app.cacheDir, "runtime-job-journal")
            val export = com.bydcollector.collector.diagnostics.DiagnosticLogRecorder::class.java.getDeclaredMethod(
                "writeOperationalJournalSnapshot", android.content.Context::class.java, File::class.java).apply { isAccessible = true }
            export.invoke(com.bydcollector.collector.diagnostics.DiagnosticLogRecorder, app, snapshot)
            val entries = File(snapshot, "operational_journal").walkTopDown().filter { it.extension == "jsonl" }
                .flatMap { it.readLines().asSequence() }.map { org.json.JSONObject(it) }.toList()
            val partial = entries.single { it.optString("message") == "logClear_partial" }.getString("detail")
            assertTrue(partial.contains(warnings.getString(0)))
            assertTrue(entries.single { it.optString("message") == "archiveShare_failed" }.getString("detail")
                .contains(failed.getString("error")))
        } finally {
            owner.destroy()
        }
    }

    private fun toggle(label: String) = ui.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
            hasAnyDescendant(hasText(label)), true)
    private fun tab(label: String) { ui.onNode(hasClickAction() and hasText(label)).performClick(); settle() }
    private fun settle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        ui.mainClock.advanceTimeBy(100)
        ui.waitForIdle()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline); shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
    }
}
