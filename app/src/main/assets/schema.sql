PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS catalog_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    version TEXT NOT NULL UNIQUE,
    seed_file TEXT,
    imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes TEXT
);

CREATE TABLE IF NOT EXISTS parameter_catalog (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    catalog_version_id INTEGER NOT NULL,
    source_id TEXT,
    key TEXT NOT NULL,
    name TEXT NOT NULL,
    group_name TEXT,
    include_desc INTEGER NOT NULL CHECK (include_desc IN (0, 1)),
    note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (catalog_version_id) REFERENCES catalog_versions(id),
    UNIQUE (catalog_version_id, key)
);

CREATE TABLE IF NOT EXISTS collection_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    catalog_version_id INTEGER,
    session_type TEXT,
    source_file TEXT,
    source_folder TEXT,
    scenario_hint TEXT,
    started_at TEXT,
    ended_at TEXT,
    di_plus_version TEXT,
    vehicle_model TEXT,
    import_quality TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (catalog_version_id) REFERENCES catalog_versions(id)
);

CREATE TABLE IF NOT EXISTS polls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    ts TEXT NOT NULL,
    ok INTEGER CHECK (ok IN (0, 1) OR ok IS NULL),
    elapsed_ms INTEGER,
    request_count INTEGER,
    errors TEXT,
    error_category TEXT,
    error_message TEXT,
    requested_parameter_count INTEGER,
    received_parameter_count INTEGER,
    missing_parameter_count INTEGER,
    raw_response_body TEXT,
    import_quality TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES collection_sessions(id)
);

CREATE TABLE IF NOT EXISTS poll_values (
    poll_id INTEGER PRIMARY KEY,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (poll_id) REFERENCES polls(id)
);

CREATE TABLE IF NOT EXISTS vehicle_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    poll_id INTEGER,
    ts TEXT NOT NULL,
    snapshot_json TEXT,
    data_quality TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES collection_sessions(id),
    FOREIGN KEY (poll_id) REFERENCES polls(id)
);

CREATE TABLE IF NOT EXISTS parameter_observations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    parameter_id INTEGER NOT NULL,
    catalog_version_id INTEGER NOT NULL,
    session_id INTEGER,
    di_plus_version TEXT,
    raw_status TEXT NOT NULL DEFAULT 'unknown',
    desc_status TEXT,
    notes TEXT,
    observed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (parameter_id) REFERENCES parameter_catalog(id),
    FOREIGN KEY (catalog_version_id) REFERENCES catalog_versions(id),
    FOREIGN KEY (session_id) REFERENCES collection_sessions(id)
);

CREATE TABLE IF NOT EXISTS ec_import_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER,
    ts TEXT NOT NULL,
    source_path TEXT,
    ok INTEGER NOT NULL CHECK (ok IN (0, 1)),
    source_row_count INTEGER NOT NULL DEFAULT 0,
    source_max_id INTEGER,
    inserted_count INTEGER NOT NULL DEFAULT 0,
    updated_count INTEGER NOT NULL DEFAULT 0,
    error_category TEXT,
    error_message TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES collection_sessions(id)
);

CREATE TABLE IF NOT EXISTS ec_energy_consumption (
    source_id INTEGER PRIMARY KEY,
    month TEXT,
    date TEXT,
    start_timestamp INTEGER,
    end_timestamp INTEGER,
    is_deleted INTEGER,
    duration INTEGER,
    trip REAL,
    electricity REAL,
    fuel REAL,
    source_path TEXT,
    first_seen_session_id INTEGER,
    last_seen_session_id INTEGER,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (first_seen_session_id) REFERENCES collection_sessions(id),
    FOREIGN KEY (last_seen_session_id) REFERENCES collection_sessions(id)
);

