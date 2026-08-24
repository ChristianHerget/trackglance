# Containerized acceptance environment

The repository includes a Docker and rootless Podman workflow for x86_64 Linux hosts with KVM. It runs
all automated Android tests on Android 12L, API 32. API 32 is the acceptance runtime, while the
bridge remains installable on Android 7.0 (API 24); Platform 36 remains the compile and target SDK.

No image is published. Locally, the Locus APK remains in a host directory. Hosted CI downloads the
pinned public fixture into `$RUNNER_TEMP` for that job only. In both cases it is mounted read-only
only for validation and bootstrap; it is never copied into an image, repository, test artifact, or
persistent cache. Android's installed Locus state lives only in the golden data volume and its
short-lived clones, which cleanup deletes after each run.

## Host requirements

`./tools/podman-test doctor acceptance` requires all of the following. In contrast, `doctor static`
checks only for Docker or rootless Podman. All JDK, Android, Node/npm, Python, C, and Pebble tools
remain in the development image; the static path never falls back to host-installed toolchains:

- x86_64 Linux and either Docker 24 or newer, or rootless Podman 4.4 or newer using `crun`;
- read/write access to `/dev/kvm`; Podman retains supplementary groups and hosted CI grants an ACL
  only to the current ephemeral runner account;
- at least four logical CPUs, 8 GiB RAM, and 35 GiB free disk;
- the pinned Locus Map fixture, which supports API 32 and x86_64.

