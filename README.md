# Locus Pebble Bridge

An Android companion and native Pebble watchapp that display live Locus Map 4 track-recording
statistics and control recording from a Pebble Time 2 or Pebble Round 2.

Fresh dashboards show elapsed time, distance, current speed, average speed, altitude, and current
heart rate. Select opens state-aware controls for start, pause/resume, stop-and-save, profile
selection, and adding a waypoint. Pebble Time 2 can optionally forward its raw heart rate to Locus
while recording; navigation and map previews remain out of scope.

The Android bridge explicitly selects the local `coredevices.coreapp` package and verifies that
incoming Binder calls resolve to that installed package and UID. Android's package manager enforces
package-name uniqueness and signature-compatible updates, so no separate certificate enrollment is
required. Only one watch is active at a time; opening another watch replaces the previous one.
Before clearing bridge storage or reinstalling the bridge, close the watchapp first, keep it closed
through restart, and reopen it afterward so snapshot ordering starts from a
coordinated new snapshot and profile-transfer ordering baseline.

## Repository layout

- `android/app` — Kotlin/Compose Android bridge using Locus API and PebbleKit Android 2.
- `watchapp` — native C Pebble Time 2 (`emery`) and Round 2 (`gabbro`) application.
- `protocol` — stable AppMessage v3 wire contract.
- `docs/development.md` — Chromebook, ARCVM, Core, and QEMU setup.
- `docs/design-decisions.md` — log of architectural choices regarding Locus API integration and background execution.
- `docs/end-to-end-testing.md` — complete installation, acceptance-test, troubleshooting, and
  containerization guide.

The Android application ID is `io.github.christianherget.locuspebble.bridge`; the PBW companion metadata points to that
package and this repository as the project/download page. A fork that changes the ID must update
both values. There is no published GitHub release yet, so the metadata does not claim a working
latest-release artifact URL.

## Build and test

Requirements are JDK 17, Node.js 18 or newer, Android SDK Platform 36 with Build Tools 36.0.0,
Pebble Tool 5.0.39, and Pebble SDK 4.33.1.

```sh
./gradlew verifyPebbleTargets :android:app:testDebugUnitTest :android:app:assembleDebug
./gradlew :android:app:compileDebugAndroidTestKotlin
cd watchapp
npm ci
npm test
pebble clean
pebble build
npm run verify:pbw
```

The parity check compares protocol keys, versions, limits, UUIDs, supported targets, and companion
metadata across Kotlin, C, PKJS, package metadata, and the protocol document. The PBW check then
verifies the actual archive, including both platform payloads, executable PKJS, manifest CRCs, and
an embedded hash of every local watch build input. The Gradle distribution and wrapper JAR are
checksum-pinned; Android dependency versions are locked and every resolved plugin/library artifact
is SHA-256 verified. Deliberate dependency updates must regenerate and review both lock and
verification data.

Artifacts:

- `android/app/build/outputs/apk/debug/locuspebble-bridge-debug.apk`
- `watchapp/build/locuspebble-watch.pbw`

Licensed under Apache-2.0.
