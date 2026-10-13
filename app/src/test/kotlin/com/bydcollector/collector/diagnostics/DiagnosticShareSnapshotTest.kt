package com.bydcollector.collector.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiagnosticShareSnapshotTest {
    @Test
    fun shareCopiesAreSanitizedBeforeZipWithoutChangingRecordingsOrDatabaseArchives() {
        val root = Files.createTempDirectory("collector-share-privacy").toFile()
        try {
            val original = File(root, "recordings").apply { mkdirs() }
            val event = JSONObject().put("version", "2.7.7").put("model", "BYD Sea Lion 07")
                .put("host", "100.100.1.2").put("port", 8086).put("pid", 512)
                .put("password", "fixture-secret").put("latitude", 50.123456)
                .put("longitude", 30.654321).put("vin", "L123456789012345X")
                .put("chat_id", "-1001234567890").put("SSID", "Private Home Network")
                .put("BSSID", "aa:bb:cc:dd:ee:ff")
                .put("IMEI", "356789012345670").put("IMSI", "255010123456789")
                .put("ICCID", "8938000123456789012")
                .put("firmware", "13.1.32.2601010.1").put("fid", 876609592)
                .put("timestamp", "2026-10-12T08:20:30+03:00")
                .put("request_id", "A1234567890123456").toString()
            val source = File(original, "operational_events.jsonl").apply { writeText(event + "\n") }
            val sourceBytes = source.readBytes()
            val databaseArchive = File(root, "database.zip").apply { writeBytes(sourceBytes) }
            val snapshot = File(root, "snapshot_test").apply { mkdirs() }
            original.copyRecursively(snapshot, overwrite = true)
            File(snapshot, "helper.log").writeText("vin=L123456789012345X version=2.7.7 host=100.100.1.2\n" +
                "WifiInfo: SSID: Private Home Network, BSSID: aa:bb:cc:dd:ee:ff\n" +
                "CarPropertyService: getVin() -> L123456789012345X\n" +
                "VIN L1234567890123456\nIMEI 356789012345671\nIMSI=255010123456780\n" +
                "iccid 8938000123456789013\npeer 01:23:45:67:89:ab\n01-23-45-67-89-ac\n" +
                "counter 1234567890123456789 firmware 13.1.32.2601010.1\n")

            assertEquals("ok", sanitizeDiagnosticSnapshot(snapshot).privacyStatus)
            val zip = File(root, "shared.zip")
            DiagnosticZipWriter.writeLatestZip(zip, snapshot)
            val entries = ZipInputStream(zip.inputStream()).use { input ->
                buildMap {
                    while (true) {
                        val entry = input.nextEntry ?: break
                        put(entry.name, input.readBytes().toString(Charsets.UTF_8))
                        input.closeEntry()
                    }
                }
            }
            val shared = entries.values.joinToString("\n")
            assertFalse(shared.contains("fixture-secret"))
            assertFalse(shared.contains("50.123456"))
            assertFalse(shared.contains("30.654321"))
            assertFalse(shared.contains("L123456789012345X"))
            assertFalse(shared.contains("-1001234567890"))
            assertFalse(shared.contains("Private Home Network"))
            assertFalse(shared.contains("aa:bb:cc:dd:ee:ff"))
            listOf("356789012345670", "356789012345671", "255010123456789", "255010123456780",
                "8938000123456789012", "8938000123456789013", "L1234567890123456",
                "01:23:45:67:89:ab", "01-23-45-67-89-ac").forEach {
                assertFalse(shared.contains(it), "Private identifier remains in ZIP: $it")
            }
            assertTrue(shared.contains("2.7.7"))
            assertTrue(shared.contains("BYD Sea Lion 07"))
            assertTrue(shared.contains("100.100.1.2"))
            assertTrue(shared.contains("13.1.32.2601010.1"))
            assertTrue(shared.contains("A1234567890123456"))
            assertTrue(shared.contains("1234567890123456789"))
            val redacted = JSONObject(entries.getValue("operational_events.jsonl"))
            assertEquals(876609592, redacted.getInt("fid"))
            assertEquals("2026-10-12T08:20:30+03:00", redacted.getString("timestamp"))
            assertEquals(8086, redacted.getInt("port"))
            assertEquals(512, redacted.getInt("pid"))
            assertTrue(entries.getValue("helper.log").contains(redacted.getString("vin")))
            assertTrue(entries.getValue("privacy_status.txt").contains("not guaranteed anonymous"))
            assertContentEquals(sourceBytes, source.readBytes())
            assertContentEquals(sourceBytes, databaseArchive.readBytes())
            assertFalse(snapshot.walkTopDown().any { it.name.endsWith(".tmp") })
            assertFailsWith<IllegalArgumentException> { sanitizeDiagnosticSnapshot(original) }
        } finally {
            root.deleteRecursively()
        }
    }

}
