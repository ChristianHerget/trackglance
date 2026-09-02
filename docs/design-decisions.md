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

## 10. Watch maintenance clock-correction policy

**Status:** Proposed

**Decision:** Retain the centralized maintenance planner and its single relative `AppTimer`.
Actual watch-clock corrections may advance or delay short maintenance deadlines. This is an
accepted limitation consistent with [Issue #47](https://github.com/ChristianHerget/trackglance/issues/47),
not a release-blocking defect.

- Continue using `time_ms()` epoch seconds for deadline calculation and one relative `AppTimer` for
  sparse wakeups.
- Rely only on the SDK's documented relative-timer contract; do not claim guaranteed FreeRTOS or
  hardware monotonicity.
- Do not introduce per-category timers, a synthetic monotonic clock, or `TickTimerService`.
- Accept early or late UI transitions, sampling, transfer expiry, reconciliation, staleness, and
  command-result timeouts after actual clock corrections.
- The sharpest unlikely consequence is that a premature command timeout could encourage retrying an
  operation that already executed.
- Timezone and daylight-saving changes do not alter Unix epoch time.
- Retain existing transport validation, recording-identity checks, subsequent-event recovery, and
  watchapp restart as recovery mechanisms.
- Reconsider per-owner timers only if real-world reports demonstrate meaningful failures.

## 11. Release certification and artifact-only builds

**Decision:** A distributable build is authorized by the successful `CI` push workflow for the
exact squash-merged commit, rather than by repeating general tests in the signing job. Every push
to `main` runs static/release checks, committed-documentation validation, and hosted Android,
Emery, and Gabbro acceptance. A release tag must equal the current `main` HEAD both before access
to signing secrets and immediately before the draft is changed.

The protected tag build verifies the signed and attested, digest-pinned acceptance runner and uses
it only as an immutable build toolchain. It freshly builds and validates the signed APK, generated
PBW, and an offline documentation archive, attests those three artifacts, and leaves a draft. It
also publishes runtime-only CycloneDX 1.6 and SPDX 2.3 SBOMs for the APK and PBW. Each format is
generated natively from the original Gradle or PBW metadata; neither is converted from the other.
CycloneDX also runs in ordinary CI for AppSec feedback, while SPDX runs only for stable release-tag
builds for legal and audit consumers. Each document is attested against its artifact. The PBW
metadata records its Pebble SDK build version and compatibility level without misrepresenting the
SDK as shipped content. Build tools
remain represented by the separate CI-image SBOMs rather than by the release SBOMs.
General unit, lint, Android-test, and acceptance entry points are deliberately absent from this
job; Android's release-internal `lintVital` remains part of `assembleRelease`.

Publication is a separate tag-ref manual workflow. It verifies the draft checksums, exact
workflow/source/runner provenance, and equality of downloaded SBOMs with their signed predicates;
it then deploys the archived documentation for review, pauses at the
protected `release` environment, then downloads and verifies everything again before publishing.
It does not require the older reviewed tag to remain the current `main` HEAD. Published releases
are not mutated; a correction requires a new patch release.

**Rationale:** This binds artifacts to tested post-merge source while keeping signing access short
and compilation-only. A durable documentation asset makes the deployed manual independently
reviewable, and explicit publication removes the fragile automatic publish chain without weakening
artifact identity or human approval.
