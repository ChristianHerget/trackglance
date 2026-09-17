# Development setup

For focused test selection and completion checks, see [Focused testing](testing.md).

## Preparing a version bump

Run `./tools/bump-version MAJOR.MINOR.PATCH` once. It verifies that the existing release markers
agree, increments Android's version code, and updates the Android, watch, JavaScript, protocol,
release-note, and documentation versions together. Use `./tools/bump-version --check` in reviews
to verify parity without changing files.

For the complete installation, QEMU/CoreApp setup, end-to-end acceptance procedure, troubleshooting,
and containerization roadmap, see [End-to-end development and testing](end-to-end-testing.md).
For the automated rootless Android 12L environment, see [Podman test environment](podman-testing.md).

The bridge supports API 24 and newer. Android 14/API 34 is the sole automated acceptance runtime,
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

Every push to `main` runs the complete `CI` suite on the exact squash commit. Before tagging,
confirm its three jobs succeeded, fetch `main`, and create `vMAJOR.MINOR.PATCH` at that exact HEAD.
The tag-triggered draft job waits for that exact-SHA certification before entering the protected
GitHub `release` environment. Confirm that the environment contains the signing secrets and a working
`VIRUSTOTAL_API_KEY`. Set the VirusTotal key interactively so its value never appears in a command
argument or log:

```sh
gh secret set VIRUSTOTAL_API_KEY --env release --repo ChristianHerget/trackglance
```

The release job verifies the published runner and invokes only
`./tools/podman-test release-artifacts --published`. It builds the signed APK, generated PBW,
their runtime-only CycloneDX and SPDX SBOMs, and the Sphinx documentation archive without rerunning
general tests or acceptance. Each SBOM format is attested against the exact artifact it describes
and included in `SHA256SUMS`. CycloneDX generation also runs during every normal static CI build for
automated security tooling. SPDX is generated independently on stable release tags for license and procurement review;
the pipeline never converts one SBOM format into the other. The PBW SBOMs record Pebble SDK 4.33.1
as build-tool metadata and compatibility level 3, not as shipped third-party code. It submits the
staged APK and PBW after deleting the private signing key. Both
uploads and their analysis links must succeed before a draft is created. The links identify
successful submissions only: the workflow does not wait for analysis or gate publication on later
malicious or suspicious detections.

The result remains a draft. Review the phone build and durable documentation asset, then dispatch
publication using the tag itself as the workflow ref (there is deliberately no tag input):

```sh
gh workflow run publish-release.yml --ref v0.2.8
```

Publication verifies the draft, requires the downloaded SBOM documents to match their signed
attestation predicates, deploys its documentation through the protected `github-pages`
environment, then waits at the protected `release` environment. Review the live site before
approving. The final job downloads and verifies the candidate again before publishing it. It does
not compare the reviewed tag with a later `main` HEAD.

## Check output and diagnostics

Development checks summarize each stage by default: status, duration, available test totals,
warning and unfamiliar-output counts, and log locations. Active stages print a heartbeat every
60 seconds. Gradle task counts distinguish executed, up-to-date, cached, and skipped work;
unstarted stages are listed after a failure. JVM totals require both an executed test task and
fresh XML reports. Missing or malformed reports do not become inferred test totals.

Add `--verbose` to `static`, `documentation`, `release-check`, `android`, `acceptance` (or `e2e`),
`acceptance-suite`, or `all` to stream complete output while retaining the same logs. For example,
`./tools/podman-test static --verbose`. Arbitrary `dev` commands keep their original output.

Each invocation retains `combined.log`, stage logs, `manifest.tsv`, refreshed reports, and separate
emulator diagnostics under `build/check-logs/<run-id>/`. Acceptance cleanup preserves this directory.
The manifest records log filename, stage, exit status, seconds, warnings, and unfamiliar lines.
An otherwise successful run fails if required logs or reports cannot be retained.

Failure excerpts share a 4 KiB / 60-line limit, whichever comes first, including filenames and
truncation notices. CI uses the same renderer and persisted budget, so it does not repeat excerpts.
Read the named stage log with `tail -n 60 build/check-logs/<run-id>/<stage>.log`, or search it with
`rg -n -C 5 'specific diagnostic' build/check-logs/<run-id>/<stage>.log`. Read `combined.log` for the
full command output. `tools/check-diagnostics` renders any remaining failure excerpt allowance for
the latest invocation. CI uploads allowed diagnostics for seven days after a failure; fixtures,
APK/PBW files, signing material, caches, and emulator state are excluded.

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

## Verification and screenshots

Use [Focused testing](testing.md) for routine checks, native test filters, and completion
requirements. The release check and signed release workflow share the compiled-manifest policy:
application ID, version, SDK levels, permissions, backup/debug/cleartext attributes, and exported
components are checked; signed builds also verify the release certificate.

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

Dependency updates should be intentional and reviewed:

```sh
./tools/podman-test dev ./gradlew --write-locks --write-verification-metadata sha256 \
  verifyPebbleTargets :android:app:testDebugUnitTest :android:app:assembleDebug
```

The first resolution may use the network; populated caches can run Gradle tasks with `--offline`.
For hosted acceptance, private fixture setup, warm/fresh provisioning, image publishing, and the
interactive Emery/Gabbro lab, use [Containerized acceptance](podman-testing.md). The lab's browser
dashboard remains bound to host loopback and uses disposable clones of validated golden state.

## Locus integration tests

The real Locus contract test is deliberately opt-in and non-mutating. It requires idle Locus,
validates numeric recording-profile identities, and confirms that obsolete Start command `1` is
rejected without changing recording state. Never run it when a user recording is active:

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
