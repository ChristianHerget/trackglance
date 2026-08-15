# Repository Guidelines

## Project Structure & Module Organization

- `android/app/` contains the Kotlin Android bridge. Production code is under
  `src/main/java/app/locuspebble/bridge/`; JVM tests use `src/test/`, and real-device Locus tests
  use `src/androidTest/`.
- `watchapp/` contains the Pebble C application. Edit `src/c/main.c`; platform declarations and
  AppMessage keys live in `package.json`. Generated files belong in `watchapp/build/`.
- `protocol/README.md` is the contract between Android and Pebble. Update it whenever message
  versions, keys, commands, or units change.
- `docs/development.md` documents Chromebook, ARCVM, hardware, and integration-test setup.

The bridge reads Locus statistics and commands Locus through its Android API, then exchanges
versioned AppMessage dictionaries with CoreApp/PebbleKit. Supported watch platforms are only
Pebble Time 2 (`emery`) and Pebble Round 2 (`gabbro`).

## Build, Test, and Development Commands

```sh
./gradlew verifyPebbleTargets :android:app:testDebugUnitTest :android:app:assembleDebug
./gradlew :android:app:compileDebugAndroidTestKotlin
cd watchapp && pebble clean && pebble build
```

These commands verify watch targets, run JVM regressions, produce the Android APK, compile Android
instrumentation tests, and produce `watchapp/build/watchapp.pbw`. The APK is written to
`android/app/build/outputs/apk/debug/app-debug.apk`.

The opt-in Locus test creates and saves a short recording. Never run it when a user recording is
active:

```sh
ANDROID_SERIAL=arc:5555 ./gradlew :android:app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.runLocusIntegration=true
```

## Coding Style & Naming Conventions

Use four-space indentation and Kotlin conventions for Android: `UpperCamelCase` types,
`lowerCamelCase` functions, and immutable values where practical. C uses two-space indentation,
`snake_case` functions, and `s_` prefixes for file-static state. Keep protocol constants synchronized
across Kotlin, C, `package.json`, and protocol documentation.

## Testing Guidelines

Use JUnit 4. Name tests after observable behavior, for example
`reopenedWatchSessionMayReuseEveryCommandId`. Add regressions for state routing, lifecycle changes,
deduplication, wire scaling, and platform packaging. Locus broadcasts do not acknowledge application;
integration tests must poll and assert the resulting recording state.

A successful Pebble build is not a runtime check. Keep large C buffers out of function-local stack
storage, run `npm test`, and smoke-test launch plus settings opening on both Emery and Gabbro QEMU
before declaring watch changes complete. Static stack checks complement, but do not replace, QEMU.

## Commit & Pull Request Guidelines

History uses short imperative subjects such as `Route resume through Locus start action`. Keep each
commit focused and include tests with behavioral fixes. Pull requests should explain user-visible
behavior, list verified commands and hardware/platforms, link relevant issues, and include watch
photos or screenshots for layout changes. Never commit SDK paths, generated builds, signing keys, or
third-party CoreApp source.
