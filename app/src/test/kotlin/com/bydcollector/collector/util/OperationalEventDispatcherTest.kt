package com.bydcollector.collector.util

import org.junit.Test
import java.io.IOException
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationalEventDispatcherTest {
    @Test
    fun diagnosticDetailKeepsStageStackCauseAndSuppressedWhileRedactingSecrets() {
        val cause = IllegalStateException("mqtt_password=secret-value")
        val error = IllegalArgumentException("Authorization: Bearer access-secret", cause)
            .apply { addSuppressed(IOException("access_token=suppressed-secret")) }

        val detail = error.diagnosticDetail("stage=sqlite_read")

        assertContains(detail, "stage=sqlite_read")
        assertContains(detail, "IllegalArgumentException")
        assertContains(detail, "Caused by: java.lang.IllegalStateException")
        assertContains(detail, "Suppressed: java.io.IOException")
        assertContains(detail, "[redacted]")
        assertFalse("access-secret" in detail)
        assertFalse("secret-value" in detail)
        assertFalse("suppressed-secret" in detail)
    }

    @Test
    fun diagnosticDetailMarksTruncationExplicitly() {
        val detail = IllegalStateException("x".repeat(40_000)).diagnosticDetail()

        assertTrue(detail.length <= 32_768)
        assertTrue(detail.endsWith("[TRUNCATED; limit=32768 chars]"))
    }
}
