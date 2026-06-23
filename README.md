# BYD Collector

> This is personal side project for own purposes
>  
> Read-only Chinese BYD EV vehicle telemetry collector for DiLink 5.0, with direct local ADB/app_process data collection, SQLite storage, normalized vehicle state, MQTT/Home Assistant and InfluxDB export support
> 
> The app reads vehicle telemetry locally, stores raw and normalized values in SQLite, and can export live state to Home Assistant through MQTT and historical state to InfluxDB
> 
> AI was used to analize and write the code


## Current Features

- Direct local vehicle telemetry collection through Android local ADB and a narrow app_process helper.
- SQLite storage for raw poll values, collection sessions, normalized current state, history, MQTT state, and diagnostics.
- Curated main polling set for useful vehicle parameters.
- Separate debug round-robin polling for research parameters.
- Normalized vehicle state for SOC, charging, battery, doors, tires, climate, speed, odometer, radar distance sensors, and related fields.
- Home Assistant MQTT Discovery and live-state publishing.
- InfluxDB v1 export for historical telemetry.

## Installation

Download and install the latest APK from GitHub Releases
After first launch:
1. Open BYD Collector.
2. Grant ADB access when Android shows the RSA authorization prompt.
3. Set `Disable background Apps -> BYD Collector = OFF` in the BYD system settings.
4. Start telemetry collection from the app.
5. Configure MQTT and/or InfluxDB if needed.

## Data Model

The main database is SQLite-based and keeps raw telemetry first. Normalized values are derived from raw fields and stored separately for UI, MQTT, and InfluxDB export.
Enum values are treated as field-specific. Unknown fields and unsupported values remain queryable instead of being discarded.

## Home Assistant

BYD Collector can publish MQTT Discovery config and live state topics for Home Assistant. MQTT is intended for current vehicle state, while InfluxDB is intended for longer-term historical telemetry.

## Tested

Tested on *Chinese version* of `BYD Sea Lion 07 EV 2025`, `DiLink 5.0`

## TO DO

- add db deleting unnecessary and old poll_values (to reduce size)
- add max size storage options?
- add user notification when db is too big to pull and delete old db
- explore possibility of collecting data when the car is turned off (already confirmed the dilink is alive and watchdog/heartbeat process can exist)
