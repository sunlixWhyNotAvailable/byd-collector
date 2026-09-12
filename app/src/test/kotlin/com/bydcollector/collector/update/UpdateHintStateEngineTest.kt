package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateHintStateEngineTest {
    @Test
    fun `exact request ties sort HUD then Extend then Collector`() {
        val engine = UpdateHintStateEngine()
        listOf(UpdateHintProtocol.OWNER_COLLECTOR, UpdateHintProtocol.OWNER_EXTEND,
            UpdateHintProtocol.OWNER_HUD).forEachIndexed { index, owner ->
            assertTrue(engine.accept(owner, record(owner, owner, index.toLong(), requested = 10)))
        }
        assertEquals(UpdateHintProtocol.OWNERS,
            engine.active(nowElapsedMs = 0, nowElapsedNanos = 10).map { it.ownerPackage })
    }

    @Test
    fun `duplicates and out of order revisions cannot replace current state`() {
        val engine = UpdateHintStateEngine()
        val current = record(UpdateHintProtocol.OWNER_HUD, "hud", 4)
        assertTrue(engine.accept(current.ownerPackage, current))
        assertFalse(engine.accept(current.ownerPackage, current))
        assertFalse(engine.accept(current.ownerPackage, current.copy(revision = 3)))
        assertEquals(current, engine.current(current.ownerPackage))
    }

    @Test
    fun `same event request and visible deadline are immutable and visible cannot regress`() {
        val engine = UpdateHintStateEngine()
        val pending = record(UpdateHintProtocol.OWNER_COLLECTOR, "collector", 1)
        val visible = pending.copy(revision = 2, phase = UpdateHintPhase.VISIBLE,
            expiresAtElapsedMs = 20_000)
        assertTrue(engine.accept(pending.ownerPackage, pending))
        assertFalse(engine.accept(pending.ownerPackage,
            pending.copy(revision = 2, requestedAtElapsedNanos = pending.requestedAtElapsedNanos + 1)))
        assertTrue(engine.accept(visible.ownerPackage, visible))
        assertFalse(engine.accept(visible.ownerPackage, visible.copy(revision = 3,
            expiresAtElapsedMs = 20_001)))
        assertFalse(engine.accept(visible.ownerPackage, pending.copy(revision = 3)))
        assertEquals(visible, engine.current(visible.ownerPackage))
    }

    @Test
    fun `expired pending may still receive its first visible state`() {
        val engine = UpdateHintStateEngine()
        val pending = record(UpdateHintProtocol.OWNER_EXTEND, "extend", 1,
            requested = 1_000_000_000L)
        assertTrue(engine.accept(pending.ownerPackage, pending))
        assertTrue(engine.active(0, 1_500_000_001L).isEmpty())

        val visible = pending.copy(revision = 2, phase = UpdateHintPhase.VISIBLE,
            expiresAtElapsedMs = 20_000)
        assertTrue(engine.accept(visible.ownerPackage, visible))
        assertEquals(listOf(visible), engine.active(19_999, Long.MAX_VALUE))
    }

    @Test
    fun `none process restart and confirmed death retire stale state`() {
        val engine = UpdateHintStateEngine()
        val old = record(UpdateHintProtocol.OWNER_EXTEND, "old", 1)
        assertTrue(engine.accept(old.ownerPackage, old))
        assertTrue(engine.accept(old.ownerPackage, old.copy(revision = 2,
            eventId = "different-event", phase = UpdateHintPhase.NONE)))
        assertFalse(engine.accept(old.ownerPackage, old.copy(revision = 3)))
        assertTrue(engine.active(0, 1).isEmpty())

        val restarted = old.copy(processSessionId = "new", revision = 0, eventId = "new-event")
        assertTrue(engine.accept(restarted.ownerPackage, restarted))
        assertFalse(engine.accept(old.ownerPackage, old.copy(revision = 99)))
        assertTrue(engine.confirmedDeath(restarted.ownerPackage, "new"))
        assertFalse(engine.accept(restarted.ownerPackage, restarted.copy(revision = 1)))
        assertNull(engine.current(restarted.ownerPackage))
    }

    @Test
    fun `same event none may clear active fields and cannot be resurrected`() {
        for (phase in listOf(UpdateHintPhase.PENDING, UpdateHintPhase.VISIBLE)) {
            val engine = UpdateHintStateEngine()
            val active = record(UpdateHintProtocol.OWNER_HUD, "session", 1).copy(
                phase = phase, expiresAtElapsedMs = if (phase == UpdateHintPhase.VISIBLE) 20_000L else 0L)
            assertTrue(engine.accept(active.ownerPackage, active))
            assertEquals(listOf(active), engine.active(1_000, 1_000_000_000L))
            val none = active.copy(revision = 2, phase = UpdateHintPhase.NONE,
                requestedAtElapsedNanos = 0, expiresAtElapsedMs = 0,
                preferredSizePercent = 0, preferredWidthPx = 0, preferredHeightPx = 0)
            assertTrue(none.isValid())
            assertTrue(engine.accept(none.ownerPackage, none))
            assertTrue(engine.active(1_000, 1_000_000_000L).isEmpty())
            assertFalse(engine.accept(active.ownerPackage, active.copy(revision = 3)))
        }
    }

    @Test
    fun `new session initial none does not retire a reused old event id`() {
        val engine = UpdateHintStateEngine()
        val old = record(UpdateHintProtocol.OWNER_HUD, "old", 1)
        assertTrue(engine.accept(old.ownerPackage, old))
        val initialNone = old.copy(processSessionId = "new", revision = 0,
            eventId = "", phase = UpdateHintPhase.NONE)
        assertTrue(engine.accept(initialNone.ownerPackage, initialNone))
        assertTrue(engine.accept(old.ownerPackage, old.copy(processSessionId = "new", revision = 1)))
    }

    private fun record(
        owner: String,
        session: String,
        revision: Long,
        requested: Long = 1_000_000_000L
    ) = UpdateHintRecord(
        ownerPackage = owner,
        processSessionId = session,
        revision = revision,
        eventId = "$owner-event",
        phase = UpdateHintPhase.PENDING,
        requestedAtElapsedNanos = requested,
        expiresAtElapsedMs = 0,
        displayId = 0,
        preferredSizePercent = 100,
        preferredWidthPx = 440,
        preferredHeightPx = 180
    )
}
