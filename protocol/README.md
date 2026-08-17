# Bridge protocol v3

The Android bridge, Pebble watchapp, and embedded PebbleKit JS use AppMessage dictionaries under
UUID `51c8d7cf-4cb2-4ef8-98c9-641706feb250`. Version 3 is intentionally incompatible with v2;
the `0.1.7` APK and PBW must be upgraded together. Receivers reject any other version.

All statistics use signed 32-bit integer SI wire units. `-2147483648` means unavailable; the watch
renders it as `—`. Time and IDs use unsigned 32-bit values where noted. Units remain metric.

| Key | Name | Encoding |
|---:|---|---|
| 0 | protocol version | `3` |
| 1 | message type | snapshot `1`, command `2`, result `3`, request snapshot `4`, config chunk `5`, Locus-profile chunk `6`, request profiles `7`, watch HR sample `8`, config result `9` |
| 2, 7 | command ID, session ID | unsigned; receiver deduplication includes the source watch, this pair, and the command payload |
| 3 | command | Start `1`, Pause/Resume `2`, Stop/Save `3`, waypoint `4`, waypoint with dictated note `5` |
| 4 | result | command/profile: OK `0`, invalid state `1`, unavailable `2`, failed `3`, invalid profile `4`, profile missing `5`, invalid waypoint name `6`; config result: applied `0`, queued `7`, invalid config `8`, storage failed `9` |
| 5, 6 | recording state, sample time | stopped/recording/paused/unavailable `0..3`; Unix seconds |
| 8, 9 | selected display name, exact Locus profile name | UTF-8; display names: at most 20 Unicode scalar values and 80 bytes; Locus names: at most 255 bytes |
| 10, 17 | elapsed, moving time | seconds |
| 11, 18 | total, moving distance | metres |
| 12, 13, 19 | current, average, maximum speed | centimetres/second |
| 14, 15, 20 | altitude, ascent, descent | decimetres |
| 16 | unit system | metric `0` (`1` reserved for imperial) |
| 21 | vertical speed | centimetres/second |
| 22 | slope | tenths of a percent |
| 23–29 | average/max HR, average/max cadence, average/max power, energy | bpm, rpm, watts, kcal |
| 30–33 | chunk index/count/data/transfer ID | zero-based index, total, at most 80 UTF-8 bytes, nonnegative signed 32-bit transfer ID |
| 34 | Locus mode | reserved |
| 35 | release version | exact APK/PBW release string, currently `0.1.7` |
| 36 | waypoint name | confirmed dictation for command `5`; nonblank UTF-8, at most 120 bytes |
| 37 | current heart rate | BPM; Locus-derived in snapshots, watch-derived only in type `8` |
| 38 | heart-rate sequence | unsigned, increasing within the watch session |

Metric IDs are: elapsed `1`, moving time `2`, total/moving distance `3/4`, current/average/max
speed `5/6/7`, current/average pace `8/9`, altitude/ascent/descent `10/11/12`, vertical speed
`13`, slope `14`, average/max heart rate `15/16`, average/max cadence `17/18`, average/max power
`19/20`, energy `21`, and current heart rate `22`. Current heart rate is always encoded from
`UpdateContainer.locMyLocation.sensorHeartRate`; the bridge never echoes an originating watch value
when Locus has not reported it. Pace is derived as `min/km` on the watch.

