package com.bydcollector.collector.keepalive

import android.content.Context
import com.bydcollector.collector.service.CollectorSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WifiPolicyStateTest {
    private fun prefs(name: String) = RuntimeEnvironment.getApplication()
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun toggle(name: String, enabled: Boolean) {
        val prefs = prefs(name)
        synchronized(WifiPolicyState.LOCK) {
            val editor = prefs.edit().putBoolean(CollectorSettings.KEY_KEEP_WIFI, enabled)
                .putBoolean(CollectorSettings.KEY_KEEP_MOBILE_DATA, enabled)
            WifiPolicyState.stageToggle(prefs, editor, enabled)
            assertTrue(editor.commit())
        }
    }

    @Test fun originalAbsentAndNumericValuesSurviveReopenAndRestore() {
        val name = "wifi-policy-roundtrip"
        toggle(name, true)
        val state = WifiPolicyState(prefs(name))
        val apply = assertNotNull(state.beginAttempt())
        val original = WifiPolicyState.Snapshot(null, "1")
        assertTrue(state.capture(apply, original))
        assertTrue(state.finish(apply))
        WifiPolicyState(prefs(name)).initializeEnabledUser(true)
        assertFalse(WifiPolicyState(prefs(name)).pending())
        toggle(name, false)
        val restore = assertNotNull(WifiPolicyState(prefs(name)).beginAttempt())
        assertEquals(WifiPolicyState.RESTORE, restore.operation)
        assertTrue(restore.captured)
        assertEquals(original, WifiPolicyState.Snapshot.decode(assertNotNull(restore.snapshot)))
    }

    @Test fun legacyEnabledMigrationIsOnceAndRetainsIncompleteSnapshotAcrossRetries() {
        val name = "wifi-policy-migration"
        assertTrue(prefs(name).edit().putBoolean(CollectorSettings.KEY_KEEP_WIFI, true).commit())
        val state = WifiPolicyState(prefs(name))
        state.initializeEnabledUser(true)
        val first = assertNotNull(state.beginAttempt())
        assertTrue(state.capture(first, WifiPolicyState.Snapshot("0", null)))
        val reopened = WifiPolicyState(prefs(name))
        reopened.initializeEnabledUser(true)
        val retry = assertNotNull(reopened.beginAttempt())
        assertEquals(first.generation, retry.generation)
        assertEquals(first.attempt + 1, retry.attempt)
        assertEquals(WifiPolicyState.Snapshot("0", null), WifiPolicyState.Snapshot.decode(retry.snapshot!!))
        assertTrue(reopened.finish(retry))
        reopened.initializeEnabledUser(true)
        assertFalse(reopened.pending())
    }

    @Test fun newToggleInvalidatesOldCompletionAndStartsNewCapture() {
        val name = "wifi-policy-generation"
        toggle(name, true)
        val state = WifiPolicyState(prefs(name))
        val oldApply = assertNotNull(state.beginAttempt())
        assertTrue(state.capture(oldApply, WifiPolicyState.Snapshot("2", "0")))
        toggle(name, false)
        val oldRestore = assertNotNull(state.beginAttempt())
        toggle(name, true)
        assertFalse(state.finish(oldApply))
        assertFalse(state.finish(oldRestore))
        val latest = assertNotNull(state.beginAttempt())
        assertEquals(WifiPolicyState.APPLY, latest.operation)
        assertFalse(latest.captured)
        assertNull(latest.snapshot)
    }

    @Test fun disabledLegacyUserDoesNotMigrateAndMissingSnapshotIsNotKnownAbsence() {
        val state = WifiPolicyState(prefs("wifi-policy-disabled"))
        state.initializeEnabledUser(false)
        assertNull(state.beginAttempt())
        assertEquals(WifiPolicyState.Snapshot(null, null), WifiPolicyState.Snapshot.decode("{\"sleep\":null,\"off\":null}"))
        assertFails { WifiPolicyState.Snapshot.decode("{}") }
        assertFails { WifiPolicyState.Snapshot.decode("{\"sleep\":\"bad\",\"off\":null}") }
    }
}
