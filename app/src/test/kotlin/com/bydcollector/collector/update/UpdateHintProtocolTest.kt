package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateHintProtocolTest {
    @Test
    fun `wire v1 names codes fields and owner order remain exact`() {
        assertEquals("com.byd.apps.updatehint.COORDINATION", UpdateHintProtocol.ACTION)
        assertEquals("com.byd.apps.updatehint.PROTOCOL_VERSION",
            UpdateHintProtocol.METADATA_PROTOCOL_VERSION)
        assertEquals(listOf(1, 1, 2, 3), listOf(UpdateHintProtocol.VERSION,
            UpdateHintProtocol.SUBSCRIBE, UpdateHintProtocol.STATE, UpdateHintProtocol.UNSUBSCRIBE))
        assertEquals(listOf("NONE", "PENDING", "VISIBLE"), UpdateHintPhase.entries.map { it.name })
        assertEquals(listOf("com.bydhud.app", "com.byd.extend", "com.bydcollector.collector"),
            UpdateHintProtocol.OWNERS)
        assertEquals(listOf(
            "protocolVersion", "ownerPackage", "processSessionId", "revision", "eventId",
            "phase", "requestedAtElapsedNanos", "expiresAtElapsedMs", "displayId",
            "preferredSizePercent", "preferredWidthPx", "preferredHeightPx"
        ), listOf(
            UpdateHintProtocol.KEY_PROTOCOL_VERSION, UpdateHintProtocol.KEY_OWNER_PACKAGE,
            UpdateHintProtocol.KEY_PROCESS_SESSION_ID, UpdateHintProtocol.KEY_REVISION,
            UpdateHintProtocol.KEY_EVENT_ID, UpdateHintProtocol.KEY_PHASE,
            UpdateHintProtocol.KEY_REQUESTED_AT_NANOS, UpdateHintProtocol.KEY_EXPIRES_AT_MS,
            UpdateHintProtocol.KEY_DISPLAY_ID, UpdateHintProtocol.KEY_PREFERRED_SIZE_PERCENT,
            UpdateHintProtocol.KEY_PREFERRED_WIDTH_PX, UpdateHintProtocol.KEY_PREFERRED_HEIGHT_PX
        ))
    }

    @Test
    fun `initial exchange budget is one bounded five hundred millisecond window`() {
        val requested = 1_000_000_000L
        assertEquals(500L, UpdateHintProtocol.initialExchangeDelayMs(requested, requested))
        assertEquals(300L, UpdateHintProtocol.initialExchangeDelayMs(
            requested, requested + 200_000_000L))
        assertEquals(0L, UpdateHintProtocol.initialExchangeDelayMs(
            requested, requested + 500_000_001L))
    }

    @Test
    fun `records reject pending deadlines and unbounded identifiers or geometry`() {
        val pending = record()
        assertTrue(pending.isValid())
        assertFalse(pending.isActive(nowElapsedMs = 0,
            nowElapsedNanos = pending.requestedAtElapsedNanos - 1))
        assertTrue(pending.isActive(nowElapsedMs = 0,
            nowElapsedNanos = pending.requestedAtElapsedNanos))
        assertFalse(pending.copy(expiresAtElapsedMs = 1L).isValid())
        assertFalse(pending.copy(processSessionId = "s".repeat(257)).isValid())
        assertFalse(pending.copy(eventId = "e".repeat(257)).isValid())
        assertFalse(pending.copy(preferredSizePercent = 1_001).isValid())
        assertFalse(pending.copy(preferredWidthPx = 100_001).isValid())
        assertFalse(pending.copy(preferredHeightPx = Int.MAX_VALUE).isValid())
        assertTrue(pending.copy(phase = UpdateHintPhase.VISIBLE,
            expiresAtElapsedMs = 10_000L).isValid())
    }

    private fun record() = UpdateHintRecord(
        ownerPackage = UpdateHintProtocol.OWNER_COLLECTOR,
        processSessionId = "session",
        revision = 1,
        eventId = "event",
        phase = UpdateHintPhase.PENDING,
        requestedAtElapsedNanos = 1,
        expiresAtElapsedMs = 0,
        displayId = 0,
        preferredSizePercent = 100,
        preferredWidthPx = 440,
        preferredHeightPx = 180
    )
}
