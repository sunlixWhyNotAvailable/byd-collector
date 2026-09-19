package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.trips.TripCompletionIntent

/** Replays only the frontier captured by the Trips owner before a later semantic input. */
internal fun <T> handoffTripCompletions(
    frontier: Long,
    pending: () -> List<TripCompletionIntent>,
    accept: (TripCompletionIntent) -> T,
    acknowledge: (TripCompletionIntent) -> Boolean,
    afterAck: (T) -> Unit
) {
    while (true) {
        val batch = pending()
        if (batch.isEmpty()) return
        for (intent in batch) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            check(intent.sequence <= frontier) { "Completion exceeds captured Trips frontier" }
            val accepted = accept(intent)
            check(acknowledge(intent)) { "Trip completion ACK was not committed" }
            afterAck(accepted)
        }
    }
}
