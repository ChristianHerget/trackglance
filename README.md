# TrackGlance

**For Locus Map and Pebble smartwatches.**

An Android companion and native Pebble watchapp that display live Locus Map 4 track-recording
statistics and control an active recording from a Pebble Time 2 or Pebble Round 2.

The bridge supports Android 7.0 (API 24) or newer. The containerized acceptance environment uses
Android 12L (API 32), independently of the bridge's installation minimum.

Fresh dashboards show elapsed time, distance, current speed, average speed, altitude, and current
heart rate. Units and compact precision follow the granular preferences read from Locus Map; the
bridge performs conversion and the watch only renders the selected value and suffix. Recording is
started in Locus Map. During an active recording, Select opens state-aware controls for
pause/resume, stop-and-save, and adding a waypoint. Up and Down wrap through the one-to-four pages
created for the active Locus activity. Pebble Time 2 can optionally forward its raw heart rate to
Locus while recording; navigation and map previews remain out of scope.

## Local-only by design

TrackGlance has no TrackGlance server or account, analytics, or hosted crash reporting. The release
Android Bridge has no network permission for runtime operation: it requests neither
`android.permission.INTERNET` nor `android.permission.ACCESS_NETWORK_STATE`. Recording data and
commands therefore stay on the device-local path between Locus Map, the Android Bridge, the Pebble
App, Bluetooth/AppMessage, and the watch.

The Bridge keeps its refresh preference only on the device, excludes it from Android backup and
device transfer, and holds at most 20 recent diagnostics in process memory. The Pebble App stores
the watch configuration and last Locus profile catalog locally; its TrackGlance settings page is a
locally generated offline page. The watch persists only the active activity's small configuration
projection and ordering counters, not the complete catalog or recorded track.

This promise covers TrackGlance itself. Locus Map and the Pebble App are required, separate
applications with their own storage, synchronization, network, and privacy behavior; it does not
promise that either third-party app works fully offline. Installing the apps and downloading this
documentation use their normal external services, and tapping the Bridge's legal link deliberately
opens the page in the user's browser. Maintainers also submit release APK and PBW artifacts to
VirusTotal; those build artifacts contain no user runtime data.

The Android bridge explicitly selects the installed Pebble App package (`coredevices.coreapp`) and
verifies that incoming Binder calls resolve to that package and UID. Android's package manager
enforces package-name uniqueness and signature-compatible updates, so no separate certificate
enrollment is required. Only one watch is active at a time; opening another watch replaces the
previous one.
Before clearing bridge storage or reinstalling the bridge, close the watchapp first, keep it closed
through restart, and reopen it afterward so snapshot ordering starts from a
coordinated new snapshot and profile-transfer ordering baseline.

## Repository layout

- `android/app` — Kotlin/Compose Android bridge using Locus API and PebbleKit Android 2.
- `watchapp` — native C Pebble Time 2 (`emery`) and Round 2 (`gabbro`) application.
- `protocol` — stable AppMessage v4 wire contract.
- `docs/development.md` — Chromebook, ARCVM, Core, and QEMU setup.
- `docs/design-decisions.md` — log of architectural choices regarding Locus API integration and background execution.
- `docs/end-to-end-testing.md` — complete installation, acceptance-test, troubleshooting, and
  containerization guide.
- `docs/podman-testing.md` — rootless API 32 Podman build, provisioning, and automated-test workflow.

The Android application ID is `app.trackglance.bridge`; the PBW companion metadata points to that
package and this repository as the project/download page. A fork that changes the ID must update
both values. There is no published GitHub release yet, so the metadata does not claim a working
latest-release artifact URL.

## Build and test

The only host development prerequisite is Docker, or rootless Podman as a Docker-compatible
fallback. JDK 17, Android SDK 37, Node, Python, Pebble Tool 5.0.40, and Pebble SDK 4.33.1 stay in the
version-pinned development image.

```sh
./tools/podman-test doctor static
./tools/podman-test static
./tools/podman-test documentation
./tools/podman-test release-check
# Optional interactive or focused work:
./tools/podman-test dev bash
./tools/podman-test dev ./gradlew :android:app:testDebugUnitTest
```

The routine parity check compares protocol keys, versions, limits, UUIDs, supported targets, and
companion metadata across Kotlin, C, PKJS, package metadata, and the protocol document. It never
launches emulators, installs packages, or rewrites documentation assets. Run `documentation` to
validate Sphinx against committed images, and run `./tools/podman-test dev ./gradlew
regenerateDocumentationScreenshots` only when intentionally updating those images.
The PBW check then
verifies the actual archive, including both platform payloads, executable PKJS, manifest CRCs, and
an embedded hash of every local watch build input. The Gradle distribution and wrapper JAR are
checksum-pinned; Android dependency versions are locked and every resolved plugin/library artifact
is SHA-256 verified. Deliberate dependency updates must regenerate and review both lock and
verification data with the repository wrapper, for example:

```sh
./tools/podman-test dev ./gradlew --write-locks --write-verification-metadata sha256 \
  verifyPebbleTargets :android:app:testDebugUnitTest :android:app:assembleDebug
```

`./tools/podman-test static` runs the public development-container suite without KVM or Locus
inputs. For fast local end-to-end feedback, `acceptance-suite --locus-apks /absolute/private/path`
reuses the validated API 32 golden state and runs Android, Emery, and Gabbro once. Every pull request
must also pass the ephemeral GitHub-hosted Docker/KVM suite from a fresh bootstrap; manual dispatch
can request a second watch pass as a soak test.

Artifacts:

- `android/app/build/outputs/apk/debug/trackglance-bridge-debug.apk`
- `android/app/build/outputs/apk/release/trackglance-bridge-release-unsigned.apk`
- `watchapp/build/watchapp.pbw`
- `docs/sphinx/_build/html/index.html`

Licensed under Apache-2.0.

## Legal and trademarks

TrackGlance is an independent, unofficial project. It is not affiliated with, endorsed by,
sponsored by, or associated with Core Devices LLC, Pebble Technology Corp., or Asamm Software,
s.r.o. Pebble is a trademark of Pebble Technology Corp. Locus Map is a brand of Asamm Software,
s.r.o. Those names are used solely to identify compatibility: TrackGlance connects supported
Pebble smartwatches with Locus Map. No ownership of those names or marks, or endorsement by their
owners, is claimed.
