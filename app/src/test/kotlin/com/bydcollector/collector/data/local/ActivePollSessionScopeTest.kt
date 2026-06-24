package com.bydcollector.collector.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivePollSessionScopeTest {
    @Test
    fun stoppedRuntimeHidesLivePollQueriesFromPreviousSessions() {
        val scope = ActivePollSessionScope.from(running = false, activeSessionId = 42L)

        assertNull(scope.sessionId)
        assertNull(scope.lastSuccessAtSql())
        assertNull(scope.lastErrorAtSql())
        assertNull(scope.lastPollStatusSql())
    }

    @Test
    fun runningRuntimeLimitsLivePollQueriesToActiveSession() {
        val scope = ActivePollSessionScope.from(running = true, activeSessionId = 42L)

        assertEquals(42L, scope.sessionId)
        assertTrue(scope.lastSuccessAtSql().orEmpty().contains("session_id = 42"))
        assertTrue(scope.lastErrorAtSql().orEmpty().contains("session_id = 42"))
        assertTrue(scope.lastPollStatusSql().orEmpty().contains("session_id = 42"))
    }
}
