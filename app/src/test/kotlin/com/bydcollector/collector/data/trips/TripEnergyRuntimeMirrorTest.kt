package com.bydcollector.collector.data.trips

import android.app.Application
import com.bydcollector.collector.data.energy.EnergyInput
import com.bydcollector.collector.data.energy.EnergyReceipt
import com.bydcollector.collector.data.energy.EnergySessionCoordinator
import com.bydcollector.collector.data.energy.EnergySessionSeed
import com.bydcollector.collector.data.energy.EnergyStateCodec
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
