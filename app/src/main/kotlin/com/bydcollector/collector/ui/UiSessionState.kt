package com.bydcollector.collector.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bydcollector.collector.ui.compose.AppTab

/** Process-owned navigation state; it intentionally has no Android or view references. */
class UiSessionState {
    var selectedTab by mutableStateOf(AppTab.MAIN)
        private set

    private val tabScrollOffsets = mutableStateMapOf<AppTab, Int>()

    var expandedTripYears by mutableStateOf<Set<String>>(emptySet())
        private set
    var expandedTripMonths by mutableStateOf<Set<String>>(emptySet())
        private set
    var expandedTripDays by mutableStateOf<Set<String>>(emptySet())
        private set
    var tripsExpansionInitialized by mutableStateOf(false)
        private set

    private var generation by mutableStateOf(0L)

    fun selectTab(tab: AppTab, generation: Long = this.generation) {
        if (!isGenerationCurrent(generation)) return
        selectedTab = tab
    }

    fun scrollOffset(tab: AppTab): Int = tabScrollOffsets[tab] ?: 0

    fun updateScrollOffset(tab: AppTab, offset: Int, generation: Long = this.generation) {
        if (!isGenerationCurrent(generation)) return
        tabScrollOffsets[tab] = offset.coerceAtLeast(0)
    }

    fun captureGeneration(): Long = generation

    fun isGenerationCurrent(value: Long): Boolean = generation == value

    fun initializeTripsExpansion(
        yearId: String,
        monthId: String?,
        dayId: String?,
        generation: Long = this.generation
    ) {
        if (!isGenerationCurrent(generation)) return
        if (tripsExpansionInitialized) return
        expandedTripYears = setOf(yearId)
        expandedTripMonths = monthId?.let(::setOf).orEmpty()
        expandedTripDays = dayId?.let(::setOf).orEmpty()
        tripsExpansionInitialized = true
    }

    fun setTripYearExpanded(id: String, expanded: Boolean, generation: Long = this.generation) {
        if (!isGenerationCurrent(generation)) return
        expandedTripYears = expandedTripYears.withToggled(id, expanded)
    }

    fun setTripMonthExpanded(id: String, expanded: Boolean, generation: Long = this.generation) {
        if (!isGenerationCurrent(generation)) return
        expandedTripMonths = expandedTripMonths.withToggled(id, expanded)
    }

    fun setTripDayExpanded(id: String, expanded: Boolean, generation: Long = this.generation) {
        if (!isGenerationCurrent(generation)) return
        expandedTripDays = expandedTripDays.withToggled(id, expanded)
    }

    /** Reconcile only after final loaded data is available; loading placeholders must not clear state. */
    fun reconcileTripsExpansion(
        yearIds: Set<String>,
        monthIds: Set<String>,
        dayIds: Set<String>,
        generation: Long = this.generation
    ) {
        if (!isGenerationCurrent(generation)) return
        if (!tripsExpansionInitialized) return
        expandedTripYears = expandedTripYears intersect yearIds
        expandedTripMonths = expandedTripMonths intersect monthIds
        expandedTripDays = expandedTripDays intersect dayIds
    }

    /** Starts a new in-process UI session, invalidating callbacks from the previous session. */
    fun clear() {
        generation += 1L
        selectedTab = AppTab.MAIN
        tabScrollOffsets.clear()
        expandedTripYears = emptySet()
        expandedTripMonths = emptySet()
        expandedTripDays = emptySet()
        tripsExpansionInitialized = false
    }

    private fun Set<String>.withToggled(id: String, expanded: Boolean): Set<String> =
        if (expanded) this + id else this - id
}
