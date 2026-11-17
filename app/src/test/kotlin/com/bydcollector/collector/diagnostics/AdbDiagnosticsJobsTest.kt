package com.bydcollector.collector.diagnostics

import android.app.Application
import com.bydcollector.collector.runtime.RuntimeJobsService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AdbDiagnosticsJobsTest {
    @Test fun remoteRequestsPersistAndDeduplicateWithoutClaimingUiRequests() {
        val context = RuntimeEnvironment.getApplication()
        val args = JSONObject().put("origin", "adb")
        val remote = RuntimeJobsService.submit(context, "logClear", args)
        assertEquals(remote, RuntimeJobsService.submit(context, "logClear", args))
        val ui = RuntimeJobsService.submit(context, "logClear", JSONObject())
        assertNotEquals(remote, ui)
        assertEquals("queued", RuntimeJobsService.adbDiagnosticJob(context, remote).getString("status"))
        assertFailsWith<IllegalArgumentException> { RuntimeJobsService.adbDiagnosticJob(context, ui) }
        val visible = JSONArray(RuntimeJobsService.snapshot(context))
        assertFalse((0 until visible.length()).any { visible.getJSONObject(it).getString("id") == remote })
        assertTrue((0 until visible.length()).any { visible.getJSONObject(it).getString("id") == ui })
        val persisted = JSONArray(File(context.filesDir, "runtime_jobs.json").readText())
        assertEquals(1, (0 until persisted.length()).count { persisted.getJSONObject(it).getString("id") == remote })
        assertTrue((0 until persisted.length()).all { persisted.getJSONObject(it).getString("status") == "queued" })
    }
}
