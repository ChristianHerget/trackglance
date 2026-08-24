# Architecture & Design Decisions

This document captures important design decisions made during the development of the Pebble Locus Bridge.

## 1. Single APK Architecture
**Decision:** The Pebble Locus Bridge is distributed as a single, standard Android APK (`app.locuspebble.bridge`) that functions both as a standalone Android app and as an optional Locus Map add-on. The Kotlin namespace remains `io.github.christianherget.locuspebble.bridge`; namespace and installed identity are intentionally independent.
**Rationale:** 
- Being an add-on does not grant special API access, better background execution, or automatic Locus lifecycle syncing. 
- Using a single APK with a lightweight `MAIN_FUNCTION` intent filter avoids the overhead of maintaining two separate apps while still providing the convenience of launching the bridge directly from Locus Map's function menu.
- When launched via the Locus menu, the bridge will attempt to auto-start the Pebble watchapp for seamless transition.

## 2. Track Recording Background Execution

**Decision:** The bridge's background service (`BridgePebbleListenerService`) relies purely on Locus
API broadcasts (for example, `ACTION_TRACK_RECORD_START`) to start and stop recordings. It does not
attempt to force Locus Map to the foreground. User documentation tells users on affected Android
and Locus versions to bring Locus to the foreground before selecting **Start recording** on the
watch; automated API-32 acceptance does the same.

**Rationale:**

- Locus's [public-intent documentation](https://docs.locusmap.app/doku.php/manual:advanced:locus_api:public_intents)
  defines START as a broadcast intent and does not document a foreground-activity precondition. The
  official API's [`actionTrackRecordStart`](https://github.com/asamm/locus-api/blob/23a33813a2c14fbd364c625ac125ed73687335e9/locus-api-android/src/main/java/locus/api/android/ActionBasics.kt#L165-L186)
  likewise constructs the action and sends a package-targeted broadcast without launching Locus.
  Foregrounding is therefore a compatibility workaround, not part of the published Locus API
  contract.
- Android restricts foreground services started while their app is backgrounded, particularly when
  they need while-in-use location or sensor permissions. On versions before Android 14, Android can
  create such a service but withhold the required resources; the platform documents the diagnostic
  `Foreground service started from background can not have location/camera/microphone access` in
  its [foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
- The API-32 emulator reproduced that exact diagnostic for Locus's `TrackRecordingService` when a
  watch START arrived while Locus was backgrounded. Bringing Locus to the foreground before START
  avoids that restricted service start. Modern Android also restricts background activity launches,
  so the bridge does not try to apply this workaround invisibly or request invasive overlay
  privileges.

## 3. Android compatibility identity

**Decision:** Keep `applicationId` `app.locuspebble.bridge` and `minSdk` 24. The `io.github...`
Kotlin namespace does not change the installed identity. Versions 0.1.6 and 0.1.8 used the former
identity and API 24, and no GitHub release published the experimental 0.1.9 identity/API-32 change.
API 32 remains the acceptance emulator level, not the product installation minimum.

## 4. Verification levels

**Decision:** Routine `verifyPebbleTargets` performs only fast stack and protocol checks.
`verifyDocumentation` validates Sphinx using committed images, while
`regenerateDocumentationScreenshots` is the only Gradle task that intentionally rewrites those
images. `fullAcceptance` is the explicit Podman Android/CoreApp/Pebble QEMU entry point.

## 5. Profile-list delivery observation

**Decision:** Profile-list transfer has no production acknowledgement. The experimental
`PROFILE_LIST_RESULT` message affected only debug automation: Android did not retry, change product
state, or provide a user action from it. It was never published in a GitHub release. Acceptance
therefore observes the normal Watch Settings/profile behavior instead of retaining watch queue,
timer, protocol, and Android status state solely for a test hook. Configuration writes retain their
production `CONFIG_RESULT` acknowledgement because PKJS uses it to commit or recover user settings.

## 6. CoreApp ingress trust boundary

**Decision:** Caller package/UID validation happens in the exported service's Binder stub, before
caller identity is lost. The service copies the request, captures a typed process-local
`TrustAdmission`, and dispatches both together. It rechecks the UID and admission before returning
the asynchronous callback. The external `WatchIdentifier` is never rewritten and carries no hidden
NUL-delimited authentication token. Trust loss increments the admission generation, so work and
callbacks captured under the former CoreApp selection fail closed.

## 7. Pebble persistence format

**Decision:** Persist each logical value with one eight-byte versioned header (`magic`, format,
payload length, chunk count) and a single fixed chunk range. Pebble limits one persistence key to
256 bytes, so configuration and profile payloads cannot literally fit in one key; chunking is the
only retained abstraction. The prior unreleased dual banks, generations, metadata checksum, data
checksum, recovery selection, and wear-leveling behavior were removed. Exact stored sizes detect
truncation, and the header is written last as the commit marker. A failed write is unreadable and a
later successful write recovers it. The old single-string keys remain readable for conservative
0.1.8 sideload compatibility and are removed after the first successful new-format write. No
migration for the unreleased dual-bank format is carried.

## 8. Transient Android operation ordering

**Decision:** One in-memory coordinator serializes command mutation, snapshot publication, and
profile transfer. It owns the snapshot epoch and profile-transfer serial counters. The separate
durable epoch/serial stores, per-operation mutexes, and single-purpose serialized-delivery wrapper
were removed. The bounded command journal remains in memory to deduplicate one bridge process
session, and trust admissions still reject completion from a superseded companion generation.

## 9. Watch module boundaries

**Decision:** `main.c` owns lifecycle, menus, and orchestration. `app_message_handler` owns strict
wire tuple/snapshot parsing, `ui_metrics` owns pure labels and unit formatting, `watch_state` owns
transfer and epoch transitions, `watch_config` owns configuration parsing, and `persistent_blob`
owns the small versioned persistence record. The build includes all modules for Emery and Gabbro,
while pure metrics/config/state/persistence transformations run in the host C sanitizer suite.
