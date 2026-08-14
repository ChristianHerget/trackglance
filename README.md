# Locus Pebble Bridge

An Android companion and native Pebble watchapp that display live Locus Map 4 track-recording
statistics and control recording from a Pebble Time.

The initial dashboard shows elapsed time, distance, current speed, average speed, altitude, and
ascent. Select opens state-aware controls for start, pause/resume, stop-and-save, and adding a
waypoint. Heart-rate input, navigation, and map previews are intentionally deferred, while the
versioned protocol leaves room for them.

## Repository layout

- `android/app` — Kotlin/Compose Android bridge using Locus API and PebbleKit Android 2.
- `watchapp` — native C Pebble Time (`basalt`) application.
- `protocol` — stable AppMessage v1 wire contract.
- `docs/development.md` — Chromebook, ARCVM, Core, and QEMU setup.

The provisional Android application ID is `app.locuspebble.bridge`. Change it, the watchapp
`companionApp` entry, and the download URL together before publishing.

## Build and test

Requirements are JDK 17, Android SDK Platform 36, and Pebble CLI/SDK 4.33 or newer.

```sh
./gradlew :android:app:testDebugUnitTest :android:app:assembleDebug
cd watchapp
pebble build
```

Artifacts:

- `android/app/build/outputs/apk/debug/app-debug.apk`
- `watchapp/build/watchapp.pbw`

Licensed under Apache-2.0.

