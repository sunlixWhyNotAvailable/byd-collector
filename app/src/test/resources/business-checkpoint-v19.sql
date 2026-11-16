-- Historical Main19 checkpoint DDL from 9efd1aa; independent of the current key catalog.
CREATE TABLE IF NOT EXISTS business_input_source_checkpoints (
    source_key TEXT PRIMARY KEY CHECK(source_key IN ('bodywork_power_level', 'charging_1009_876609560_5', 'charging_1009_876609586_5', 'charging_1009_89128973_5', 'charging_charge_battery_volt', 'charging_charge_current', 'charging_charging_vtov_discharge_status', 'gearbox_1011_555745336_5', 'speed_1013_-1807745016_7', 'statistic_1014_1145045040_5', 'statistic_1014_1246765072_5', 'statistic_statistic_this_trip_total_elec_consumption')),
    raw_value TEXT,
    desc_value TEXT,
    source_kind TEXT NOT NULL CHECK(source_kind IN ('poll', 'callback')),
    source_identity TEXT NOT NULL,
    source_boot_id TEXT NOT NULL,
    source_generator_id TEXT,
    source_sequence INTEGER,
    source_epoch INTEGER,
    source_wall_ms INTEGER NOT NULL,
    source_elapsed_ms INTEGER NOT NULL,
    source_poll_id INTEGER,
    updated_at_ms INTEGER NOT NULL
) WITHOUT ROWID;
