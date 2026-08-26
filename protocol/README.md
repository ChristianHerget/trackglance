# Bridge protocol v4

Protocol v4 is retained for release 0.2.5. The `0.2.5` APK and PBW must be upgraded together;
receivers require both protocol `4` and exact release `0.2.5`. The watchapp UUID is
`51c8d7cf-4cb2-4ef8-98c9-641706feb250`.

Every AppMessage dictionary is smaller than the 512-byte inbox/outbox allocation. Strings are UTF-8
and must be well-formed, nonempty where required, free of controls, and free of `|` in delimited
records. Display names are at most 20 Unicode scalar values and 80 bytes; Locus names are at most
255 bytes; stable page IDs are limited to 39 bytes; waypoint names may not exceed 120 UTF-8 bytes
before calling Locus.

## Message types

| Type | Direction | Meaning |
|---:|---|---|
| 1 | Android → watch | telemetry snapshot |
| 2 | watch → Android | command |
| 3 | Android → watch | command result |
| 4 | watch → Android | request snapshot |
| 5 | PKJS → watch | active-activity config chunk |
| 6 | Android → watch → PKJS | Locus catalog chunk |
| 7 | watch/PKJS → peer | request fresh Locus catalog |
| 8 | watch → Android | heart-rate sample |
| 9 | watch → PKJS | config result |
| 10 | Android → watch | recording context |
| 11 | watch → PKJS | runtime-config request/reconciliation |

## Keys

| Keys | Meaning |
|---:|---|
| 0 | protocol version |
| 1 | message type |
| 2 | command ID |
| 3 | command: obsolete Start `1` (rejected), Pause/Resume `2`, Stop/Save `3`, quick waypoint `4`, dictated waypoint `5` |
| 4 | result |
| 5 | recording state: stopped `0`, recording `1`, paused `2`, unavailable `3` |
| 6 | sample epoch seconds |
| 7 | watch session ID |
| 8 | reserved legacy display-profile name |
| 9 | current Locus profile name or waypoint mapping legacy field |
| 10–29 | telemetry values and formats |
| 30 | chunk index |
| 31 | chunk count |
| 32 | chunk data |
| 33 | transfer ID |
| 34 | reserved legacy Locus mode |
| 35 | release version, currently `0.2.5` |
| 36 | dictated waypoint name |
| 37 | current heart rate |
| 38 | heart-rate sequence |
| 39 | durable transfer generation |
| 40–50 | telemetry formats and pace values |
| 51 | decimal Locus recording-profile ID |
| 52 | canonical-config fingerprint A (unsigned 32-bit FNV-1a) |
| 53 | canonical-config fingerprint B (unsigned 32-bit CRC-32) |

## Recording ownership and commands

Locus owns recording start. Command value `1` remains reserved so old senders cannot silently change
meaning, but Android rejects it before execution and the watch never emits it. Pause/Resume and
Stop/Save require recording or paused state. Waypoints require recording state; command `4` uses
`Pebble waypoint`, while command `5` requires key 36.

Locus command broadcasts do not acknowledge application. Android polls Pause/Resume or Stop/Save
to the expected recording state for up to 1.5 seconds. Command results correlate by session and
command ID and the watch retains correlation for 120 seconds.

## Telemetry and recording context

A type-1 snapshot always contains the closed telemetry key set and no profile name. When stopped or
unavailable, recording metrics are encoded as unavailable even if generic location/sensor fields
exist. The official Locus `UpdateContainer` documents `trackRecStats` only during active recording.

Type 10 carries state plus key 51 as a decimal ID and key 9 as current display name when Android can
resolve the active name through its latest catalog. Context is separate so a 255-byte Locus name
cannot push telemetry over 512 bytes. A new active ID resets page selection to page 1.

## Locus catalog

Android obtains `TrackRecordProfileSimple` records and serializes each as `id|name`, separated by
newlines. IDs must be positive decimal signed-64-bit values and unique. Names may repeat because the
ID is identity. The complete list is at most 8191 bytes and 103 chunks; chunk data is at most 80
UTF-8 bytes. The list is refreshed at watch launch, when Watch Settings opens, and immediately when
an active name cannot be resolved—not by a periodic catalog timer.

A successful nonempty query uses result `0`. A successful empty query uses result `3` with an empty
payload and is non-authoritative. Query failure or validation failure produces no completed catalog
transfer, preserving PKJS's stale cache. PKJS performs destructive removal only after a
well-formed, nonempty result `0` catalog.

## Canonical configuration and active projection

PKJS stores the full canonical activity library under stable localStorage key `config`. The watch
stores only global settings and the latest activity projection. A projection is:

```text
theme|watchHr|interval|locusId|fingerprintA|fingerprintB
pageName|metric,metric,...|stablePageId
```

The canonical schema stores exactly four typed metric-page slots per activity. Only active slots are
projected, so the wire has one through four pages and one through six unique metric IDs per page. The complete
serialized projection is at most 4095 bytes and 52 chunks. Keys 52 and 53 must accompany every
chunk and equal the header values; inconsistency invalidates the candidate. Config result `0` means
applied, `8` invalid, and `9` storage failure. Legacy queued result `7` remains accepted, but 0.2.1
applies a valid active projection immediately and obsolete full-config pending journals do not
control the cache.

Type 11 carries key 51 and the watch's cached fingerprints. PKJS sends a projection when the ID is
known and either fingerprint differs. The watch requests this on context arrival and every 60
seconds. Display names may repeat, but stable page IDs remain unique. Unnamed active slots are
projected as localized `Page N`, numbered among active pages, so fingerprints include watch locale.
A refresh preserves the selected stable page ID if present, otherwise page 1.

## Transfer and delivery rules

Transfer IDs form a serial space modulo `2^31`; a candidate is newer only when
`0 < distance < 2^30`. The exactly-half-range ambiguous value is rejected. Generation `1` marks
durable v4 transfer state. After a fully envelope-valid marked chunk 0 has been seen, receivers
ignore every unmarked or unknown-generation frame. Duplicate identical chunks are harmless;
conflicts in data, count, result, fingerprints, or generation invalidate the partial transfer.
Incomplete transfers expire after 45 seconds.

Android reliable delivery uses three attempts, a 10-second attempt timeout, and bounded backoff.
Transport failure is always ambiguous. Snapshot delivery epochs order deliveries rather than date
the underlying Locus observation; equal epochs remain valid and lower epochs are rejected only for
the current watchapp process/session baseline. Heart-rate key 6 remains the actual Unix sample time.