Configuration is chunked as
`theme|legacy-selected-index|watch-HR-to-Locus (0/1)|heart-rate-interval-seconds`, followed by one newline-separated profile
per line: `display-name|exact-locus-name|protected-flag|comma-separated-metric-ids|stable-profile-id`.
There are one through eight profiles, each with one through six unique metric IDs. Display names,
Locus names, and stable IDs must be valid UTF-8, contain neither U+0000–U+001F, U+007F, nor `|`,
and contain at least one scalar outside the shared whitespace set U+0020, U+0085, U+00A0, U+1680,
U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000, and U+FEFF. Stable IDs are limited to
39 bytes. Display names must also be unique under a bounded, scalar-wise
fold shared by the watch and PKJS: U+0041–U+005A, U+00C0–U+00D6, U+00D8–U+00DE,
U+0391–U+03A1, U+03A3–U+03AB, and U+0410–U+042F map to the scalar 0x20 higher;
U+0400–U+040F maps 0x50 higher. There is no locale, normalization, contextual, or multi-scalar
folding; all other Unicode scalars compare exactly, so `Ā` and `ā` are distinct. The fifth field is
optional when reading older protocol-v3 data. The complete serialized configuration is at most
4095 bytes and 52 chunks. A receiver applies only a complete, validated transfer. The watch persists
the last complete configuration; complete configuration received during recording or pause is
stored separately and applied after Stop.

PKJS sends one AppMessage at a time. A configuration transaction registers its correlated result
before enqueueing, sends chunks in index order, retries the identical frame after a NACK or timeout
up to three total frame attempts, and aborts the unsent remainder if that limit is exhausted. The
startup profile request is queued after the initial configuration transport completes or aborts;
it does not wait for the application-level result. Transfer IDs are nonzero and advance between
transactions within a PKJS process.

After every complete, well-formed configuration transfer, the watch emits type `9` with the same
transfer ID and exactly one context-specific result: applied `0`, queued-until-a-fresh-stopped-state
`7`, invalid `8`, or storage failure `9`. A fresh stopped snapshot is required for direct apply;
recording, paused, unavailable, missing, or stale state queues a valid configuration. If the result
deadline expires after successful transport, PKJS retries the entire identical transfer with the
same ID, up to three application attempts. The desired configuration is unchanged, but the watch
does not durably remember or replay a completed result: it validates and stores every completed
retry again, so a later storage result may differ from an earlier lost result. Wrong-ID, malformed,
duplicate, and late results cannot settle another transaction.

A webview save is first stored in a separate protocol-, release-, and format-tagged pending queue.
Before any frame of a pending candidate is enqueued, PKJS durably marks that wire as possibly
applied. The committed settings key changes only after correlated applied/queued confirmation.
Concurrent saves are serialized and unsent candidates are last-write-wins, but each newer candidate
carries the complete newest-first lineage of possibly applied predecessors. The lineage is bounded
at eight distinct configurations. A ninth distinct ambiguity is not sent or stored in place of that
lineage; the prior recovery state remains durable and settings reports unresolved reconciliation.

During one live send, PKJS retains the uncertainty state captured before that pre-send marker. A
correlated invalid result is deterministic. A first-attempt correlated storage result likewise
proves that attempt did not apply, so PKJS clears only the uncertainty introduced for that live
attempt and restores or rebases any captured predecessor. Transport failure is always ambiguous:
PebbleKit can report a missing transport acknowledgement after the watch processed the final frame.
An application-result timeout or process loss also destroys proof of non-application, so a later
storage/transport failure leaves the candidate durable for reconciliation. If an ambiguous older
candidate fails while a newer durable save is already waiting, the newer candidate proceeds with
that candidate and all of its predecessors as its recovery lineage. Confirmed success establishes
the new baseline and clears all inherited uncertainty.

Legacy pending records without uncertainty metadata migrate as possibly applied. A canonical queue
in a recognized pending format with the same protocol also migrates across release tags and is
rewritten in the current format.
Startup always sends migratable durable pending state before the older committed value. If a pending
record exists but its protocol, wire, or lineage cannot be safely migrated and validated, startup
fails closed and does not send the committed value. These rules prevent a lost result or an update
from reverting an already-applied watch. Settings continues to report unresolved reconciliation on
every opening while pending or blocked state remains; terminal rejection or storage notices are
shown on the next opening. The page continues to show the last confirmed configuration.

