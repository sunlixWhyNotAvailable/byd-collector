package com.bydcollector.collector

import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.StartupAdbPeer
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.trips.RoutePoint
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.data.trips.TripSession
import com.bydcollector.collector.data.trips.TripStore
import com.bydcollector.collector.maintenance.ArchiveStorageJobMode
import com.bydcollector.collector.maintenance.ArchiveStorageManager
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.ui.ArchiveStorageSnapshotCache
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
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [29],
    application = BydCollectorApplication::class,
    qualifiers = "w1280dp-h480dp-mdpi",
    shadows = [StartupAdbPeer::class]
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveDeletionUiTest {
    @get:Rule val ui = createEmptyComposeRule()

    private lateinit var app: BydCollectorApplication
    private lateinit var screen: ActivityController<MainActivity>
    private lateinit var mainStore: TelemetryStore
    private lateinit var debugStore: DirectDebugStore
    private lateinit var tripHelper: TripDatabaseHelper
    private lateinit var tripStore: TripStore
    private lateinit var archiveCache: ArchiveStorageSnapshotCache
    private lateinit var scanExecutor: QueuedExecutor
    private lateinit var selectedArchive: File
    private lateinit var unselectedArchive: File
    private lateinit var spoolFixture: File
    private lateinit var testSessionId: String
    private lateinit var testTripId: String
    private var mainSessionId: Long? = null
    private var lateScanThread: Thread? = null
    private var releaseLateScan = CountDownLatch(0)
    private var serviceController: ServiceController<CollectorService>? = null

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

        mainStore = BydCollectorApplication.store(app)
        assertTrue(mainStore.verifyWritableDatabase())
        mainSessionId = mainStore.openSession(sessionType = "archive_delete_test")
        debugStore = DirectDebugStore(app)
        assertTrue(debugStore.verifyWritableDatabase())
        tripHelper = TripDatabaseHelper(app)
        tripStore = TripStore(tripHelper)
        testTripId = "archive-delete-${UUID.randomUUID()}"
        val now = java.time.Instant.now().toString()
        tripStore.upsertSession(TripSession(
            tripId = testTripId,
            state = TripSession.STATE_OPEN,
            startedAt = now,
            startElapsedMs = android.os.SystemClock.elapsedRealtime(),
            startBootId = "archive-delete-test"
        ))
        tripStore.upsertRoutePoint(RoutePoint(
            tripId = testTripId,
            sequence = 0L,
            observedAt = now,
            elapsedMs = android.os.SystemClock.elapsedRealtime(),
            bootId = "archive-delete-test",
            latitude = 50.4501,
            longitude = 30.5234,
            accuracyM = 5.0,
            isFirst = true
        ))

        val archiveRoot = File(app.filesDir, "db_archive").apply { check(isDirectory || mkdirs()) }
        testSessionId = UUID.randomUUID().toString().take(8)
        val selectedId = "bydcollector_telemetry_ui_$testSessionId.zip"
        val unselectedId = "bydcollector_telemetry_ui_other_$testSessionId.zip"
        selectedArchive = File(archiveRoot, selectedId).also { writeArchiveZip(it, "selected-$testSessionId") }
        unselectedArchive = File(archiveRoot, unselectedId).also { writeArchiveZip(it, "unselected-$testSessionId") }
        unselectedArchive.setLastModified(System.currentTimeMillis() - 10_000L)
        spoolFixture = File(app.filesDir, "archive-delete-spool-fixture-$testSessionId/pending.record").apply {
            parentFile?.mkdirs()
            writeBytes("pending raw spool bytes\u0000$testSessionId".toByteArray(Charsets.UTF_8))
        }

        val directDebugFile = debugStore.databaseFile()
        val tripsFile = tripHelper.databaseFile
        val manager = ArchiveStorageManager(
            archiveRoot = archiveRoot,
            mainDatabaseFile = mainStore.databaseFile(),
            debugDatabaseFile = directDebugFile,
            debugDatabaseFileProvider = { directDebugFile },
            tripsDatabaseFile = tripsFile
        )
        scanExecutor = QueuedExecutor()
        val scanCount = AtomicInteger()
        val lateScanCaptured = CountDownLatch(1)
        releaseLateScan = CountDownLatch(1)
        archiveCache = ArchiveStorageSnapshotCache(
            archiveRoot = archiveRoot,
            mainDatabaseFile = mainStore.databaseFile(),
            debugDatabaseFile = directDebugFile,
            debugDatabaseFileProvider = { directDebugFile },
            tripsDatabaseFile = tripsFile,
            executor = scanExecutor,
            loader = { limitBytes ->
                val loaded = manager.snapshot(limitBytes)
                if (scanCount.incrementAndGet() == 2) {
                    check(loaded.entries.any { it.id == selectedId }) { "late scan must capture the selected archive before deletion" }
                    lateScanCaptured.countDown()
                    check(releaseLateScan.await(10, TimeUnit.SECONDS)) { "late scan was not released" }
                }
                loaded
            }
        )
        val cacheDelegate = ReflectionHelpers.getField<Lazy<ArchiveStorageSnapshotCache>>(
            app,
            "archiveStorageSnapshotCacheDelegate"
        )
        check(!cacheDelegate.isInitialized()) { "archive snapshot cache must be replaceable before Activity setup" }
        ReflectionHelpers.setField(app, "archiveStorageSnapshotCacheDelegate", lazyOf(archiveCache))
        assertSame(archiveCache, app.archiveStorageSnapshotCache)

        assertTrue(archiveCache.snapshot(2L * 1024 * 1024 * 1024, includeDetails = true).pending)
        scanExecutor.takeNext().run()
        val initial = archiveCache.snapshot(2L * 1024 * 1024 * 1024, includeDetails = true)
        assertFalse(initial.pending)
        assertTrue(initial.snapshot.entries.any { it.id == selectedId })
        assertTrue(initial.snapshot.entries.any { it.id == unselectedId })

        archiveCache.invalidate()
        assertTrue(archiveCache.snapshot(2L * 1024 * 1024 * 1024, includeDetails = true).pending)
        lateScanThread = Thread(scanExecutor.takeNext(), "archive-delete-late-scan").apply { start() }
        assertTrue(lateScanCaptured.await(5, TimeUnit.SECONDS), "controlled scan did not capture pre-delete snapshot")

        screen = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        settle()
        drainStartedServices()
    }

    @After fun cleanup() {
        releaseLateScan.countDown()
        lateScanThread?.join(2_000L)
        AdbAuthorizationManager.cancelObservation()
        runCatching { serviceController?.destroy() }
        serviceController = null
        if (::screen.isInitialized) {
            screen.get().disposeTestComposition()
            screen.pause().stop().destroy()
        }
        if (::app.isInitialized) app.navigationSession.clear()
        if (::mainStore.isInitialized) mainSessionId?.let { runCatching { mainStore.endSession(it, "archive_delete_test_cleanup") } }
        if (::tripStore.isInitialized) {
            runCatching { tripHelper.writableDatabase.delete("route_points", "trip_id = ?", arrayOf(testTripId)) }
            runCatching { tripHelper.writableDatabase.delete("trip_sessions", "trip_id = ?", arrayOf(testTripId)) }
            runCatching { tripStore.close() }
        }
        if (::debugStore.isInitialized) runCatching { debugStore.close() }
        if (::archiveCache.isInitialized) runCatching { archiveCache.close() }
        lateScanThread?.join(2_000L)
        if (::selectedArchive.isInitialized) selectedArchive.delete()
        if (::unselectedArchive.isInitialized) unselectedArchive.delete()
        if (::spoolFixture.isInitialized) spoolFixture.parentFile?.deleteRecursively()
    }

    @Test fun deleteArchivesOnceUpdatesVisibleListAndPreservesActiveFiles() {
        val selectedId = selectedArchive.name
        val selectedLabel = selectedArchive.name.removeSuffix(".zip")
        val unselectedBytes = unselectedArchive.readBytes()
        val spoolBytes = spoolFixture.readBytes()
        val activeFiles = activeDatabaseFiles().filter { it.exists() }
        assertTrue(activeFiles.any { it == mainStore.databaseFile() })
        assertTrue(activeFiles.any { it == debugStore.databaseFile() })
        assertTrue(activeFiles.any { it == tripHelper.databaseFile })
        val activeSessionId = assertNotNull(mainSessionId)

        tab("Storage")
        scrollTextIntoView(selectedLabel)
        ui.onNodeWithText(selectedLabel, useUnmergedTree = true).assertIsDisplayed()
        clickArchiveSelector(selectedLabel)
        scrollTextIntoView("Delete selected")
        ui.onNodeWithText("Delete selected").performClick()
        ui.onNodeWithText("Selected: 1").assertIsDisplayed()
        ui.onNodeWithText("Delete selected archives?").assertIsDisplayed()
        ui.onNodeWithText("Yes").performClick()
        settle()

        val deleteIntent = assertNotNull(shadowOf(app).nextStartedService,
            "MainActivity confirmation must dispatch CollectorServiceController.deleteArchives")
        assertEquals(CollectorService.ACTION_DELETE_ARCHIVES, deleteIntent.action)
        val requestedArchiveIds = deleteIntent.getStringArrayListExtra(CollectorService.EXTRA_ARCHIVE_IDS)
        assertTrue(
            requestedArchiveIds != null && requestedArchiveIds.size == 1 && requestedArchiveIds[0] == selectedId,
            "the Activity must dispatch only the selected archive ID"
        )
        assertTrue(selectedArchive.exists(), "Activity retires the row and dispatches; the Service owns physical deletion")
        assertFalse(archiveCache.snapshot(2L * 1024 * 1024 * 1024, includeDetails = true)
            .snapshot.entries.any { it.id == selectedId }, "the real Activity path should retire the selected row immediately")

        val settings = CollectorSettings(app, mainStore)
        val priorOperationId = settings.archiveStorageJobStatus().operationId
        shadowOf(app).grantPermissions("com.bydcollector.collector.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        serviceController = Robolectric.buildService(CollectorService::class.java).create()
        val service = requireNotNull(serviceController).get()
        await { ReflectionHelpers.getField<Boolean>(service, "runtimeInitialized") }
        // Exercise the exact Intent dispatched by MainActivity through the service's real entrypoint.
        service.onStartCommand(deleteIntent, 0, 1)
        await {
            val status = settings.archiveStorageJobStatus()
            status.operationId != null && status.operationId != priorOperationId && !status.running
        }
        val startedOperationId = assertNotNull(settings.archiveStorageJobStatus().operationId)
        assertNotEquals(priorOperationId, startedOperationId)
        assertEquals(1, settings.archiveStorageJobStatus().stepIndex)
        assertEquals(1, settings.archiveStorageJobStatus().stepCount)
        assertEquals(ArchiveStorageJobMode.DELETE, settings.archiveStorageJobStatus().mode)
        assertEquals(selectedId, settings.archiveStorageJobStatus().itemId)
        assertEquals(com.bydcollector.collector.maintenance.ArchiveStorageItemPhase.DELETED,
            settings.archiveStorageJobStatus().phase)
        assertNull(settings.archiveStorageJobStatus().error)
        assertFalse(selectedArchive.exists(), "the attached Service must physically remove the selected archive")
        assertTrue(unselectedArchive.isFile)
        assertContentEquals(unselectedBytes, unselectedArchive.readBytes(), "unselected archive bytes must not change")
        assertContentEquals(spoolBytes, spoolFixture.readBytes(), "app-private spool sentinel outside the archive root must be untouched")

        // Main SQLite receives legitimate archive-operation event/progress rows, so preservation is semantic, not byte equality.
        assertTrue(mainStore.verifyWritableDatabase())
        assertTrue(sqliteHasRow(mainStore.databaseFile(), "collection_sessions", "id", activeSessionId.toString()))
        assertTrue(debugStore.verifyWritableDatabase())
        assertEquals(testTripId, tripStore.session(testTripId)?.tripId)
        assertEquals(1, tripStore.queryRoutePoints(testTripId).size)
        assertEquals("ok", quickCheck(tripHelper.writableDatabase))
        activeFiles.forEach { file -> assertTrue(file.exists(), "active database sidecar disappeared: ${file.name}") }

        releaseLateScan.countDown()
        checkNotNull(lateScanThread).join(5_000L)
        assertFalse(checkNotNull(lateScanThread).isAlive, "stale archive scan did not finish")
        assertNotNull(scanExecutor.pollNext(5_000L), "cache must queue a fresh scan after discarding the in-flight generation")
            .run()
        val settledCache = archiveCache.snapshot(2L * 1024 * 1024 * 1024, includeDetails = true)
        assertFalse(settledCache.pending)
        assertFalse(settledCache.snapshot.entries.any { it.id == selectedId }, "the late pre-delete scan must not resurrect the retired row")
        assertTrue(settledCache.snapshot.entries.any { it.id == unselectedArchive.name })

        tab("Main")
        tab("Storage")
        ui.onNodeWithText(selectedLabel).assertDoesNotExist()
        val unselectedLabel = unselectedArchive.name.removeSuffix(".zip")
        scrollTextIntoView(unselectedLabel)
        ui.onNodeWithText(unselectedLabel).assertIsDisplayed()
    }

    private fun activeDatabaseFiles(): List<File> {
        val main = mainStore.databaseFile()
        val debug = debugStore.databaseFile()
        val trips = tripHelper.databaseFile
        return listOf(main, debug, trips).flatMap { file ->
            listOf(file, File(file.absolutePath + "-wal"), File(file.absolutePath + "-shm"))
        }
    }

    private fun sqliteHasRow(file: File, table: String, column: String, value: String): Boolean =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT 1 FROM $table WHERE $column = ? LIMIT 1", arrayOf(value)).use { it.moveToFirst() }
        }

    private fun quickCheck(db: SQLiteDatabase): String = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
        check(cursor.moveToFirst()) { "SQLite quick_check returned no row" }
        cursor.getString(0)
    }

    private fun clickArchiveSelector(label: String) {
        val labelBounds = ui.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val rootBounds = ui.onRoot().fetchSemanticsNode().boundsInRoot
        val density = app.resources.displayMetrics.density
        val selectorCenterX = labelBounds.left - 26f * density
        ui.onRoot().performTouchInput {
            click(Offset(selectorCenterX - rootBounds.left, labelBounds.center.y - rootBounds.top))
        }
        settle()
    }

    private fun scrollTextIntoView(label: String) {
        val scroller = ui.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true
        )[0]
        repeat(20) {
            val node = ui.onNodeWithText(label, useUnmergedTree = true)
            if (runCatching { node.assertIsDisplayed() }.isSuccess) return
            val bounds = ui.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val viewport = scroller.fetchSemanticsNode().boundsInRoot
            if (bounds.top >= viewport.top && bounds.bottom <= viewport.bottom) {
                node.assertIsDisplayed()
                return
            }
            val scrollDown = bounds.height <= 0f || bounds.bottom > viewport.bottom
            scroller.performSemanticsAction(SemanticsActions.ScrollBy) { action ->
                action(0f, if (scrollDown) 170f else -170f)
            }
            settle()
        }
        ui.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun tab(label: String) {
        ui.onNode(hasClickAction() and hasText(label)).performClick()
        settle()
    }

    private fun drainStartedServices() {
        repeat(64) {
            if (shadowOf(app).nextStartedService == null) return
        }
        error("service start queue did not drain")
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        ui.mainClock.advanceTimeBy(100)
        ui.waitForIdle()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "archive delete operation did not settle" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
    }

    private fun writeArchiveZip(file: File, payload: String) {
        file.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("payload.txt"))
            zip.write(payload.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private class QueuedExecutor : Executor {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun execute(command: Runnable) { queue.put(command) }
        fun takeNext(): Runnable = checkNotNull(queue.poll(5, TimeUnit.SECONDS)) { "expected cache scan task was not queued" }
        fun pollNext(timeoutMs: Long): Runnable? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
