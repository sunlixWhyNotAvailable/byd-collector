package com.bydcollector.collector

import android.content.Context
import com.bydcollector.collector.data.trips.TripBusinessInputCursor
import com.bydcollector.collector.data.trips.TripBusinessInputMutation
import com.bydcollector.collector.data.trips.TripCompression
import com.bydcollector.collector.data.trips.TripCompressionPhase
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.data.trips.TripStore
import java.util.UUID

/** Android directory fsync and SQLite cutover are exercised on a real emulator filesystem. */
internal object BusinessInputCompressionGate {
    fun run(context: Context): String {
        val name = "business_cursor_${UUID.randomUUID()}.db"
        val store = TripStore(TripDatabaseHelper(context, name))
        try {
            store.consumeBusinessInput("generation-a", 1L, "source-1") {
                TripBusinessInputMutation(Unit, null)
            }
            val result = TripCompression(context, store, onPhase = { phase ->
                if (phase == TripCompressionPhase.CANDIDATE_BUILT) {
                    store.consumeBusinessInput("generation-a", 2L, "source-2") {
                        TripBusinessInputMutation(Unit, "trip-2")
                    }
                }
            }).run()
            check(result.afterBytes > 0L)
            check(store.businessInputCursor() ==
                TripBusinessInputCursor("generation-a", 2L, "source-2", 0L, 0L, "trip-2"))
            return "BUSINESS_COMPRESSION_GATE_PASS"
        } finally {
            store.close()
            check(context.deleteDatabase(name)) { "Could not remove owned test database $name" }
        }
    }
}
