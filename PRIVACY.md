# Privacy

**English** | [Українська](PRIVACY.uk.md)

BYD Collector is local-first. It does not automatically send vehicle telemetry, databases, diagnostics, screenshots, crash reports, or usage analytics to the developer.

## Data stored on the device

Main collection stores raw vehicle readings, Chinese field names and descriptions, timestamps, quality and failure metadata, and derived vehicle-state history in app-private SQLite databases.

A separate app-private `bydcollector_telegram.db` stores rendered Telegram messages that are waiting for delivery and one current event/runtime-state row. It is isolated from Main and Debug database maintenance so archiving either telemetry database does not discard pending notifications or charging/trip/debounce state after the one-time migration succeeds. While migration cannot be verified, Telegram delivery pauses and retries through normal runtime recovery. A confirmed completed migration remains valid after Main archiving; unknown reads are distinguished from proven missing legacy messages. Proven unresolved legacy obligations remain protected in the preserved Main archive.

When trip history is enabled (the default), a separate app-private `bydcollector_trips.db` stores session times, SOC/distance/energy summaries, received Android GPS route points, speed/consumption values, trust/quality metadata, and explicit location gaps. Location collection starts only after Android location permission is granted. Rejected fixes can remain as local diagnostics, but they are excluded from derived routes, current location, MQTT/InfluxDB location export, and Telegram.

The Trips database also retains pending completion records with the observed power-off time, available trip metrics and last trusted trip coordinate until the Telegram delivery store accepts them. This local recovery record is independent of the trip-history display switch and is not itself an instruction to send a message; configured Telegram switches still apply. Accepted completion records are removed, while a sequence/receipt prevents duplicate local handoff.

At the start of a main session, the collector can read BYD's `/storage/emulated/0/energydata/EC_database.db` (or its `/sdcard` equivalent) when that file and Android all-files access are available. It opens only those known paths read-only and imports energy-consumption rows such as trip timestamps, duration, distance, electricity, and fuel fields into its private database. It does not modify the BYD-owned source database.

The optional `All data` collector reads a much broader read-only FID catalogue. Successful values can include vehicle identifiers, configuration and state fields, precise location coordinates, and other values whose meaning has not been confirmed. A field name does not prove that a returned value is valid or correctly decoded.

Operational events and retry or delivery state for enabled integrations are also stored locally. Existing application events are continuously mirrored—not telemetry samples—to an app-private JSONL journal with one active file plus three 2 MiB rotations (8 MiB maximum). There is no separate Journal UI. Full-system logcat recording starts only when the user explicitly starts it under `Options -> Keep alive`; it uses authorized local ADB, is capped at eight 16 MiB segments (128 MiB total), and can contain system messages and data from unrelated processes, not only BYD Collector.

## Network connections

### GitHub updates

Automatic update checking is enabled by default and can be disabled in `Options`. A check requests the latest release metadata from `api.github.com` with the `BYDCollector-UpdateCheck` user agent. It does not attach vehicle telemetry, databases, logs, credentials, or screenshots. GitHub still receives normal connection metadata such as the public IP address and request headers.

An APK is downloaded from the project's GitHub Releases only after the user accepts an available update.

### MQTT and Home Assistant

MQTT is disabled until configured and enabled by the user. It sends selected normalized current-state fields and Home Assistant discovery messages to the broker specified by the user.

Precise location is excluded unless the ordinary MQTT `location` category is enabled; that category is off by default.

The current MQTT transport uses plain `tcp://` without TLS. MQTT credentials and payloads can therefore be visible to the local network or intermediaries. Use only a trusted network and broker until TLS support is implemented.

### InfluxDB

InfluxDB export is disabled until configured and enabled by the user. It sends selected normalized history, timestamps, field names, values, and tags to the endpoint specified by the user.

Precise location history is excluded unless the ordinary InfluxDB `location` category is enabled; that category is off by default.

The current InfluxDB v1 transport uses plain `http://` without TLS. InfluxDB credentials and telemetry can therefore be visible to the local network or intermediaries. Use only a trusted network and endpoint until TLS support is implemented.

### Telegram

Telegram is disabled until configured and enabled by the user. It sends only user-selected event messages to the configured chat through the Telegram Bot API over HTTPS. Trip-summary location is off by default; its separate button opens a modal for enabling it and selecting Google, Waze, Apple, and OpenStreetMap links. When enabled, the selected navigation links encode the last trusted coordinate, but the summary adds no separate raw-coordinate, capture-time, or age rows. The collector does not accept Telegram commands, register a webhook, poll incoming updates, or send media.

### OpenStreetMap

Opening a recorded route requests online map tiles from OpenStreetMap. The tile server receives ordinary connection metadata such as the public IP address, user agent, requested tile coordinates, and time. Tiles are cached in the app cache with a 64 MiB cap; no offline map package is downloaded.

