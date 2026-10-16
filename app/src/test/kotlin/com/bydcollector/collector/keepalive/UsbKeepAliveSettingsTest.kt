package com.bydcollector.collector.keepalive

import com.bydcollector.collector.service.CollectorSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbKeepAliveSettingsTest {
    @Test
    fun persistedUsbAloneRequestsRecoveryButShutdownSuppressesIt() {
        val context = RuntimeEnvironment.getApplication()
        val settings = CollectorSettings(context)
        assertFalse(settings.isKeepUsbEnabled())
        settings.setKeepUsbEnabled(true)

        val reopened = CollectorSettings(context)
        assertTrue(reopened.keepAliveConfig().keepUsb)
        assertTrue(reopened.runtimeDemand().keepAlive)
        assertFalse(reopened.runtimeDemand().main)
        assertFalse(reopened.runtimeDemand().debug)
        assertFalse(reopened.keepAliveConfig().keepWifi)
        assertFalse(reopened.keepAliveConfig().keepBluetooth)

        assertTrue(reopened.setUserShutdownRequested(true))
        assertFalse(CollectorSettings(context).runtimeDemand().any)
        assertTrue(CollectorSettings(context).isKeepUsbEnabled())
        assertTrue(reopened.setUserShutdownRequested(false))
        reopened.setKeepUsbEnabled(false)
        assertFalse(CollectorSettings(context).keepAliveConfig().anyEnabled)
    }
}
