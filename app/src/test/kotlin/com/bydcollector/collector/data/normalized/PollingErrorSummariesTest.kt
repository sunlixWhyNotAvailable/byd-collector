package com.bydcollector.collector.data.normalized

import kotlin.test.Test
import kotlin.test.assertEquals

class PollingErrorSummariesTest {
    @Test
    fun mapsCurrentDirectAndAdbFailures() {
        assertEquals("ADB not authorized", PollingErrorSummaries.summary("adb_authorization_required"))
        assertEquals("ADB unavailable", PollingErrorSummaries.summary("adb_authorization_unavailable"))
        assertEquals("ADB authorization timeout", PollingErrorSummaries.summary("adb_authorization_timeout"))
        assertEquals("Direct telemetry unavailable", PollingErrorSummaries.summary("helper_launch_failed"))
        assertEquals("Direct telemetry unavailable", PollingErrorSummaries.summary("helper_unavailable"))
        assertEquals("Direct telemetry unavailable", PollingErrorSummaries.summary("helper_launch_backoff"))
        assertEquals("Direct telemetry unavailable", PollingErrorSummaries.summary("bridge_launch_failed"))
        assertEquals("Direct telemetry unavailable", PollingErrorSummaries.summary("bridge_unavailable"))
        assertEquals("Direct telemetry error", PollingErrorSummaries.summary("autoservice_snapshot_empty"))
        assertEquals("Direct telemetry error", PollingErrorSummaries.summary("autoservice_partial_failure"))
        assertEquals("service start failed", PollingErrorSummaries.summary("service_start_error"))
    }

    @Test
    fun appendsMessageOnlyForStoredLastPollStatus() {
        assertEquals(
            "Direct telemetry unavailable: timeout waiting for helper",
            PollingErrorSummaries.summary("helper_unavailable", "timeout waiting for helper")
        )
        assertEquals("unknown", PollingErrorSummaries.summary(null, ""))
    }
}
