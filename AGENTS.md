# Repository Guidelines

## Project Structure & Module Organization

- `android/app/` contains the Kotlin Android bridge. Production code is under
  `src/main/java/io/github/christianherget/trackglance/bridge/`; JVM tests use `src/test/`, and real-device Locus tests
  use `src/androidTest/`.
- `watchapp/` contains the Pebble C application. Cohesive modules under `src/c/` own AppMessage
  parsing, metrics, configuration, persistence, and transfer state; platform declarations and
  AppMessage keys live in `package.json`. Generated files belong in `watchapp/build/`.
- `protocol/README.md` is the contract between Android and Pebble. Update it whenever message
  versions, keys, commands, or units change.
- `docs/development.md` documents Chromebook, ARCVM, hardware, and integration-test setup.

The bridge reads Locus statistics and commands Locus through its Android API, then exchanges
versioned AppMessage dictionaries with CoreApp/PebbleKit. Supported watch platforms are only
Pebble Time 2 (`emery`) and Pebble Round 2 (`gabbro`).

In user-facing documentation, call the phone application the **Pebble App**, never CoreApp.
CoreApp may remain in source code and developer-only documentation when identifying the upstream
project or package.

Maintain `CHANGELOG.md` for every release with changes that matter to users. Summarize behavior,
features, compatibility, and important fixes; do not list every build-system or maintenance change.

## Build, Test, and Development Commands

Keep development dependencies inside the version-pinned container. Do not install the JDK, Android
SDK, Node, Python tools, Pebble Tool, or Pebble SDK into the developer's user profile. Docker is the
preferred static-development engine; rootless Podman is a supported Docker-compatible fallback.
The wrapper selects Docker when available, otherwise Podman. Set `DEV_CONTAINER_ENGINE=docker` or
`DEV_CONTAINER_ENGINE=podman` to choose explicitly.

```sh
./tools/bump-version 0.2.4
./tools/podman-test doctor static
./tools/podman-test build-static
./tools/podman-test dev bash
./tools/podman-test static
./tools/podman-test documentation
./tools/podman-test release-check
```

`build-static` builds only the development image and may use the network; `--refresh` explicitly
refreshes its digest-pinned base reference. `static` automatically builds that image when missing,
then runs Gradle/JVM, Android assembly, Android-test compilation, JavaScript, Python, shell, C
sanitizer, protocol, Pebble build, and PBW checks inside it. `documentation` validates committed
screenshots without regenerating them. `release-check` assembles the release APK and verifies its
application ID, API 24 minimum, and absence of `DebugStatusProvider`. Generated outputs go under
`build/`, `android/app/build/`, and `watchapp/build/`; the APK is written to
`android/app/build/outputs/apk/debug/trackglance-bridge-debug.apk`. Run
`./tools/podman-test dev ./gradlew regenerateDocumentationScreenshots` only when intentionally
updating tracked images. Use `dev bash` for an interactive container shell or prefix any focused
repository command with `./tools/podman-test dev`; do not reproduce its toolchain on the host.

Heavy acceptance remains separate from the development container. It supports Docker or rootless
Podman with `crun`, KVM, the pinned emulator stack, and the pinned Locus fixture. Never copy the APK
into an image, repository, Actions artifact, or persistent cache:

```sh
./tools/podman-test doctor acceptance
./tools/podman-test build
./tools/podman-test bootstrap --locus-apks /absolute/private/path
./tools/podman-test acceptance --locus-apks /absolute/private/path
./tools/podman-test acceptance-suite --locus-apks /absolute/private/path
```

The required pull-request GitHub-hosted path uses Docker, downloads the official public fixture with
`tools/download-locus-apk` into `$RUNNER_TEMP`, validates every pin in
`tools/locus-test-apk.properties`, performs headless bootstrap, and runs Android, Emery, and Gabbro
acceptance once. Manual dispatch can select a second Emery/Gabbro pass as a soak test. The local
`acceptance-suite` command reuses the validated golden state by default; `--fresh --cleanup` matches
the hosted provisioning lifecycle but is intentionally slower and removes generated acceptance state.

The API 32 image includes Google Play services, so use the regular Locus Map 4 Google Play APK for
acceptance. Do not use the `GooglePlayAfa` all-files-access build unless that permission is the
specific subject of a test, and do not use the Amazon/no-Google-services build in this emulator.
Keep the selected APK set in the absolute private directory passed to the wrapper.

The opt-in Locus test creates and saves a short recording. Never run it when a user recording is
active:

```sh
./tools/podman-test dev bash -c 'ANDROID_SERIAL=arc:5555 ./gradlew :android:app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.runLocusIntegration=true'
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
storage, run `./tools/podman-test dev npm test --prefix watchapp`, and smoke-test launch plus settings
opening on both Emery and Gabbro QEMU before declaring watch changes complete. Static stack checks
complement, but do not replace, QEMU.

## Commit & Pull Request Guidelines

History uses short imperative subjects such as `Route resume through Locus start action`. Keep each
commit focused and include tests with behavioral fixes. Pull requests should explain user-visible
behavior, list verified commands and hardware/platforms, link relevant issues, and include watch
photos or screenshots for layout changes. Never commit SDK paths, generated builds, signing keys, or
third-party CoreApp source.
