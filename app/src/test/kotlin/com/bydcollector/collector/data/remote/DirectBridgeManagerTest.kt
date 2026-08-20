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
        assertContains(command, "</dev/null >/data/local/tmp/bydcollector_helper.log 2>&1 &")
        assertContains(command, "service list 2>/dev/null | grep -q bydcollector_helper")
        assert(command.indexOf("kill \"${'$'}pid\"") < command.indexOf("setsid app_process"))
        assert(command.indexOf("HELPER_STOP_TIMEOUT") < command.indexOf("rm -f /data/local/tmp/bydcollector_helper.lock"))
        assert(command.indexOf("rm -f /data/local/tmp/bydcollector_helper.lock") < command.indexOf("setsid app_process"))
        assertFalse(command.contains("base.apk' worker"))
        assertFalse(command.contains("DirectVehicleBridgeServer"))
        assertFalse(command.contains("19837"))
    }

    @Test
    fun autonomousWorkerLaunchAddsOnlyTheExplicitModeArgument() {
        val command = DirectBridgeManager.launchCommand(
            apkPath = "/data/app/com.bydcollector.collector/base.apk",
            appUid = 12345,
            ownerMode = DirectHelperOwnerMode.AUTONOMOUS_WORKER
        )

        assertContains(
            command,
            "com.bydcollector.collector.direct.CollectorHelperDaemon 12345 '/data/app/com.bydcollector.collector/base.apk' worker"
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
