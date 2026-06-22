package com.bydcollector.collector.data.local

import android.os.SystemClock
import java.time.OffsetDateTime
import java.time.ZoneId

class SystemClockAdapter : Clock {
    override fun nowIso(): String = OffsetDateTime.now(ZoneId.systemDefault()).toString()

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
