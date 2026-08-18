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
| 5 | recording state | stopped/recording/paused/unavailable `0..3` |
| 6 | snapshot delivery epoch / HR sample time | snapshot: durable delivery-order stamp seeded by Unix seconds; type `8` HR: actual Unix sample seconds |
| 8, 9 | selected display name, exact Locus profile name | UTF-8; display names: at most 20 Unicode scalar values and 80 bytes; Locus names: at most 255 bytes |
| 10, 17 | elapsed, moving time | seconds |
| 11, 18 | total, moving distance | metres |
| 12, 13, 19 | current, average, maximum speed | centimetres/second |
| 14, 15, 20 | altitude, ascent, descent | decimetres |
| 16 | unit system | metric `0` (`1` reserved for imperial) |
| 21 | vertical speed | centimetres/second |
| 22 | slope | tenths of a percent |
| 23–29 | average/max HR, average/max cadence, average/max power, energy | bpm, rpm, watts, kcal |
| 30–33 | chunk index/count/data/transfer ID | zero-based index, total, at most 80 UTF-8 bytes, 31-bit serial ID `0..2147483647` |
| 34 | Locus mode | reserved |
| 35 | release version | exact APK/PBW release string, currently `0.1.7` |
| 36 | waypoint name | confirmed dictation for command `5`; nonblank UTF-8, at most 120 bytes |
| 37 | current heart rate | BPM; Locus-derived in snapshots, watch-derived only in type `8` |
| 38 | heart-rate sequence | unsigned, increasing within the watch session |
| 39 | transfer generation | integer `1` on every chunk emitted by a durable serial sender |

Metric IDs are: elapsed `1`, moving time `2`, total/moving distance `3/4`, current/average/max
speed `5/6/7`, current/average pace `8/9`, altitude/ascent/descent `10/11/12`, vertical speed
`13`, slope `14`, average/max heart rate `15/16`, average/max cadence `17/18`, average/max power
`19/20`, energy `21`, and current heart rate `22`. Current heart rate is always encoded from
`UpdateContainer.locMyLocation.sensorHeartRate`; the bridge never echoes an originating watch value
when Locus has not reported it. Pace is derived as `min/km` on the watch.

Within one watchapp lifetime, the delivery epoch of an accepted snapshot establishes a
nondecreasing floor. The watch rejects every later snapshot with a lower delivery epoch even after
the installed snapshot becomes stale; equal delivery epochs remain valid for an identical retry.
This snapshot field orders deliveries rather than dating the underlying Locus observation. Because
it advances synthetically, it can be ahead of phone wall time. In a type `8` heart-rate message,
key `6` instead remains the watch sample's actual Unix timestamp and is never a delivery-order stamp.

Before issuing any snapshot request, the bridge reserves a delivery epoch equal to the
greater of the observed phone time and one more than the last reserved epoch. This epoch floor
is managed in-memory, so a bridge process restart naturally establishes a new baseline from
the system clock.

After a state-changing command, the bridge reserves the command's barrier epoch before journaling or
mutating Locus. Because Locus command broadcasts do not acknowledge application, a newly executed OK
Start, Pause/Resume, or Stop command is polled to its expected recording state for a bounded interval.
For Pause/Resume, that expected state comes from the same Locus state read that selected Pause versus
Resume, rather than a separate pre-command sample.
The bridge then must successfully deliver the latest authoritative snapshot with the barrier epoch
before it sends `COMMAND_RESULT`. If the expected transition is not observed, it journals and returns
`FAILED` with the latest snapshot; a retry replays that result without waiting for the now-obsolete
target. A reservation failure executes no command. A pre-command request that completes remotely
after its local timeout has a lower epoch than the barrier snapshot and cannot roll back the displayed
state.

The watch retains each command correlation for 120 seconds. This exceeds the bridge's bounded
worst case of one older serialized snapshot delivery, state confirmation, the barrier snapshot,
and the command-result delivery, including all three ten-second attempts and retry delays for each
delivery. A result that follows any permitted retry schedule therefore arrives before correlation
expiry.

