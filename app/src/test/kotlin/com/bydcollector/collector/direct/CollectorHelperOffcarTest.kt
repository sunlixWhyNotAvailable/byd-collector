package com.bydcollector.collector.direct

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectorHelperOffcarTest {
    @Test
    fun protocolV3ControlsStayBehindTheAppUidGuardAndClientDoesNotLaunch() {
        val daemon = sourceFile("java/com/bydcollector/collector/direct/CollectorHelperDaemon.java").readText()
        val client = sourceFile("kotlin/com/bydcollector/collector/data/direct/DirectVehicleHelperClient.kt").readText()

        assertEquals(3, CollectorHelperProtocol.PROTOCOL_VERSION)
        assertTrue(CollectorHelperProtocol.TX_MAIN_HEARTBEAT != CollectorHelperProtocol.TX_READ_BATCH)
        assertTrue(CollectorHelperProtocol.TX_OFFCAR_DISARM != CollectorHelperProtocol.TX_READ_BATCH)
        val uidGuard = daemon.indexOf("Binder.getCallingUid() != appUid")
        assertTrue(uidGuard >= 0)
        assertTrue(uidGuard < daemon.indexOf("code == CollectorHelperProtocol.TX_MAIN_HEARTBEAT"))
        assertTrue(uidGuard < daemon.indexOf("code == CollectorHelperProtocol.TX_OFFCAR_DISARM"))
        assertTrue(client.contains("fun mainHeartbeat(): Boolean"))
        assertTrue(client.contains("fun offcarDisarm(): Boolean"))
        assertFalse(client.contains("DirectBridgeManager"))
    }

    @Test
    fun offcarStateArmsAfterFirstHeartbeatPollsAtTenThenFiveSecondsAndSuppressesInflightReads() {
        val state = CollectorHelperDaemon.OffcarState()
        assertNull(state.beginPoll(20_000))
        assertEquals(CollectorHelperDaemon.OffcarEvent.ARMED, state.heartbeat(100))
        assertNull(state.beginPoll(10_099))

        val first = assertNotNull(state.beginPoll(10_100))
        assertTrue(first.firstFallback)
        assertNotNull(state.completePoll(first, 10_200))
        assertNull(state.beginPoll(15_099))
        val second = assertNotNull(state.beginPoll(15_100))
        assertFalse(second.firstFallback)

        assertEquals(CollectorHelperDaemon.OffcarEvent.RECOVERED, state.heartbeat(15_200))
        assertNull(state.completePoll(second, 15_201))
        assertNull(state.beginPoll(25_199))
        val afterRecovery = assertNotNull(state.beginPoll(25_200))
        assertEquals(CollectorHelperDaemon.OffcarEvent.DISARMED, state.disarm())
        assertNull(state.completePoll(afterRecovery, 25_201))
        assertNull(state.beginPoll(50_000))
    }

    @Test
    fun controlAfterInitialPollGateSuppressesTheFinalAppendCommit() {
        listOf<(CollectorHelperDaemon.OffcarState) -> Unit>(
            { it.heartbeat(10_201) },
            { it.disarm() }
        ).forEach { control ->
            val state = CollectorHelperDaemon.OffcarState()
            state.heartbeat(100)
            val permit = assertNotNull(state.beginPoll(10_100))
            assertNotNull(state.completePoll(permit, 10_200))
            control(state)

            var appended = false
            assertFalse(state.commitPoll(permit, 10_202) {
                appended = true
                true
            })
            assertFalse(appended)
        }
    }

    @Test
    fun evidenceRowsCarryBootAndRunIdentityAndAppendAcrossHelperRestart() {
        val root = Files.createTempDirectory("bydcollector-offcar").toFile()
        try {
            val catalog = CollectorHelperDaemon.loadMainCatalog()
            val reserveCap = catalog.tsvBytes.size.toLong() + 64_000L
            val totalCap = reserveCap + 64_000L
            val telemetry = File(root, "telemetry_samples.jsonl")
            telemetry.writeText("partial", Charsets.UTF_8)
            val firstIdentity = identity("boot-a", "run-1", 101)
            val firstStore = CollectorHelperDaemon.EvidenceStore(
                root, catalog, firstIdentity, totalCap, reserveCap, 64_000L
            )

            assertTrue(firstStore.appendSample("{\"n\":1}", 0L))
            firstStore.appendLifecycle("helper_started", 0L, null, 1_700_000_000_101L, 50_101L)

            val secondIdentity = identity("boot-a", "run-2", 202)
            val secondStore = CollectorHelperDaemon.EvidenceStore(
                root, catalog, secondIdentity, totalCap, reserveCap, 64_000L
            )
            assertTrue(secondStore.appendSample("{\"n\":2}", 30_000L))
            secondStore.appendLifecycle("helper_started", 0L, null, 1_700_000_000_202L, 50_202L)

            val telemetryRows = telemetry.readLines(Charsets.UTF_8)
            assertEquals("partial", telemetryRows[0])
            assertTrue(telemetryRows[1].contains("\"boot_id\":\"boot-a\""))
            assertTrue(telemetryRows[1].contains("\"helper_run_id\":\"run-1\""))
            assertTrue(telemetryRows[1].contains("\"helper_pid\":101"))
            assertTrue(telemetryRows[2].contains("\"boot_id\":\"boot-a\""))
            assertTrue(telemetryRows[2].contains("\"helper_run_id\":\"run-2\""))
            assertTrue(telemetryRows[2].contains("\"helper_pid\":202"))

            val lifecycleRows = File(root, "helper_lifecycle.jsonl").readLines(Charsets.UTF_8)
            assertEquals(2, lifecycleRows.size)
            assertTrue(lifecycleRows[0].contains("\"boot_id\":\"boot-a\""))
            assertTrue(lifecycleRows[0].contains("\"helper_run_id\":\"run-1\""))
            assertTrue(lifecycleRows[1].contains("\"boot_id\":\"boot-a\""))
            assertTrue(lifecycleRows[1].contains("\"helper_run_id\":\"run-2\""))

            val beforeCap = telemetry.readBytes()
            val cappedStore = CollectorHelperDaemon.EvidenceStore(
                root, catalog, secondIdentity, totalCap, reserveCap, telemetry.length()
            )
            assertFalse(cappedStore.appendSample("{\"n\":3}", 60_000L))
            assertTrue(beforeCap.contentEquals(telemetry.readBytes()))
            assertEquals("cap", cappedStore.stopReason())
            assertTrue(File(root, "main_catalog_${catalog.sha256}.tsv").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sampleCarriesCatalogTimingMetricsQualityAndAllMainPositions() {
        val catalog = CollectorHelperDaemon.loadMainCatalog()
        val values = Array(catalog.rows.size) { index ->
            if (index == 1) CollectorHelperDaemon.ReadValue.error(CollectorHelperProtocol.STATUS_READ_ERROR, "failed")
            else CollectorHelperDaemon.ReadValue.ok(index)
        }
        val result = CollectorHelperDaemon.BatchResult(
            CollectorHelperProtocol.STATUS_OK,
            CollectorHelperProtocol.MODE_NATIVE_WITH_FALLBACK,
            true,
            4,
            1,
            1,
            1,
            12L,
            values,
            "one fallback"
        )

        val json = CollectorHelperDaemon.sampleJson(
            catalog,
            CollectorHelperDaemon.SampleGate(7L, 10_250L),
            result,
            1_700_000_000_000L,
            50_000L
        )

        assertEquals(81, catalog.rows.size)
        assertTrue(json.contains("\"generation\":7"))
        assertTrue(json.contains("\"catalog_sha256\":\"${catalog.sha256}\""))
        assertTrue(json.contains("\"catalog_version\":"))
        assertTrue(json.contains("\"epoch_ms\":1700000000000"))
        assertTrue(json.contains("\"elapsed_ms\":50000"))
        assertTrue(json.contains("\"heartbeat_age_ms\":10250"))
        assertTrue(json.contains("\"position_count\":81"))
        assertTrue(json.contains("\"mode\":\"native_with_fallback\""))
        assertTrue(json.contains("\"status_ok\":80"))
        assertTrue(json.contains("\"raw_missing\":1"))
        assertEquals(81, json.substringAfter("\"raw_values\":[").substringBefore(']').split(',').size)
        assertEquals(81, json.substringAfter("\"status_values\":[").substringBefore(']').split(',').size)
    }

    private fun sourceFile(path: String): File = listOf(
        File("src/main/$path"),
        File("app/src/main/$path")
    ).firstOrNull { it.isFile } ?: error("Missing source file: $path")

    private fun identity(bootId: String, runId: String, pid: Int) = CollectorHelperDaemon.HelperIdentity(
        bootId,
        runId,
        pid,
        1_700_000_000_000L + pid,
        50_000L + pid
    )
}
