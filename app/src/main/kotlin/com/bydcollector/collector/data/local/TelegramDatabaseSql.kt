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

    const val CREATE_DELIVERY_OWNER_TABLE =
        "CREATE TABLE IF NOT EXISTS telegram_delivery_owner (" +
            "id INTEGER PRIMARY KEY CHECK (id = 1), " +
            "owner_id TEXT NOT NULL, " +
            "last_sequence INTEGER NOT NULL DEFAULT 0)"

    const val CREATE_DELIVERY_ATTEMPT_TABLE =
        "CREATE TABLE IF NOT EXISTS telegram_delivery_attempt (" +
            "id INTEGER PRIMARY KEY CHECK (id = 1), " +
            "owner_id TEXT NOT NULL, " +
            "attempt_id TEXT NOT NULL UNIQUE, " +
            "sequence INTEGER NOT NULL CHECK (sequence > 0), " +
            "outbox_id INTEGER NOT NULL, " +
            "dedupe_key TEXT NOT NULL, " +
            "event_type TEXT NOT NULL, " +
            "request_digest TEXT NOT NULL, " +
            "destination_digest TEXT NOT NULL, " +
            "dedupe_ref TEXT NOT NULL, " +
            "bot_scope TEXT NOT NULL, " +
            "started_at_ms INTEGER NOT NULL, " +
            "local_settled INTEGER NOT NULL DEFAULT 0 CHECK (local_settled IN (0, 1)), " +
            "reconciled_outcome TEXT)"

    const val CREATE_CONFLICT_RECOVERY_TABLE =
        "CREATE TABLE IF NOT EXISTS telegram_conflicting_delivery (" +
            "dedupe_key TEXT PRIMARY KEY NOT NULL, attempt_id TEXT NOT NULL, owner_id TEXT NOT NULL, " +
            "sequence INTEGER NOT NULL, event_type TEXT NOT NULL, payload TEXT, key_json TEXT NOT NULL, " +
            "diagnostic TEXT NOT NULL, archived_at_ms INTEGER NOT NULL, recovered_at_ms INTEGER, " +
            "resolution TEXT NOT NULL DEFAULT 'pending', last_error TEXT, reconciled_outcome TEXT)"

    const val CREATE_BUSINESS_RECOVERY_TABLE =
        "CREATE TABLE IF NOT EXISTS telegram_unconfirmed_business_recovery (" +
            "page_key TEXT PRIMARY KEY, page_payload TEXT NOT NULL, completions_json TEXT NOT NULL, " +
            "before_state_json TEXT, sources_json TEXT, messages_json TEXT, " +
            "status TEXT NOT NULL DEFAULT 'pending', next_attempt_at_ms INTEGER NOT NULL DEFAULT 0, " +
            "updated_at_ms INTEGER NOT NULL DEFAULT 0, last_error TEXT)"

    const val CREATE_PAGE_RECEIPTS_TABLE =
        "CREATE TABLE IF NOT EXISTS telegram_page_receipts (page_key TEXT PRIMARY KEY NOT NULL, " +
            "before_state_json TEXT, sources_json TEXT, messages_json TEXT NOT NULL, " +
            "completions_json TEXT NOT NULL, created_at_ms INTEGER NOT NULL)"

    const val CREATE_COMPLETION_RECEIPTS_TABLE =
        "CREATE TABLE IF NOT EXISTS telegram_completion_receipts (sequence INTEGER PRIMARY KEY, " +
            "identity TEXT NOT NULL UNIQUE, created_at_ms INTEGER NOT NULL)"

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
        "DELETE FROM telegram_delivery_receipt WHERE confirmed_at_ms < ? " +
            "AND NOT EXISTS (SELECT 1 FROM telegram_delivery_attempt a " +
            "WHERE a.dedupe_key = telegram_delivery_receipt.dedupe_key)"
}