On an enforcing SELinux host, these checks can pass even if policy later prevents the Android
Emulator's render thread from creating executable heap mappings. If the accelerated emulator exits
with status 139, follow
[Android Emulator exits 139 on an SELinux host](#android-emulator-exits-139-on-an-selinux-host)
before changing kernels or disabling KVM.

Podman uses a dedicated test pod. Docker attaches the Android container to a dedicated network and
runs the build, CoreApp/Pebble, and relay containers in its network namespace. ADB, emulator
console, emulator gRPC, Pebble QEMU, and the relay control socket remain inside that group.
Interactive bootstrap publishes only the Google WebRTC frontend as `127.0.0.1:5173`; headless
bootstrap and all test runs publish no ports.

## Pinned inputs

`tools/podman/versions.env` is the machine-readable source of these pins:

| Input | Pin |
| --- | --- |
| Build image | `debian:12-slim@sha256:abd67ffcfa541b485a3dff59865ab629aa048a6c613e639d36e7456b0b229241` |
| Android command-line tools | `13114758`, SHA-256 `7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee` |
| Android SDK packages | Platform Tools 37.0.1; API 32 revision 1; API 36 revision 2; API 37.0 revision 2; Build Tools 36.0.0; NDK 28.2.13676358; CMake 3.22.1 |
| Android emulator container scripts | `0654f694b46794fae4b178f1e1a17cb60c5d2d34` |
| Android emulator WebRTC protocol definitions | AEMU `emu-main-dev` commit `863dffe2c8c7d278c918f1fc409f85d3188c691e` |
| API 32 Google APIs x86_64 image | revision 8, `x86_64-32_r08.zip`, SHA-256 `2709bcc5a4aa98539b12c2169df606dfe9184fc3b4a0aac7120f319721e63bf1` |
| Android Emulator | 37.1.11, `emulator-linux_x64-15917651.zip`, SHA-256 `95771e0ae431897b2a4bd2d97fa095f29a8b0624a7b216baf529f9306161c266` |
| Pebble App source | `coredevices/mobileapp` commit `38fd4c6892599d6a02b4b3ca0b3fd518a51d6170` |
| Pebble Tool / SDK | 5.0.39 / 4.33.1 |
| uv bootstrap | 0.12.4, SHA-256 `c8c60f47e6f88d18dbf6f33d7279fb1fbf7ae76631768152cf5578c3d65729b4` |
| Public Locus fixture | 4.35.0 (1215), SHA-256 `d8bf8fe208193f0e491caf742956681bb8992541c1e51c1e9616772e40d431aa` |

`tools/locus-test-apk.properties` is the fixture source of truth. Besides its official public URL
and SHA-256, it pins size, package, version, x86_64 ABI, minimum/target SDK, and signing-certificate
SHA-256. `tools/download-locus-apk` handles Google's unattended confirmation form, writes
atomically, and rejects HTTP errors, HTML, non-APK bytes, and checksum mismatches. Bootstrap then
validates the Android metadata and signature inside the pinned build image.

Android SDK packages are installed by Google's
[`sdkmanager`](https://developer.android.com/tools/sdkmanager), after accepting the SDK licenses.
The build verifies every installed package revision and fails closed if a rolling SDK package has
changed rather than silently producing a different image.
The emulator image is generated with Google's pinned
[Android emulator container scripts](https://github.com/google/android-emulator-container-scripts).
The pinned WebRTC gateway generates both its Python protocol stubs and the browser's
`emulator_controller_pb.js` module from a pinned AEMU source commit; neither a moving branch nor
host-provided generated files are used. Its Vite proxy targets the gateway explicitly at
`127.0.0.1`, avoiding ambiguous container-local `localhost` resolution. A small checked-in
compatibility patch converts the generator's parsed `--dest` string back to a `Path`;
`git apply --check` makes upstream drift against the pinned generator commit fail loudly.
It uses the Google APIs image, not the Play Store image: Google Play Services are available, while
Locus Map is installed from the validated fixture input directory. Emulator metrics are disabled.

The Pebble App build copies its committed dummy Firebase configuration and applies the reviewed
`tools/podman/coreapp-x86_64.patch`. That patch adds the app's x86_64 ABI and makes the Cactus native
capability probe return unsupported on non-ARM CPUs, keeping local Cactus features disabled on the
emulator.

## Build and provision

Run these commands from the repository root:

```sh
./tools/podman-test doctor acceptance
./tools/podman-test build
./tools/podman-test bootstrap --locus-apks /absolute/private/path
```

`build` creates the build/test, emulator-generator, API 32 emulator, and Google WebRTC images. The
Google generator uses the selected engine's Docker-compatible socket; Podman's temporary
user-scoped service is removed immediately afterward. It also builds the x86_64 Pebble App,
bridge APK, and PBW. Build-time network access is expected. Named Gradle, npm, and download caches
are reused on later invocations.

`build-acceptance` omits the WebRTC image and interactive-only setup. With
`ACCEPTANCE_BOOTSTRAP_AUTOMATED=1`, bootstrap grants only the required test permissions, completes
Locus's first-run setup, verifies its default recording profiles, and leaves CoreApp onboarding to
the first connected QEMU run. It builds the required APKs and PBW without repeating the static JVM,
JavaScript, or protocol checks, which run in their own CI job. Interactive local bootstrap remains
the default.

Normal builds pull only missing digest-pinned base images. Use `./tools/podman-test build --refresh`
when intentionally refreshing those references; routine builds never imply `--pull=always`.

Before installation, bootstrap verifies that the APK directory is absolute, has exactly one Locus
Map base APK, contains one consistent package/version and unique splits, declares a minimum no
higher than API 32, and includes x86_64 when native code is present. `adb install-multiple` performs
the final platform split-completeness check. Locus documents its builds for older Android versions
in [Devices with older Android](https://docs.locusmap.app/doku.php/manual:faq:devices_older_android)
and publishes the APKs in its linked
[Google Drive folder](https://drive.google.com/drive/folders/1U8U1D-NGQ9CAnqXAkleEXi46wH2T7tMR).
Download the pinned API-32-compatible regular Google Play Locus Map 4 variant into a directory
outside the repository because this image provides Google Play services. Reserve `GooglePlayAfa`
for tests that intentionally need all-files access; the Amazon variant targets devices without
Google Play services and is not the acceptance input here. Open `http://127.0.0.1:5173/`, complete
Pebble App and Locus Map onboarding, configure at least one recording profile, leave recording
stopped, and return to the terminal. Bootstrap then records non-secret provenance in the golden
volume.

If an emulator, Pebble App, or Locus input changes, run `clean`, rebuild, and bootstrap again. The
test commands reject a missing or stale golden volume rather than silently mixing inputs. Runtime
commands require the same directory so its current fingerprint can be compared with the bootstrap
provenance; the APK remains read-only and is not mounted into the test pod.

## GitHub-hosted acceptance

Fast CI runs `static`, documentation, and the release check on GitHub-hosted Ubuntu without KVM or
Locus. Full hosted acceptance is a separate manual `workflow_dispatch` job selected with
`run_hosted_acceptance`. It uses `ubuntu-24.04`, Docker, and `/dev/kvm`, downloads the official
public fixture only into `$RUNNER_TEMP`, builds headless inputs, creates its golden volume from
scratch, runs every Android/Locus instrumentation test, and runs Emery plus Gabbro acceptance
twice. The second pass uses new golden clones and detects state leakage and cleanup regressions.

The workflow prints `df -h`, `docker system df`, and relevant directory sizes after each major
stage. Failure handling prints only bounded text logs; it does not upload the APK. Its final step
removes all `locuspebble-` containers, volumes, networks, images, build output, and the downloaded
fixture even when an earlier stage fails. The probe input `run_acceptance_probe` exercises only the
pinned emulator cold boot and creates no golden state.

Hosted acceptance was proved on a standard four-CPU `ubuntu-24.04` runner by
[run 32677775620](https://github.com/ChristianHerget/pebble-locus-map/actions/runs/32677775620)
at commit `47d9eb36a3ec10d80bffe337686b5e36120f972d`. The 34-minute-41-second job built
all headless inputs, bootstrapped Locus and CoreApp from fresh state, passed all Android/Locus
instrumentation tests, and passed Emery plus Gabbro acceptance twice from separate clean clones.
The first and repeated watch passes took 6 minutes 25 seconds and 6 minutes 22 seconds respectively.

That runner started with 15 GiB RAM and 87 GiB free disk space. The lowest observed free-space
snapshot was 59 GiB. At that point Docker reported 20.65 GB of images and 5.662 GB of named volumes,
and the repository's generated `build/` tree used 3.5 GB. Cleanup left no containers or named
volumes; the remaining 8.315 GB of images and 6.716 GB of build cache belonged to the hosted
runner's preinstalled/shared Docker state. Allow about 35 minutes and 28 GB of incremental disk for
a cold hosted run; the acceptance doctor retains a 35-GiB free-space requirement rather than
tuning to this single observation.

The protected self-hosted job remains available as a fallback. One cold hosted workflow, including
its clean-clone repeat, proves feasibility but not reliability over time. Retire the protected job
only after another complete cold hosted run (or a short sequence of such runs) succeeds without an
environment-specific workaround.

## Automated stages

```sh
./tools/podman-test build-static
./tools/podman-test static
./tools/podman-test documentation
./tools/podman-test release-check
./tools/podman-test android --locus-apks /absolute/private/path
./tools/podman-test acceptance --locus-apks /absolute/private/path
./tools/podman-test all --locus-apks /absolute/private/path
```

`static` runs downloader/validator and relay unit tests, JVM tests, bridge builds, Android-test
compilation, npm tests, stack/protocol checks, Pebble builds and PBW verification. It does not
require KVM, emulator/WebRTC images, Locus APKs, golden state, screenshot generation, or Sphinx.

`android` clones the golden Android data volume, enables the real Locus integration test, and runs
every instrumentation test. Missing Locus Map, an active recording, missing profiles, any skipped
test, or the absence of the Locus integration result fails the stage.

`acceptance` (with `e2e` retained as a compatibility alias) places a protocol relay between Pebble
App and PebbleOS QEMU. Its private Unix control socket injects button and heart-rate QEMU frames
without taking ownership of the phone connection. PebbleOS QEMU reports an empty hardware serial
and a legacy board code that current PebbleKit treats as unknown. For only those serial-less QEMU
watch-version responses, the relay supplies a stable synthetic serial and the runner's Emery or
Gabbro hardware identifier. Serial-bearing real-watch responses, including their platform fields,
pass through unchanged. This lets PebbleKit use the emulated watch as a valid, correctly typed watch
without carrying an acceptance-only modification in Pebble App.
The Emery run opens Watch Settings after profile transfer, verifies the resulting user-visible
configuration flow, and exercises start,
pause, resume, waypoint, heart-rate forwarding, and stop/save. The Gabbro run repeats connection,
launch, Controls menu, and Watch Settings checks, and proves that unsupported watch-originated heart
rate is not forwarded. PBWs are sideloaded through Pebble App's existing `ACTION_VIEW` deep-link
handler, with no document picker. Android receives an emulator-console GPS fix at Wartburg
(`50.9662,10.3065`) after every emulator boot and again immediately before START. The emulator
retains that fix until Locus registers its real GPS listener, so Locus tests start from a current,
deterministic location. The console token stays in the private per-run volume and the console port
stays inside the test container group. Android shell test-provider points are not used because
Locus identifies them as mock locations and can reject them as invalid input.
Immediately before Emery sends START, acceptance foregrounds Locus. Without that step, the API-32
guest logs Android's `Foreground service started from background can not have
location/camera/microphone access` diagnostic for Locus's `TrackRecordingService`. Locus's public
API documents START only as a broadcast and does not state a foreground requirement, so this is an
explicit compatibility condition of the tested Android/Locus combination rather than an API
contract. After Android reports the Locus activity resumed, acceptance allows ten seconds for its
map and recording engine to finish a cold initialization before sending START. The bridge itself
does not launch Locus from the background; see
[Track Recording Background Execution](design-decisions.md#2-track-recording-background-execution).

Every Android stage clones the golden state to a new named volume and deletes it during bounded
cleanup, including on test failure. Readiness and state changes use polling deadlines rather than
fixed provisioning sleeps. `all` runs the three stages against the sole API 32 image. On a target
host, run `all` twice to demonstrate repeatable provisioning, disposable Locus recording state,
complete screenshots/HTML, and no skipped tests.

Each stage writes a timestamped directory under `build/podman/`. Depending on the stage it contains
the APK, PBW, Sphinx HTML, Android and Pebble screenshots, UI XML, bridge status, relay transcript,
instrumentation XML, serial output, container logs, and logcat. These directories never include the
Locus APK.

## Failure handling and remaining manual checks

Failures are explicit for missing KVM/resources, unsupported APKs, stale bootstrap provenance,
Android or QEMU readiness timeouts, missing services, QEMU disconnects, state-transition timeouts,
and interrupted cleanup. `./tools/podman-test clean` removes only objects with this workflow's
`locuspebble-` names plus `build/podman`.

### Android Emulator exits 139 on an SELinux host

On Fedora or another enforcing SELinux host, `/dev/kvm` can be readable and writable and
`emulator -accel-check` can report KVM usable while the accelerated emulator still exits with
status 139 (`SIGSEGV`) before Android finishes booting. Disabling KVM avoids this particular crash
but makes the x86_64 guest too slow for acceptance. A desktop crash notification or a QEMU entry in
`coredumpctl` confirms the process failure but does not identify the policy denial.

Reproduce the failure once, note its time, and inspect the immediately corresponding SELinux
records on the host:

```sh
getenforce
getsebool selinuxuser_execheap
sudo ausearch -m AVC,USER_AVC -ts recent
sudo journalctl -t setroubleshoot --since "10 minutes ago"
coredumpctl list --since "10 minutes ago"
```

The relevant denial has `comm="RenderThread"`, source and target type `spc_t`, process class, and
denied permission `{ execheap }`, for example:

```text
avc: denied { execheap } for comm="RenderThread" \
  scontext=unconfined_u:unconfined_r:spc_t:s0 \
  tcontext=unconfined_u:unconfined_r:spc_t:s0 tclass=process permissive=0
```

Correlate the AVC timestamp with the failed container. Fedora's problem-reporting applet can surface
old QEMU core dumps after login, so an old notification is not evidence of a concurrent emulator.
Basic device access is also not the cause when `/dev/kvm` is readable and writable in the container
and `-accel-check` succeeds.

The workflow already starts the Android container with `--security-opt label=disable`. That option
disables SELinux label separation for the container, but the process still runs as `spc_t` and
SELinux can deny its `execheap` operation. Rootless `--privileged` does not grant more authority than
the launching account and is not a substitute for reading the AVC.

To confirm the diagnosis, enable the broad SELinux boolean only for one test and do not make the
change persistent. Run the failed workflow command in a subshell whose exit trap restores the
boolean even when the command fails or is interrupted:

```sh
(
  trap 'sudo setsebool selinuxuser_execheap off' EXIT
  sudo setsebool selinuxuser_execheap on
  ./tools/podman-test bootstrap --locus-apks /absolute/private/path
)
```

Always restore the boolean even if the emulator test fails. If the emulator boots without another
AVC or core dump, generate a local module from one exact audit event instead of leaving the broad
boolean enabled. In the `ausearch` output, the audit event ID is the number after the colon in
`msg=audit(...:EVENT_ID)`. Use that ID so unrelated historical denials cannot enter the module:

```sh
policy_dir=$(mktemp -d)
cd "$policy_dir"
sudo ausearch -a EVENT_ID --raw | audit2allow -M android-emulator-qemu
cat android-emulator-qemu.te
```

On Fedora, `audit2allow` is supplied by `policycoreutils-python-utils`. Do not install the generated
module until its type-enforcement source contains only the expected permission, equivalent to:

```selinux
module android-emulator-qemu 1.0;

require {
    type spc_t;
    class process execheap;
}

allow spc_t self:process execheap;
```

Install the reviewed module at an explicit priority, then keep SELinux enforcing and the broad
boolean disabled:

```sh
sudo semodule -X 300 -i android-emulator-qemu.pp
getenforce
getsebool selinuxuser_execheap
sudo semodule -lfull | grep android-emulator-qemu
```

Verify the fix with several fresh accelerated cold boots. Each must reach
`sys.boot_completed=1`; `ausearch` must show no new matching AVC and `coredumpctl` no new QEMU core.
Remove the local module with the same priority if it is no longer needed:

```sh
sudo semodule -X 300 -r android-emulator-qemu
```

This rule is narrow in permission but not exclusive to Android Emulator: `spc_t` is shared by
label-disabled or privileged containers, so it permits `execheap` for every process in that domain.
Do not install it on a host where that scope is unacceptable. A stricter deployment needs a
dedicated SELinux domain for the emulator container rather than a rule selected by process or thread
name.

Bluetooth reconnection, physical battery behavior, microphone dictation, and real GPS remain manual
hardware checks. The container uses synthetic watch heart rate; it does not claim to validate the
physical sensor.

## Revisiting the minimum Android version

When Locus Map changes its minimum, first verify the minimum supported release on a clean emulator.
Then update `minSdk`, the sole emulator API/system-image revision and checksums, this document, the
user-facing Getting Started requirement, Sphinx metadata if part of a release, and parity tests in
one change. Rebuild and bootstrap from scratch, run `all` twice, and retain a physical-device smoke
test. Do not lower the bridge minimum based only on manifest inspection; prove the current Locus Map
runtime and recording API on that Android version.
