package com.bydcollector.collector.ui

import android.os.SystemClock
import com.bydcollector.collector.ui.compose.AppTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CachedDashboardState(
    val state: DashboardState,
    val loadedAtElapsedMs: Long,
    val generation: Long,
    val inFlight: Boolean,
    val lastError: String?
)

class DashboardUiStateStore(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val lock = Any() //Serializes cache reads, writes, and generation checks.
    private var nextGeneration = 0L
    private val chrome = MutableStateFlow<CachedDashboardState?>(null)
    private val tabs = AppTab.entries.associateWith {
        MutableStateFlow<CachedDashboardState?>(null)
    }
    private val pendingGenerations = mutableMapOf<AppTab?, Long>()

    val chromeState: StateFlow<CachedDashboardState?> = chrome.asStateFlow()
    private val tabFlows = tabs.mapValues { (_, flow) -> flow.asStateFlow() }

    fun tabState(tab: AppTab): StateFlow<CachedDashboardState?> = tabFlows.getValue(tab)

    fun seed(initial: DashboardState) {
        synchronized(lock) {
            val emptyTabs = AppTab.entries.filter { tabs.getValue(it).value == null }
            if (chrome.value != null && emptyTabs.isEmpty()) return

            val generation = nextGenerationLocked()
            val loadedAtElapsedMs = clock()
            if (chrome.value == null) {
                pendingGenerations.remove(null)
                chrome.value = CachedDashboardState(
                    state = initial,
                    loadedAtElapsedMs = loadedAtElapsedMs,
                    generation = generation,
                    inFlight = false,
                    lastError = null
                )
            }
            emptyTabs.forEach { tab ->
                pendingGenerations.remove(tab)
                tabs.getValue(tab).value = CachedDashboardState(
                    state = initial,
                    loadedAtElapsedMs = loadedAtElapsedMs,
                    generation = generation,
                    inFlight = false,
                    lastError = null
                )
            }
        }
    }

    fun beginChromeRefresh(): Long = beginRefresh(null)

    fun beginTabRefresh(tab: AppTab): Long = beginRefresh(tab)

    fun publishChrome(generation: Long, state: DashboardState): Boolean {
        return publish(null, generation, state)
    }

    fun publishTab(tab: AppTab, generation: Long, state: DashboardState): Boolean {
        return publish(tab, generation, state)
    }

    fun failChrome(generation: Long, error: String): Boolean {
        return fail(null, generation, error)
    }

    fun failTab(tab: AppTab, generation: Long, error: String): Boolean {
        return fail(tab, generation, error)
    }

    fun currentChrome(): DashboardState? = synchronized(lock) { chrome.value?.state }

    fun currentTab(tab: AppTab): DashboardState? = synchronized(lock) { tabs.getValue(tab).value?.state }

    fun markTabStale(tab: AppTab) {
        synchronized(lock) {
            val flow = tabs.getValue(tab)
            //Zero is the stale sentinel; the last non-null snapshot remains visible.
            flow.value = flow.value?.copy(loadedAtElapsedMs = 0L)
        }
    }

    private fun beginRefresh(tab: AppTab?): Long {
        return synchronized(lock) {
            val generation = nextGenerationLocked()
            pendingGenerations[tab] = generation
            val flow = mutableFlow(tab)
            flow.value = flow.value?.copy(
                generation = generation,
                inFlight = true
            )
            generation
        }
    }

    private fun publish(tab: AppTab?, generation: Long, state: DashboardState): Boolean {
        return synchronized(lock) {
            val flow = mutableFlow(tab)
            val current = flow.value
            if (!isCurrentGeneration(tab, current, generation)) {
                false
            } else {
                flow.value = CachedDashboardState(
                    state = state,
                    loadedAtElapsedMs = clock(),
                    generation = generation,
                    inFlight = false,
                    lastError = null
                )
                pendingGenerations.remove(tab)
                true
            }
        }
    }

    private fun fail(tab: AppTab?, generation: Long, error: String): Boolean {
        return synchronized(lock) {
            val flow = mutableFlow(tab)
            val current = flow.value
            if (!isCurrentGeneration(tab, current, generation)) {
                false
            } else {
                if (current != null) {
                    flow.value = current.copy(
                        inFlight = false,
                        lastError = error
                    )
                }
                pendingGenerations.remove(tab)
                true
            }
        }
    }

    private fun isCurrentGeneration(
        tab: AppTab?,
        current: CachedDashboardState?,
        generation: Long
    ): Boolean {
        return if (current == null) {
            pendingGenerations[tab] == generation
        } else {
            current.inFlight && current.generation == generation
        }
    }

    private fun mutableFlow(tab: AppTab?): MutableStateFlow<CachedDashboardState?> {
        return if (tab == null) chrome else tabs.getValue(tab)
    }

    private fun nextGenerationLocked(): Long {
        check(nextGeneration < Long.MAX_VALUE) { "dashboard UI generation overflow" }
        nextGeneration += 1L
        return nextGeneration
    }
}
