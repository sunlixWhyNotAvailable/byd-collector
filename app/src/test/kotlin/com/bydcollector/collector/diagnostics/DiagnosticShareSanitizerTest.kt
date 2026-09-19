package com.bydcollector.collector.diagnostics

import org.json.JSONException
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticShareSanitizerTest {
    @Test
    fun wifiAndVinContextsAreMaskedWithoutGenericIdentifierHeuristics() {
        val sanitizer = DiagnosticShareSanitizer()
        val vin = "L123456789012345X"
        val text = sanitizer.sanitizeText(
            "WifiInfo: SSID: Home Network, BSSID: aa:bb:cc:dd:ee:ff, RSSI: -40\n" +
                "mSSID=\"Home, Network\" mBSSID=aa:bb:cc:dd:ee:ff version=2.8.1\n" +
                "wifi_ssid=Home Network firmware=1.2.3.4\n" +
                "CarPropertyService: getVin() -> $vin\nINFO_VIN value=$vin\n" +
                "device_id=$vin bluetooth_mac=aa:bb:cc:dd:ee:ff model=BYD Sea Lion 07"
        )
        assertFalse(text.contains("Home"))
        assertTrue(text.contains("SSID: [redacted], BSSID: [redacted], RSSI: -40"))
        assertTrue(text.contains("mSSID=\"[redacted]\" mBSSID=[redacted] version=2.8.1"))
        assertTrue(text.contains("wifi_ssid=[redacted] firmware=1.2.3.4"))
        assertTrue(text.contains("getVin() -> [VIN-1]"))
        assertTrue(text.contains("INFO_VIN value=[VIN-1]"))
        assertTrue(text.contains("device_id=$vin bluetooth_mac=aa:bb:cc:dd:ee:ff model=BYD Sea Lion 07"))
        val json = JSONObject(sanitizer.sanitizeJsonLine(
            """{"SSID":"Home Network","wifi_BSSID":"aa:bb:cc:dd:ee:ff","INFO_VIN":"$vin","technical_id":"$vin","firmware":"1.2.3.4"}"""
        ))
        assertEquals("[redacted]", json.getString("SSID"))
        assertEquals("[redacted]", json.getString("wifi_BSSID"))
        assertEquals("[VIN-1]", json.getString("INFO_VIN"))
        assertEquals(vin, json.getString("technical_id"))
        assertEquals("1.2.3.4", json.getString("firmware"))
    }

    @Test
    fun masksKnownSecretsHeadersCredentialsAndCoordinates() {
        val sanitizer = DiagnosticShareSanitizer()
        val line = sanitizer.sanitizeJsonLine(
            """{"password":"pw","mqttPassword":"mq","api_key":"api-secret","Proxy-Authorization":"basic abc","Set-Cookie":"sid=123; theme=dark","X-API-Key":"x-secret","telegram_bot_token":"12345:ABCDE","url":"https://u:p@example.test:8443/x","telegram":"https://api.telegram.org/bot12345:ABCDE/getMe?access_token=at","latitude":50.123,"longitude":30.456,"message":"geo:50.123,30.456 https://www.waze.com/ul?ll=50.123%2C30.456"}"""
        )
        val json = JSONObject(line)
        assertTrue(line.contains("\"password\""))
        assertTrue(line.contains("\"mqttPassword\""))
        assertEquals("[redacted]", json.getString("mqttPassword"))
        assertEquals("[redacted]", json.getString("Proxy-Authorization"))
        assertFalse(line.contains("pw"))
        assertFalse(line.contains("api-secret"))
        assertFalse(line.contains("basic abc"))
        assertFalse(line.contains("Bearer abc"))
        assertFalse(line.contains("sid=123"))
        assertFalse(line.contains("x-secret"))
        assertFalse(line.contains("12345:ABCDE"))
        assertFalse(line.contains("u:p@"))
        assertFalse(line.contains("50.123"))
        assertFalse(line.contains("30.456"))
        assertTrue(line.contains("waze.com"))

        val headers = sanitizer.sanitizeText("Authorization: Bearer abc\nProxy-Authorization=Basic xyz")
        assertTrue(headers.contains("Authorization: [redacted]"))
        assertTrue(headers.contains("Proxy-Authorization=[redacted]"))
        assertFalse(headers.contains("Bearer abc"))
        assertFalse(headers.contains("Basic xyz"))
    }

    @Test
    fun aliasesOnlyKnownPrivateIdentitiesAndRepeatWithinBundle() {
        val sanitizer = DiagnosticShareSanitizer()
        val first = sanitizer.sanitizeText("vin=LSVTEST1234567890 chat_id=-10042 account_id=acct-7")
        val second = sanitizer.sanitizeJsonLine(
            """{"vehicle_vin":"LSVTEST1234567890","chatId":-10042,"accountId":"acct-7"}"""
        )
        assertTrue(first.contains("[VIN-1]"))
        assertTrue(first.contains("[CHAT_ID-1]"))
        assertTrue(first.contains("[ACCOUNT_ID-1]"))
        assertTrue(second.contains("[VIN-1]"))
        assertTrue(second.contains("[CHAT_ID-1]"))
        assertTrue(second.contains("[ACCOUNT_ID-1]"))
        assertFalse(first.contains("LSVTEST1234567890"))
        assertFalse(second.contains("LSVTEST1234567890"))
    }

    @Test
    fun preservesTechnicalValuesAndDoesNotUseGenericHeuristics() {
        val sanitizer = DiagnosticShareSanitizer()
        val text = sanitizer.sanitizeText(
            "version=2.7.7 firmware=1.2.3.4 model=BYD Sea Lion07 " +
                "host=192.168.1.8:8086 pid=17 uid=2000 fid=42 boot_id=boot123 " +
                "id=123 token=ordinary secret=ordinary location=1,2"
        )
        assertTrue(text.contains("2.7.7"))
        assertTrue(text.contains("1.2.3.4"))
        assertTrue(text.contains("BYD Sea Lion07"))
        assertTrue(text.contains("192.168.1.8:8086"))
        assertTrue(text.contains("pid=17"))
        assertTrue(text.contains("uid=2000"))
        assertTrue(text.contains("fid=42"))
        assertTrue(text.contains("boot_id=boot123"))
        assertTrue(text.contains("id=123 token=ordinary secret=ordinary location=1,2"))
    }

    @Test
    fun handlesAndroidLocationAndRecognizedMapUrlsButNotArbitraryPairs() {
        val sanitizer = DiagnosticShareSanitizer()
        val text = sanitizer.sanitizeText(
                "Location[gps 50.1, 30.2 hAcc=5] " +
                "Google: https://www.google.com/maps/search/?api=1&query=50.1,30.2 " +
                "OSM: https://www.openstreetmap.org/?mlat=50.1&mlon=30.2#map=17/50.1/30.2 " +
                "pair=51.1,31.2"
        )
        assertFalse(text.contains("50.1"))
        assertFalse(text.contains("30.2"))
        assertTrue(text.contains("pair=51.1,31.2"))
        assertTrue(text.contains("google.com/maps"))
        assertTrue(text.contains("openstreetmap.org"))
    }

    @Test
    fun rejectsTrailingJsonData() {
        val error = assertFailsWith<JSONException> {
            DiagnosticShareSanitizer().sanitizeJsonLine("{\"ok\":true} trailing")
        }
        assertTrue(error.message.orEmpty().contains("Trailing data"))
    }

    @Test
    fun preservesJsonTypesAndScalarLines() {
        val sanitizer = DiagnosticShareSanitizer()
        val output = JSONObject(sanitizer.sanitizeJsonLine("{\"count\":3,\"ok\":true,\"none\":null}"))
        assertEquals(3, output.getInt("count"))
        assertTrue(output.getBoolean("ok"))
        assertTrue(output.isNull("none"))
        assertEquals("3", sanitizer.sanitizeJsonLine("3"))
    }

    @Test
    fun preservesSurroundingFieldsAndDoesNotReplaceMatchingSecretValuesGlobally() {
        val sanitizer = DiagnosticShareSanitizer()
        assertEquals("password=[redacted] version=2.7.7", sanitizer.sanitizeText("password=2.7.7 version=2.7.7"))
        assertEquals("firmware_password=1.2.3.4", sanitizer.sanitizeText("firmware_password=1.2.3.4"))
        assertEquals("vin=\"[VIN-1]\" version=2.7.7", sanitizer.sanitizeText("vin=\"V1234567890123456X\" version=2.7.7"))
        assertEquals(
            "Authorization: [redacted] version=2.7.7 host=100.1.2.3\nProxy-Authorization=[redacted] pid=123",
            sanitizer.sanitizeText("Authorization: Bearer abc version=2.7.7 host=100.1.2.3\nProxy-Authorization=Basic eHl6 pid=123")
        )
        assertEquals("{\"Authorization\":\"[redacted]\",\"count\":3}",
            sanitizer.sanitizeText("{\"Authorization\":\"Bearer abc\",\"count\":3}"))
        assertEquals("Cookie=\"[redacted]\" version=2.7.7",
            sanitizer.sanitizeText("Cookie=\"sid=abc; private=xyz\" version=2.7.7"))
        assertEquals("Cookie: [redacted]\nversion=2.7.7",
            sanitizer.sanitizeText("Cookie: sid=abc; private=xyz\nversion=2.7.7"))
    }

    @Test
    fun masksOnlyCoordinatesInRecognizedMapParameters() {
        val sanitizer = DiagnosticShareSanitizer()
        val unrelated = "https://example.test/?next=waze.com/&q=51.1,31.2"
        assertEquals(unrelated, sanitizer.sanitizeText(unrelated))
        val map = "https://maps.apple.com/?ll=50.1,30.2&version=2.7.7,3.0.0"
        val output = sanitizer.sanitizeText(map)
        assertFalse(output.contains("50.1"))
        assertFalse(output.contains("30.2"))
        assertTrue(output.contains("version=2.7.7,3.0.0"))
        assertEquals("https://www.google.com/maps/?q=2.7.7,3.0.0",
            sanitizer.sanitizeText("https://www.google.com/maps/?q=2.7.7,3.0.0"))
    }
}
