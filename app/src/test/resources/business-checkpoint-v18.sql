-- Historical Main18 checkpoint DDL from 9a1447d; retained for real upgrade/replay verification.
CREATE TABLE IF NOT EXISTS business_input_source_checkpoints (
                source_key TEXT PRIMARY KEY CHECK(source_key IN ('charging_charge_battery_volt', 'charging_charge_current', 'bodywork_power_level', 'charging_1009_876609586_5', 'charging_1009_89128973_5', 'charging_charging_vtov_discharge_status', 'charging_1009_876609560_5', 'statistic_1014_1145045040_5', 'statistic_1014_1246765072_5', 'statistic_statistic_this_trip_total_elec_consumption', 'speed_1013_-1807745016_7')),
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
