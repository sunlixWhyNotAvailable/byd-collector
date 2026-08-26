# README screenshots

The paired public READMEs use the approved screenshot set for the `v2.7.2`
documentation. Newly captured assets show `v2.7.2`; the existing Telegram and
Storage captures are retained because the represented tabs did not change.
English assets live under `en/`; Ukrainian assets use the same basenames under
`uk/`.

## Published set

| File | README placement |
| --- | --- |
| `main.png` | Main collection controls, channel status, and database state. |
| `parameters.png` | All data controls, current vehicle state, and round-robin database state. |
| `trips.png` | Grouped trip history with distance, energy, SOC, consumption, and route actions. |
| `home-assistant.png` | MQTT and InfluxDB controls, status, and category selection. |
| `telegram.png` | Telegram connection and event-template controls; retained because the represented tab did not change. |
| `storage.png` | Active databases, archive limit, and archive selection; retained because the represented tab did not change. |
| `archive.png` | Database-archive confirmation and queue warnings. |
| `archive-done.png` | Successful database-archive result. |
| `options.png` | Recovery, logcat, Share logs, Clear logs, update, and shutdown controls. |

System status/navigation bars are removed from every image. `main.png`,
`parameters.png`, and `trips.png` are pixel-preserving composites of approved
continuation captures; the remaining images are `1920 x 906` crops. A trip-map
capture is intentionally not published to avoid exposing private route data.

Future replacements must keep both language sets synchronized, use the current
production version and dark theme, and exclude credentials, private endpoints,
vehicle identity, home location, and other sensitive data.
