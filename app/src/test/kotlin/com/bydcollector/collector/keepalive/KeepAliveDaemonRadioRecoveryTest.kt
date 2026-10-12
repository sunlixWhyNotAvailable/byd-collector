package com.bydcollector.collector.keepalive

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.ArrayDeque
import java.util.function.BiFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeepAliveDaemonRadioRecoveryTest {
    @Test fun eachRadioUsesFreshPowerBeforeItsEnableRequest() {
        val commands = mutableListOf<String>()
        val powerReplies = ArrayDeque(listOf(power("00000000"), power("00000002"), power("00000002"), power("00000000")))
        val commandRunner = runner(commands, powerReplies)
        var wifiReads = 0
        var mobileDataReads = 0

        repeat(2) {
            KeepAliveDaemon.runOnce(true, true, false, false,
                { wifiReads++; null }, { mobileDataReads++; false }, {}, commandRunner)
        }

        assertEquals(2, wifiReads)
        assertEquals(2, mobileDataReads)
        assertEquals(
            listOf(POWER, "svc wifi enable", POWER, POWER, POWER, "svc data enable"),
            commands
        )
    }

    @Test fun vehicleOnOrUnavailablePowerNeverIssuesAnEnableCommand() {
        val commands = mutableListOf<String>()
        val powerReplies = ArrayDeque(listOf(
            power("00000002"),
            result(true, "not a parcel"),
            result(false, powerOutput("00000000"), "exit_code=1"),
            result(false, "", "timeout"),
            power("FFFFFFFF")
        ))
        val output = captureOutput {
            repeat(powerReplies.size) {
                KeepAliveDaemon.runOnce(true, false, false, false,
                    { false }, { false }, {}, runner(commands, powerReplies))
            }
        }

        assertEquals(List(5) { POWER }, commands)
        assertTrue(output.contains("wifi_enable_skipped_vehicle_on"))
        assertEquals(4, Regex("wifi_vehicle_power_state_unavailable").findAll(output).count())
    }

    @Test fun disabledToggleAndAlreadyEnabledRadioSkipCommandsButServiceRecoveryRuns() {
        val commands = mutableListOf<String>()
        val powerReplies = ArrayDeque(listOf(power("00000002")))
        var wifiReads = 0
        var mobileDataReads = 0
        var serviceRecoveries = 0
        val commandRunner = runner(commands, powerReplies)

        KeepAliveDaemon.runOnce(false, false, false, true,
            { wifiReads++; false }, { mobileDataReads++; false }, { serviceRecoveries++ }, commandRunner)
        KeepAliveDaemon.runOnce(true, false, false, true,
            { wifiReads++; false }, { mobileDataReads++; false }, { serviceRecoveries++ }, commandRunner)
        KeepAliveDaemon.runOnce(true, false, false, false,
            { wifiReads++; true }, { mobileDataReads++; false }, { serviceRecoveries++ }, commandRunner)

        assertEquals(2, wifiReads)
        assertEquals(0, mobileDataReads)
        assertEquals(2, serviceRecoveries)
        assertEquals(listOf(POWER), commands)
    }

    private fun runner(
        commands: MutableList<String>,
        powerReplies: ArrayDeque<KeepAliveDaemon.ShellResult>
    ) = BiFunction<String, Long, KeepAliveDaemon.ShellResult> { command, _ ->
        commands += command
        if (command == POWER) powerReplies.removeFirst() else result(true, "")
    }

    private fun power(raw: String) = result(true, powerOutput(raw))

    private fun powerOutput(raw: String) = "Result: Parcel(00000000 $raw '........')"

    private fun result(ok: Boolean, output: String, error: String = "") =
        KeepAliveDaemon.ShellResult(ok, output, error, 1L)

    private fun captureOutput(block: () -> Unit): String {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(bytes, true, Charsets.UTF_8.name()))
            block()
        } finally {
            System.setOut(original)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val POWER = "service call autoservice 5 i32 1001 i32 315621418"
    }
}
