# BYD Collector

**English** | [Українська](README.uk.md)

<img align="right" src="app/src/main/res/drawable-nodpi/collector_apk_icon.png" alt="BYD Collector icon" width="112">

BYD Collector is a read-only telemetry collector for Chinese-market BYD vehicles using DiLink 5.0. It reads vehicle data locally through ADB, stores raw readings in SQLite, and displays vehicle status and trip history. Optional integrations send data to MQTT/Home Assistant, InfluxDB, and Telegram.

- **Vehicle focus:** Chinese-market BYD Sea Lion 07 EV
- **Package:** `com.bydcollector.collector`
- **Download:** [latest GitHub release](https://github.com/sunlixWhyNotAvailable/byd-collector/releases/latest)
- **Help:** read [Troubleshooting](#troubleshooting), then [Report a problem](#report-a-problem)

> **AI disclosure:** Generative AI tools are used in this project for code and diagnostic-log analysis, implementation, testing, and documentation.

The collector is an engineering and research tool, not an OEM diagnostic or safety system. It never sends vehicle-control commands.

## How collection works

The app reads vehicle parameters without changing them. It keeps raw values, available Chinese names and descriptions, timestamps, and data-quality information in a local SQLite database. These readings also provide the vehicle status shown in the app and shared with enabled integrations. Unavailable or unknown values remain identifiable rather than being presented as valid readings.

The v2.8.3 personal-test build uses hybrid callback collection. Supported fields switch to push updates after usable callbacks are observed, with verification reads every five seconds; unproven fields keep polling. Power, gear and SOC keep their regular fast reads. Every received callback, including repeated equal values, is retained separately in the existing SQLite databases. Vehicle validation is still required before a wider release.

Callback buffering is bounded: each stream has a combined 16 MiB RAM allowance for queued/in-flight callback payloads, of which the live transport can retain up to 12 MiB. Unconsumed retained batches move to disk after five seconds or when the RAM cap is reached; poll and callback files share the existing 128 MiB fallback-spool allowance. While the vehicle and screen are on, SQLite import is paced unless disk backlog grows; when the vehicle is off and the app remains running, it catches up without that pacing. Capacity or I/O losses are reported explicitly; data still in memory is not guaranteed to survive a kernel reboot.

Energy-projection errors are retried from a durable SQLite queue without stopping raw collection. Main database archiving is deferred until that queue drains. Normalized source ordering treats readings from the current boot as newer than retained readings from an earlier boot, even if the wall clock moved backwards.

The app remembers your selected tab, scroll positions, and expanded trip groups during the current session, including when you switch tabs or return from another app. Closing the app with `Shutdown` or restarting its process resets navigation.

You can toggle a switch by tapping its label or row; Telegram message switches also respond to their section headers. Finish editing a single-line field with the keyboard's `Done` button or by hiding the keyboard. Telegram templates keep Enter for new lines and finish editing when the keyboard is hidden.

## Features

| Area | What it provides |
| --- | --- |
| Main tab | Collection of 95 selected vehicle fields, automatic start, current status, and database information |
| All data tab | Research collection of 23,069 active read-only signatures, with raw values and recorded changes |
| Vehicle status | SOC, SOH, range, odometer, battery energy, charging, power, temperatures, doors, tires, climate, speed, and related readings |
| MQTT / Home Assistant | Home Assistant MQTT Discovery and live state for selected categories, with primary and alternative connections |
| InfluxDB | Historical export to `InfluxDB v1`, with automatic retries and progress saved between sessions |
| Telegram | Optional event notifications with editable templates and saved messages awaiting delivery |
| Trips | Local trip history, GPS routes, lossless route compression, and OpenStreetMap views coloured by speed or consumption |
| Storage | SQLite databases, ZIP archives, archive sharing/deletion, and a shared archive-size limit |
| Background operation | Service and connection recovery, optional Tailscale activation, and explicit shutdown |
| Diagnostics | Local event logs, optional full-system logcat recording, ZIP sharing, and log cleanup |

The app is available in English and Ukrainian, with dark and light themes.

<p align="center"><img src="docs/screenshots/en/main.png" alt="BYD Collector Main tab" width="100%"></p>

## Main tab

`Main` controls regular telemetry collection. Start it after ADB is authorized, or enable automatic start.

- Collects 95 selected read-only vehicle fields.
- Shows collection, MQTT, and InfluxDB status, last success, last error, and session errors.
- Displays SOC, remaining battery energy, SOH, odometer, cabin and battery temperatures, charging/discharging, range, cell-voltage difference, and category summaries. These KPI cards use an independent reader while the vehicle is on, even if Main and All data collection are stopped.
- Stores raw readings and normalized history locally. History is not automatically deleted or reduced.

The collected data includes charging mode and battery charging status, the charging connector type, opening percentages for all four windows, and estimated remaining perfume and installation state for three slots. The vehicle's estimated charging time remaining is exported as one `hh:mm:ss` value; unavailable readings are not shown as zero. Front and rear motor-current readings remain unitless raw values because their physical units are not yet verified. These fields are available to MQTT and InfluxDB through their selected categories.

Some fields have known interpretation limits: raw `CHARGING_STATE` is unreliable and is not used to identify charging for exports or Telegram. Charging notifications use confirmed battery charging states together with connector and power readings, falling back to connector and power when the battery state is unavailable. A confirmed full charge takes priority over a stopped notification. `max_discharge_power_allow_raw` has no verified unit or kW scale, and sunroof/windblind position codes must not be interpreted as a simple open/closed value.

Use `Stop` when collection is no longer needed. Background collection and its vehicle-specific limits are described under [Known limitations](#known-limitations).

## All data tab

`All data` is intended for research and diagnostics. It can run independently once the main collection service is available.

Its compact vehicle-status cards include the remaining percentage for all three perfume slots. A valid zero is shown as zero; unavailable readings stay distinct.

- Reads 23,069 active signatures from the retained 23,083-definition research catalog. Seven high-volume or irrelevant native fields are excluded from both polling and subscriptions; their old data remains available.
- Saves raw values, descriptions, data quality, and changes in a separate database.
- Helps identify changing fields, but a changing value alone does not prove what the field means.

This collection can use substantial storage. Stop it after your research session and archive its database when needed. Stopping `All data` does not stop Main collection.

<p align="center"><img src="docs/screenshots/en/parameters.png" alt="BYD Collector All data tab" width="100%"></p>

## Trips and routes

The `Trips` tab keeps sessions from vehicle power-on to power-off in a separate local database. It stores trip summaries and GPS routes rather than duplicating all telemetry. Sessions without movement are hidden from the ordinary list.

The table separates battery energy used, energy recovered during driving, and signed battery net (used minus recovered). Average consumption uses that net balance and may be negative. These calculated values are also available in the Battery category for MQTT and InfluxDB. Missing readings are not filled with assumed power. Data-quality information remains available in stored telemetry. Older trips retain their previous energy value as battery net and keep the corresponding average consumption; unavailable used/recovered values show a dash.

Older trips are recalculated in the background when the current main database still contains complete, usable telemetry for them. If that history is incomplete, their previous balance is retained and average consumption is calculated from that balance and the trip distance. Archived databases are not used for this recalculation.

`Current trip`, beside route compression, opens the active session's metrics and map. It refreshes while visible without resetting your map position or zoom. The existing Finish flag artwork marks the latest trusted coordinate as `Current position`, or `Last known position` when stale or followed by a GPS gap. No trusted coordinate means no flag. This uses the existing route refresh, without additional GPS collection or database writes. When the trip ends, the same trip stays open with its actual Finish marker.

Route recording requires Android location access. If you decline the first-run request, grant access later in Android settings. Untrusted GPS readings—including implausible jumps or speeds—are excluded from the displayed route. Loss of reception or rejected readings can leave gaps; the app does not draw a connecting line across rejected points.

The map uses online OpenStreetMap tiles and colours the route by speed or instantaneous consumption. A black outline helps distinguish it from map objects. Start and Finish markers have a matching legend. Gray consumption sections mean that consumption data is unavailable, not zero. Colour thresholds are configurable; defaults are `90/30 km/h` for speed and `15/20 kWh/100 km` for consumption.

Use route compression on the Trips tab to reduce storage without losing trips, route points, or maps. The current trip continues recording. Compression is not archiving or deletion; there is no automatic deletion of trip history.

<p align="center"><img src="docs/screenshots/en/trips.png" alt="BYD Collector trip history" width="100%"></p>

## MQTT, Home Assistant, and InfluxDB

The `HA integration` tab configures two independent export channels. A connection problem does not stop local collection.

Each channel supports a primary host/port and an optional alternative host/port for reaching the same server, for example through a home network or VPN. Labels show both the profile being edited and the connection currently in use. Connection fields and profile selection remain locked while the channel is started, including while it waits to retry; stop the channel before editing them. `Test connection` checks the selected profile only and does not switch the active connection.

### MQTT and Home Assistant

- Publishes Home Assistant MQTT Discovery configuration and current vehicle state.
- Supports battery, motion, body, climate, safety, and location categories.
- Location export is off by default and is independent of local route recording.
- Configure the broker host/port, optional alternative host/port, shared credentials, client ID, topic prefix, and discovery prefix.
- Tries the primary connection first and switches to the alternative for connection failures. It reuses a working connection; authentication, TLS, and data errors require attention rather than switching profiles.
- Retries failed publications automatically. MQTT carries current state, so updates can be missed while the broker is unreachable; local SQLite history remains available.

### InfluxDB

- Exports vehicle history to `InfluxDB v1` for charts and Grafana.
- Configure the server host/port, optional alternative host/port, database, measurement, credentials, and categories separately from MQTT.
- Location export is off by default. When enabled, trusted coordinates are exported with their original timestamps.
- Saves export progress and resumes remaining history after interruptions. Large queues use larger batches, adjusted after temporary failures.
- Reuses a working connection and tries the alternative for network failures and certain temporary server errors. Authentication, TLS, rate-limit, and data errors do not trigger that switch.
- Isolates a confirmed malformed record without deleting its local raw history or blocking unrelated fields.

Protect the server and credentials on the network where the collector is used.

<p align="center"><img src="docs/screenshots/en/home-assistant.png" alt="BYD Collector Home Assistant and InfluxDB settings" width="100%"></p>

## Telegram notifications

Telegram is optional and disabled by default. The app sends text notifications through your bot; it does not accept remote commands or upload route images.

Configure the bot token, chat ID, event switches, delays, and templates on the `Telegram` tab, then use `Test connection`. Credentials are protected using Android Keystore and masked in the UI.

Supported notifications include:

- charging started, charging progress, charged to 100%, and charging stopped;
- charge-gun connected and disconnected;
- low 12 V battery voltage;
- telemetry unavailable; and
- trip summary.

Built-in templates follow the app language. Your custom text and saved settings are preserved. The template editor offers variables for the available readings and warns about Telegram's 4,096-character limit, including overflow caused by location links.

Charging reports can include SOC, energy, power, local event time (`dd.MM.yyyy HH:mm`), and step/session duration. The 100% report includes the observed charging start, end, and added charge. If collection starts partway through charging, the report covers only the observed portion. The default low-12 V threshold is `12.5 V`, adjustable in `0.1 V` steps.

**Trip summaries**

- A driving segment ends after the vehicle stays in `P` for the configured delay: 5–300 seconds, 10 seconds by default. A shorter stop remains part of the same segment.
- The first valid vehicle power-off reading triggers an immediate send attempt without waiting for the parking delay. Delivery still depends on connectivity.
- Current statistics show that drive's energy used, recovered energy, and battery net with net-based average consumption and start/end SOC. Total statistics use the power-on session, including parked consumption; Overall SOC runs from the first drive to the latest reading. Recognized default templates update automatically; custom text and legacy energy variables retain their meaning.
- The Overall block is omitted when it duplicates the only trip. Different totals or parked SOC changes keep it visible.
- A summary requires at least a 1% SOC change, more than 0.1 km, or more than 0.1 kWh.

**Location links**

Location sharing is off by default. Open `Send location` to choose Google, Waze, Apple, and/or OSM. Links are sent at vehicle power-off, not for an ordinary parking-delay summary.

If GPS is unavailable at shutdown, the app uses the last trusted point from that trip/session. It may be where reception was last available, not the final parking spot. Without a trusted point, no location link is sent; the trip summary can still be delivered.

If a parking summary was already sent, location follows in a separate message after power-off. If a later drive produces a new final summary, the links accompany that summary instead. A short final move that does not qualify for a new summary does not cancel the earlier location follow-up.

**Delivery and storage**

Undelivered messages survive restarts and Main/All data database archiving. Network failures are retried automatically; vehicle startup, useful network changes, and a successful connection test can prompt another attempt. During continued outages, retry waits grow from 30 seconds to 30 minutes. Telegram's own server-imposed waits still apply.

A waiting or failed message does not block unrelated ready messages, but location-only follow-ups wait for their matching summary. Telemetry-unavailable notifications start a fresh waiting period when Telegram starts, rather than reporting an old outage immediately after a restart.

The queue is limited to 1,000 pending messages. Previously attempted messages older than 30 days can expire. A Telegram storage error pauses Telegram without stopping local collection. The message queue is separate from database archives and diagnostic ZIPs; `Clear logs` does not remove it.

<p align="center"><img src="docs/screenshots/en/telegram.png" alt="BYD Collector Telegram notification settings" width="100%"></p>

## Storage and archives

Telemetry and trips are stored locally in SQLite. Main and All data databases can be archived to ZIP so a fresh database can be used.

- `Storage` shows the combined size of Main, All data, and Trips, including supporting database files, plus archive size/count and the shared archive limit.
- Select existing archives to share them or delete them after confirmation. Deletion shows progress and reports files that could not be removed.
- The archive limit applies to completed Main/All data archives, not active databases or Trips. Automatic cleanup removes older archives while protecting the newest archive of each type.
- Trips uses lossless compression instead of archiving. Main and Trips history are not automatically deleted.
- When a Main/All data storage-format update is required, the existing database is archived before replacement. Trips updates preserve its history.

Manual archiving also provides a recovery path when a database can no longer be used normally. Failed preliminary checks are shown as warnings; the operation still stops if it cannot preserve the source database or create a usable replacement.

Archiving can temporarily stop collection and integrations. Read the confirmation carefully, especially warnings about queued MQTT/InfluxDB data or an older Telegram queue awaiting transfer. Do not interrupt an archive operation. Archiving Main or All data does not remove the current Telegram message queue.

<p align="center"><img src="docs/screenshots/en/storage.png" alt="BYD Collector storage and database archives" width="100%"></p>

<p align="center">
  <a href="docs/screenshots/en/archive.png"><img src="docs/screenshots/en/archive.png" alt="BYD Collector database archive confirmation" width="49%"></a>
  <a href="docs/screenshots/en/archive-done.png"><img src="docs/screenshots/en/archive-done.png" alt="BYD Collector completed database archive" width="49%"></a>
</p>

## Options and runtime

`Options` provides ADB and background-service controls, connection recovery, Tailscale activation, update checks, and log tools. Automatic-start switches on the collection and integration tabs control their respective functions.

- Use the background-app settings and ADB checks to allow collection to run.
- Enable `Restore Wi-Fi and cellular` to recover both connections together, or enable Bluetooth recovery separately.
- Bluetooth recovery only requests activation when Bluetooth is off and the vehicle is confirmed powered off. It does not issue that request while the vehicle is on or its power state is unknown.
- Service recovery helps the app return after supported process or boot events. Its notification-listener permission is used for background recovery, not to read notification contents.
- Optional Tailscale activation can help reach a configured server through your VPN.
- Use `Shutdown` to stop operation until you open the app again.

These controls restore Android services and connections; they do not send vehicle-control commands.

The autonomous telemetry helper asks Android to keep the CPU awake while it is running, including with the screen off. If that request fails, collection continues without this protection. It does not prevent Android from killing the process or the vehicle from rebooting; its effect on parked-car operation still needs vehicle validation.

Automatic update checking starts after 30 seconds and runs in the background without opening Collector. Failed checks retry after 30, 60, 120, and then every 300 seconds, measured from completion of the failed attempt; a successful check stops retries. Screen-off pauses only update checking, not telemetry collection. The next screen wake starts a fresh 30-second cycle. Opening or minimizing Collector does not reset this timer.

When Collector is visible, an available update is offered inside the app. Closing an update offer pauses automatic checks for an hour, unless a new wake starts a fresh cycle first. Manual checking remains available and checks the latest published release; if a valid check is already running, it shares that request instead of starting another.

`New version hint widget` is enabled by default. When a background check finds an update, it can show a hint above other apps for 10 seconds. Tap it to open `Options` and the saved update offer, or close it with the X. Returning to Collector removes its hint without repeating the check; minimizing the app does not replay a hint or offer that was already shown. The settings button beside the switch adjusts size, transparency, corners, and frame/stripe color; the default accent is green.

The hint requires Android's permission to display over other apps, requested through the system settings screen. Declining does not trigger repeated prompts; enable the hint again to retry. If a hint could not be displayed, its saved offer remains available without another network check. Without that permission, normal update offers and collection still work. With compatible BYD HUD and BYD Extend versions, update hints arrange themselves in the available screen area and temporarily shrink when needed; each keeps its own display time and saved appearance settings.

<p align="center"><img src="docs/screenshots/en/options.png" alt="BYD Collector options and runtime settings" width="100%"></p>

## Diagnostics and privacy

The app keeps a local operational event log, separate from telemetry databases. For a reproducible problem, use `Start logcat` under `Options -> Keep alive`, reproduce the issue, then stop recording. Full-system recording requires ADB authorization and is limited to 128 MiB; the regular event log is limited to 8 MiB.

`Share logs` prepares a fresh ZIP and opens Android's share chooser with just the file. It includes app/device information, recent operational events, available system and background-service logs, and diagnostic summaries for InfluxDB, trips, and Telegram. Missing sources are reported without blocking the rest of the archive. Telemetry and Telegram databases are not included. ZIP preparation needs additional free space; a failure preserves existing logs and the previous valid bundle.

The bundle also includes available telemetry-helper startup logs and a summary of buffered telemetry occupancy, imports, and released space. These diagnostic logs are bounded and can be cleared without deleting unimported telemetry or resetting the current occupancy summary.

`Clear logs` asks for confirmation, removes completed captures and generated bundles, and resets the event log without stopping an active logcat recording. Unavailable background-service logs may result in partial cleanup. A recently shared copy can remain temporarily so the receiving app can finish reading it. Database archives, trip history, and pending Telegram messages are not cleared.

Before sharing, recognized credentials, Wi-Fi network identifiers (SSID/BSSID), explicit coordinates, and private vehicle/chat/account identifiers are masked in the diagnostic copies. App/firmware versions, vehicle names, hosts/IP addresses, and ports remain readable for troubleshooting. Original logs and database archives are not changed. **This filtering does not guarantee that full-system logs are anonymous.**

Review the ZIP before sharing: system logs and background-service output may still contain private information or data from other apps. Database archives are shared separately through `Storage` and may contain raw telemetry, trips, and location data.

Diagnostics stay on the device unless you choose to share them. The app does not automatically send telemetry, screenshots, crash reports, or logs to the project. MQTT, InfluxDB, and Telegram are opt-in destinations you configure. See [PRIVACY.md](PRIVACY.md) for the full data-handling policy.

## Installation and ADB

### Requirements

- Android 8.0 (API 26) or newer;
- a compatible Chinese-market BYD vehicle with DiLink 5.0;
- permission to install APK files on the vehicle tablet; and
- local ADB access to the vehicle tablet.

### First setup

1. Download the current APK from [GitHub Releases](https://github.com/sunlixWhyNotAvailable/byd-collector/releases/latest) and verify the release checksum when one is published.
2. Install and open **BYD Collector** on the vehicle tablet.
3. Follow the first-run background-app prompt and set `Disable background Apps -> BYD Collector` to `OFF`.
4. Allow Android location access if route recording is wanted. After a denial, grant it later in Android settings; the app does not repeatedly prompt.
5. When Android shows the ADB RSA prompt, confirm it. In the app, use `Grant ADB` to re-check the bridge.
6. Start `Main` and confirm that the status changes from waiting to successful polling.
7. Enable the default-off `location` category only for MQTT/Influx channels that need precise-location export.
8. Enable only the integrations and Telegram events you need, then test each connection from its tab.
9. Start `All data` only for research sessions; it writes a separate, much larger database.

ADB authorization is required to collect fresh vehicle telemetry. Without it, you can still view stored data, settings, and archives. Re-authorize after a tablet reset or when Android requests it again.

## Troubleshooting

### Main stays in `waiting` or reports `ADB`/permission errors

- Confirm the tablet is awake and the local ADB bridge is authorized.
- Press `Grant ADB` and accept the RSA prompt again if Android asks.
- Set `Disable background Apps -> BYD Collector` to `OFF`.
- Stop another copy of the collector or an app that may own the local ADB session, then start Main again.
- Start logcat under `Options -> Keep alive`, reproduce the issue, then stop it to finish the diagnostic bundle.

### Main is successful but a value is blank, stale, or unknown

The vehicle may not supply the field, a read may be incomplete, or the field's meaning may not yet be confirmed. Raw values and quality information remain available for analysis. A numeric code in one field does not necessarily mean the same thing in another.

### All data is slow or storage grows quickly

All data intentionally polls the research catalog and stores raw evidence. Stop it when the session is complete, then use the All data tab's `Archive database` action; `Storage` is for sharing or deleting existing archives. Share only the affected archive when reporting a discovery.

### Home Assistant or MQTT is offline

Check the selected profile's host/port, credentials, topic/discovery prefixes, and categories. `Test connection` checks that profile only. An alternative connection can help with network failures, but not incorrect credentials or data. MQTT sends current state, so updates can be missed while offline. Local collection continues independently.

### InfluxDB is behind or shows retries

Check the selected profile, credentials, database/measurement, categories, and VPN if required. Use `Test connection` and compare the active connection with the profile you tested. A successful test or collection cycle does not mean the backlog has already been exported. Export progress is saved and retries are automatic; if the queue stays unchanged, share diagnostic logs with the approximate time of the problem.

### Telegram messages are delayed or missing

Verify the bot token and chat ID with `Test connection` and enable the specific event. A successful test can restart network-failed delivery attempts while Telegram is enabled, but cannot override Telegram's server-imposed wait. If no location arrives, check the selected navigators and whether the trip had any trusted GPS point. For persistent failures, share diagnostic logs and the approximate event time.

### Archive is deferred or maintenance cannot start

Read the confirmation's warnings about queued export data or older Telegram messages awaiting transfer. Let pending exports finish when possible. If ordinary collection is blocked by a database problem, use its manual archive action to preserve the old data and start fresh. Do not interrupt a running archive operation.

## Report a problem

Choose the appropriate issue form:

- [Report a bug](https://github.com/sunlixWhyNotAvailable/byd-collector/issues/new?template=bug_report.yml) for reproducible failures or incorrect behavior;
- [Suggest an improvement](https://github.com/sunlixWhyNotAvailable/byd-collector/issues/new?template=suggestion.yml) for a new workflow or product change; or
- open the [issue chooser](https://github.com/sunlixWhyNotAvailable/byd-collector/issues/new/choose) and select `Open a blank issue` when neither structured form fits.

The bug form asks for:

- collector version and APK source;
- vehicle model/year, market, DiLink version, and tablet firmware;
- whether ADB was authorized and which tab/status failed;
- the approximate local time, expected behavior, and observed behavior; and
- the smallest relevant selected database or diagnostic archive, after removing secrets and unrelated trips.

For a collection problem, reproduce it once with full-system logcat recording. For an integration problem, include the channel status and endpoint type without posting credentials. For a catalog discovery, use a blank issue, include the All data archive, and explain how the value changed; changing alone is not proof of semantics.

Archives can expose vehicle identifiers, raw Chinese descriptions, timestamps, trips, and location-related values. Review the warning and share the minimum necessary data.

## Known limitations

- Primarily tested on the Chinese-market `BYD Sea Lion 07 EV 2025` with `DiLink 5.0`; other vehicles and firmware may expose different data or behaviour.
- Telemetry access is read-only; vehicle-control commands are unsupported.
- ADB authorization and background permissions may need restoring after resets or firmware changes.
- All data is a research catalog, not a guarantee that every field is available or understood.
- Active Main and Trips history can continue growing. Monitor storage, archive Main when needed, and compress completed routes.
- GPS interference or poor reception can create route gaps; online maps need an internet connection.
- Collection while the vehicle is off has been observed on the tested car, including after the app process stopped. A tablet/kernel reboot interrupts collection; recovery depends on Android and the vehicle firmware.
- MQTT can miss live updates while offline. InfluxDB exports history progressively, and Telegram delivery depends on network and server availability.
- Tailscale is optional and is not needed for local collection; its operation depends on the vehicle's network restrictions.

## Tested device

| Vehicle | Market | DiLink | Status |
| --- | --- | --- | --- |
| BYD Sea Lion 07 EV 2025 | China | 5.0 | Tested by the maintainer |

The collector may work on other Chinese-market BYD vehicles, but their available parameters and meanings must be verified independently.

## License and disclaimer

BYD Collector is licensed under the [GNU Affero General Public License v3.0](LICENSE).

This is an independent project. It is not affiliated with, endorsed by, or sponsored by BYD, DiLink, MQTT, Home Assistant, InfluxDB, Telegram, or any vehicle manufacturer. Product names and trademarks belong to their respective owners.

Use the software at your own risk. Telemetry can be incomplete, delayed, stale, or wrong; it must not be used as a safety-critical or legally authoritative source. Keep attention on the road and install, configure, or diagnose the system only while parked.
