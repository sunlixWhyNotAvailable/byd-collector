package com.bydcollector.collector.data.local

/** SQL shared with the host migration check; runtime code executes these same statements. */
internal object TelegramDatabaseSql {
    const val MIGRATE_V4_TO_V5 =
        "ALTER TABLE telegram_outbox ADD COLUMN occurred_at_ms INTEGER"

    const val CREATE_DELIVERY_RECEIPT_TABLE =
        "CREATE TABLE IF NOT EXISTS telegram_delivery_receipt (" +
            "dedupe_key TEXT PRIMARY KEY NOT NULL, " +
            "event_type TEXT NOT NULL, " +
            "confirmed_at_ms INTEGER NOT NULL, " +
            "telegram_message_id INTEGER)"

    const val CREATE_DELIVERY_RECEIPT_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_telegram_delivery_receipt_confirmed " +
            "ON telegram_delivery_receipt(confirmed_at_ms)"

    const val RECEIPT_BY_KEY =
        "SELECT dedupe_key, event_type, confirmed_at_ms, telegram_message_id " +
            "FROM telegram_delivery_receipt WHERE dedupe_key = ? LIMIT 1"

    const val INSERT_DELIVERY_RECEIPT =
        "INSERT OR IGNORE INTO telegram_delivery_receipt " +
            "(dedupe_key, event_type, confirmed_at_ms, telegram_message_id) VALUES (?, ?, ?, ?)"

    const val DELETE_OUTBOX_BY_KEY =
        "DELETE FROM telegram_outbox WHERE dedupe_key = ?"

    const val RELEASE_SUMMARY_DEPENDENTS =
        "UPDATE telegram_outbox SET waits_for_summary_key = NULL WHERE waits_for_summary_key = ?"

    const val PRUNE_DELIVERY_RECEIPTS =
        "DELETE FROM telegram_delivery_receipt WHERE confirmed_at_ms < ?"
}
