package com.bydcollector.collector.data.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TelemetryWorkerImportContractTest {
    @Test
    fun workerIdentityAndPollAreCommittedInOneTransaction() {
        val store = projectFile(
            "app/src/main/kotlin/com/bydcollector/collector/data/local/TelemetryStore.kt",
            "src/main/kotlin/com/bydcollector/collector/data/local/TelemetryStore.kt"
        ).readText()
        val method = store.substringAfter("fun insertWorkerPoll(")
            .substringBefore("fun recordEvent(")

        assertInOrder(
            method,
            "db.beginTransaction()",
            "workerPollId(db, identity)",
            "insertPollInTransaction(db, sessionId, input, parameters)",
            "\"telemetry_worker_imports\"",
            "db.setTransactionSuccessful()",
            "db.endTransaction()"
        )
        assertTrue(method.contains("WorkerPollImportResult(existingPollId, inserted = false)"))
        assertTrue(method.contains("WorkerPollImportResult(inserted.pollId, inserted = true)"))
    }

    private fun assertInOrder(text: String, vararg snippets: String) {
        var cursor = -1
        snippets.forEach { snippet ->
            val index = text.indexOf(snippet, cursor + 1)
            assertTrue(index > cursor, "Missing or out-of-order snippet: $snippet")
            cursor = index
        }
    }

    private fun projectFile(vararg paths: String): File =
        paths.map(::File).firstOrNull(File::isFile) ?: error("Missing project file: ${paths.joinToString()}")
}