Each configured destination is operated by its respective provider or by the user. Its own privacy and retention rules apply after data leaves the device. BYD Collector does not relay MQTT, InfluxDB, or Telegram data through a developer-operated server.

## Credentials and local files

The Telegram bot token and MQTT/InfluxDB usernames and passwords are stored as encrypted payloads backed by Android Keystore. Connection addresses, ports, database names, Telegram chat ID, message templates, and ordinary preferences remain in app-private preferences. The persistent local ADB key is stored in the app's private files.

The detached read-only helper and keep-alive component can write operational logs to `/data/local/tmp/bydcollector_helper.log` and `/data/local/tmp/bydcollector_keepalive.log`. These files are outside the app-private directory and can remain after the app is uninstalled until they are removed or the device clears them. `Share logs` attempts to include only the last 512 KiB of the keep-alive log and records a controlled unavailable/error status when it cannot be read. `Clear logs` attempts to truncate that keep-alive file in place; an ADB/helper failure does not block cleanup of app-private diagnostics.

## Archives and sharing

Database archives can contain raw and normalized telemetry, timestamps, trip and charging history, possible location values, quality and error metadata, and Main-owned integration queues. Current Main/Debug archives do not include the live Telegram sidecar. Older Main archives created before the one-time sidecar migration can still contain rendered Telegram payloads and legacy event state. Database archives are separate from diagnostic bundles. A diagnostic ZIP contains snapshot metadata, the bounded JSONL operational journal, up to 200 recent SQLite `collector_events`, active/latest logcat provenance and a best-effort logcat copy, the bounded keep-alive helper tail or its status entry, and a bounded 64 KiB redacted `trips_telegram_evidence.txt` sidecar. That sidecar contains hashed trip/dedupe/dependency references, route continuity/final-point quality, and bounded Telegram outbox/runtime metadata; it excludes coordinates, message payloads, credentials, URLs, and database copies. Main, Debug, Trips, and Telegram databases are not included.

The ZIP also includes up to 128 KiB of Influx evidence: nonsecret configured/current connection state, passive Android network flags, cursor metadata and up to 200 recent export events. The rotating journal records cycle/skip reasons, retry/work state, Test/export endpoint profiles and request outcomes, without telemetry payloads or credentials. Database evidence is read-only and may be partial when maintenance is active or a table is unavailable; missing data is reported rather than recovered or initialized by Share.

Before ZIP creation, the app filters only temporary diagnostic Share copies using recognized sensitive contexts: credential and authentication/cookie fields, URL credentials, explicit coordinate formats, named Wi-Fi SSID/BSSID properties and private VIN/chat/account fields and VIN API results. Private identifiers receive bundle-local aliases so repeated occurrences remain comparable. This is not a general number or IP filter: app/firmware versions, vehicle names, hosts/IP addresses, ports, timing, technical identifiers and existing hashed trip references remain intact. Invalid or oversized records/components are omitted with safe status metadata; valid surrounding JSONL records are retained. `privacy_status.txt` reports omissions and warns that arbitrary full-system logcat is best-effort, not guaranteed anonymous. Original recordings, database archives and integration exports are not rewritten by this filter.

The app shares selected archives only after an explicit user action through Android's system share chooser. Each `Share logs` action creates a fresh app-private diagnostic snapshot and opens that chooser with only the ZIP attachment, without a prefilled subject or message; it does not upload automatically or provide a `Send to developer` endpoint. The selected receiving application's privacy policy applies once an archive is shared. Source archives are not automatically deleted after sharing.

Review every archive before sending it. Do not publish precise location or trip history, bot tokens, passwords, private network addresses, personal identifiers, or unrelated logcat data.

## Retention and deletion

The active main normalized history and the separate trip-route database currently have no automatic retention period. The trip database is not yet part of the archive/retention UI. The Telegram sidecar keeps at most 1,000 pending messages, deletes delivered rows, and can prune previously attempted rows after 30 days; `Clear logs` does not delete it. Database archives are retained locally until the user deletes them or the configured archive storage limit removes the oldest deletable archives. Diagnostic files remain until cleared or app data is removed. `Clear logs` deletes completed diagnostic captures and generated bundles, resets the JSONL journal before a new cleanup-result event starts the next journal, and attempts to truncate the keep-alive helper log while preserving an active logcat capture. An immutable copy selected for sharing is protected from cleanup for 10 minutes so the receiving application can finish reading it; after that it is removed by the next Share/Clear action or by Android cache cleanup.

Clearing BYD Collector's app data or uninstalling the app removes its app-private databases, preferences, encrypted secret payloads, and ADB key, subject to Android device behavior. Files under `/data/local/tmp` may require separate removal. Data already sent to MQTT, InfluxDB, Telegram, or another receiving application must be managed at that destination; normal GitHub request metadata follows GitHub's own retention rules.
