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
