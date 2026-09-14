package com.bydcollector.collector.data.remote

import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectBridgeManagerTest {
    @Test
    fun launchCommandMatchesBydMateStyleHelperPipeline() {
        val command = DirectBridgeManager.launchCommand(
            apkPath = "/data/app/com.bydcollector.collector/base.apk",
            appUid = 12345
        )

        assertContains(command, "CLASSPATH='/data/app/com.bydcollector.collector/base.apk'")
        assertContains(command, "for pid in ${'$'}(pidof bydcollector_helper 2>/dev/null); do kill \"${'$'}pid\" 2>/dev/null || true; done")
        assertContains(command, "if pidof bydcollector_helper >/dev/null; then echo HELPER_STOP_TIMEOUT >&2; exit 73; fi")
        assertContains(command, "rm -f /data/local/tmp/bydcollector_helper.lock")
        assertContains(command, "setsid app_process /system/bin --nice-name=bydcollector_helper")
        assertContains(command, "com.bydcollector.collector.direct.CollectorHelperDaemon 12345 '/data/app/com.bydcollector.collector/base.apk'")
        assertContains(command, "</dev/null >>/data/local/tmp/bydcollector_helper.log 2>&1 &")
        assertContains(command, "service list 2>/dev/null | grep -q bydcollector_helper")
        assert(command.indexOf("kill \"${'$'}pid\"") < command.indexOf("setsid app_process"))
        assert(command.indexOf("HELPER_STOP_TIMEOUT") < command.indexOf("rm -f /data/local/tmp/bydcollector_helper.lock"))
        assert(command.indexOf("rm -f /data/local/tmp/bydcollector_helper.lock") < command.indexOf("setsid app_process"))
        assertFalse(command.contains("base.apk' worker"))
        assertFalse(command.contains("DirectVehicleBridgeServer"))
        assertFalse(command.contains("19837"))
    }

    @Test
    fun bootstrapHistoryIsBoundedAndPreservedBeforeAppendingTheNewLaunch() {
        val history = DirectBridgeManager.bootstrapHistoryCommand()
        val command = DirectBridgeManager.launchCommand("/data/app/collector/base.apk", 12345)
        val log = "/data/local/tmp/bydcollector_helper.log"

        for (index in 3 downTo 1) {
            val source = if (index == 1) log else "$log.${index - 1}"
            assertContains(history, "tail -c 2097152 $source >$log.$index.tmp && mv -f $log.$index.tmp $log.$index")
        }
        assertTrue(history.indexOf("$log.3.tmp") < history.indexOf("$log.2.tmp"))
        assertTrue(history.indexOf("$log.2.tmp") < history.indexOf("$log.1.tmp"))
        assertContains(history, "if [ \"${'$'}bootstrap_history_ok\" = 1 ]; then : >$log;")
        assertContains(history, "HELPER_BOOTSTRAP_HISTORY_PARTIAL")
        assertTrue(command.indexOf("HELPER_STOP_TIMEOUT") < command.indexOf(history))
        assertTrue(command.indexOf(history) < command.indexOf("setsid app_process"))
        assertFalse(history.contains("telemetry_spool"))
        assertFalse(history.contains("rm -r"))
    }

    @Test
    fun appGapSpoolLaunchAddsOnlyTheExplicitModeArgument() {
        val command = DirectBridgeManager.launchCommand(
            apkPath = "/data/app/com.bydcollector.collector/base.apk",
            appUid = 12345,
            ownerMode = DirectHelperOwnerMode.APP_GAP_SPOOL
        )

        assertContains(
            command,
            "com.bydcollector.collector.direct.CollectorHelperDaemon 12345 '/data/app/com.bydcollector.collector/base.apk' spool"
        )
    }

    @Test
    fun helperLaunchIsSerializedAndRecheckedInsideTheLock() {
        val source = java.io.File(
            "app/src/main/kotlin/com/bydcollector/collector/data/remote/DirectBridgeManager.kt"
        ).takeIf { it.isFile } ?: java.io.File(
            "src/main/kotlin/com/bydcollector/collector/data/remote/DirectBridgeManager.kt"
        )
        val text = source.readText()
        val ensure = text.substringAfter("fun ensureRunning(").substringBefore("fun launchCommand(")

        assertTrue(text.contains("private val launchLock = ReentrantLock()"))
        assertTrue(ensure.contains("launchLock.lockInterruptibly()"))
        assertTrue(ensure.indexOf("launchLock.lockInterruptibly()") < ensure.indexOf("if (helper.ownerMode() == ownerMode)"))
        assertTrue(ensure.contains("launchLock.unlock()"))
    }
}
