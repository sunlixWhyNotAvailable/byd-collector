package com.bydcollector.collector.data.trips

import android.app.Application
import com.bydcollector.collector.data.energy.EnergyInput
import com.bydcollector.collector.data.energy.EnergyReceipt
import com.bydcollector.collector.data.energy.EnergySessionCoordinator
import com.bydcollector.collector.data.energy.EnergySessionSeed
import com.bydcollector.collector.data.energy.EnergyStateCodec
import com.bydcollector.collector.data.energy.BufferedEnergyStorage
import com.bydcollector.collector.data.energy.EnergySnapshot
import com.bydcollector.collector.data.input.BusinessPage
import com.bydcollector.collector.data.input.BusinessPageEntry
import com.bydcollector.collector.diagnostics.DiagnosticLogRecorder
import com.bydcollector.collector.diagnostics.DiagnosticZipWriter
import com.bydcollector.collector.diagnostics.sanitizeDiagnosticSnapshot
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class TripEnergyRuntimeMirrorTest {
    @Test fun diagnosticZipKeepsLatestTenClosedSessionsAndDistinguishesUnknownFromZero() {
        val context = RuntimeEnvironment.getApplication()
        val name = "recent_closed_energy.db"
        val snapshot = File(context.cacheDir, "snapshot_closed_energy").apply { mkdirs() }
        val zip = File(context.cacheDir, "closed_energy.zip")
        try {
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                for (hour in 0..11) {
                    val start = java.time.Instant.parse("2026-11-18T00:00:00Z").plusSeconds(hour * 3600L)
                    val trip = TripSession("private-id-$hour", TripSession.STATE_CLOSED, start.toString(),
                        endedAt = start.plusSeconds(600).toString())
                    store.upsertSession(if (hour == 11) trip.copy(dischargedKwh = 0.0, regeneratedKwh = 0.25,
                        netKwh = -0.25, energyCoveredMs = 550_000, energyUncoveredMs = 50_000,
                        energyPartial = true, quality = "trip_energy_reset,trip_energy_incomplete") else trip)
                }
                store.upsertSession(TripSession("private-open", startedAt = "2026-11-18T12:00:00Z"))
                val file = File(snapshot, "trips_telegram_evidence.txt")
                file.writeText(DiagnosticLogRecorder.recentClosedTripEvidence(store.databaseFile).joinToString("\n"))
            }
            sanitizeDiagnosticSnapshot(snapshot)
            DiagnosticZipWriter.writeLatestZip(zip, snapshot)
            val rows = ZipFile(zip).use { archive ->
                val text = archive.getInputStream(archive.getEntry("trips_telegram_evidence.txt")).bufferedReader().use { it.readText() }
                assertFalse(text.contains("private-"))
                text.lineSequence().filter { it.startsWith("recent_closed_trip=") }.map { JSONObject(it.substringAfter('=')) }.toList()
            }
            val starts = (11 downTo 2).map { java.time.Instant.parse("2026-11-18T00:00:00Z").plusSeconds(it * 3600L).toString() }
            assertEquals(starts, rows.map { it.getString("started_at") })
            val first = rows.first()
            assertEquals(0.0, first.getDouble("discharged_kwh"))
            assertEquals(0.25, first.getDouble("regenerated_kwh"))
            assertEquals(-0.25, first.getDouble("net_kwh"))
            assertEquals(550_000L, first.getLong("energy_covered_ms"))
            assertEquals(50_000L, first.getLong("energy_uncovered_ms"))
            assertTrue(first.getBoolean("energy_partial"))
            assertEquals("trip_energy_reset,trip_energy_incomplete", first.getString("quality"))
            assertTrue(rows[1].has("net_kwh") && rows[1].isNull("net_kwh"))
            val active = context.getDatabasePath(name)
            val journal = File(active.path + ".compression-journal")
            try {
                journal.writeText("PREPARED")
                assertEquals(listOf("recent_closed_status=compression_recovery_pending"),
                    DiagnosticLogRecorder.recentClosedTripEvidence(active))
                assertEquals("PREPARED", journal.readText())
            } finally { journal.delete() }
        } finally { context.deleteDatabase(name); snapshot.deleteRecursively(); zip.delete() }
    }

    @Test fun hierarchyKeepsStationaryHistoryAndIncludesItsEnergyInGroupedConsumption() {
        val context = RuntimeEnvironment.getApplication()
        val name = "stationary_history.db"
        val stationary = TripSession("parked", TripSession.STATE_CLOSED, "2026-11-17T10:00:00Z",
            endedAt = "2026-11-17T11:00:00Z", distanceKm = 0.0, durationMs = 3_600_000,
            startSoc = 75.0, endSoc = 75.0, dischargedKwh = 1.0, regeneratedKwh = 0.0,
            netKwh = 1.0, energyPartial = false)
        try {
            TripStore(TripDatabaseHelper(context, name)).use { it.upsertSession(stationary) }
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                val before = store.queryHierarchy()
                assertEquals(listOf("parked"), before.flatMap { it.trips }.map { it.tripId })
                val pure = com.bydcollector.collector.ui.compose.TripsUiMapper.years(before,
                    com.bydcollector.collector.ui.compose.UiLanguage.UK).single()
                assertEquals(1.0, pure.netKwh)
                assertNull(pure.averageConsumptionKwhPer100Km)
                store.upsertSession(stationary.copy(tripId = "drive", movementObserved = true,
                    distanceKm = 10.0, dischargedKwh = 2.0, netKwh = 2.0))
                store.upsertSession(stationary.copy(tripId = "unfinished", state = TripSession.STATE_OPEN, endedAt = null))
                val groups = store.queryHierarchy()
                assertEquals(setOf("parked", "drive"), groups.flatMap { it.trips }.map { it.tripId }.toSet())
                listOf(com.bydcollector.collector.ui.compose.UiLanguage.UK,
                    com.bydcollector.collector.ui.compose.UiLanguage.EN).forEach { language ->
                    val year = com.bydcollector.collector.ui.compose.TripsUiMapper.years(groups, language).single()
                    assertEquals(3.0, year.netKwh)
                    assertEquals(30.0, year.averageConsumptionKwhPer100Km)
                    assertEquals(30.0, year.months.single().days.single().averageConsumptionKwhPer100Km)
                    assertEquals(0.0, year.months.single().days.single().trips.single { it.id == "parked" }.distanceKm)
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun routePowerUsesTheIntervalAtTheFixAndStopsAtActualLoss() {
        var history = RoutePowerHistory().observe("boot", 0L, 20.0, true)
        assertEquals(20.0, history.powerAt("boot", 60_000L))
        history = history.observe("boot", 61_000L, -10.0, true)
        val sample = com.bydcollector.collector.location.GpsLocationSample(
            "2026-11-17T08:00:00Z", 1_800_000_000_000L, 60_000_000_000L,
            "boot", "segment", 50.0, 30.0, 5.0, 10.0, null, null,
            receiveElapsedRealtimeNanos = 62_000_000_000L)
        val point = TripMetrics.routePoint("trip", 1L, sample,
            history.powerAt(sample.bootId, sample.elapsedRealtimeNanos / 1_000_000L))
        assertEquals(20.0 * 100.0 / 36.0, point.instantaneousConsumptionKwhPer100Km!!, 1e-9)
        history = history.observe("boot", 62_000L, null, false)
        assertEquals(-10.0, history.powerAt("boot", 61_500L))
        assertNull(history.powerAt("boot", 62_000L))
        assertNull(history.powerAt("other-boot", 61_500L))
        assertNull(TripMetrics.instantaneousConsumptionKwhPer100Km(20.0, 0.0))
        assertNull(RoutePowerHistory().observe("boot", 0L, 20.0, false).powerAt("boot", 2_001L))
    }

    @Test
    fun pageClosingAnExistingSessionAndStartingAnotherKeepsBothFinalIntegrals() {
        val context = RuntimeEnvironment.getApplication()
        val name = "multi_session_energy_${System.nanoTime()}.db"
        var finalA: EnergySnapshot? = null
        var finalB: EnergySnapshot? = null
        fun receipt(id: Long, on: Boolean) = EnergyReceipt(EnergyReceipt.businessIdentity("multi", id),
            java.time.Instant.ofEpochMilli(1_793_000_000_000L + id * 1_000).toString(),
            EnergyInput("boot", id * 1_000, 400.0, 100.0, on, true, false),
            businessGeneration = "multi", businessInputId = id)
        try {
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                val direct = EnergySessionCoordinator(store)
                val startA = assertNotNull(direct.process(receipt(1, true)).snapshot)
                assertTrue(direct.confirmProjected(startA.snapshotId))
                val a = TripSession(tripId = startA.powerSessionId!!, startedAt = startA.startedAt!!).withEnergySnapshot(startA)
                store.upsertSession(a)
                val priorA = assertNotNull(direct.process(receipt(2, true)).snapshot)
                assertTrue(direct.confirmProjected(priorA.snapshotId))
                store.commitBusinessPage {
                    val buffered = BufferedEnergyStorage(store.readEnergyRuntimeRow())
                    val energy = EnergySessionCoordinator(buffered)
                    val entries = listOf(3L to false, 4L to true, 5L to true).map { (id, on) ->
                        val snapshot = assertNotNull(energy.process(receipt(id, on)).snapshot)
                        assertTrue(energy.confirmProjected(snapshot.snapshotId))
                        if (id == 3L) {
                            finalA = snapshot
                            val closed = a.withEnergySnapshot(snapshot).copy(state = TripSession.STATE_CLOSED,
                                endedAt = snapshot.observedAt)
                            store.closeSessionWithCompletion(closed, null)
                            assertEquals(snapshot.dischargedKwh, store.session(a.tripId)?.dischargedKwh)
                        } else {
                            finalB = snapshot
                            store.upsertSession(TripSession(tripId = snapshot.powerSessionId!!,
                                startedAt = snapshot.startedAt!!).withEnergySnapshot(snapshot))
                        }
                        BusinessPageEntry(id, "source-$id", snapshot, true, null)
                    }
                    buffered.commitTo(store)
                    BusinessPage("multi", "boot", entries)
                }
            }
            TripStore(TripDatabaseHelper(context, name)).use { store ->
                val a = assertNotNull(finalA)
                val b = assertNotNull(finalB)
                assertEquals(80.0 / 3600, a.dischargedKwh!!, 1e-9)
                assertEquals(a.dischargedKwh, store.session(a.powerSessionId!!)?.dischargedKwh)
                assertEquals(a.energyCoveredMs, store.session(a.powerSessionId!!)?.energyCoveredMs)
                assertEquals(b.dischargedKwh, store.session(b.powerSessionId!!)?.dischargedKwh)
                assertEquals(b, EnergyStateCodec.decodeState(store.readEnergyRuntimeRow()!!.stateJson).currentSnapshot)
                val aId = requireNotNull(a.powerSessionId)
                val bId = requireNotNull(b.powerSessionId)
                val retained = assertNotNull(store.energyAtBoundary(aId, "boot", 2_500L, 3_000L))
                assertEquals(60.0 / 3600, assertNotNull(retained.dischargedKwh), 1e-9)
                assertNull(store.energyAtBoundary(bId, "boot", 2_500L, 3_000L))
                val candidateName = "energy-copy.db"
                try {
                    TripStore(TripDatabaseHelper(context, candidateName)).use { candidate ->
                        candidate.syncEnergyBoundariesFrom(store) {}
                        assertEquals(retained, candidate.energyAtBoundary(aId, "boot", 2_500L, 3_000L))
                        store.acknowledgeBusinessPage(assertNotNull(store.pendingBusinessPage()).key)
                        candidate.syncEnergyBoundariesFrom(store) {}
                        assertNull(candidate.energyAtBoundary(aId, "boot", 2_500L, 3_000L))
                        assertNotNull(candidate.energyAtBoundary(bId, "boot", 4_500L, 5_000L))
                    }
                } finally { context.deleteDatabase(candidateName) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun offCursorCheckpointKeepsRuntimeProgressWithoutRewritingFinalTripEnergy() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "energy_mirror_${System.nanoTime()}.db"
        val helper = TripDatabaseHelper(context, databaseName)
        val store = TripStore(helper)
        val tripId = "power-session-test"
        val startedAt = "2026-10-26T10:00:00Z"
        try {
            store.upsertSession(TripSession(tripId = tripId, startedAt = startedAt))
            val coordinator = EnergySessionCoordinator(store) { EnergySessionSeed(tripId, startedAt) }

            fun process(identity: String, elapsedMs: Long, powerOn: Boolean) = coordinator.process(
                EnergyReceipt(
                    sourceIdentity = identity,
                    observedAt = "2026-10-26T10:00:${(elapsedMs / 1_000L).toString().padStart(2, '0')}Z",
                    input = EnergyInput(
                        bootId = "energy-mirror-test-boot",
                        elapsedMs = elapsedMs,
                        voltage = 400.0,
                        current = -25.0,
                        powerOn = powerOn,
                        gunDisconnected = true,
                        externalCharging = false
                    )
                )
            ).also { result ->
                result.pendingProjection?.let { pending ->
                    assertTrue(coordinator.confirmProjected(pending.snapshot.snapshotId))
                }
            }

            process("power-on-1", 100L, true)
            process("power-on-2", 1_100L, true)
            val completed = assertNotNull(process("power-off", 2_100L, false).snapshot)
            assertFalse(completed.active)
            assertEquals(tripId, completed.powerSessionId)
            assertTrue(completed.energyCoveredMs > 0L)

            val db = helper.writableDatabase
            db.execSQL("CREATE TABLE trip_session_update_count (count INTEGER NOT NULL)")
            db.execSQL("INSERT INTO trip_session_update_count VALUES (0)")
            db.execSQL(
                "CREATE TRIGGER count_trip_session_update AFTER UPDATE ON trip_sessions " +
                    "BEGIN UPDATE trip_session_update_count SET count = count + 1; END"
            )

            val cursorOnly = process("power-off-cursor", 3_100L, false)
            assertEquals(completed, cursorOnly.snapshot)
            assertNull(cursorOnly.pendingProjection)

            val checkpoint = assertNotNull(store.readEnergyRuntimeRow())
            assertEquals("power-off-cursor", EnergyStateCodec.decodeState(checkpoint.stateJson).lastSourceIdentity)
            assertNull(checkpoint.pendingProjectionJson)
            assertEquals("2026-10-26T10:00:03Z", checkpoint.updatedAt)
            val mirrorUpdates = db.rawQuery("SELECT count FROM trip_session_update_count", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals(0, mirrorUpdates)
            assertEquals(completed.observedAt, store.allSessions().single().energyObservedAt)
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }
}
