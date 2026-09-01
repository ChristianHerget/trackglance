# Development setup

## Preparing a version bump

Run `./tools/bump-version MAJOR.MINOR.PATCH` once. It verifies that the existing release markers
agree, increments Android's version code, and updates the Android, watch, JavaScript, protocol,
release-note, and documentation versions together. Use `./tools/bump-version --check` in reviews
to verify parity without changing files.

For the complete installation, QEMU/CoreApp setup, end-to-end acceptance procedure, troubleshooting,
and containerization roadmap, see [End-to-end development and testing](end-to-end-testing.md).
For the automated rootless Android 12L environment, see [Podman test environment](podman-testing.md).

The bridge supports API 24 and newer. Android 12L/API 32 is the sole automated acceptance runtime,
not the product installation minimum. Platform 36 remains the compile and target SDK.

## Repository security merge gates

The repository ruleset named **Require high-severity CodeQL results** is active for the default
branch. Its `code_scanning` rule requires the `CodeQL` tool with `alerts_threshold` set to `none`
and `security_alerts_threshold` set to `high_or_higher`. Non-security findings and low- or
medium-severity security alerts remain advisory. High- and critical-severity security alerts block
the update, as do missing, failed, or still-running required CodeQL results.

The ruleset is separate from the existing `main` branch protection and must not replace its
required CI, documentation, acceptance, and dependency-review checks or its administrator,
conversation-resolution, force-push, and deletion restrictions. Audit the live rule and thresholds
with:

```sh
gh api repos/ChristianHerget/trackglance/rulesets \
  --jq '.[] | {id, name, target, enforcement}'
gh api repos/ChristianHerget/trackglance/rulesets/RULESET_ID
```

The five advanced CodeQL categories and their `security-extended` query configuration remain in
`.github/workflows/codeql.yml`; changing the remote merge rule does not configure or replace those
analyses.

## Release environment prerequisites

The tag-triggered release job uses the protected GitHub `release` environment. Before creating a
release tag, confirm that the environment contains the Android signing secrets and a working
`VIRUSTOTAL_API_KEY`. Set the VirusTotal key interactively so its value never appears in a command
argument or log:

```sh
gh secret set VIRUSTOTAL_API_KEY --env release --repo ChristianHerget/trackglance
```

The release job submits the staged signed APK and PBW after deleting the private signing key. Both
uploads and their analysis links must succeed before a draft is created. The links identify
successful submissions only: the workflow does not wait for analysis or gate publication on later
malicious or suspicious detections.

## Private Locus acceptance fixture

On the primary development machine, keep the regular Locus Map 4 Google Play APK outside the
repository in:

```text
/home/christian/.local/share/trackglance-acceptance/locus-apks
```

Pass that directory to the container wrapper; the filename inside it is not significant because
the wrapper validates the APK against `tools/locus-test-apk.properties`:

```sh
./tools/podman-test bootstrap \
  --locus-apks /home/christian/.local/share/trackglance-acceptance/locus-apks
./tools/podman-test acceptance \
  --locus-apks /home/christian/.local/share/trackglance-acceptance/locus-apks
```

The APK is private input. Never copy it into this repository, a container image, an Actions
artifact, or a persistent cache.

## Chromebook Linux and ARCVM

Enable ChromeOS **Develop Android apps / ADB debugging**, then connect from the normal Linux
terminal. This does not require full ChromeOS Developer Mode.

```sh
adb connect arc
adb devices -l
adb -s arc:5555 install -r android/app/build/outputs/apk/debug/trackglance-bridge-debug.apk
```

If both `arc:5555` and `emulator-5554` appear, use an explicit `-s` selector. ADB daemon socket
errors inside a managed coding sandbox do not imply an ARCVM configuration problem.

