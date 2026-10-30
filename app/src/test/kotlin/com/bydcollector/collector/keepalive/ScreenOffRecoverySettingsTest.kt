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
class ScreenOffRecoverySettingsTest {
    @Test
    fun defaultDoesNotStartRecoveryAndUserChoiceSurvivesParentAndShutdown() {
        val context = RuntimeEnvironment.getApplication()
        val settings = CollectorSettings(context)
        assertTrue(settings.isScreenOffAfterRecoveryEnabled())
        assertFalse(settings.keepAliveConfig().anyEnabled)
        settings.setScreenOffAfterRecoveryEnabled(false)
        settings.setConnectivityRecoveryEnabled(true)
        assertFalse(CollectorSettings(context).keepAliveConfig().screenOffAfterRecovery)
        settings.setConnectivityRecoveryEnabled(false)
        assertFalse(CollectorSettings(context).isScreenOffAfterRecoveryEnabled())
        settings.setScreenOffAfterRecoveryEnabled(true)
        assertTrue(settings.setUserShutdownRequested(true))
        val reopened = CollectorSettings(context)
        assertTrue(reopened.isScreenOffAfterRecoveryEnabled())
        assertFalse(reopened.runtimeDemand().any)
        assertTrue(reopened.setUserShutdownRequested(false))
        assertFalse(reopened.keepAliveConfig().anyEnabled)
    }
}
