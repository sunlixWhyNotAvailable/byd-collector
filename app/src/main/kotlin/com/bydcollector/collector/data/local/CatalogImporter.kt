package com.bydcollector.collector.data.local

import android.content.ContentValues
import android.content.Context

class CatalogImporter(
    private val context: Context,
    private val helper: TelemetryDatabaseHelper
) {
    fun ensureImported(
        version: String = DEFAULT_VERSION,
        seedFile: String = DEFAULT_SEED_FILE
    ): Long {
        val db = helper.writableDatabase
        db.rawQuery("SELECT id FROM catalog_versions WHERE version = ?", arrayOf(version)).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }

        val rows = context.assets.open(seedFile).bufferedReader(Charsets.UTF_8).use { reader ->
            CsvCatalogParser.parse(reader.readText())
        }
        require(rows.size >= 120) { "Catalog seed must contain at least 120 parameters; found ${rows.size}" }
        require(rows.none { it.name.contains("????") }) { "Catalog seed contains corrupted Chinese names" }

        db.beginTransaction()
        try {
            val catalogVersionId = db.insertOrThrow(
                "catalog_versions",
                null,
                ContentValues().apply {
                    put("version", version)
                    put("seed_file", seedFile)
                    put("notes", "Bundled Android collector catalog seed")
                }
            )

            rows.forEach { row ->
                db.insertOrThrow(
                    "parameter_catalog",
                    null,
                    ContentValues().apply {
                        put("catalog_version_id", catalogVersionId)
                        put("source_id", row.sourceId)
                        put("key", row.key)
                        put("name", row.name)
                        put("group_name", row.groupName)
                        put("include_desc", if (row.includeDesc) 1 else 0)
                        put("note", row.note)
                    }
                )
            }

            db.setTransactionSuccessful()
            return catalogVersionId
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val DEFAULT_VERSION = "diplus-120-mixed-desc-utf8"
        const val DEFAULT_SEED_FILE = "diplus_params_120_mixed_desc_utf8.csv"
    }
}
