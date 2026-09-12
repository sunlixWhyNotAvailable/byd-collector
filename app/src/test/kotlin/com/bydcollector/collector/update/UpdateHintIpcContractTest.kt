package com.bydcollector.collector.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateHintIpcContractTest {
    @Test
    fun `wire is exact twelve-field Messenger v1 contract`() {
        val protocol = source("UpdateHintProtocol.kt")
        val coordinator = source("UpdateHintCoordinator.kt")
        val keys = Regex("const val KEY_[A-Z_]+ = \"([^\"]+)\"")
            .findAll(protocol).map { it.groupValues[1] }.toList()

        assertEquals(listOf(
            "protocolVersion", "ownerPackage", "processSessionId", "revision", "eventId", "phase",
            "requestedAtElapsedNanos", "expiresAtElapsedMs", "displayId", "preferredSizePercent",
            "preferredWidthPx", "preferredHeightPx"
        ), keys)
        assertTrue(protocol.contains("const val ACTION = \"com.byd.apps.updatehint.COORDINATION\""))
        assertTrue(protocol.contains("const val VERSION = 1"))
        assertTrue(protocol.contains("const val SUBSCRIBE = 1"))
        assertTrue(protocol.contains("const val STATE = 2"))
        assertTrue(protocol.contains("const val UNSUBSCRIBE = 3"))
        assertTrue(coordinator.contains("recordValues(data.keySet().associateWith { data[it] })"))
        assertTrue(coordinator.contains("getPackagesForUid(sendingUid)"))
        assertFalse(coordinator.contains("checkSignatures"))
    }

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

        assertEquals(12, values.size)
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

    @Test
    fun `coordination stays bounded and distinguishes transient failure from death`() {
        val coordinator = source("UpdateHintCoordinator.kt")
        val service = source("UpdateHintCoordinationService.kt")

        assertTrue(coordinator.contains("initialExchangeDelayMs("))
        assertTrue(coordinator.contains("onServiceDisconnected(name: ComponentName)"))
        assertTrue(coordinator.contains("remote = null"))
        val bindingDied = coordinator.substringAfter("override fun onBindingDied(name: ComponentName)")
            .substringBefore("override fun onNullBinding")
        assertTrue(bindingDied.contains("disconnect(owner, sendUnsubscribe = false)"))
        assertTrue(bindingDied.contains("discoverPeer(owner, initial = !admitted)"))
        assertFalse(bindingDied.contains("binderDied"))
        assertTrue(coordinator.contains("catch (_: DeadObjectException)"))
        assertTrue(coordinator.contains("catch (_: RemoteException)"))
        assertTrue(coordinator.contains("if (engine.current(record.ownerPackage)?.processSessionId != record.processSessionId) return true"))
        val send = service.substringAfter("private fun send(reply:").substringBefore("private fun journal")
        assertTrue(send.contains("catch (_: DeadObjectException)"))
        assertTrue(send.contains("SendResult.DEAD"))
        assertTrue(send.contains("catch (_: RemoteException)"))
        assertTrue(send.contains("SendResult.TRANSIENT_FAILURE"))
        assertFalse(send.contains("confirmedDeath("))
        assertTrue(coordinator.contains("Context.RECEIVER_EXPORTED"))
        assertTrue(coordinator.contains("failure?.invoke(reason)"))
        val inbound = coordinator.substringAfter("internal fun acceptFromService(")
            .substringBefore("internal fun confirmedDeath(")
        val acceptedSessionGuard = inbound.indexOf("if (engine.current(authenticatedOwner)?.processSessionId != record.processSessionId) return false")
        val completeInitial = inbound.indexOf("waitingForInitial.remove(authenticatedOwner)")
        assertTrue(acceptedSessionGuard >= 0 && completeInitial > acceptedSessionGuard)
        assertTrue(inbound.indexOf("maybeAdmit()") > completeInitial)
        assertFalse((coordinator + service).contains("updateRuntime"))
    }

    private fun source(name: String): String = projectFile(
        "src/main/kotlin/com/bydcollector/collector/update/$name").readText()

    private fun projectFile(relative: String): File = listOf(File(relative), File("app/$relative"))
        .firstOrNull(File::isFile) ?: error("Missing source: $relative")
}