Install Locus Map 4 from Google Play. Install a current CoreApp build compatible with PebbleKit
Android 2, then install `watchapp/build/watchapp.pbw` through it. The version-pinned QEMU setup uses
CoreApp's direct transport from the pinned `coredevices/mobileapp` commit
`38fd4c6892599d6a02b4b3ca0b3fd518a51d6170`.
The bridge disables PebbleKit auto-selection and explicitly selects the installed
`coredevices.coreapp` package. Incoming Binder calls must resolve to that package and its installed
UID; Android itself enforces package-name uniqueness and signature-compatible updates. There is no
separate signer enrollment step. The Android diagnostics screen reports CoreApp selection, Locus, watch
connection, recording state, refresh mode, and the last bridge error.

## Toolchain

Keep the toolchain in the repository's development container. Install Docker, or rootless Podman
as a Docker-compatible fallback, but do not install Android, Java, Node, Python/Pebble, or Pebble
SDK tooling in the user profile:

```sh
./tools/podman-test doctor static
./tools/podman-test build-static
./tools/podman-test dev bash
```

The image contains the exact Android, Gradle/JDK, Node, Python, uv, Pebble Tool, and Pebble SDK
versions. `build-static` may use the network; later container runs reuse engine and Gradle/npm
caches. Docker is selected when present. Set `DEV_CONTAINER_ENGINE=podman` explicitly on this
repository's rootless Podman environment.

## Update lifecycle

The PebbleKit bound service starts polling when the watchapp opens and stops it when the watchapp
closes. Adaptive mode sends an immediate snapshot, polls every two seconds for 15 seconds after
opening or a command, and then every ten seconds. Fixed five- and ten-second modes are available
from the Android diagnostics screen.

Snapshot delivery epochs and profile transfer serials are managed in-memory and re-seeded from the
system clock upon bridge process restart. The command deduplication journal is also an in-memory
cache for the duration of the process. Because deduplication and ordering states are in-memory, a
bridge process restart naturally establishes a new baseline with the watch.
Locus command broadcasts have no acknowledgement, so the bridge polls newly executed recording-state
changes before it sends an OK result; an unconfirmed transition returns FAILED with the latest state.

## QEMU/Core integration

CoreApp must be the sole client of a directly launched Emery or Gabbro QEMU. Do not use an
SDK-managed Basalt emulator; Basalt is not a supported target, and the standard Pebble phone
simulator would compete with CoreApp for the QEMU protocol connection. Follow the direct launch and
CoreApp attachment procedure in [End-to-end development and testing](end-to-end-testing.md).

If Core expects its watch transport through ARCVM, expose the QEMU/Core TCP port with `adb
reverse`. Confirm the port used by the installed Core build before creating the tunnel; `12344`
is common in current development setups:

```sh
adb -s arc:5555 reverse tcp:12344 tcp:12344
```

End-to-end acceptance requires snapshots and all controls to round-trip through QEMU, Core,
the Android bridge, and Locus. Physical Bluetooth, GPS, battery, and background-restriction tests
remain a later hardware smoke test.

## Locus integration tests

Normal verification compiles the instrumentation test but does not change Locus data:

```sh
./tools/podman-test static
./tools/podman-test documentation
./tools/podman-test release-check
```

`verifyPebbleTargets` checks stack/scheduler invariants plus cross-language protocol and packaging
metadata without launching emulators, installing packages, or changing tracked files.
`documentation` validates the committed screenshots without regenerating them.
`release-check` assembles the final release APK with the pinned Android SDK tools and enforces its
application ID, version, minimum and target SDK, permission declarations, backup/debug/cleartext
attributes, and exported-component allowlist. The unsigned local build and signed release workflow
use the same compiled-manifest policy; signed builds additionally verify the release certificate.

Before documentation deployment, build the manual and visually review **Getting Started**,
**Features**, **Android Bridge Settings**, and **User Guide** with light and dark browser themes at
desktop and 390px mobile widths. Confirm that table and figure captions plus their permalinks are
legible, referenced phone screenshots follow the light-theme policy and current release version,
images retain their intended size and aspect ratio, and no text, images, tables, or navigation
overflow or clip at either width. Record and explain any intentional screenshot-theme exception in
the affected page.

