package com.bydcollector.collector.data.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactStorageSchemaContractTest {
    @Test
    fun rawHelperValuesRoundTripAsSignedInt() {
        assertEquals(Int.MIN_VALUE, PollReading("min", Int.MIN_VALUE.toString()).rawInt)
        assertEquals(Int.MAX_VALUE, PollReading("max", Int.MAX_VALUE.toString()).rawInt)
        assertEquals(-1, PollReading("float_bits", "-1").rawInt)
        assertNull(PollReading("missing", null).rawInt)
        assertNull(PollReading("invalid", "not-an-int").rawInt)
    }

    @Test
    fun freshSchemaIsMarkedCompactAndDoesNotRepeatHistoryMetadata() {
        val schema = projectFile("app/src/main/assets/schema_v2.sql", "src/main/assets/schema_v2.sql").readText()
        val history = schema.substringAfter("CREATE TABLE IF NOT EXISTS vehicle_state_history (")
            .substringBefore(");")

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS storage_meta"))
        assertTrue(schema.contains("VALUES ('main_telemetry', 2,"))
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS decoded_value_dictionary"))
        assertTrue(schema.contains("value TEXT COLLATE BINARY NOT NULL UNIQUE"))
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS normalized_history_field_catalog"))
        assertTrue(schema.contains("UNIQUE (field_key, category, value_type, unit, source_keys, normalizer_id, catalog_version)"))
        assertTrue(history.contains("field_id INTEGER NOT NULL"))
        assertTrue(history.contains("quality_code INTEGER NOT NULL"))
        assertTrue(history.contains("observed_at_ms INTEGER NOT NULL"))
        assertTrue(history.contains("changed_at_ms INTEGER NOT NULL"))
        assertFalse(history.contains("field_key TEXT"))
        assertFalse(history.contains("category TEXT"))
        assertFalse(history.contains("source_keys TEXT"))
        assertTrue(history.contains("REFERENCES normalized_history_field_catalog(id)"))
        assertFalse(schema.contains("idx_vehicle_state_history_field_time"))
        assertFalse(schema.contains("idx_vehicle_state_history_category_time"))
        assertTrue(schema.contains("ON vehicle_state_history(field_id, id)"))
    }

    @Test
    fun legacySchemaRemainsSeparateAndDowngradeFailsClosed() {
        val legacy = projectFile("app/src/main/assets/schema.sql", "src/main/assets/schema.sql").readText()
        val helper = projectFile(
            "app/src/main/kotlin/com/bydcollector/collector/data/local/TelemetryDatabaseHelper.kt",
            "src/main/kotlin/com/bydcollector/collector/data/local/TelemetryDatabaseHelper.kt"
        ).readText()

        assertFalse(legacy.contains("CREATE TABLE IF NOT EXISTS storage_meta"))
        assertTrue(legacy.contains("field_key TEXT NOT NULL"))
        assertTrue(helper.contains("if (compactV2) COMPACT_SCHEMA_ASSET else LEGACY_SCHEMA_ASSET"))
        assertTrue(helper.contains("if (!compactV2) ensureLegacySchemaCompatibility(db)"))
        assertTrue(helper.contains("Telemetry database downgrade \$oldVersion->\$newVersion is not supported"))
        assertFalse(helper.contains("private fun recreateDatabase"))
        assertFalse(helper.contains("DROP TABLE IF EXISTS"))
    }

    @Test
    fun storeUsesCompactRawDictionaryAndReconstructsInfluxRows() {
        val store = projectFile(
            "app/src/main/kotlin/com/bydcollector/collector/data/local/TelemetryStore.kt",
            "src/main/kotlin/com/bydcollector/collector/data/local/TelemetryStore.kt"
        ).readText()

        assertTrue(store.contains("reading.rawInt?.let"))
        assertTrue(store.contains("PollValueColumns.descId(parameter.key)"))
        assertTrue(store.contains("decoded_value_dictionary"))
        assertTrue(store.contains("INNER JOIN normalized_history_field_catalog AS field ON field.id = history.field_id"))
        assertTrue(store.contains("NULLIF(field.unit, '')"))
        assertTrue(store.contains("NormalizedQuality.fromStorageCode"))
        assertTrue(store.contains("normalizedIsoTime(cursor.getLong(11))"))
    }

    private fun projectFile(vararg paths: String): File =
        paths.map(::File).firstOrNull(File::isFile) ?: error("Missing project file: ${paths.joinToString()}")
}
