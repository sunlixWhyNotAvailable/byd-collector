package com.bydcollector.collector.system

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationListenerRecoveryContractTest {
    @Test
    fun manifestUsesNotificationListenerInsteadOfUserPresent() {
        val manifest = sourceFile("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").readText()
        val bootReceiver = sourceFile(
            "src/main/kotlin/com/bydcollector/collector/system/BootReceiver.kt",
            "app/src/main/kotlin/com/bydcollector/collector/system/BootReceiver.kt"
        ).readText()

        assertTrue(manifest.contains("CollectorNotificationListenerService"))
        assertTrue(manifest.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(manifest.contains("android.service.notification.NotificationListenerService"))
        assertFalse(manifest.contains("android.intent.action.USER_PRESENT"))
        assertFalse(bootReceiver.contains("ACTION_USER_PRESENT"))
    }

    @Test
    fun listenerRequestsTheExistingRecoveryPathWithoutReadingNotifications() {
        val listener = sourceFile(
            "src/main/kotlin/com/bydcollector/collector/system/CollectorNotificationListenerService.kt",
            "app/src/main/kotlin/com/bydcollector/collector/system/CollectorNotificationListenerService.kt"
        ).readText()

        assertTrue(listener.contains("override fun onCreate()"))
        assertTrue(listener.contains("override fun onListenerConnected()"))
        assertTrue(listener.contains("dispatchOperationalEvent(sharedOperationalEventExecutor)"))
        assertTrue(listener.contains("CollectorAutoStart.recoverFromForeground("))
        assertTrue(listener.contains("notification_listener_recovery_requested"))
        assertFalse(listener.contains("onNotificationPosted"))
        assertFalse(listener.contains("onNotificationRemoved"))
        assertFalse(listener.contains("StatusBarNotification"))
    }

    @Test
    fun localAdbRepairGrantsAndVerifiesTheExactListenerComponent() {
        val access = sourceFile(
            "src/main/kotlin/com/bydcollector/collector/system/RequiredAccessChecker.kt",
            "app/src/main/kotlin/com/bydcollector/collector/system/RequiredAccessChecker.kt"
        ).readText()

        assertTrue(access.contains("ENABLED_NOTIFICATION_LISTENERS = \"enabled_notification_listeners\""))
        assertTrue(access.contains("ComponentName.unflattenFromString(it) == required"))
        assertTrue(access.contains("CollectorNotificationListenerService::class.java"))
        assertTrue(access.contains("cmd notification allow_listener"))
        assertTrue(access.contains("key = \"notification_listener\""))
    }

    private fun sourceFile(vararg paths: String): File {
        return paths.map { File(it) }.firstOrNull { it.isFile }
            ?: error("Missing source file: ${paths.joinToString()}")
    }
}
