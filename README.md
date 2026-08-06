# BYD Collector

> This is personal side project for own purposes
>  
> Read-only Chinese BYD EV vehicle telemetry collector for DiLink 5.0, with direct local ADB/app_process data collection, SQLite storage, normalized vehicle state, MQTT/Home Assistant and InfluxDB export support, and outbound Telegram notifications
> 
> The app reads vehicle telemetry locally, stores raw and normalized values in SQLite, and can export live state to Home Assistant through MQTT and historical state to InfluxDB
> 
> AI was used to analize and write the code


## Current Features

- direct local vehicle telemetry collection through Android local ADB and a protocol-versioned app_process helper with an APK-owned read-only address whitelist
- compact-v2 SQLite storage for raw poll values, collection sessions, normalized current state/history, integration queues, and diagnostics
- grouped native int/float polling for the curated main set, with ordered results and in-helper scalar fallback
- grouped native debug round-robin polling for the full research catalog in one batch, on the same 500 ms cadence and fallback contract
- normalized vehicle state for three SOC sources, remaining/trip/cumulative battery energy, charging, doors, tires, climate, speed, odometer, radar distance sensors and related fields
- Home Assistant MQTT Discovery and live-state publishing
- `InfluxDB v1` export for historical telemetry, limited globally to 300 rows per export pass
- optional outbound-only Telegram Bot API notifications with editable event templates, a durable retry queue, and trip-summary delay configured in seconds
- Android Keystore-backed storage for MQTT, InfluxDB, and Telegram secrets, with fail-closed migration, masked fields, and explicit eye controls
- main and debug round-robin database archives with shared storage retention and Android chooser sharing for completed ZIP archives
- shell-side keep-alive recovery with an explicit Shutdown gate and idempotent service reconcile
- process-aware delayed Tailscale start with exact foreground task/Home restoration
- transition-only diagnostics for native, mixed-fallback, and scalar-fallback read modes

## Installation

Download and install the latest APK from GitHub Releases
After first launch:
1. Open BYD Collector.
2. Set `Disable background Apps -> BYD Collector = OFF` when prompted.
3. Complete any Android runtime permission prompts when present.
4. Grant ADB access when Android shows the RSA authorization prompt.
5. Start telemetry collection from the app and enable the automatic starts you need.
6. Configure MQTT, InfluxDB, and/or outbound Telegram notifications if needed.

## Data Model

The main database is SQLite-based and keeps raw telemetry first. Normalized values are derived from raw fields and stored separately for UI, MQTT, and InfluxDB export.
Enum values are treated as field-specific. Unknown fields and unsupported values remain queryable instead of being discarded.

Fresh installations create compact-v2 main and round-robin databases. Existing round-robin databases are archived once before v2 starts. An existing main database is archived automatically only when the live Telegram, MQTT, and InfluxDB queues are empty and Telegram has no unfinished event state. Otherwise the automatic transition is deferred once and the legacy database remains active until the user performs the existing manual archive after reviewing the warning.

This is a format cutover, not size-based rotation: the active main normalized history remains lossless and has no automatic retention period. Existing archived databases still count against the configured shared archive limit and are compressed/retained by the existing archive worker.

## Home Assistant

BYD Collector can publish MQTT Discovery config and live state topics for Home Assistant. MQTT is intended for current vehicle state, while InfluxDB is intended for longer-term historical telemetry.
Personally for HA purposes influxDB seems like more viable options since when using in combination with Grafana you can get correct car state in connection with time. While MQTT only sends CURRENT state of the car (which means if you have missing data due to e.g. no HA connection, you're going to lose some statistics).
When enabled, the built-in Tailscale policy reacts to an unreachable configured HA endpoint and waits 10 seconds before a technical launch. A running Tailscale process is left untouched. After a real launch, Collector restores the previous main-display task or Home only while Tailscale is still foreground, so a user-selected foreground app is not minimized.

## Telegram notifications

Telegram integration is optional and disabled by default. Each supported event is also disabled independently until selected by the user.
The app sends plain text through the Telegram Bot API only; it does not accept Telegram commands, register webhooks, poll updates, or send media.
Charging notifications use a connected charge gun plus positive charging power as the primary signal, with the vehicle charging-state field used only when that primary data is incomplete. After process recovery, a restored charging session requires a new continuous 60-second low-power interval before it can be declared stopped.
Trip summaries default to 10 seconds after parking and can be configured from 5 to 300 seconds. The parked snapshot is persisted and finalized at that deadline even when no later telemetry poll succeeds. After process recovery, an overdue snapshot waits for fresh confirmed gear; a resumed drive suppresses the stale summary, while a 30-second fallback is used only if telemetry never arrives. Starting a new trip discards undelivered summaries from earlier trips so delayed messages do not arrive during the next drive.

## Tested

Tested on *Chinese version* of `BYD Sea Lion 07 EV 2025`, `DiLink 5.0`

## TO DO

- improve influxDB reupload
- change polling collection to callback collection on parameters change
- add parameter saving when the car is turned down
- add user notification when database storage approaches its configured limit
