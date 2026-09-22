package com.bydcollector.collector.keepalive

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeepAliveShellPlannerTest {
    @Test
    fun mirrorCommandsWriteAllFourGlobalFlagsAsZeroOrOne() {
        val commands = KeepAliveShellPlanner.mirrorSettingsCommands(
            KeepAliveConfig(
                keepWifi = true,
                keepMobileData = false,
                keepBluetooth = true,
                recoverCollectorService = false
            ),
            userShutdown = false
        )

        assertEquals(
            listOf(
                "settings put global bydcollector_keep_wifi 1",
                "settings put global bydcollector_keep_mobile_data 0",
                "settings put global bydcollector_keep_bluetooth 1",
                "settings put global bydcollector_recover_collector_service 0",
                "settings put global bydcollector_user_shutdown 0"
            ),
            commands
        )
    }

    @Test
    fun daemonMirrorsShutdownGateForShellRecovery() {
        val commands = KeepAliveShellPlanner.mirrorSettingsCommands(
            KeepAliveConfig(
                keepWifi = true,
                keepMobileData = true,
                keepBluetooth = true,
                recoverCollectorService = true
            ),
            userShutdown = false
        )

        assertTrue(commands.contains("settings put global bydcollector_user_shutdown 0"))
    }

    @Test
    fun daemonLaunchCommandQuotesApkPathAndUsesFixedClass() {
        val command = KeepAliveShellPlanner.daemonLaunchCommand("/data/app/path with space/base.apk")

        assertTrue(command.contains("CLASSPATH='/data/app/path with space/base.apk'"))
        assertTrue(command.contains("setsid app_process /system/bin --nice-name=bydcollector_keepalive"))
        assertTrue(command.contains("com.bydcollector.collector.keepalive.KeepAliveDaemon"))
        assertTrue(command.contains(">>/data/local/tmp/bydcollector_keepalive.log 2>&1"))
        assertTrue(command.startsWith("if ! pidof bydcollector_keepalive >/dev/null 2>&1; then "))
        assertTrue(command.endsWith("2>&1 & fi; sleep 1"))
        assertFalse(command.contains("|| CLASSPATH="))
        assertFalse(command.contains("</dev/null >/data/local/tmp/bydcollector_keepalive.log 2>&1"))
    }

    @Test
    fun daemonStopCommandIsFixed() {
        assertEquals(
            "pidof bydcollector_keepalive >/dev/null 2>&1 && kill -TERM \$(pidof bydcollector_keepalive) 2>/dev/null || true; " +
                "for i in 1 2 3; do if ! pidof bydcollector_keepalive >/dev/null 2>&1; then exit 0; fi; sleep 1; done; exit 1",
            KeepAliveShellPlanner.daemonStopCommand()
        )
    }

    @Test
    fun daemonStopCommandOnlySucceedsAfterPidIsAbsent() {
        val command = KeepAliveShellPlanner.daemonStopCommand()

        assertTrue(command.contains("for i in 1 2 3"))
        assertTrue(command.contains("if ! pidof bydcollector_keepalive"))
        assertTrue(command.endsWith("done; exit 1"))
    }

    @Test
    fun bluetoothProfilesRestoreCommandReturnsNormalPolicy() {
        assertEquals(
            "settings put global bluetooth_disabled_profiles 0",
            KeepAliveShellPlanner.bluetoothProfilesRestoreCommand()
        )
    }

    @Test
    fun bluetoothRecoveryUsesActualManagerStateInsteadOfSvcExitCode() {
        assertEquals(true, KeepAliveDaemon.parseBluetoothEnabled("  enabled: true\n  state: ON"))
        assertEquals(false, KeepAliveDaemon.parseBluetoothEnabled("  enabled: false\n  state: OFF"))
        assertEquals(null, KeepAliveDaemon.parseBluetoothEnabled("  enabled: true\n  state: TURNING_ON"))
        assertEquals(null, KeepAliveDaemon.parseBluetoothEnabled("  enabled: true"))
        assertEquals(null, KeepAliveDaemon.parseBluetoothEnabled("Bluetooth manager unavailable"))
    }

    @Test
    fun vehiclePowerParserAcceptsOnlyKnownParcelPowerValues() {
        assertEquals(
            true,
            KeepAliveDaemon.parseVehiclePoweredOff("Result: Parcel(00000000 00000000 '........')")
        )
        assertEquals(
            false,
            KeepAliveDaemon.parseVehiclePoweredOff("Result: Parcel(00000000 00000001 '........')")
        )
        assertEquals(
            false,
            KeepAliveDaemon.parseVehiclePoweredOff("Result: Parcel(00000000 00000002 '........')")
        )
        assertEquals(
            true,
            KeepAliveDaemon.parseVehiclePoweredOff(
                "  \nResult: Parcel(\n 0x00000000: 00000000 00000000 '........'\n)  \n"
            )
        )

        listOf("FFFFD8E3", "FFFFD8E5", "FFFFFFFF").forEach { raw ->
            assertNull(KeepAliveDaemon.parseVehiclePoweredOff("Result: Parcel(00000000 $raw '........')"))
        }
        assertNull(KeepAliveDaemon.parseVehiclePoweredOff("Result: Parcel(00000001 00000000 '........')"))
    }

    @Test
    fun vehiclePowerParserRejectsMalformedOrDiagnosticOutput() {
        listOf(
            null,
            "",
            "NULL",
            "Result: Parcel(00000000 00000000)",
            "Result: Parcel(00000000 0000000 '........')",
            "Result: Parcel(00000000 000000000 '........')",
            "Result: Parcel(00000000 00000000 NULL)",
            "Result: Parcel(00000000 00000000 '........') warning",
            "warning Result: Parcel(00000000 00000000 '........')",
            "Result: Parcel(00000000 00000000 '........') Result: Parcel(00000000 00000000 '........')",
            "0x00000000: Result: Parcel(00000000 00000000 '........')"
        ).forEach { output ->
            assertNull(KeepAliveDaemon.parseVehiclePoweredOff(output))
        }
    }

    @Test
    fun singleQuoteInApkPathIsShellQuoted() {
        val command = KeepAliveShellPlanner.daemonLaunchCommand("/data/app/a'b/base.apk")

        assertTrue(command.contains("CLASSPATH='/data/app/a'\\''b/base.apk'"))
    }

    @Test
    fun daemonStatusRetryChecksPidThreeTimes() {
        val command = KeepAliveShellPlanner.daemonStatusRetryCommand()

        assertTrue(command.contains("for i in 1 2 3"))
        assertTrue(command.contains("pidof bydcollector_keepalive"))
    }

    @Test
    fun daemonLogTailReadsDelegateLogOnly() {
        assertEquals(
            "tail -n 40 /data/local/tmp/bydcollector_keepalive.log 2>/dev/null || true",
            KeepAliveShellPlanner.daemonLogTailCommand()
        )
    }

    @Test
    fun daemonLogCapTruncatesAtBoundaryAndIsWiredIntoLog() {
        val logFile = Files.createTempFile("bydcollector-keepalive", ".log").toFile()
        logFile.writeBytes(ByteArray(63))

        assertFalse(KeepAliveDaemon.truncateLogIfNeeded(logFile, 64))
        assertEquals(63L, logFile.length())

        logFile.appendBytes(byteArrayOf(1))
        assertTrue(KeepAliveDaemon.truncateLogIfNeeded(logFile, 64))
        assertEquals(0L, logFile.length())
    }

    @Test
    fun daemonDrainsCommandOutputBeforeWaitingAndCapsRetainedBytes() {
        val input = ByteArrayInputStream(ByteArray(128) { 'a'.code.toByte() })

        assertEquals("a".repeat(64), KeepAliveDaemon.drainOutput(input, 64))
        assertEquals(0, input.available())
    }

}
