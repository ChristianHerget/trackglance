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
preference; development installs under the former ID must be uninstalled. API 34 remains the
acceptance emulator level, not the product installation minimum.

## 4. Verification levels

**Status:** Decision 12 defines the synthetic and real-app test tiers. The rollout keeps the
previous required acceptance check until the replacement synthetic gate is validated.

**Decision:** Routine `verifyPebbleTargets` performs only fast stack and protocol checks.
`verifyDocumentation` validates Sphinx using committed images, while
`regenerateDocumentationScreenshots` is the only Gradle task that intentionally rewrites those
images. `fullAcceptance` is the explicit Podman Android/Pebble App/Pebble QEMU entry point.

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

**Status:** Updated by decision 12. GitHub workflow results authorize releases; artifact,
signing, SBOM, and publication requirements remain applicable.

**Decision:** Require successful synthetic main CI and full real-app acceptance for the exact
release commit. A manual or weekly acceptance result can qualify that commit; otherwise the
release workflow dispatches full acceptance before signing. Failed or superseded results cannot
qualify it. A release tag must equal current main before access to signing secrets and immediately
before the draft is changed. There are no signed CI records or PR-to-main evidence reuse.

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

**Rationale:** This binds artifacts to the qualified main commit while keeping signing access
short and compilation-only. A durable documentation asset makes the deployed manual independently
reviewable, and explicit publication preserves artifact identity and human approval.

## 12. Synthetic CI and release qualification

**Status:** Confirmed; implementation and rollout validation are in progress.

**Decision:** Use synthetic integration tests for required PR and main CI. Run acceptance with
real Locus Map and the Pebble App weekly, manually, and before releases. Replace signed CI
certification and PR-to-main evidence reuse with checks of successful GitHub workflow runs for
the exact release commit. Keep image and release artifact signatures and attestations.

### Context, goals, and boundaries

The existing CI provisions real apps for Android instrumentation and both watch acceptance jobs.
The watch jobs wait for Android instrumentation, extending the critical path. Routine static and
documentation jobs also build the development image on an empty runner, exposing each run to SDK
provisioning failures. Strict hosted-runner matching can prevent reuse of otherwise successful PR
evidence on main.

Aim for required PR checks to finish within five minutes on a fresh hosted runner. This is an
optimization target: retain the selected coverage if timing exceeds it, and report the overrun.
Measure from the first required job starting until all required checks complete, including
provisioning and downloads. Report restored-cache and cache-miss timings separately; report queue
time separately from execution.

Production behavior, application identities, supported platforms, and the wire protocol do not
change. Extend the existing in-process fakes instead of maintaining replacement Locus and Pebble
App APKs. Synthetic tests complement real-app acceptance and do not establish external app
compatibility.

### Components, data flow, and interfaces

| Layer | Required routine coverage |
| --- | --- |
| Android | JVM tests, lint, builds, packaging checks, and instrumentation on a clean Android emulator using fake Locus and Pebble transport collaborators |
| Watch | C sanitizer tests, JavaScript and settings tests, protocol checks, and Emery/Gabbro builds |
| Watch runtime | Standalone QEMU launch and settings smoke checks on both platforms using the pinned SDK protocol client and a Node phone peer |
| Documentation and security | Committed-documentation validation, dependency review, and existing CodeQL protections |

The Android suite uses the real Android runtime with existing `LocusBridgeGateway` and transport
fakes. Explicitly exclude real-Locus integration tests from synthetic execution rather than
allowing them to skip. Validate the expected test cases in fresh reports; missing results and
unexpected skips fail the job. Keep production service trust checks intact.

Each watch smoke run installs the PBW built by the current workflow, confirms launch, exchanges
representative AppMessages, and exercises settings opening, saving, and application. Use the
packaged JavaScript in Node and the SDK protocol client. Headless settings-page checks cover the HTML and
configuration exchange; the real Pebble App's Android WebView remains a real-app acceptance
responsibility. A successful build or transport acknowledgement alone does not pass the smoke
check.

Keep test fixtures and simulator control in the test harness. Cover recording states, command
results, profile and configuration transfers, malformed messages, transport failures, reconnects,
and stale sessions. Do not add production protocol messages or runtime behavior solely for these
tests. Share current-run build outputs between jobs; do not treat cached APK/PBW files or earlier
test reports as current results.

