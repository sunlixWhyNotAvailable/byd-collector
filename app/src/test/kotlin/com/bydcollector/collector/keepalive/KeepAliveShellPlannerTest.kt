package com.bydcollector.collector.keepalive

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
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

        val source = sourceFile("com/bydcollector/collector/keepalive/KeepAliveDaemon.java").readText()
        val method = source.substringAfter("private static void keepBluetoothAlive() {")
            .substringBefore("    private static Boolean readBluetoothEnabled()")

        assertTrue(method.indexOf("readBluetoothEnabled()") < method.indexOf("run(\"svc bluetooth enable\""))
        assertTrue(method.contains("bluetooth_already_enabled"))
        assertTrue(method.contains("bluetooth_state_unavailable"))
        assertTrue(method.contains("bluetooth_enable_confirmed"))
        assertTrue(method.contains("bluetooth_enable_not_confirmed"))
        assertFalse(method.contains("runAndLog(\"bluetooth_enable_requested\""))
        assertEquals(1, Regex("""run\(\"svc bluetooth enable\"""").findAll(method).count())
        assertTrue(source.contains("BLUETOOTH_VERIFY_ATTEMPTS = 5"))
        assertTrue(source.contains("BLUETOOTH_VERIFY_DELAY_MS = 1_000L"))
    }

    @Test
    fun supervisorMirrorsFlagsBeforeBluetoothRollbackAndStopsAfterIt() {
        val source = sourceFile("com/bydcollector/collector/keepalive/KeepAliveSupervisor.kt").readText()
        val mirrorIndex = source.indexOf("mirrorSettingsCommands")
        val rollbackIndex = source.indexOf("bluetoothProfilesRestoreCommand")
        val stopIndex = source.indexOf("daemonStopCommand")

        assertTrue(mirrorIndex in 0..<rollbackIndex)
        assertTrue(rollbackIndex in 0..<stopIndex)
    }

    @Test
    fun supervisorLetsTheWatchdogBypassTheOrdinaryAliveCache() {
        val source = sourceFile("com/bydcollector/collector/keepalive/KeepAliveSupervisor.kt").readText()
        val reconcile = source.substringAfter("private fun runReconcile(config: KeepAliveConfig, forceStatusCheck: Boolean)")
            .substringBefore("fun shutdown()")

        assertTrue(reconcile.contains("daemonStatusCommand()"))
        assertTrue(reconcile.contains("forceStatusCheck = forceStatusCheck"))
        assertTrue(reconcile.contains("aliveFresh = reconcileState.aliveFresh(nowMs)"))
        assertTrue(source.contains("ALIVE_TTL_MS"))
    }

    @Test
    fun completionReconcileAlwaysForcesARealDaemonCheck() {
        val source = sourceFile("com/bydcollector/collector/keepalive/KeepAliveSupervisor.kt").readText()
        val reconcileThen = source.substringAfter("fun reconcileThen")
            .substringBefore("private fun runReconcileSerialized")

        assertTrue(reconcileThen.contains("forceStatusCheck = true"))
        assertTrue(reconcileThen.contains("catch (error: Throwable)"))
        assertTrue(reconcileThen.contains("executor.execute(task)"))
        assertTrue(reconcileThen.contains("after(false)"))
        assertTrue(source.contains("val shouldStopDisabledDaemon = forceStatusCheck ||"))
    }

    @Test
    fun keepAliveStopRetryUsesExistingReceiverHandoffAndBoundedSchedule() {
        val autoStart = sourceFile("com/bydcollector/collector/system/CollectorAutoStart.kt").readText()
        val receiver = sourceFile("com/bydcollector/collector/system/InternalAutoStartReceiver.kt").readText()
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val retry = service.substringAfter("private fun reconcileKeepAliveStopRetry")
            .substringBefore("private fun finishKeepAliveStopAfterFailure")
        val failure = service.substringAfter("private fun finishKeepAliveStopAfterFailure")
            .substringBefore("private fun startMainIfNeeded")

        assertTrue(autoStart.contains("ACTION_KEEP_ALIVE_STOP_RETRY"))
        assertTrue(autoStart.contains("KeepAliveStopRetrySchedule.delayMs(retryAttempt)"))
        assertTrue(autoStart.contains("AlarmManager.ELAPSED_REALTIME_WAKEUP"))
        assertTrue(autoStart.contains("keepAliveStopRetryIntent(appContext, nextAttempt)"))
        assertTrue(autoStart.contains("KEEP_ALIVE_MAINTENANCE_DEFER_MS = 60_000L"))
        assertTrue(autoStart.contains("keepAliveStopRetryIntent(appContext, retryAttempt)"))
        assertTrue(receiver.contains("ACTION_KEEP_ALIVE_STOP_RETRY"))
        assertTrue(service.contains("reconcileKeepAliveStopRetry("))
        assertTrue(service.contains("finishKeepAliveStopAfterFailure("))
        assertTrue(service.contains("keepAliveStopGeneration"))
        assertTrue(service.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue(retry.contains("if (!settings.isUserShutdownRequested() && keepAliveEnabled)"))
        assertFalse(retry.contains("settings.runtimeDemand().any"))
        assertTrue(retry.contains("if (!hasRuntimeOwner())"))
        assertTrue(retry.contains("reconcilePersistedRuntime(forceKeepAliveStatusCheck = true)"))
        assertTrue(retry.contains("restoreNotificationAfterKeepAliveStop()"))
        assertTrue(retry.contains("stopIfNoActiveRuntime()"))
        assertTrue(failure.contains("if (hasRuntimeOwner())"))
        assertTrue(failure.contains("reconcilePersistedRuntime(forceKeepAliveStatusCheck = true)"))
        assertTrue(failure.contains("restoreNotificationAfterKeepAliveStop()"))
        val normalSuccessIndex = retry.indexOf("reconcilePersistedRuntime(forceKeepAliveStatusCheck = true)")
        assertTrue(normalSuccessIndex >= 0)
        assertTrue(retry.indexOf("stopSelf()", normalSuccessIndex) < 0)
        val retainedRuntimeIndex = failure.indexOf("if (hasRuntimeOwner())")
        val retainedReturnIndex = failure.indexOf("return", retainedRuntimeIndex)
        val releaseIndex = failure.indexOf("releaseWakeLock()", retainedRuntimeIndex)
        assertTrue(retainedRuntimeIndex >= 0)
        assertTrue(retainedReturnIndex > retainedRuntimeIndex && retainedReturnIndex < releaseIndex)
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
        val source = sourceFile("com/bydcollector/collector/keepalive/KeepAliveDaemon.java").readText()
        assertTrue(source.contains("LOG_MAX_BYTES = 1_048_576L"))
        assertTrue(source.contains("truncateLogIfNeeded(LOG_FILE, LOG_MAX_BYTES);"))
    }

    @Test
    fun daemonDrainsCommandOutputBeforeWaitingAndCapsRetainedBytes() {
        val input = ByteArrayInputStream(ByteArray(128) { 'a'.code.toByte() })

        assertEquals("a".repeat(64), KeepAliveDaemon.drainOutput(input, 64))
        assertEquals(0, input.available())

        val source = sourceFile("com/bydcollector/collector/keepalive/KeepAliveDaemon.java").readText()
        val drainStartIndex = source.indexOf("outputDrainer.start();")
        val processWaitIndex = source.indexOf("process.waitFor(timeoutMs")
        assertTrue(drainStartIndex in 0..<processWaitIndex)
        assertTrue(source.contains("COMMAND_OUTPUT_MAX_BYTES = 65_536"))
        assertTrue(source.contains("if (outputDrainTimedOut)"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/java/$path"),
            File("app/src/main/java/$path"),
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
