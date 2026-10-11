package com.bydcollector.collector.runtime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.disposeTestComposition
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.StartupAdbPeer
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.system.RequiredAccessChecker
import org.junit.Test
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = BydCollectorApplication::class,
    qualifiers = "w1280dp-h800dp-mdpi", shadows = [StartupAdbPeer::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupAccessBehaviorTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val screens = mutableListOf<ActivityController<RuntimeLauncherActivity>>()

    @After fun cleanup() {
        screens.forEach { it.get().disposeTestComposition(); it.pause().stop().destroy() }
        screens.clear()
        AdbAuthorizationManager.cancelObservation()
    }

    @Test fun startupRsaAndPermissionsAreSequential() {
        ui.mainClock.autoAdvance = false
        val app = RuntimeEnvironment.getApplication() as BydCollectorApplication
        CollectorSettings(app).apply {
            setUiLanguageCode("en"); setUpdateAutoCheckEnabled(false); setUpdateHintEnabled(true)
        }
        shadowOf(app).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "other/other.Listener")
        StartupAdbPeer.reset()
        val controller = Robolectric.buildActivity(RuntimeLauncherActivity::class.java).also(screens::add).setup().visible()
        val activity = controller.get()
        val model = ReflectionHelpers.getField<RuntimeAccessModel>(activity, "model")
        // Embedded-client installation is outside permission ordering; use an already-installed client.
        model.clientReady = true
        activity.onWindowFocusChanged(true)
        await { model.backgroundVisible }
        advance(5_000)
        assertEquals(StartupAccessSequence.Step.BACKGROUND, model.flow.step)
        assertTrue(StartupAdbPeer.events.isEmpty(), "ADB before background acknowledgement: ${StartupAdbPeer.events}")
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(model.backgroundVisible)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "completeBackground")
        settle()
        activity.onWindowFocusChanged(false)
        advance(600)
        assertEquals(StartupAccessSequence.Step.ADB, model.flow.step)
        assertTrue(StartupAdbPeer.events.isEmpty(), "RSA must wait for window focus")
        activity.onWindowFocusChanged(true)
        settle(); advance(600)
        await { model.flow.step == StartupAccessSequence.Step.OVERLAY }
        settle()
        assertTrue(StartupAdbPeer.events.contains("denied"))
        assertTrue(StartupAdbPeer.events.none { it.startsWith("shell:") })
        assertTrue(RequiredAccessChecker.hasMissingRequiredAccess(app))
        val fingerprint = AdbLocalClient(File(app.filesDir, "adb_keys")).keyFingerprint()
        val deniedKey = StartupAdbPeer.publicKeys.single()
        val overlay = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay.intent.action)
        assertNull(shadowOf(activity).nextStartedActivityForResult, "only one permission screen at a time")
        shadowOf(activity).receiveResult(overlay.intent, Activity.RESULT_CANCELED, Intent())
        settle()
        assertEquals(StartupAccessSequence.Step.LOCATION, model.flow.step)
        assertTrue(model.flow.inFlight)
        val location = requireNotNull(shadowOf(activity).lastRequestedPermission)
        assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            location.requestedPermissions.toSet())
        activity.onRequestPermissionsResult(location.requestCode, location.requestedPermissions,
            IntArray(location.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_DENIED })
        settle()
        assertEquals(StartupAccessSequence.Step.COMPLETE, model.flow.step)
        assertTrue(activity.isFinishing, "permission denial must not strand the launcher")
        activity.disposeTestComposition()
        controller.pause().stop().destroy()
        screens.remove(controller)

        StartupAdbPeer.consent = true
        val manual = Robolectric.buildActivity(RuntimeLauncherActivity::class.java,
            Intent(app, RuntimeLauncherActivity::class.java).putExtra("accessRequest", "adb")).also(screens::add).setup().visible()
        manual.get().onWindowFocusChanged(true)
        settle(); advance(600)
        await { manual.get().isFinishing }
        assertEquals(fingerprint, AdbLocalClient(File(app.filesDir, "adb_keys")).keyFingerprint())
        assertTrue(StartupAdbPeer.publicKeys.all { it == deniedKey })
        val accepted = StartupAdbPeer.events.indexOf("accepted")
        val grants = StartupAdbPeer.events.withIndex().filter { it.value.startsWith("shell:") }
        assertTrue(accepted >= 0 && grants.isNotEmpty())
        assertTrue(grants.all { it.index > accepted }, "no grant before successful RSA authorization")
        assertFalse(RequiredAccessChecker.hasMissingRequiredAccess(app))
        assertTrue(Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners").contains("other/other.Listener"))
        manual.get().disposeTestComposition()
        manual.pause().stop().destroy()
        screens.remove(manual)
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        // This launcher embeds a TextView; advance effects without waiting for global layout idleness.
        ui.mainClock.advanceTimeBy(32)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); settle() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline) { StartupAdbPeer.events.toString() }; settle(); Thread.sleep(10) }
    }
}
