# README screenshot refresh

The current screenshots show the retired `v2.6.1` layout. Replace them with
current production `v2.7.2` captures in both languages before the next public
release.

## Capture rules

- Use the production UI in landscape at `1920 x 906`, matching the existing
  image dimensions.
- Capture the same state twice: English under `en/` and Ukrainian under `uk/`.
- Use the dark theme and keep the complete app surface in frame.
- The header must show `v2.7.2`; the bottom bar must include Trips and must not
  include the retired Logs tab.
- Use emulator or synthetic data. Do not expose a real bot token, chat ID,
  broker/Influx credentials, private hostnames or addresses, vehicle identity,
  home location, or a real private route.
- Prefer stable states: collection running, ADB/permissions OK, and no transient
  loading overlay unless that state is the subject of the screenshot.

## Required captures

Create every basename below under both `docs/screenshots/en/` and
`docs/screenshots/uk/`.

| File | Required state |
| --- | --- |
| `main.png` | Main tab at the top, with current header pills, collection controls, channel status, and useful vehicle KPI cards visible. |
| `parameters.png` | All data tab with a non-zero catalog/cycle count and representative parameter rows; no active error. |
| `trips.png` | Trips list with at least two non-zero synthetic trips so grouping, times, distance, energy, and consumption are visible. |
| `trip-map.png` | One synthetic trip opened on the map with a continuous outlined route, Start/Finish markers, and the lower legend/colour controls visible. |
| `home-assistant.png` | HA integration tab showing MQTT and InfluxDB controls/status plus the category grid; credentials blank or masked and location category off. |
| `telegram.png` | Trip summary card showing the location action with its centered default `no` / `ні` status and the current template controls; secrets must be outside the frame or masked. |
| `storage.png` | Storage tab with active database cards, archive limit, and share/delete archive actions visible. |
| `options.png` | Options tab with combined Wi-Fi/cellular recovery, Bluetooth/service recovery, Start/Stop logcat, Share logs, and Clear logs visible. |

The six existing filenames are already referenced by the paired READMEs and can
be replaced in place. `trips.png` and `trip-map.png` have prepared hidden slots
in both READMEs; enable their image blocks only after both language pairs exist,
so the public README never contains a broken image.