Because the sender floor is in-memory, it is lost if the Android bridge process restarts. A
process restart naturally establishes a new coordinated baseline with the watchapp.

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
it does not wait for the application-level result. Before chunk 0 can be enqueued, PKJS durably
reserves the transaction's next 31-bit serial. The counter survives process restart, starts at zero,
includes zero at wrap, and is read back after writing; missing, corrupt, failed, or unconfirmed
storage authorizes no configuration frames. An abandoned reservation remains a harmless gap. Every
whole-transfer application-result retry reuses the already reserved ID, and every frame carries
transfer-generation marker `1`.

The watch retains an incomplete configuration or profile-list transfer for 45 seconds after its
last accepted chunk. This exceeds a frame's full three-attempt, ten-second-per-attempt sender budget
plus Android retry delays, so a valid final retry cannot expire the receiver between chunks.

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
accepted as integers. A durable sender places transfer-generation marker `1` on every chunk. Before
a receiver has seen that generation, only a fully envelope-valid marked chunk 0 may atomically clear
its legacy in-memory serial floor and establish the new generation; a marked nonzero chunk cannot
trigger the transition. Afterward, the receiver ignores every unmarked or unknown-generation frame,
including delayed legacy traffic. PKJS persists the generation with its profile-list floor across JS
recreation.

Transfer IDs form a serial space modulo `2^31`. Relative to a receiver's floor,
`distance = (candidate - floor) mod 2^31`; a candidate is newer only when
`0 < distance < 2^30`. Older IDs and the exactly-half-range ambiguous value are ignored. A valid
newer chunk 0 advances the floor before reassembly, and active reset, conflict, timeout, or
completion never lowers it within the live receiver instance. Thus a delayed older chunk 0 cannot
replace either an active or a completed newer transfer. Sender storage must not be reset
independently while an old receiver or its durable floor can still observe delayed frames; such
maintenance is a coordinated reset boundary. Close the watchapp before clearing transfer sender or
receiver storage, keep it closed throughout that reset, and reopen it only after both sides are ready
to establish a fresh marked generation.

For the active ID, an identical chunk 0 with the same count and result is a harmless duplicate that
preserves every later chunk already received. A same-ID chunk 0 that conflicts in data, count, or
result invalidates the entire partial transfer and is itself discarded; another valid chunk 0 is
then required to begin again. Later chunks must keep the same ID, count, and result. Any other
identical duplicate is harmless, while a conflicting duplicate invalidates the partial transfer.
After a profile-list transfer completes, an equal-ID replay is ignored. Configuration is the one
exception: after completion, an equal-ID chunk 0 may begin the documented whole-transfer retry for
a lost type-`9` result. The watch retains the completed chunk count, first-chunk fingerprint, total
length, and dual whole-payload checksums; a same-ID retry whose bytes differ is rejected without
changing the completed floor. A payload becomes visible only after every chunk is present and the
joined byte and field limits pass validation.

Android reserves an in-memory profile-list serial from a dedicated counter before it emits
chunk 0, and the watch durably reserves a new relay serial before making the validated list
sendable to PKJS. Both counters advance modulo `2^31`, keep abandoned reservations as gaps,
and start at zero when absent. The Android counter's in-memory nature means process restarts
will re-initialize the counter.
The watch uses a dedicated relay counter rather than deriving IDs from its session counter, because
one watch session can relay more than one list. PKJS also persists its completed profile-list floor,
so a JS restart cannot make a delayed older watch relay replace its cache. A failed or unconfirmed
PKJS floor write blocks that live receiver; process recreation reloads whatever storage actually
committed before accepting another transfer.

The watch shares one fixed-slot buffer between Android reassembly and PKJS relay to stay below
Pebble's virtual-size ceiling. That buffer has one owner at a time: Android profile chunks received
while a relay is active are ignored, and the watch coalesces them into one fresh profile request
after the relay. A PKJS request received during Android reassembly likewise waits instead of
overwriting the partial transfer; successful completion satisfies it with the fresh relay, while a
failed or expired transfer triggers a new request once the buffer is idle.

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
