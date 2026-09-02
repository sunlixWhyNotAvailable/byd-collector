package com.bydcollector.collector.ui

import com.bydcollector.collector.ui.compose.AppTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiSessionStateTest {
    @Test
    fun tabOffsetsRemainIndependentAcrossActivityReattach() {
        val session = UiSessionState()
        session.updateScrollOffset(AppTab.MAIN, 120)
        session.updateScrollOffset(AppTab.TRIPS, 480)
        session.selectTab(AppTab.TRIPS)

        assertEquals(AppTab.TRIPS, session.selectedTab)
        assertEquals(120, session.scrollOffset(AppTab.MAIN))
        assertEquals(480, session.scrollOffset(AppTab.TRIPS))
        assertEquals(AppTab.MAIN, UiSessionState().selectedTab)
        assertEquals(0, UiSessionState().scrollOffset(AppTab.TRIPS))
    }

    @Test
    fun clearStartsFreshSessionAndInvalidatesOldGeneration() {
        val session = UiSessionState()
        session.selectTab(AppTab.STORAGE)
        session.updateScrollOffset(AppTab.STORAGE, 240)
        val oldGeneration = session.captureGeneration()

        session.clear()

        assertFalse(session.isGenerationCurrent(oldGeneration))
        assertTrue(session.isGenerationCurrent(session.captureGeneration()))
        session.selectTab(AppTab.STORAGE, oldGeneration)
        session.updateScrollOffset(AppTab.STORAGE, 999, oldGeneration)
        session.initializeTripsExpansion("2026", "2026-08", "2026-08-01", oldGeneration)
        session.setTripYearExpanded("2026", true, oldGeneration)
        session.setTripMonthExpanded("2026-08", true, oldGeneration)
        session.setTripDayExpanded("2026-08-01", true, oldGeneration)
        assertEquals(AppTab.MAIN, session.selectedTab)
        assertEquals(0, session.scrollOffset(AppTab.STORAGE))
        assertFalse(session.tripsExpansionInitialized)
        assertTrue(session.expandedTripYears.isEmpty())
        assertTrue(session.expandedTripMonths.isEmpty())
        assertTrue(session.expandedTripDays.isEmpty())
    }

    @Test
    fun tripCollapseSurvivesRefreshAndRemovedIdsReconcileOnlyWhenRequested() {
        val session = UiSessionState()
        session.initializeTripsExpansion("2026", "2026-08", "2026-08-01")
        session.setTripYearExpanded("2026", false)
        session.setTripMonthExpanded("2026-08", false)

        session.reconcileTripsExpansion(setOf("2026"), setOf("2026-08"), setOf("2026-08-01"))
        assertFalse("2026" in session.expandedTripYears)
        assertFalse("2026-08" in session.expandedTripMonths)

        session.reconcileTripsExpansion(emptySet(), emptySet(), emptySet())
        assertTrue(session.expandedTripYears.isEmpty())
        assertTrue(session.expandedTripMonths.isEmpty())
    }
}
