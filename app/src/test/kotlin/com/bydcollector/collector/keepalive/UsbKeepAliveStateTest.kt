package com.bydcollector.collector.keepalive

import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsbKeepAliveStateTest {
    @Test
    fun persistsReservationAndStartedStateAcrossReopen() = withStateFile { file ->
        val state = UsbKeepAliveState.open(file)
        assertTrue(state.observeBoot(BOOT_A))
        assertEquals("first_enable_off", state.eligibleTrigger(BOOT_A))
        val reservation = state.reserveAttempt(BOOT_A, 0, 0, POWER_OFF, ACC_OFF, 100, 50)
        assertNotNull(reservation)
        assertEquals("RESERVED", UsbKeepAliveState.open(file).snapshot().lastStatus)
        assertNull(UsbKeepAliveState.open(file).eligibleTrigger(BOOT_A))

        assertTrue(state.markStarted(reservation, 110, 60))
        val reopened = UsbKeepAliveState.open(file)
        assertEquals("STARTED", reopened.snapshot().lastStatus)
        assertEquals(1L, reopened.snapshot().attemptCount)
        assertNull(reopened.eligibleTrigger(BOOT_A))
    }

    @Test
    fun newBootAndObservedAccCycleEachPermitOnlyOneFreshReservation() = withStateFile { file ->
        val state = UsbKeepAliveState.open(file)
        assertTrue(state.observeBoot(BOOT_A))
        val first = state.reserveAttempt(BOOT_A, 0, 0, POWER_OFF, ACC_OFF, 100, 50)
        assertNotNull(first)
        assertTrue(state.markStarted(first, 101, 51))

        assertTrue(state.observeBoot(BOOT_B))
        assertEquals("new_boot", state.eligibleTrigger(BOOT_B))
        val afterBoot = state.reserveAttempt(BOOT_B, 0, 0, POWER_OFF, ACC_OFF, 200, 100)
        assertNotNull(afterBoot)
        assertEquals("new_boot", afterBoot.trigger)
        assertNull(state.eligibleTrigger(BOOT_B))
        assertTrue(state.markStarted(afterBoot, 201, 101))

        assertTrue(state.observeAcc(1))
        assertFalse(state.observeAcc(-1))
        assertNull(state.eligibleTrigger(BOOT_B))
        assertTrue(state.observeAcc(0))
        assertEquals(1L, state.snapshot().cycleCount)
        assertEquals("acc_cycle", state.eligibleTrigger(BOOT_B))
        val afterCycle = state.reserveAttempt(BOOT_B, 0, 0, POWER_OFF, ACC_OFF, 300, 150)
        assertTrue(afterCycle != null)
        assertEquals("acc_cycle", afterCycle.trigger)
        assertEquals(3L, state.snapshot().reservationCount)
        assertEquals(2L, state.snapshot().attemptCount)
        assertNull(state.eligibleTrigger(BOOT_B))
    }

    @Test
    fun invalidTelemetryCannotReserveOrCreateAnAccCycle() = withStateFile { file ->
        val state = UsbKeepAliveState.open(file)
        assertTrue(state.observeBoot(BOOT_A))
        val initial = state.reserveAttempt(BOOT_A, 0, 0, POWER_OFF, ACC_OFF, 100, 50)
        assertTrue(initial != null)
        assertTrue(state.markStarted(initial, 101, 51))

        assertFalse(state.observeAcc(-1))
        assertFalse(state.observeAcc(2))
        assertNull(state.reserveAttempt(BOOT_A, -1, 0, "", ACC_OFF, 200, 100))
        assertNull(state.reserveAttempt(BOOT_A, 0, -1, POWER_OFF, "", 200, 100))
        assertNull(state.eligibleTrigger(BOOT_A))
        assertEquals(0L, state.snapshot().cycleCount)
        assertEquals(1L, state.snapshot().reservationCount)
    }

    @Test
    fun corruptHistoryWaitsForProvableNewCycleOrBoot() = withStateFile { file ->
        val initialized = UsbKeepAliveState.open(file)
        assertTrue(initialized.observeBoot(BOOT_A))
        val validPrefix = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        Files.write(file.toPath(), (validPrefix + "trailing-data") .toByteArray(Charsets.UTF_8))

        val corrupt = UsbKeepAliveState.open(file)
        assertFalse(corrupt.snapshot().usable)
        assertContains(corrupt.snapshot().error, "stage=usb_state_read")
        assertContains(corrupt.snapshot().error, "org.json.JSONException")
        assertContains(corrupt.snapshot().error, "state_json_invalid")
        assertTrue(corrupt.observeBoot(BOOT_A))
        assertEquals(0L, corrupt.snapshot().reservationCount)
        assertEquals(0L, corrupt.snapshot().attemptCount)
        assertTrue(corrupt.snapshot().historyRecovered)
        assertEquals("history_blocked", JSONObject(
            String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        ).getString("initial_trigger"))
        assertNull(corrupt.eligibleTrigger(BOOT_A))
        assertNull(corrupt.reserveAttempt(BOOT_A, 0, 0, POWER_OFF, ACC_OFF, 100, 50))

        val reopened = UsbKeepAliveState.open(file)
        assertTrue(reopened.snapshot().usable)
        assertNull(reopened.eligibleTrigger(BOOT_A))
        assertTrue(reopened.observeAcc(0))
        assertNull(reopened.eligibleTrigger(BOOT_A))
        assertTrue(reopened.observeAcc(1))
        assertTrue(reopened.observeAcc(0))
        assertEquals("acc_cycle", reopened.eligibleTrigger(BOOT_A))
    }

    @Test
    fun corruptBootUuidCannotPermitSameBootRetry() = withStateFile { file ->
        val initialized = UsbKeepAliveState.open(file)
        assertTrue(initialized.observeBoot(BOOT_A))
        val priorAttempt = initialized.reserveAttempt(BOOT_A, 0, 0, POWER_OFF, ACC_OFF, 100, 50)
        assertNotNull(priorAttempt)
        assertTrue(initialized.markStarted(priorAttempt, 101, 51))
        val invalidHistory = JSONObject(
            String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        ).put("boot_id", BOOT_B).put("cycle_count", -1).toString()
        Files.write(file.toPath(), invalidHistory.toByteArray(Charsets.UTF_8))

        val corrupt = UsbKeepAliveState.open(file)
        assertFalse(corrupt.snapshot().usable)
        assertTrue(corrupt.observeBoot(BOOT_A))
        assertEquals(BOOT_A, corrupt.snapshot().bootId)
        assertEquals(0L, corrupt.snapshot().reservationCount)
        assertEquals(0L, corrupt.snapshot().attemptCount)
        assertNull(corrupt.eligibleTrigger(BOOT_A))
        assertNull(corrupt.reserveAttempt(BOOT_A, 0, 0, POWER_OFF, ACC_OFF, 200, 100))

        val reopened = UsbKeepAliveState.open(file)
        assertTrue(reopened.snapshot().usable)
        assertNull(reopened.eligibleTrigger(BOOT_A))
        assertTrue(reopened.observeBoot(BOOT_B))
        assertEquals("new_boot", reopened.eligibleTrigger(BOOT_B))
    }

    @Test
    fun oversizedHistoryBlocksUntilCurrentBootIsAnchoredThenReboot() = withStateFile { file ->
        val initialized = UsbKeepAliveState.open(file)
        assertTrue(initialized.observeBoot(BOOT_A))
        val oversizedWithOtherBoot = JSONObject(
            String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        ).put("boot_id", BOOT_B).toString().toByteArray(Charsets.UTF_8) + ByteArray(20_000) { 'x'.code.toByte() }
        Files.write(file.toPath(), oversizedWithOtherBoot)

        val corrupt = UsbKeepAliveState.open(file)
        assertFalse(corrupt.snapshot().usable)
        assertTrue(corrupt.observeBoot(BOOT_A))
        assertTrue(corrupt.snapshot().usable)
        assertEquals(BOOT_A, corrupt.snapshot().bootId)
        assertNull(corrupt.eligibleTrigger(BOOT_A))

        val reopened = UsbKeepAliveState.open(file)
        assertTrue(reopened.snapshot().usable)
        assertNull(reopened.eligibleTrigger(BOOT_A))
        assertTrue(reopened.observeBoot(BOOT_B))
        assertEquals("new_boot", reopened.eligibleTrigger(BOOT_B))
    }

    private fun withStateFile(test: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("usb-keepalive-state-test").toFile()
        try {
            test(directory.resolve("state.json"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val BOOT_A = "11111111-1111-1111-1111-111111111111"
        const val BOOT_B = "22222222-2222-2222-2222-222222222222"
        const val POWER_OFF = "Result: Parcel(00000000 00000000 '........')"
        const val ACC_OFF = "Result: Parcel(00000000 00000000 '........')"
    }
}
