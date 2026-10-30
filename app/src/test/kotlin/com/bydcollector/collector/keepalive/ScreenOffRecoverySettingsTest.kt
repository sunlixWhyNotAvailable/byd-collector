package com.bydcollector.collector.keepalive

import com.bydcollector.collector.service.CollectorSettings
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFails
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScreenOffRecoverySettingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun confirmedBacklightOwnershipSurvivesReopenButNotAnotherKernelBoot() {
        val path = temporary.root.toPath().resolve("owner.json")
        val owner = BacklightRecoveryState(path)
        assertFalse(owner.owned("boot-a"))
        owner.confirmOff("boot-a", 1234L)
        assertTrue(BacklightRecoveryState(path).owned("boot-a"))
        assertFalse(BacklightRecoveryState(path).owned("boot-b"))
        assertFalse(Files.exists(path))
        owner.confirmOff("boot-b", 90L)
        BacklightRecoveryState(path).clear()
        assertFalse(owner.owned("boot-b"))
        Files.write(path, "broken".toByteArray())
        assertFails { owner.owned("boot-b") }
        assertTrue(Files.exists(path))
    }

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