Keep the existing container-based development entry points and full acceptance suite. Provide
focused entry points for synthetic Android execution and standalone watch smoke tests. Command
names, helper organization, and test layout remain implementation choices.

### Workflow, release qualification, and trust

Run independent Android, watch, documentation, and security work in parallel. Replace the required
full-stack PR acceptance aggregate with a required synthetic aggregate after validation. Preserve
dependency review and CodeQL protections, including the high-severity merge rule. Main runs the
synthetic checks directly; it does not reuse PR evidence.

Remove signed CI certification records, host fingerprints, and their release consumers together.
Use GitHub workflow and job conclusions instead. This changes test authorization, not image trust
or release artifact provenance: retain digest pins, keyless image signature verification, image
provenance/SBOM attestations, and release artifact attestations.

Run the full real-app suite weekly on main and expose a manual qualification trigger before
tagging. Weekly failures remain visible through GitHub's notifications and do not become PR merge
requirements. They prevent that unsuccessful result from qualifying the commit for release.

Before signing or creating a release draft, require successful synthetic main CI and full real-app
acceptance for the exact tagged commit. Retain the requirement that the tag equals current main
before access to signing secrets and before changing the draft. Reuse an exact-commit manual or
weekly qualification result when available; otherwise run full acceptance automatically for the
release tag. Keep acceptance outside the signing job.

Qualification checks must verify the repository, trusted workflow identity, tested commit, latest
run/attempt, and all required job conclusions. Wait for pending qualification. Failed, cancelled,
incomplete, or skipped required results cannot qualify a release or be hidden by an older success.
An API lookup failure also cannot authorize signing. Do not automatically rerun failed
qualification to obtain a green result.

### Runtime, reliability, and failure handling

Use a lean, digest-pinned CI toolchain published through the protected image workflow. Routine
jobs must not rebuild the SDK image or install Locus Map or the Pebble App. Keep toolchains inside
the container, emulator state disposable, and dependency caches optional for correctness. Preserve
the existing private-fixture, image-content, and cleanup constraints for real-app acceptance.

Use bounded readiness assertions instead of fixed sleeps. Retain bounded logs, fresh test reports,
screenshots, and cleanup on failure or cancellation. Record provisioning, build, and test durations
separately. Keep the evidence-based retry policy: diagnose the exact failed attempt, retry failed
jobs once only for a supported isolated external failure, and investigate recurrence before another
retry.

### Migration, rollback, and acceptance

Introduce the synthetic tier alongside the current required gates. Publish and verify replacement
toolchain images before adopting their digests. Replace branch protection only after synthetic
Android tests and both standalone QEMU smoke checks pass. Update release consumers and operational
documentation when retiring CI certification; this architecture document does not itself remove
any current gate. Rollback restores the previous workflow, matching release consumers, and required
checks together.

Validate the following before completing the migration:

- Routine tests pass without Locus Map or the Pebble App installed.
- Both watch platforms launch and complete settings save/apply checks using the current PBW.
- Missing tests, crashes, malformed transfers, and stale results fail the appropriate gate.
- Manual qualification works before tagging; automatic release qualification runs when no result
  exists for the exact commit.
- Release authorization rejects another commit and unsuccessful or incomplete qualification.
- Full real-app acceptance still passes after harness changes.
- Fresh-runner timings cover every required check, with restored caches and cache misses reported
  separately. Disclose any five-minute overrun without dropping coverage.

The pinned SDK provides installation, AppMessage, and screenshot interfaces. A Node phone peer
executes the actual packaged JavaScript and settings HTML. Standalone smoke checks pass on Emery
and Gabbro, and the clean API 34 Android suite passes without either third-party app installed.
The five-minute target still requires fresh hosted-runner measurements.

**Rationale:** Existing boundary fakes retain Android lifecycle coverage without third-party app
onboarding. Both watch emulators retain launch and settings coverage. Weekly and release acceptance
detect external compatibility drift. GitHub run checks remove routine certification complexity
while exact-commit qualification continues to protect releases.

**Acceptance of architecture:** Implementation may proceed without requiring the implementer to
make additional material architectural decisions. Internal helper structure and command naming
remain delegated to implementation. The implementation must retain the
previous required gate until the migration checks above pass.
