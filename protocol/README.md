# Bridge protocol v3

The Android bridge, Pebble watchapp, and embedded PebbleKit JS use AppMessage dictionaries under
UUID `51c8d7cf-4cb2-4ef8-98c9-641706feb250`. Version 3 is intentionally incompatible with v2;
the `0.1.3` APK and PBW must be upgraded together. Receivers reject any other version.

All statistics use signed 32-bit integer SI wire units. `-2147483648` means unavailable; the watch
renders it as `—`. Time and IDs use unsigned 32-bit values where noted. Units remain metric.

| Key | Name | Encoding |
|---:|---|---|
| 0 | protocol version | `3` |
| 1 | message type | snapshot `1`, command `2`, result `3`, request snapshot `4`, config chunk `5`, Locus-profile chunk `6`, request profiles `7` |
| 2, 7 | command ID, session ID | unsigned; deduplication key is the pair |
| 3 | command | Start `1`, Pause/Resume `2`, Stop/Save `3`, waypoint `4` |
| 4 | result | OK `0`, invalid state `1`, unavailable `2`, failed `3`, invalid profile `4`, profile missing `5` |
| 5, 6 | recording state, sample time | stopped/recording/paused/unavailable `0..3`; Unix seconds |
| 8, 9 | selected display name, exact Locus profile name | UTF-8; display names are limited to 20 characters, Locus names are preserved exactly |
| 10, 17 | elapsed, moving time | seconds |
| 11, 18 | total, moving distance | metres |
| 12, 13, 19 | current, average, maximum speed | centimetres/second |
| 14, 15, 20 | altitude, ascent, descent | decimetres |
| 16 | unit system | metric `0` (`1` reserved for imperial) |
| 21 | vertical speed | centimetres/second |
| 22 | slope | tenths of a percent |
| 23–29 | average/max HR, average/max cadence, average/max power, energy | bpm, rpm, watts, kcal |
| 30–33 | chunk index/count/data/transfer ID | zero-based index, total, UTF-8 chunk, signed transfer ID |
| 34 | Locus mode | reserved |
| 35 | release version | exact APK/PBW release string, currently `0.1.3` |

Metric IDs are: elapsed `1`, moving time `2`, total/moving distance `3/4`, current/average/max
speed `5/6/7`, current/average pace `8/9`, altitude/ascent/descent `10/11/12`, vertical speed
`13`, slope `14`, average/max heart rate `15/16`, average/max cadence `17/18`, average/max power
`19/20`, and energy `21`. Pace is derived as `min/km` on the watch.

Configuration is chunked as `theme|selected-index`, followed by one newline-separated profile per
line: `display-name|exact-locus-name|protected-flag|comma-separated-metric-ids`. A receiver applies
only a complete, validated transfer. The watch persists the last complete configuration; complete
configuration received during recording or pause is stored separately and applied after Stop.
Installed Locus profile lists are newline-separated UTF-8 names using the same chunk envelope.
Android obtains them from `ActionBasics.getTrackRecordingProfiles`. The watch validates and caches
only a complete transfer, then relays a complete cached list to PebbleKit JS. JS atomically replaces
its persistent cache. Settings opens immediately with the latest complete cache while requesting a
fresh transfer in the background. Profile-list chunks use result `0` for a non-empty query and `3`
when Locus returns no profiles, so an empty Locus result is distinguishable from no relay response.

The serialized protection field remains present for protocol-v3 compatibility. Version 0.1.3
ignores incoming values and always emits `0`; formerly protected defaults migrate to ordinary
profiles without changing their name, mapping, metrics, order, or selected index. Display names are
local user data and may be localized only when a fresh configuration is created. Exact Locus names
are external identifiers and are never translated or truncated.

Start always carries key 9. Android validates the name, resolves it case-insensitively against the
currently installed profiles, and calls Locus with the exact installed spelling. An unresolved
mapping returns result `5` and does not start recording.

Every control and profile-relay message carries key 35. Android, watch C, and PebbleKit JS require
the exact same release version and show an explicit incompatibility error when it differs or is
missing; protocol version 3 remains unchanged.
