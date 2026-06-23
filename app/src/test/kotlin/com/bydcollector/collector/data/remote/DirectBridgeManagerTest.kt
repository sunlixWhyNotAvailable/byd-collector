package com.bydcollector.collector.data.remote

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DirectBridgeManagerTest {
    @Test
    fun launchCommandMatchesBydMateStyleHelperPipeline() {
        val command = DirectBridgeManager.launchCommand(
            apkPath = "/data/app/com.bydcollector.collector/base.apk",
            appUid = 12345
        )

        assertContains(command, "CLASSPATH='/data/app/com.bydcollector.collector/base.apk'")
        assertContains(command, "for pid in ${'$'}(pidof bydcollector_helper 2>/dev/null); do kill \"${'$'}pid\" 2>/dev/null || true; done")
        assertContains(command, "rm -f /data/local/tmp/bydcollector_helper.lock")
        assertContains(command, "setsid app_process /system/bin --nice-name=bydcollector_helper")
        assertContains(command, "com.bydcollector.collector.direct.CollectorHelperDaemon 12345")
        assertContains(command, "</dev/null >/data/local/tmp/bydcollector_helper.log 2>&1 &")
        assertContains(command, "service list 2>/dev/null | grep -q bydcollector_helper")
        assert(command.indexOf("kill \"${'$'}pid\"") < command.indexOf("setsid app_process"))
        assert(command.indexOf("rm -f /data/local/tmp/bydcollector_helper.lock") < command.indexOf("setsid app_process"))
        assertFalse(command.contains("DirectVehicleBridgeServer"))
        assertFalse(command.contains("19837"))
    }
}
