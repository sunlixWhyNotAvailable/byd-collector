package com.bydcollector.collector.keepalive

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun daemonRecoveryBroadcastIsExactAndDirectStartIsAbsent() {
        val javaSource = sourceFile("com/bydcollector/collector/keepalive/KeepAliveDaemon.java").readText()
        val expectedCommand =
            "am broadcast --include-stopped-packages -a com.bydcollector.collector.action.KEEP_ALIVE_RECOVERY " +
                "-n com.bydcollector.collector/com.bydcollector.collector.system.KeepAliveRecoveryReceiver"
        val commandParts = Regex(
            """private static final String RECOVER_COLLECTOR_COMMAND =\s*"([^"]*)"\s*\+\s*"([^"]*)";"""
        ).find(javaSource) ?: error("Missing recovery command")

        assertEquals(expectedCommand, commandParts.groupValues[1] + commandParts.groupValues[2])
        assertFalse(javaSource.contains("am start-foreground-service"))
        assertFalse(javaSource.contains("START_COLLECTOR_SERVICE_COMMAND"))
    }

    @Test
    fun daemonRecoveryDiagnosticsNeverSkipBroadcastAfterShutdownGate() {
        val javaSource = sourceFile("com/bydcollector/collector/keepalive/KeepAliveDaemon.java").readText()
        val recoveryMethod = javaSource
            .substringAfter("private static void recoverCollectorServiceIfNeeded() {")
            .substringBefore("    private static void runAndLog")

        assertTrue(recoveryMethod.contains("collector_process_present"))
        assertTrue(recoveryMethod.contains("collector_process_missing"))
        assertTrue(recoveryMethod.contains("collector_service_record_present"))
        assertTrue(recoveryMethod.contains("collector_service_record_missing"))
        assertFalse(recoveryMethod.contains("collector_service_alive"))
        assertEquals(1, Regex("""\breturn;""").findAll(recoveryMethod).count())
        val broadcastIndex = recoveryMethod.indexOf("runAndLog(\"collector_service_recovery_broadcast_requested\"")
        assertTrue(recoveryMethod.indexOf("pidof \" + PACKAGE_NAME") in 0..<broadcastIndex)
        assertTrue(recoveryMethod.indexOf("dumpsys activity services") in 0..<broadcastIndex)
        assertTrue(
            Regex(
                """if \(isEnabled\(USER_SHUTDOWN_COMMAND\)\) \{\s*""" +
                    """log\("collector_recovery_blocked_user_shutdown"\);\s*return;\s*\}"""
            ).containsMatchIn(recoveryMethod)
        )
        assertTrue(
            recoveryMethod.contains(
                "\n        runAndLog(\"collector_service_recovery_broadcast_requested\", RECOVER_COLLECTOR_COMMAND);"
            )
        )
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
            "pidof bydcollector_keepalive >/dev/null 2>&1 && kill -TERM \$(pidof bydcollector_keepalive) 2>/dev/null || true",
            KeepAliveShellPlanner.daemonStopCommand()
        )
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

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/java/$path"),
            File("app/src/main/java/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
