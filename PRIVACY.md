# Privacy

**English** | [Українська](PRIVACY.uk.md)

BYD Collector is local-first. It does not automatically send vehicle telemetry, databases, diagnostics, screenshots, crash reports, or usage analytics to the developer.

## Data stored on the device

Main collection stores raw vehicle readings, Chinese field names and descriptions, timestamps, quality and failure metadata, and derived vehicle-state history in app-private SQLite databases.

When trip history is enabled (the default), a separate app-private `bydcollector_trips.db` stores session times, SOC/distance/energy summaries, received Android GPS route points, speed/consumption values, trust/quality metadata, and explicit location gaps. Location collection starts only after Android location permission is granted. Rejected fixes can remain as local diagnostics, but they are excluded from derived routes, current location, MQTT/InfluxDB location export, and Telegram.

At the start of a main session, the collector can read BYD's `/storage/emulated/0/energydata/EC_database.db` (or its `/sdcard` equivalent) when that file and Android all-files access are available. It opens only those known paths read-only and imports energy-consumption rows such as trip timestamps, duration, distance, electricity, and fuel fields into its private database. It does not modify the BYD-owned source database.

The optional `All data` collector reads a much broader read-only FID catalogue. Successful values can include vehicle identifiers, configuration and state fields, precise location coordinates, and other values whose meaning has not been confirmed. A field name does not prove that a returned value is valid or correctly decoded.

Operational events and retry or delivery state for enabled integrations are also stored locally. Diagnostic journal and logcat recording starts only when the user enables it from the `Logs` tab. Full-system logcat uses authorized local ADB and can contain system messages and data from unrelated processes, not only BYD Collector.

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

The detached read-only helper and keep-alive component can write operational logs to `/data/local/tmp/bydcollector_helper.log` and `/data/local/tmp/bydcollector_keepalive.log`. These files are outside the app-private directory and can remain after the app is uninstalled until they are removed or the device clears them.

## Archives and sharing

Database archives can contain raw and normalized telemetry, timestamps, trip and charging history, possible location values, quality and error metadata, integration queues, and rendered notification payloads. Diagnostic bundles can contain application events and logcat output recorded during the selected diagnostic session.

The app shares selected archives only after an explicit user action through Android's system share chooser. It does not provide a `Send to developer` upload. The selected receiving application's privacy policy applies once an archive is shared. Source archives are not automatically deleted after sharing.

Review every archive before sending it. Do not publish precise location or trip history, bot tokens, passwords, private network addresses, personal identifiers, or unrelated logcat data.

## Retention and deletion

The active main normalized history and the separate trip-route database currently have no automatic retention period. The trip database is not yet part of the archive/retention UI. Database archives are retained locally until the user deletes them or the configured archive storage limit removes the oldest deletable archives. Diagnostic files remain until they are replaced, deleted, or app data is cleared.

Clearing BYD Collector's app data or uninstalling the app removes its app-private databases, preferences, encrypted secret payloads, and ADB key, subject to Android device behavior. Files under `/data/local/tmp` may require separate removal. Data already sent to MQTT, InfluxDB, Telegram, or another receiving application must be managed at that destination; normal GitHub request metadata follows GitHub's own retention rules.