CREATE TABLE IF NOT EXISTS normalized_field_catalog (
    field_key TEXT PRIMARY KEY,
    category TEXT NOT NULL,
    value_type TEXT NOT NULL,
    unit TEXT,
    display_name TEXT NOT NULL,
    device_class TEXT,
    state_class TEXT,
    entity_platform TEXT NOT NULL,
    source_keys TEXT NOT NULL,
    normalizer_id TEXT NOT NULL,
    stale_after_ms INTEGER NOT NULL,
    mqtt_default_enabled INTEGER NOT NULL CHECK (mqtt_default_enabled IN (0, 1)),
    catalog_version TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS vehicle_state_current (
    field_key TEXT PRIMARY KEY,
    category TEXT NOT NULL,
    value_type TEXT NOT NULL,
    value_text TEXT,
    value_number REAL,
    value_bool INTEGER CHECK (value_bool IN (0, 1) OR value_bool IS NULL),
    quality TEXT NOT NULL,
    unit TEXT,
    source_poll_id INTEGER,
    source_keys TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    changed_at TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (field_key) REFERENCES normalized_field_catalog(field_key),
    FOREIGN KEY (source_poll_id) REFERENCES polls(id)
);

CREATE TABLE IF NOT EXISTS vehicle_state_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    field_key TEXT NOT NULL,
    category TEXT NOT NULL,
    value_type TEXT NOT NULL,
    value_text TEXT,
    value_number REAL,
    value_bool INTEGER CHECK (value_bool IN (0, 1) OR value_bool IS NULL),
    quality TEXT NOT NULL,
    unit TEXT,
    source_poll_id INTEGER,
    source_keys TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    changed_at TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (field_key) REFERENCES normalized_field_catalog(field_key),
    FOREIGN KEY (source_poll_id) REFERENCES polls(id)
);

CREATE TABLE IF NOT EXISTS mqtt_publish_state (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    target_key TEXT NOT NULL UNIQUE,
    target_type TEXT NOT NULL,
    payload_hash TEXT,
    last_published_at TEXT,
    last_error_at TEXT,
    last_error TEXT,
    settings_hash TEXT,
    catalog_hash TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mqtt_outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    target_key TEXT NOT NULL UNIQUE,
    target_type TEXT NOT NULL,
    topic TEXT NOT NULL,
    payload TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    retained INTEGER NOT NULL CHECK (retained IN (0, 1)),
    qos INTEGER NOT NULL,
    priority INTEGER NOT NULL DEFAULT 100,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    next_attempt_at TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TEXT,
    last_error_at TEXT,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_mqtt_outbox_due
ON mqtt_outbox(next_attempt_at, priority, updated_at);

CREATE TABLE IF NOT EXISTS mqtt_retry_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    failure_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TEXT,
    last_failure_at TEXT,
    last_success_at TEXT,
    last_error TEXT,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS influx_export_cursor (
    field_key TEXT PRIMARY KEY,
    last_exported_history_id INTEGER NOT NULL DEFAULT 0,
    last_success_at TEXT,
    last_error_at TEXT,
    last_error TEXT
);

CREATE TABLE IF NOT EXISTS influx_export_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    status TEXT NOT NULL DEFAULT 'stopped',
    mode TEXT,
    pending_rows INTEGER NOT NULL DEFAULT 0,
    oldest_pending_at TEXT,
    next_retry_at TEXT,
    last_success_at TEXT,
    last_error_at TEXT,
    last_error TEXT,
    exported_rows_total INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT
);

CREATE TABLE IF NOT EXISTS influx_export_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts TEXT NOT NULL,
    event_type TEXT NOT NULL,
    message TEXT,
    batch_count INTEGER,
    from_history_id INTEGER,
    to_history_id INTEGER
);

CREATE INDEX IF NOT EXISTS idx_vehicle_state_current_category
    ON vehicle_state_current(category);

CREATE INDEX IF NOT EXISTS idx_vehicle_state_history_field_time
    ON vehicle_state_history(field_key, changed_at);

CREATE INDEX IF NOT EXISTS idx_vehicle_state_history_category_time
    ON vehicle_state_history(category, changed_at);

CREATE INDEX IF NOT EXISTS idx_parameter_catalog_version_key
    ON parameter_catalog(catalog_version_id, key);

CREATE INDEX IF NOT EXISTS idx_collection_sessions_catalog_version
    ON collection_sessions(catalog_version_id);

CREATE INDEX IF NOT EXISTS idx_polls_session_ts
    ON polls(session_id, ts);

CREATE INDEX IF NOT EXISTS idx_vehicle_snapshots_session_ts
    ON vehicle_snapshots(session_id, ts);

CREATE INDEX IF NOT EXISTS idx_ec_import_runs_session_ts
    ON ec_import_runs(session_id, ts);

CREATE INDEX IF NOT EXISTS idx_ec_energy_consumption_start
    ON ec_energy_consumption(start_timestamp);
