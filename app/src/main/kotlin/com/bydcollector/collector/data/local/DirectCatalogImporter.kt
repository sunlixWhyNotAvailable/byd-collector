package com.bydcollector.collector.data.local

import android.content.ContentValues
import com.bydcollector.collector.data.direct.DirectFidRegistry

class DirectCatalogImporter(
    private val helper: TelemetryDatabaseHelper
) {
    fun ensureImported(): Long {
        val db = helper.writableDatabase
        db.rawQuery("SELECT id FROM catalog_versions WHERE version = ?", arrayOf(DirectFidRegistry.CATALOG_VERSION)).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }

        db.beginTransaction()
        try {
            val catalogVersionId = db.insertOrThrow(
                "catalog_versions",
                null,
                ContentValues().apply {
                    put("version", DirectFidRegistry.CATALOG_VERSION)
                    put("seed_file", "code:DirectFidRegistry")
                    put("notes", "Read-only autoservice FID catalog from wide poll session 20260605_161751; dynamic=134; all dynamic rows promoted to prod polling")
                }
            )

            DirectFidRegistry.entries.forEach { entry ->
                db.insertOrThrow(
                    "parameter_catalog",
                    null,
                    ContentValues().apply {
                        put("catalog_version_id", catalogVersionId)
                        put("source_id", entry.sourceId)
                        put("key", entry.key)
                        put("name", entry.featureNames)
                        put("group_name", entry.groupName)
                        put("include_desc", 1)
                        put("note", entry.note)
                    }
                )
            }

            db.setTransactionSuccessful()
            return catalogVersionId
        } finally {
            db.endTransaction()
        }
    }
}
