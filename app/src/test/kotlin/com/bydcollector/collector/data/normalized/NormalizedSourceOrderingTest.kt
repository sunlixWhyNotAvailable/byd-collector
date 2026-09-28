package com.bydcollector.collector.data.normalized

import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.direct.CallbackValueSource
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NormalizedSourceOrderingTest {
    @Test
    fun sameBootUsesElapsedTimeBeforeGeneratorSequence() {
        val previous = stamp("previous", "boot", "generator", 20, wall = 2_000, elapsed = 200)

        assertEquals(
            NormalizedSourceOrder.OLDER,
            NormalizedSourceOrdering.compare(
                stamp("incoming", "boot", "generator", 99, wall = 9_000, elapsed = 199),
                previous
            )
        )
        assertEquals(
            NormalizedSourceOrder.NEWER,
            NormalizedSourceOrdering.compare(
                stamp("incoming", "boot", "generator", 21, wall = 2_000, elapsed = 200),
                previous
            )
        )
    }

    @Test
    fun exactIdentityIsEqualAndNeverReorderedByClocks() {
        val previous = stamp("same", "boot", "generator", 20, wall = 2_000, elapsed = 200)
        val replay = stamp("same", "other-boot", "other", 1, wall = 9_000, elapsed = 900)

        assertEquals(NormalizedSourceOrder.EQUAL, NormalizedSourceOrdering.compare(replay, previous))
    }

    @Test
    fun crossBootFallsBackToStrictWallTimeWithoutLexicalTieBreak() {
        val previous = stamp("z-identity", "boot-b", null, null, wall = 2_000, elapsed = 800)

        assertEquals(
            NormalizedSourceOrder.OLDER,
            NormalizedSourceOrdering.compare(
                stamp("a-identity", "boot-a", null, null, wall = 1_999, elapsed = 9_000),
                previous
            )
        )
        assertEquals(
            NormalizedSourceOrder.INCOMPARABLE,
            NormalizedSourceOrdering.compare(
                stamp("a-identity", "boot-a", null, null, wall = 2_000, elapsed = 9_000),
                previous
            )
        )
    }

    @Test
    fun trustedCurrentBootWinsEvenWhenItsWallClockMovedBackward() {
        val old = stamp("old", "old-boot", null, null, wall = 9_000, elapsed = 800)
        val current = stamp("current", "current-boot", null, null, wall = 1_000, elapsed = 20)

        assertEquals(NormalizedSourceOrder.NEWER, NormalizedSourceOrdering.compare(current, old, "current-boot"))
        assertEquals(NormalizedSourceOrder.OLDER, NormalizedSourceOrdering.compare(old, current, "current-boot"))
        assertEquals(NormalizedSourceOrder.OLDER, NormalizedSourceOrdering.compare(
            stamp("old-replay", "old-boot", null, null, wall = 50_000, elapsed = 900),
            current,
            "current-boot"
        ))
    }

    @Test
    fun getterKeepsPollProvenanceWhileCachedIntAndFloatKeepOriginalAcquisition() {
        val timestamp = "2026-09-28T10:00:00+03:00"
        val poll = normalizedPollSourceStamp(PollSampleSource("poll:7", "boot", 700, "gen", 7), timestamp)
        assertEquals(Instant.parse("2026-09-28T07:00:00Z").toEpochMilli(), poll.wallMs)
        assertEquals(700L, poll.elapsedMs)
        for ((key, raw) in listOf("charging_1009_876609586_5" to 1, "charging_charge_current" to 10.25f.toRawBits())) {
            val entry = DirectFidRegistry.entries.single { it.key == key }
            val reading = PollReading(key, raw.toString(), "原始描述")
            assertEquals(NormalizedSourceInput(reading, poll, 19L), normalizedSourceInputForPollReading(reading, poll, 19L))
            val callback = CallbackValueSource("boot", "gen", 1, 7, 9, entry.dev, entry.fid,
                if (entry.tx == 5) TelemetryCallbackBatch.TYPE_INT else TelemetryCallbackBatch.TYPE_FLOAT,
                raw, 500, 50, null, "usable")
            val cached = reading.copy(callbackSource = callback)
            val first = assertNotNull(normalizedSourceInputForPollReading(cached, poll, 19L))
            val repeated = normalizedSourceInputForPollReading(cached, poll.copy(identity = "poll:8", elapsedMs = 800), 20L)
            assertEquals(first, repeated, "re-polling cache must not manufacture a fresh source")
            assertEquals("callback:4:boot:3:gen:1:7:9", first.stamp.identity)
            assertEquals(500L, first.stamp.wallMs)
            assertEquals(50L, first.stamp.elapsedMs)
            assertEquals(reading, first.reading)
            assertNull(first.sourcePollId)
            assertNotNull(cached.callbackSource, "conversion must not mutate stored raw input")
        }
    }

    @Test
    fun invalidCacheProvenanceCannotFallBackToFreshGetter() {
        val key = "charging_1009_876609586_5"
        val poll = stamp("poll", "boot", "gen", 1, 1000, 100)
        fun callback(dev: Int = 1009, fid: Int = 876609586, raw: Int = 1,
                     type: Int = TelemetryCallbackBatch.TYPE_INT, quality: String = "callback") =
            CallbackValueSource("boot", "gen", 1, 7, 9, dev, fid, type, raw, 500, 50, null, quality)
        assertNotNull(normalizedSourceInputForPollReading(PollReading(key, "1", callbackSource = callback()), poll))
        for (invalid in listOf(callback(dev = 1001), callback(fid = 42), callback(raw = 0),
            callback(type = TelemetryCallbackBatch.TYPE_FLOAT), callback(quality = "stale"))) {
            assertNull(normalizedSourceInputForPollReading(PollReading(key, "1", callbackSource = invalid), poll))
        }
        assertNull(normalizedSourceInputForPollReading(PollReading(key, null, callbackSource = callback()), poll))
        assertNull(normalizedSourceInputForPollReading(PollReading("unknown", "1"), poll))
        assertNotEquals(
            normalizedCallbackSourceStamp("a:b", "c", 1, 0, 1, 1, 1).identity,
            normalizedCallbackSourceStamp("a", "b:c", 1, 0, 1, 1, 1).identity
        )
    }

    private fun stamp(
        identity: String,
        boot: String,
        generator: String?,
        sequence: Long?,
        wall: Long,
        elapsed: Long
    ) = NormalizedSourceStamp(
        kind = NormalizedSourceKind.POLL,
        identity = identity,
        bootId = boot,
        generatorId = generator,
        sequence = sequence,
        wallMs = wall,
        elapsedMs = elapsed
    )
}