Installed Locus profile lists are newline-separated UTF-8 names using the same chunk envelope.
Android obtains them from `ActionBasics.getTrackRecordingProfiles`. It sends and persists result `3`
with an empty payload only when that query succeeds and authoritatively returns an empty list. Locus
unavailability, a thrown query, invalid names, duplicates, or an oversized result NACK the request
and produce no completed profile-list transfer, preserving the watch and PKJS stale caches. The
watch validates and caches only a complete transfer, then relays a complete cached list to PebbleKit
JS. The complete list is at most 8191 bytes and 103 chunks; every name obeys the 255-byte field rules
and exact duplicates are invalid. Profile-list chunks use result `0` for a non-empty query and `3`
for an authoritative empty list, so an empty Locus result is distinguishable from no relay response.

All envelope fields use their documented integer or string tuple types; decimal strings are not
accepted as integers. Chunk 0 starts or restarts a profile-list transfer even when an ID is reused.
Later chunks must keep the same ID, count, and result. An identical duplicate is harmless, while a
conflicting duplicate invalidates the partial transfer. A payload becomes visible only after every
chunk is present and the joined byte and field limits pass validation.

JS atomically replaces its persistent cache after a complete compatible transfer, including a
complete empty result. Cache entries carry protocol and release metadata; missing or mismatched
metadata is ignored. With a valid cache, settings opens immediately with a stale-data notice while
requesting a refresh. Any prior in-memory response is likewise demoted to stale at the start of a
later settings opening. Without a cache, settings waits up to 500 ms for a fresh response before
opening. A response that arrives after the data-URL page has opened is cached and appears as stale on
the next opening while another refresh is requested. A later complete compatible response clears a
prior in-memory incompatibility notice.

Older protocol-v3 configuration headers migrate to watch injection disabled and a five-second
interval; profiles, metric selections, and order are not rewritten. Fresh English and German
defaults use current heart rate in slot six. The interval is an integer from 1 through 60 seconds.

On physical Emery hardware the watch subscribes to HealthService only while Locus reports active
recording and the global option is enabled. It sends valid raw values from 25 through 250 BPM at no
greater frequency than the configured interval. Pause, stop, Locus unavailability, disabling the
option, and app exit unsubscribe and restore automatic sampling. Commands and profile relays have
priority in the single-flight outbox; the low-priority HR slot is conflated.

CoreApp delivers a type-8 AppMessage to embedded PKJS and PebbleKit Android in parallel. PKJS
validates/tolerates the type without sending it back. Android checks release, protocol, range,
timestamp freshness, recording state, session, and increasing sequence, and conflates pending input.
It package-targets `com.asamm.locus.DATA_TASK` through `LocusUtils.sendBroadcast` with extra `tasks`
equal to `{heart_rate:{data:<bpm>.0}}`. This undocumented Locus interface has no acknowledgement, so
the bridge polls Locus for about 1.5 seconds and returns the resulting snapshot. The diagnostics UI
shows only ephemeral last-watch BPM, forwarding time, and current Locus BPM; no HR history is stored
or logged.

The serialized protection field remains present for protocol-v3 compatibility. Version 0.1.7
ignores incoming values and always emits `0`; formerly protected defaults migrate to ordinary
profiles without changing their name, mapping, metrics, order, or active watch selection. Display names are
local user data and may be localized only when a fresh configuration is created. Exact Locus names
are external identifiers and are never translated or truncated.

Start always carries key 9. Android validates the name, resolves it case-insensitively against the
currently installed profiles, and calls Locus with the exact installed spelling. An unresolved
mapping returns result `5` and does not start recording.

Every protocol dictionary carries keys 0, 1, and 35 with the exact protocol version, message type,
and release string. Android, watch C, and PebbleKit JS reject a missing, mistyped, or mismatched
envelope and show an explicit incompatibility error where a user-facing surface is available.

Waypoint command `4` retains the fixed name `Pebble waypoint`. On microphone-capable watches,
command `5` carries the exact text accepted in Pebble's confirmation UI under key 36. Android
rejects names that contain U+0000–U+001F or U+007F, consist only of the shared whitespace set above,
or exceed 120 UTF-8 bytes before calling Locus. Both waypoint commands auto-save at the current
recording position and are valid only while actively recording.
