package com.bydcollector.collector.update

import com.bydcollector.collector.BuildConfig
import org.json.JSONArray

data class ReleaseNotesEntry(val version: String, val body: String)

data class ReleaseNotesHistory(
    val entries: List<ReleaseNotesEntry>,
    val loading: Boolean = false,
    val incomplete: Boolean = false
)

internal data class ReleaseNotesPage(val json: String, val hasNext: Boolean)

/** Notes are optional enrichment; failure must never invalidate an already trusted APK offer. */
internal fun loadReleaseNotesHistory(
    installedVersion: String,
    target: UpdateInfo,
    isCurrent: () -> Boolean = { true },
    fetchPage: (Int) -> ReleaseNotesPage = ::fetchReleaseNotesPage
): ReleaseNotesHistory {
    val entries = linkedMapOf(target.version.removePrefix("v") to ReleaseNotesEntry(target.version, target.releaseNotes))
    var incomplete = false
    try {
        var page = 1
        do {
            check(isCurrent()) { "Release history request superseded" }
            // A corrupt/repeating pagination response must not keep a worker occupied forever.
            check(page <= 100) { "Release history pagination limit" }
            val response = fetchPage(page++)
            val releases = JSONArray(response.json)
            for (index in 0 until releases.length()) {
                val release = releases.getJSONObject(index)
                if (release.optBoolean("draft")) continue
                val version = release.getString("tag_name")
                if (!Regex("v?\\d+(?:\\.\\d+)+(?:[-+].+)?").matches(version)) continue
                if (!UpdateVersionComparator.isNewer(version, installedVersion) ||
                    UpdateVersionComparator.isNewer(version, target.version)) continue
                entries.putIfAbsent(version.removePrefix("v"), ReleaseNotesEntry(version, release.optString("body", "")))
            }
        } while (response.hasNext)
    } catch (_: Exception) {
        incomplete = true
    }
    return ReleaseNotesHistory(
        entries = entries.values.sortedWith { left, right ->
            when {
                UpdateVersionComparator.isNewer(left.version, right.version) -> -1
                UpdateVersionComparator.isNewer(right.version, left.version) -> 1
                else -> right.version.compareTo(left.version)
            }
        },
        incomplete = incomplete
    )
}

private fun fetchReleaseNotesPage(page: Int): ReleaseNotesPage {
    val base = BuildConfig.UPDATE_RELEASES_API_URL.removeSuffix("/latest")
    val connection = openUpdateConnection("$base?per_page=20&page=$page")
    return try {
        // Construct pages on our trusted endpoint, never follow URLs supplied in Link headers.
        val hasNext = connection.getHeaderField("Link").orEmpty()
            .split(',').any { it.substringAfter(';', "").contains(Regex("rel=\"next\"")) }
        ReleaseNotesPage(readUpdateResponse(connection), hasNext)
    } finally {
        connection.disconnect()
    }
}

/** A single process-owned cache; neither an Activity nor the update-hint scheduler owns it. */
class ReleaseNotesHistorySession(
    private val dispatch: (() -> Unit) -> Unit,
    private val loader: (UpdateInfo, () -> Boolean) -> ReleaseNotesHistory
) {
    private val lock = Any()
    private val listeners = mutableSetOf<() -> Unit>()
    private var target: UpdateInfo? = null
    private var history: ReleaseNotesHistory? = null
    private var generation = 0L

    fun snapshot(info: UpdateInfo): ReleaseNotesHistory? = synchronized(lock) {
        history.takeIf { target == info }
    }

    fun request(info: UpdateInfo) {
        val token = synchronized(lock) {
            if (target == info && history != null) return
            target = info
            history = ReleaseNotesHistory(listOf(ReleaseNotesEntry(info.version, info.releaseNotes)), loading = true)
            ++generation
        }
        notifyListeners()
        try {
            dispatch {
                val result = runCatching { loader(info) { synchronized(lock) { generation == token } } }
                    .getOrElse { ReleaseNotesHistory(listOf(ReleaseNotesEntry(info.version, info.releaseNotes)), incomplete = true) }
                settle(token, result)
            }
        } catch (_: Exception) {
            settle(token, ReleaseNotesHistory(listOf(ReleaseNotesEntry(info.version, info.releaseNotes)), incomplete = true))
        }
    }

    fun reset() {
        synchronized(lock) {
            ++generation
            target = null
            history = null
        }
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) { synchronized(lock) { listeners += listener } }
    fun removeListener(listener: () -> Unit) { synchronized(lock) { listeners -= listener } }

    private fun settle(token: Long, result: ReleaseNotesHistory) {
        synchronized(lock) {
            if (generation != token) return
            history = result.copy(loading = false)
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        synchronized(lock) { listeners.toList() }.forEach { runCatching { it() } }
    }
}