Run `./tools/podman-test dev ./gradlew regenerateDocumentationScreenshots` only when intentionally updating images; that
maintenance task may launch Pebble QEMU and install the pinned browser tooling. Both documentation
tasks are explicit and are not dependencies of routine code verification.
With a disposable Android emulator available through `adb`, run `./tools/podman-test dev ./gradlew
regenerateAndroidBridgeScreenshots` to refresh the native Bridge light/dark images. The task installs
the debug APK, fixes font scale and orientation during capture, and restores the device settings
afterward.
`verify:pbw` must run after `pebble build`; it inspects the generated archive rather than assuming
package declarations were honored.

The independent public static entry point is `./tools/podman-test static`. It builds or uses the
development image and never checks for KVM, emulator
images, Locus APKs, golden state, or acceptance-host RAM/disk. Dependency updates should be
intentional and reviewed:

```sh
./tools/podman-test dev ./gradlew --write-locks --write-verification-metadata sha256 \
  verifyPebbleTargets :android:app:testDebugUnitTest :android:app:assembleDebug
```

The first resolution may use the network; a populated cache can subsequently run the lightweight
Gradle tasks with `--offline`. CI mirrors this split: public static checks and documentation are
separate. Every pull request also runs full KVM acceptance on an ephemeral GitHub-hosted Docker
runner. The job verifies and pulls the signed, digest-pinned prebuilt GHCR runner and emulator,
downloads the pinned public Locus fixture, creates fresh golden state, and tests the current
TrackGlance build. Protected `main` relies on these required pull-request results instead of
rerunning CI or CodeQL after the merge; tag CI, scheduled CodeQL, and manual dispatch remain
available. The same test stages run locally with the private fixture through
`./tools/podman-test acceptance-suite --locus-apks
/home/christian/.local/share/trackglance-acceptance/locus-apks`; this warm path preserves caches and
the validated golden state for fast feedback. Use `--published --cleanup` to reproduce hosted
provisioning locally, and reserve `--fresh --cleanup` for source-provisioning or image comparisons.

For interactive work against the same validated golden state, start a disposable Emery or Gabbro
lab and open `http://127.0.0.1:5173/`:

```sh
./tools/podman-test manual --platform emery \
  --locus-apks /home/christian/.local/share/trackglance-acceptance/locus-apks
```

The page places the Android and Pebble displays together with watch buttons, Q/W/S/X and arrow-key
controls, heart-rate and absolute-step inputs, readiness state, and named PNG captures. Captures and
bounded shutdown diagnostics remain under the printed `build/podman/<run>-manual/` path. Press
Ctrl-C to remove the cloned Android, watch, and runtime state without changing the golden volume.
Use `--platform gabbro` for Pebble Round 2. Missing-image errors require `build`; stale or missing
golden provenance requires `clean`, `build`, and `bootstrap` with the same private Locus directory.
The complete lifecycle and input limits are documented in
[Interactive emulator lab](podman-testing.md#interactive-emulator-lab).

The real Locus contract test is deliberately opt-in and non-mutating. It requires idle Locus,
validates numeric recording-profile identities, and confirms that obsolete Start command `1` is
rejected without changing recording state:

```sh
adb connect arc
./tools/podman-test dev bash -c 'ANDROID_SERIAL=arc:5555 ./gradlew :android:app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.runLocusIntegration=true'
```

Recording lifecycle acceptance is manual because recording start is owned by Locus Map. Start in
Locus, then verify page selection, 60-second configuration reconciliation, pause/resume, stop/save,
heart rate, quick waypoint, and dictation on physical Emery. Smoke-test launch, stopped state,
settings opening, active pages, controls, and page wrapping on both Emery and Gabbro QEMU.
The watch's plain waypoint command saves a point named `Pebble waypoint` immediately
(`autoSave=true`). On microphone-capable watches, the second waypoint command uses Pebble
dictation confirmation and saves the accepted text as the waypoint name. Dictation needs the
phone transcription service; the plain command remains the fallback. The public update container
does not expose the active recording's waypoint count.
