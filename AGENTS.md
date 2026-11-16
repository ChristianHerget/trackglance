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

## Agent Communication

Use the installed `$write-clearly` skill for all user-facing progress updates, final responses, and
prose artifacts. Lead with the outcome, use direct language, and preserve exact technical terms,
commands, paths, identifiers, quotations, uncertainty, and necessary detail.

## Build, Test, and Development Commands

Use [docs/testing.md](docs/testing.md) for changed-area commands, test selection, and completion
checks. Keep JDK, Android SDK, Node, Python tools, Pebble Tool, and Pebble SDK inside the
version-pinned container; never install them in the developer's user profile. Docker is preferred;
rootless Podman is the fallback. Select explicitly with `DEV_CONTAINER_ENGINE=docker` or `podman`.

```sh
./tools/podman-test doctor static
./tools/podman-test build-static
./tools/podman-test dev bash
./tools/podman-test static
./tools/podman-test documentation
./tools/podman-test release-check
```

`build-static --refresh` explicitly refreshes the digest-pinned base. `static` builds a missing
image automatically. Generated outputs belong in `build/`, `android/app/build/`, and
`watchapp/build/`; the debug APK is
`android/app/build/outputs/apk/debug/trackglance-bridge-debug.apk`. Documentation checks must not
regenerate tracked screenshots; intentional updates use
`./tools/podman-test dev ./gradlew regenerateDocumentationScreenshots`.

[docs/podman-testing.md](docs/podman-testing.md) owns acceptance setup, private fixtures, image
publishing, and cleanup. Acceptance needs KVM and the pinned emulator/Locus stack. Keep the regular
Locus Map 4 Google Play APK in the absolute private directory passed to `--locus-apks`; never copy
it into an image, repository, Actions artifact, or persistent cache. Do not use Amazon/no-Google-
services builds, or GooglePlayAfa unless testing all-files access specifically.

Published images use immutable GHCR digests in `tools/ci-images.env`. Update them only through the
protected `Publish CI images` workflow, retain provenance/SBOM attestations and keyless signature
verification, and compare source versus published acceptance before adopting new pins. Published
images must never contain Locus, TrackGlance APK/PBW files, signing material, emulator state, or
persistent caches. Hosted acceptance downloads and validates the public fixture in `$RUNNER_TEMP`,
creates fresh golden state, and runs Android, Emery, and Gabbro. Run this full suite weekly,
manually, and before releases; routine PR/main gates use synthetic Android and watch checks.

[docs/development.md](docs/development.md) owns ARCVM/hardware and real-device commands. The opt-in
Locus contract test is non-mutating: it validates numeric profile identities and rejection of
obsolete Start command `1`. Never run it when a user recording is active.

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
photos or screenshots for layout changes. The protected pull-request workflow is the authoritative
synthetic gate and uses the published GHCR image set. Real-app acceptance remains mandatory for
the exact release commit. Run focused local checks appropriate to
the change before opening a pull request. For changes that affect Android/watch runtime behavior or
the acceptance harness, a warm local suite provides useful end-to-end feedback:

```sh
./tools/podman-test acceptance-suite --locus-apks /absolute/private/path
```

Use `--published --cleanup` only when reproducing the hosted lifecycle locally. Use
`--fresh --cleanup` when changing acceptance provisioning or validating a replacement published
image set. Do not duplicate full acceptance for documentation, workflow, or other changes that
cannot affect runtime behavior. Never commit SDK paths, generated builds, signing keys, or
third-party CoreApp source.
