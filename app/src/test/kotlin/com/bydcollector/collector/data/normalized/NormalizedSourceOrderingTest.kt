package com.bydcollector.collector.data.normalized

import kotlin.test.Test
import kotlin.test.assertEquals

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
