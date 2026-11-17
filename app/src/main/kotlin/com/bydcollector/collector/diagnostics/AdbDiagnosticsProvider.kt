package com.bydcollector.collector.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.runtime.RuntimeJobsService
import org.json.JSONObject
import java.util.UUID

/** ADB has diagnostic access only, never the general UI/runtime command surface. */
class AdbDiagnosticsProvider : ContentProvider() {
    override fun onCreate(): Boolean = !BuildConfig.RUNTIME_CLIENT

    private fun <T> asOwner(action: (Context) -> T): T {
        check(!BuildConfig.RUNTIME_CLIENT)
        val uid = Binder.getCallingUid()
        if (uid != 0 && uid != 2000) throw SecurityException("Diagnostics requires ADB shell or root")
        val ctx = requireNotNull(context)
        val identity = Binder.clearCallingIdentity()
        return try { action(ctx) } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = asOwner { ctx ->
        require(extras == null || extras.isEmpty) { "Diagnostics takes no extras" }
        when (method) {
            "collect", "clear" -> {
                require(arg == null) { "Operation takes no argument" }
                val kind = if (method == "collect") "logShare" else "logClear"
                val id = RuntimeJobsService.submit(ctx, kind, JSONObject().put("origin", "adb"))
                Bundle().apply { putString("id", id) }
            }
            "status" -> {
                val id = jobId(arg)
                val job = RuntimeJobsService.adbDiagnosticJob(ctx, id)
                Bundle().apply {
                    putString("id", id)
                    putString("status", job.getString("status"))
                    putString("kind", job.getString("kind"))
                    if (job.has("error")) putString("error", job.getString("error"))
                    if (job.has("diagnostic")) putString("diagnostic", job.getString("diagnostic"))
                    job.optJSONObject("result")?.let { result ->
                        if (job.getString("kind") == "logShare") putString("archive", "content://$AUTHORITY/archive/$id")
                        else putString("result", result.toString())
                    }
                }
            }
            else -> throw IllegalArgumentException("Unknown diagnostics operation: $method")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = asOwner { ctx ->
        require(mode == "r" && uri.scheme == "content" && uri.authority == AUTHORITY &&
            uri.query == null && uri.fragment == null && uri.pathSegments.size == 2 &&
            uri.pathSegments[0] == "archive") { "Only a completed diagnostic archive can be read" }
        val job = RuntimeJobsService.adbDiagnosticJob(ctx, jobId(uri.pathSegments[1]))
        check(job.getString("kind") == "logShare" && job.getString("status") == "complete") {
            "Diagnostic archive is not complete"
        }
        val source = Uri.parse(job.getJSONObject("result").getString("uri"))
        check(source.scheme == "content" && source.authority == "${ctx.packageName}.fileprovider") {
            "Invalid diagnostic archive owner"
        }
        ctx.contentResolver.openFileDescriptor(source, "r") ?: error("Diagnostic archive could not be opened")
    }

    override fun getType(uri: Uri): String = asOwner { "application/zip" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use the explicit clear operation")

    private fun jobId(value: String?): String = requireNotNull(value).also {
        require(UUID.fromString(it).toString() == it) { "Invalid diagnostic operation ID" }
    }

    companion object { const val AUTHORITY = "com.bydcollector.collector.diagnostics" }
}
