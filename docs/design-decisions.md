# Architecture & Design Decisions

This document captures important design decisions made during the development of the TrackGlance Bridge.

## 1. Single APK Architecture
**Decision:** The TrackGlance Bridge is distributed as a single, standard Android APK (`app.trackglance.bridge`) that functions both as a standalone Android app and as an optional Locus Map add-on. The Kotlin namespace remains `io.github.christianherget.trackglance.bridge`; namespace and installed identity are intentionally independent.
**Rationale:** 
- Being an add-on does not grant special API access, better background execution, or automatic Locus lifecycle syncing. 
- Using a single APK with a lightweight `MAIN_FUNCTION` intent filter avoids the overhead of maintaining two separate apps while still providing the convenience of launching the bridge directly from Locus Map's function menu.
- When launched via the Locus menu, the bridge will attempt to auto-start the Pebble watchapp for seamless transition.

## 2. Locus-owned recording start

**Decision:** Recording start is performed only in Locus Map. The watch shows an instruction while
stopped; protocol command `1` is reserved and rejected. Pause/resume, stop/save, and waypoints remain
available after recording begins.

**Rationale:**

- Starting in Locus makes profile choice and Android foreground-service ownership explicit.
- It removes a compatibility path that could create Locus's recording service without usable
  location or sensors on recent Android versions.
- Keeping value `1` reserved makes old watch software fail closed instead of reinterpreting it.

## 3. Android compatibility identity

**Decision:** Starting with the first public release, use `applicationId` `app.trackglance.bridge`
and keep `minSdk` 24. The `io.github...` Kotlin namespace does not change the installed identity.
This intentionally does not migrate the unpublished old debug installation or its refresh
preference; development installs under the former ID must be uninstalled. API 32 remains the
acceptance emulator level, not the product installation minimum.

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

**Decision:** Persist the latest active-activity projection with one eight-byte versioned header
(`magic`, format, payload length, chunk count) and a single fixed chunk range. Pebble limits one
persistence key to 256 bytes, so a projection cannot literally fit in one key; chunking is the only
retained abstraction. Catalogs remain phone-owned and are relayed without watch persistence. The
prior unreleased dual banks, generations, metadata checksum, data
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
wire tuple/snapshot parsing, `i18n` owns the compact native English/German catalog, `ui_metrics`
owns validated integer mantissa/format rendering, `watch_state` owns
transfer and epoch transitions, `watch_config` owns configuration parsing, and `persistent_blob`
owns the small versioned persistence record. The build includes all modules for Emery and Gabbro,
while pure metrics/config/state/persistence transformations run in the host C sanitizer suite.

Unit selection, floating-point conversion, Locus medium-precision thresholds, rounding, slope
trigonometry, pace, and energy conversion remain on Android. This keeps the watch protocol closed
and the per-render watch work bounded to integer arithmetic and suffix lookup.
