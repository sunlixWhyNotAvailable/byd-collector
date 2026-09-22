package com.bydcollector.collector.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateHintIpcContractTest {

    @Test
    fun `wire values round trip and reject malformed payloads`() {
        val expected = UpdateHintRecord(
            ownerPackage = UpdateHintProtocol.OWNER_HUD,
            processSessionId = "hud-session",
            revision = 7,
            eventId = "hud-event",
            phase = UpdateHintPhase.VISIBLE,
            requestedAtElapsedNanos = 9_000_000,
            expiresAtElapsedMs = 12_345,
            displayId = 0,
            preferredSizePercent = 125,
            preferredWidthPx = 550,
            preferredHeightPx = 225
        )
        val values = UpdateHintWire.values(expected)

        assertEquals(setOf(
            "protocolVersion", "ownerPackage", "processSessionId", "revision", "eventId", "phase",
            "requestedAtElapsedNanos", "expiresAtElapsedMs", "displayId", "preferredSizePercent",
            "preferredWidthPx", "preferredHeightPx"
        ), values.keys)
        assertEquals(expected, UpdateHintWire.recordValues(values))
        assertTrue(values[UpdateHintProtocol.KEY_PROTOCOL_VERSION] is Int)
        assertTrue(values[UpdateHintProtocol.KEY_REVISION] is Long)
        assertTrue(values[UpdateHintProtocol.KEY_OWNER_PACKAGE] is String)
        assertNull(UpdateHintWire.recordValues(values - UpdateHintProtocol.KEY_EVENT_ID))
        assertNull(UpdateHintWire.recordValues(values + ("extra" to 1)))
        assertNull(UpdateHintWire.recordValues(values + (UpdateHintProtocol.KEY_REVISION to 7)))
        assertNull(UpdateHintWire.recordValues(values + (UpdateHintProtocol.KEY_PHASE to "BROKEN")))
        assertNull(UpdateHintWire.recordValues(values + (UpdateHintProtocol.KEY_PROTOCOL_VERSION to 2)))
    }

    @Test
    fun `uid packages must contain the claimed allowlisted owner`() {
        assertTrue(UpdateHintWire.authenticatedPackages(
            arrayOf("shared.uid.package", UpdateHintProtocol.OWNER_EXTEND), UpdateHintProtocol.OWNER_EXTEND))
        assertFalse(UpdateHintWire.authenticatedPackages(
            arrayOf(UpdateHintProtocol.OWNER_HUD), UpdateHintProtocol.OWNER_EXTEND))
        assertFalse(UpdateHintWire.authenticatedPackages(null, UpdateHintProtocol.OWNER_HUD))
        assertFalse(UpdateHintWire.authenticatedPackages(arrayOf("attacker"), "attacker"))
    }

    @Test
    fun `state and unsubscribe require the subscribed owner session pair`() {
        val identity = UpdateHintSubscriptionIdentity(UpdateHintProtocol.OWNER_HUD, "hud-session")
        val record = UpdateHintRecord(
            ownerPackage = UpdateHintProtocol.OWNER_HUD,
            processSessionId = "hud-session",
            revision = 1,
            eventId = "hud-event",
            phase = UpdateHintPhase.PENDING,
            requestedAtElapsedNanos = 1,
            expiresAtElapsedMs = 0,
            displayId = 0,
            preferredSizePercent = 100,
            preferredWidthPx = 440,
            preferredHeightPx = 180
        )

        assertTrue(identity.matches(record))
        assertFalse(identity.matches(record.copy(processSessionId = "stale-session")))
        assertFalse(identity.matches(record.copy(ownerPackage = UpdateHintProtocol.OWNER_EXTEND)))
    }

    @Test
    fun `manifest exposes protocol service and both peer package queries`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("com.bydcollector.collector.update.UpdateHintCoordinationService"))
        assertTrue(manifest.contains("android:name=\"com.byd.apps.updatehint.COORDINATION\""))
        assertTrue(manifest.contains("android:name=\"com.byd.apps.updatehint.PROTOCOL_VERSION\" android:value=\"1\""))
        assertTrue(manifest.contains("<package android:name=\"com.bydhud.app\""))
        assertTrue(manifest.contains("<package android:name=\"com.byd.extend\""))
    }

    private fun projectFile(relative: String): File = listOf(File(relative), File("app/$relative"))
        .firstOrNull(File::isFile) ?: error("Missing source: $relative")
}
