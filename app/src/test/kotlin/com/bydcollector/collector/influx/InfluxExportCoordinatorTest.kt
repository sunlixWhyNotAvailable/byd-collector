package com.bydcollector.collector.influx

import android.os.Looper
import android.os.SystemClock
import com.bydcollector.collector.disposeTestComposition
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.MainActivity
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.StartupAdbPeer
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.ha.HaEndpointProfile
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorServiceController
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.ui.RuntimeActionStatus
import com.bydcollector.collector.ui.compose.AppTab
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = BydCollectorApplication::class,
    qualifiers = "w1280dp-h800dp-mdpi", shadows = [StartupAdbPeer::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InfluxExportCoordinatorTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var app: BydCollectorApplication
    private lateinit var screen: ActivityController<MainActivity>
    private var serviceController: ServiceController<CollectorService>? = null
    private var service: CollectorService? = null
    private var nextServiceStartId = 1

    @Before
    fun prepare() {
        app = RuntimeEnvironment.getApplication() as BydCollectorApplication
        shadowOf(app).grantPermissions("com.bydcollector.collector.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        CollectorService.influxConnection.release()
        com.bydcollector.collector.ha.HaRunSession.process.clear()
        val settings = CollectorSettings(app)
        settings.setUiLanguageCode("en")
        settings.setUpdateAutoCheckEnabled(false)
        settings.setUpdateHintEnabled(false)
        settings.setInfluxEnabled(false)
        settings.setInfluxAutoStartEnabled(false)
        settings.setInfluxManuallyStopped(false)
        settings.setPollingEnabled(false)
        settings.setDebugPollingEnabled(false)
        settings.setMqttEnabled(false)
        settings.setTelegramEnabled(false)
        settings.setConnectivityRecoveryEnabled(false)
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
        dispatchStartedServices()
        await { service?.let { ReflectionHelpers.getField<Boolean>(it, "runtimeInitialized") } == true }
    }

    @After
    fun cleanup() {
        AdbAuthorizationManager.cancelObservation()
        if (CollectorService.influxConnection.owned) {
            CollectorSettings(app).apply {
                setInfluxEnabled(false)
                setInfluxManuallyStopped(true)
            }
            runCatching { CollectorServiceController.stopInfluxExport(app) }
            dispatchStartedServices()
            await { !CollectorService.influxConnection.owned }
        }
        runCatching { serviceController?.destroy() }
        serviceController = null
        service = null
        try {
            // This Activity is created directly rather than by the Compose rule. Dispose its
            // composition explicitly so a stopped service cannot leave a registered Compose root
            // updating while the next Robolectric test starts.
            screen.get().disposeTestComposition()
        } finally {
            screen.pause().stop().destroy()
        }
        app.navigationSession.clear()
    }

    @Test
    fun influxOutageFallsBackThenAcknowledgesSqliteRowsAndPublishesMatchingUiState() {
        val settings = CollectorSettings(app)
        val alternative = LocalInfluxEndpoint()
        val primaryPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val store = BydCollectorApplication.store(app)
        val field = NormalizedFieldCatalog.soc
        val observedAt = java.time.Instant.now().toString()
        settings.setInfluxHost("127.0.0.1")
        settings.setInfluxPort(primaryPort)
        settings.setInfluxAlternativeHost("127.0.0.1")
        settings.setInfluxAlternativePort(alternative.port)
        settings.setInfluxDatabase("t16telemetry")
        settings.setInfluxCategoryEnabled("battery", true)
        // One full realtime batch plus one queued row exposes the post-retry export status.
        repeat(301) { index -> store.applyNormalizedObservations(listOf(NormalizedObservation(
            field = field,
            value = NormalizedValue(NormalizedValueType.NUMBER, number = 73.0 + index / 100.0),
            quality = NormalizedQuality.OK,
            sourcePollId = null,
            sourceKey = field.sourceKeys.firstOrNull(),
            observedAt = Instant.parse(observedAt).plusMillis(index.toLong()).toString()
        ))) }
        val historyRows = store.pendingInfluxRows(setOf(field.fieldKey), 400)
        assertEquals(301, historyRows.size)
        val initialCursor = exportedCursor(field.fieldKey)
        try {
            // Load the configured endpoint into the real editable draft before pressing Start.
            screen.recreate().visible()
            settle()
            dispatchStartedServices()
            ui.onNodeWithText("HA integration").performClick()
            settle()
            // The real button commits desired settings before dispatching the owner command.
            val retryClock = MutableClock(System.currentTimeMillis())
            val owner = requireNotNull(service)
            ReflectionHelpers.getField<InfluxExportCoordinator>(owner, "influxCoordinator").also {
                ReflectionHelpers.setField(it, "clock", retryClock)
            }
            ui.onAllNodes(hasText("Start") and hasClickAction())[1].performClick()
            dispatchStartedServices()

            await {
                alternative.requests.size >= 1 &&
                    store.influxExportState().status == "backoff" &&
                    store.pendingInfluxRows(setOf(field.fieldKey), 400).size == 301
            }
            assertTrue(CollectorService.influxConnection.owned)
            assertEquals(1, alternative.requests.size, "the failed alternative write must leave the SQLite row pending")
            assertEquals(initialCursor, exportedCursor(field.fieldKey))
            settle()
            ui.onNodeWithText("error").assertIsDisplayed()
            assertEquals(androidx.compose.ui.graphics.Color(0xFFFF8C8C), textColor("error"))
            assertButtonCounts(startEnabled = 1, startDisabled = 1, stopEnabled = 1, stopDisabled = 1)
            ui.onNodeWithText("t16telemetry").assertIsNotEnabled()

            // Advance the production retry task through its persisted 30-second deadline.
            retryClock.advanceBy(31_000L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            await {
                alternative.requests.size >= 2 &&
                    store.influxExportState().exportedRowsTotal == 300L &&
                    app.dashboardUiStateStore.currentTab(AppTab.HA)?.influxStatus?.contains("scheduled") == true
            }
            settle()
            ui.onNodeWithText("exporting").assertIsDisplayed()
            assertEquals(androidx.compose.ui.graphics.Color(0xFF54D898), textColor("exporting"))
            ui.onNodeWithText("Stop the channel to edit its connection.").assertExists()
            assertButtonCounts(startEnabled = 1, startDisabled = 1, stopEnabled = 1, stopDisabled = 1)
            ui.onNodeWithText("t16telemetry").assertIsNotEnabled()
            assertEquals(2, alternative.requests.size)
            assertTrue(alternative.requests.all { it.startsWith("POST /write?") })
            assertEquals(historyRows[299].id, exportedCursor(field.fieldKey))
            assertEquals(historyRows.last().id, store.pendingInfluxRows(setOf(field.fieldKey), 10).single().id)
            assertEquals("scheduled", store.influxExportState().status)
            assertEquals(HaEndpointProfile.ALTERNATIVE, CollectorService.influxConnection.state.value.activeRoute)

            ui.onAllNodes(hasText("Stop") and hasClickAction())[1].performClick()
            dispatchStartedServices()
            await {
                !CollectorService.influxConnection.owned &&
                    store.influxExportState().status == "stopped" &&
                    app.dashboardUiStateStore.currentTab(AppTab.HA)?.influxRuntimeStatus == RuntimeActionStatus.STOPPED
            }
            settle()
            ui.onNodeWithText("t16telemetry").assertIsEnabled()
            assertFalse(CollectorService.influxConnection.owned)
            assertEquals(2, alternative.requests.size, "Stop must prevent the remaining batch from starting")
            assertEquals(historyRows.last().id, store.pendingInfluxRows(setOf(field.fieldKey), 10).single().id)
        } finally {
            alternative.close()
        }
    }

    private fun exportedCursor(fieldKey: String): Long {
        val helper = TelemetryDatabaseHelper(app)
        return try {
            helper.readableDatabase.rawQuery(
                "SELECT last_exported_history_id FROM influx_export_cursor WHERE field_key = ?",
                arrayOf(fieldKey)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst(), "cursor for $fieldKey should be persisted by the exporter")
                cursor.getLong(0)
            }
        } finally {
            helper.close()
        }
    }

    private fun assertButtonCounts(startEnabled: Int, startDisabled: Int, stopEnabled: Int, stopDisabled: Int) {
        fun counts(label: String): Pair<Int, Int> {
            val nodes = ui.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes()
            val disabled = nodes.count { it.config.contains(SemanticsProperties.Disabled) }
            return (nodes.size - disabled) to disabled
        }
        assertEquals(startEnabled to startDisabled, counts("Start"))
        assertEquals(stopEnabled to stopDisabled, counts("Stop"))
    }

    private fun textColor(text: String): androidx.compose.ui.graphics.Color {
        val result = mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
            it(result)
        }
        return result.single().layoutInput.style.color
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        ui.mainClock.advanceTimeBy(100)
        ui.waitForIdle()
    }

    private fun dispatchStartedServices() {
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: return
            val owner = service ?: Robolectric.buildService(CollectorService::class.java).create().also {
                serviceController = it
                service = it.get()
            }.get()
            owner.onStartCommand(intent, 0, nextServiceStartId++)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) {
                "Timed out: state=${BydCollectorApplication.store(app).influxExportState()} " +
                    "demand=${CollectorSettings(app).runtimeDemand()} connection=${CollectorService.influxConnection.state.value} " +
                    "dashboard=${app.dashboardUiStateStore.currentTab(AppTab.HA)?.influxStatus} " +
                    "foreground=${ReflectionHelpers.getField<Boolean>(screen.get(), "foreground")} " +
                    "tab=${app.navigationSession.selectedTab} " +
                    "refresh=${ReflectionHelpers.getField<Boolean>(screen.get(), "refreshInFlight") }"
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(10)
        }
    }

    private class LocalInfluxEndpoint : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val responseCodes = java.util.concurrent.ConcurrentLinkedQueue<Int>().apply {
            add(503)
            add(204)
        }
        private val worker = thread(isDaemon = true, name = "test-influx-endpoint") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                socket.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                    val request = reader.readLine().orEmpty()
                    var length = 0
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    repeat(length) { reader.read() }
                    requests += request
                    it.getOutputStream().write(
                        "HTTP/1.1 ${responseCodes.poll() ?: 204} Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII)
                    )
                }
            }
        }

        override fun close() {
            server.close()
            worker.join(5_000L)
            check(!worker.isAlive)
        }
    }

    private class MutableClock(@Volatile private var nowMs: Long) : Clock {
        override fun nowIso(): String = Instant.ofEpochMilli(nowMs).toString()
        override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
        fun advanceBy(deltaMs: Long) { nowMs += deltaMs }
    }
}
