# End-to-end development and testing

This guide describes a version-pinned environment for building TrackGlance Bridge and testing the
complete path from a PebbleOS watchapp to Locus Map. It records the setup that was proven on a
Chromebook with Linux, ChromeOS ARCVM, CoreApp, and PebbleOS QEMU.

The supported development environment is containerized. The build toolchains and automated
PebbleOS QEMU acceptance run in containers. Locus Map and CoreApp still run on an Android target,
such as the workflow's emulator, ARCVM, or a physical device. A public container must not
redistribute Play Store APKs, signing material, private API keys, or other third-party artifacts.

## System topology

```text
repository / build host
  |-- Gradle + Android SDK -----------> Locus Bridge APK
  |-- Node.js + Pebble SDK -----------> watchapp PBW (C + embedded PKJS)
  |-- PebbleOS QEMU :12344 <--- TCP ---> CoreApp on Android
  |                                      |
  |                                      | PebbleKit Android bound service
  |                                      v
  +--- ADB --------------------------> Locus Bridge APK
                                         |
                                         | Locus Android API
                                         v
                                      Locus Map
```

The end-to-end path for a command is:

```text
watch button -> watchapp C -> AppMessage -> CoreApp -> Android bridge -> Locus API
             -> Android bridge -> CoreApp -> AppMessage -> watchapp dashboard
```

CoreApp also hosts the embedded PKJS settings process and delivers AppMessages to it in parallel;
PKJS is not an intermediate hop in the watch-to-Android command path. All runtime layers are
required. A successful C build, a successful APK build, or even a successful AppMessage
acknowledgement proves only one part of the path.

## Known-good baseline

The following versions were used for the verified setup on 2026-08-16:

| Component | Known-good version or setting |
| --- | --- |
| JDK | 17 |
| Node.js | 18 |
| Pebble Tool | 5.0.40 |
| Pebble SDK | 4.33.1 |
| Android compile/target SDK | 36 |
| Android Build Tools | 36.0.0 |
| Bridge and watchapp | 0.2.5 |
| Wire protocol | v4 |
| CoreApp QEMU support | `coredevices/mobileapp` commit `38fd4c6892599d6a02b4b3ca0b3fd518a51d6170` |
| Watch targets | Emery (Pebble Time 2) and Gabbro (Pebble Round 2) only |

Pin tool versions in automation. An SDK directory named `latest`, a moving Git branch, or an
unversioned APK makes failures difficult to reproduce.

Allow at least 15 GB of free disk for this repository, Gradle caches, Android SDK packages, the
Pebble SDK, and an optional CoreApp source build. The known-good host used approximately 4.8 GB for
the Android SDK, 1.6 GB for Pebble SDK data, and 3.8 GB for Gradle caches. Building CoreApp adds an
NDK of approximately 2.2 GB and substantially more build output.

## 1. Build the development container

Install Docker only, or use rootless Podman as the Docker-compatible fallback used by the full
acceptance environment. Keep Java, Android SDK/NDK, Node, Python/uv, Pebble Tool, and Pebble SDK out
of the host user profile:

```sh
./tools/podman-test doctor static
./tools/podman-test build-static
./tools/podman-test dev bash
```

The digest-pinned image contains the complete development toolchain. `build-static` may download
the base image and pinned SDK inputs; later runs reuse container-engine and Gradle/npm caches. The
wrapper prefers Docker when installed and otherwise uses rootless Podman. Select explicitly with
`DEV_CONTAINER_ENGINE=docker` or `DEV_CONTAINER_ENGINE=podman`. Use `dev COMMAND...` for focused
commands instead of installing their tools on the host.

## 2. Build and test TrackGlance Bridge

From the repository root:

```sh
./tools/podman-test static
./tools/podman-test documentation
./tools/podman-test release-check
```

The outputs are:

```text
android/app/build/outputs/apk/debug/trackglance-bridge-debug.apk
watchapp/build/watchapp.pbw
```

The final npm command verifies PBW targets, metadata, resources, and embedded PKJS. Check versions
and contents manually as needed before installation:

```sh
./tools/podman-test dev bash -c '
  aapt2 dump badging android/app/build/outputs/apk/debug/trackglance-bridge-debug.apk | head
  unzip -p watchapp/build/watchapp.pbw appinfo.json
  unzip -l watchapp/build/watchapp.pbw
'
```

The APK and PBW versions must match. The PBW must contain the embedded `pebble-js-app.js` and only
the `emery/` and `gabbro/` platform directories. Protocol constants must remain synchronized across
Kotlin, C, `watchapp/package.json`, and `protocol/README.md`.

## 3. Prepare an Android target

The Android target must run all three apps:

1. CoreApp (`coredevices.coreapp`)
2. TrackGlance Bridge (`app.trackglance.bridge`)
3. Locus Map 4 (`menion.android.locus`)

### Chromebook ARCVM

Enable **Develop Android apps / ADB debugging** in ChromeOS settings. Full ChromeOS Developer Mode
is not required. From the Linux terminal:

```sh
adb connect arc
adb devices -l
export ANDROID_SERIAL=arc:5555
```

Always use an explicit serial if another emulator is present:

```sh
adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$ANDROID_SERIAL" install -r \
  android/app/build/outputs/apk/debug/trackglance-bridge-debug.apk
```

Install Locus Map from Google Play where possible. Launch it once, complete onboarding, grant the
required location permissions, and confirm that track recording works directly in Locus before
testing the bridge.

### CoreApp APK choices

A Play-distributed CoreApp APK may be ARM-only. Check before using a generic x86 Android emulator:

```sh
"$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt" dump badging coreapp.apk \
  | grep native-code
adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abilist
```

ARCVM on supported Chromebooks can provide ARM translation. A plain x86 Android emulator usually
cannot install an ARM-only APK. In that case, use a compatible ARM Android target or build CoreApp
from source for the required ABI.

CoreApp's direct QEMU transport is present in
[`coredevices/mobileapp`](https://github.com/coredevices/mobileapp) commit
`38fd4c6892599d6a02b4b3ca0b3fd518a51d6170`. The repository helper checks out that exact revision
and builds it inside the development container:

```sh
./tools/podman-test dev env \
  CORE_APP_COMMIT=38fd4c6892599d6a02b4b3ca0b3fd518a51d6170 \
  bash tools/podman/build-coreapp.sh /workspace
```

The resulting x86_64 debug APK is
`build/podman/images/pebble-app-x86_64-debug.apk`. Install it using the ADB connection for the
chosen Android target; the automated Podman workflow performs this installation itself.

Open **TrackGlance Bridge** after installing CoreApp. The bridge disables PebbleKit auto-selection
and selects the exact `coredevices.coreapp` package automatically. Diagnostics should show that
package under **Pebble/Core app**. Incoming Binder calls must resolve to the installed package UID;
no separate signing-certificate enrollment is required.
If Core attempted its first delivery while picker initialization was still in progress, wait for
diagnostics to show CoreApp selected, then close and reopen the watchapp to
replay the lifecycle open and start polling.

CoreApp's basic watch, QEMU, locker, and PebbleKit functionality does not require production API
tokens. Some account, bug-reporting, transcription, and online services do. Keep any such tokens,
Firebase configuration, and signing keys outside this repository and outside public container
images. A CoreApp source build is large and may install NDK 28.2.13676358 and CMake 3.22.1.

Confirm installed packages and versions:

```sh
adb -s "$ANDROID_SERIAL" shell dumpsys package coredevices.coreapp \
  | grep -E 'versionName=|versionCode='
adb -s "$ANDROID_SERIAL" shell dumpsys package app.trackglance.bridge \
  | grep -E 'versionName=|versionCode='
adb -s "$ANDROID_SERIAL" shell pm path menion.android.locus
```

## 4. Start PebbleOS QEMU without a competing phone simulator

CoreApp must own QEMU's Pebble protocol socket. Do not start the normal SDK phone simulator or
another CoreApp connection at the same time.

The direct launch below exposes these ports:

| Port | Purpose |
| --- | --- |
| 12344 | QEMU Pebble protocol; CoreApp must be the sole client |
| 12345 | secondary QEMU serial/log channel |
| 12346 | GDB |
| 12347 | QEMU monitor |

Set paths and create a persistent flash image for Emery:

```sh
export PEBBLE_VERSION=4.33.1
export PEBBLE_PLATFORM=emery
export PEBBLE_DATA_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/pebble-sdk"
export PEBBLE_SDK_DIR="$PEBBLE_DATA_ROOT/SDKs/$PEBBLE_VERSION"
export PEBBLE_BOARD_DIR="$PEBBLE_SDK_DIR/sdk-core/pebble/$PEBBLE_PLATFORM"
export PEBBLE_STATE_DIR="$PEBBLE_DATA_ROOT/$PEBBLE_VERSION/$PEBBLE_PLATFORM"
mkdir -p "$PEBBLE_STATE_DIR"

if [ ! -s "$PEBBLE_STATE_DIR/qemu_spi_flash.bin" ]; then
  PEBBLE_FLASH_TEMP="$PEBBLE_STATE_DIR/qemu_spi_flash.bin.partial"
  bzip2 -dc "$PEBBLE_BOARD_DIR/qemu/qemu_spi_flash.bin.bz2" \
    > "$PEBBLE_FLASH_TEMP"
  test "$(stat -c %s "$PEBBLE_FLASH_TEMP")" -eq 33554432
  mv "$PEBBLE_FLASH_TEMP" "$PEBBLE_STATE_DIR/qemu_spi_flash.bin"
fi
```

Launch QEMU in its own terminal:

```sh
"$PEBBLE_SDK_DIR/toolchain/bin/qemu-pebble" \
  -rtc base=localtime \
  -serial null \
  -serial tcp::12344,server=on,wait=off \
  -serial tcp::12345,server=on,wait=off \
  -kernel "$PEBBLE_BOARD_DIR/qemu/qemu_micro_flash.bin" \
  -gdb tcp::12346,server=on,wait=off \
  -monitor tcp::12347,server=on,wait=off \
  -machine pebble-emery \
  -cpu cortex-m33 \
  -drive if=mtd,format=raw,file="$PEBBLE_STATE_DIR/qemu_spi_flash.bin" \
  -audio driver=sdl,id=audio0 \
  -display sdl,show-cursor=on
```

For Gabbro, use `PEBBLE_PLATFORM=gabbro`, its separate persistent flash image, and
`-machine pebble-gabbro`. Never share one writable flash image between platforms or concurrent
QEMU processes.

Verify that exactly one expected QEMU process owns the ports:

```sh
ps -ef | grep '[q]emu-pebble'
ss -ltnp | grep -E ':1234[4-7]'
```

Do not run `pebble logs --emulator`, `pebble install --emulator`, or another SDK-managed emulator
against this setup. Those commands can start a competing QEMU or phone simulator. A symptom is a
second QEMU window or a system screen saying that an unrelated app is not responding. Resolve the
exact processes and ports before stopping anything; do not use broad kill commands when a valuable
persistent test session is running.

## 5. Connect CoreApp to QEMU

ARCVM sees its own loopback interface, not the Linux container/VM loopback interface. Reverse the
QEMU protocol port through ADB:

```sh
adb -s "$ANDROID_SERIAL" reverse tcp:12344 tcp:12344
adb -s "$ANDROID_SERIAL" reverse --list
```

Attach the QEMU watch through CoreApp's shell-only receiver:

```sh
adb -s "$ANDROID_SERIAL" shell am broadcast \
  -a coredevices.coreapp.ADD_QEMU_WATCH \
  -n coredevices.coreapp/coredevices.coreapp.debug.QemuSetupReceiver \
  --es host 127.0.0.1 \
  --ei port 12344 \
  --ez connect true
```

The receiver is guarded by Android's `DUMP` permission, so it can be invoked by ADB shell but not
by an ordinary installed application.

Open CoreApp and verify that the virtual watch is connected:

```sh
adb -s "$ANDROID_SERIAL" shell am start -W \
  -a android.intent.action.VIEW \
  -d pebble://navbar/apps \
  -n coredevices.coreapp/.MainActivity
adb -s "$ANDROID_SERIAL" logcat -d \
  | grep -E 'QemuSetupReceiver|QemuTransport|PebbleProtocol'
```

Only one client can use port 12344. Consequently, `pebble emu-button --qemu`, `pebble screenshot
--qemu`, and similar direct CLI commands cannot connect while CoreApp owns the socket. Use the QEMU
window's keyboard for buttons. This is an important current limitation for unattended tests.

## 6. Install and launch the PBW through CoreApp

Build the PBW before this step. Copy it to Android storage:

```sh
adb -s "$ANDROID_SERIAL" push watchapp/build/watchapp.pbw \
  /sdcard/Download/locus-bridge.pbw
```

In CoreApp:

1. Open **Apps**.
2. Choose **Sideload App**.
3. Select `/sdcard/Download/locus-bridge.pbw` in the Android document picker.
4. Wait until the locker reports that the app is **On Watch**.
5. Open the app details and choose **Start App**.

The install must send the app binary and platform resources before launch. The embedded PKJS is
run by CoreApp on Android; it is not a separate host process.

After launch, open the bridge diagnostics screen:

```sh
adb -s "$ANDROID_SERIAL" shell am start -W \
  -n app.trackglance.bridge/io.github.christianherget.trackglance.bridge.MainActivity
```

Expected values are:

- Pebble watchapp: Open
- Pebble/Core app: `coredevices.coreapp`
- Pebble watch: Connected
- Watchapp version: identical to the bridge version
- Locus Map: Available
- Recording: Stopped, Recording, or Paused as appropriate
- Locus profiles: the exact names returned by Locus

Start the chosen recording profile in Locus itself:

```sh
adb -s "$ANDROID_SERIAL" shell am start -W \
  -n menion.android.locus/com.asamm.android.library.androidCore.features.startScreen.StartScreen
```

Release 0.2.1 never starts a recording from the watch or bridge. Confirm the stopped instruction on
both platforms, then use Locus UI to start the desired activity.

The delivered activity may be reported as
`menion.android.locus/com.asamm.locus.basic.features.mainActivity.MainActivityMap`; that is normal.

## 7. Configure activity pages and verify settings

Open the watchapp settings from CoreApp while the watchapp is running. It waits up to 500 ms for the
fresh catalog requested by that opening, then falls back to a valid same-version phone cache or an
unavailable notice. A response received after the page is already open is stored atomically for the
next opening; the data URL cannot update an already-open page. Empty, failed, and malformed
responses never replace canonical activity settings.

Verify all of the following:

1. CoreApp, watchapp, and Android bridge versions match.
2. The settings page opens on the first click.
3. Fully expanded activity groups are alphabetical and match Locus IDs and names.
4. Activity editing, page and metric reordering, activation/deactivation, and split resets work within bounds.
5. Saving stores canonical configuration and pushes the active activity projection.
6. Reopening preserves custom names, page and metric order, inactive slots, theme, and stable page IDs.

Fresh groups use `Standard` in German and `Default` in English with heuristic metrics. Generated
and custom page names are user data and are never translated automatically; automatic names follow
the watch language.

If settings says no profile response has arrived:

```sh
adb -s "$ANDROID_SERIAL" shell am start -W \
  -n app.trackglance.bridge/io.github.christianherget.trackglance.bridge.MainActivity
adb -s "$ANDROID_SERIAL" logcat -d \
  | grep -E 'AppMessagePush|PROFILE|QemuTransport|PebbleProtocol'
```

Check that Locus is running, the bridge reports Locus as available, CoreApp is connected, and all
versions match. An old cached list is not evidence that a fresh query succeeded.

## 8. Manual end-to-end acceptance test

### Safety precondition

Never begin if the user has an active recording. Starting, stopping, or saving a track changes
Locus data. Check the bridge diagnostics and Locus UI first. Decide explicitly whether a successful
test recording should be left running or stopped and saved.

### Start in Locus and select pages

1. Confirm the watch shows the stopped instruction and Select has no action.
2. In Locus, start a known recording profile.
3. Clear logcat so the command is easy to identify:

   ```sh
   adb -s "$ANDROID_SERIAL" logcat -c
   ```

4. Confirm page 1 appears automatically, the header reads ``Recording · 1/N``, and Up/Down wrap.
5. Inspect telemetry, recording-context type 10, and runtime-config request type 11:

   ```sh
   adb -s "$ANDROID_SERIAL" logcat -d -v time \
     | grep -E 'AppMessagePush|PebbleProtocol|Locus'
   ```

Context must carry the decimal Locus profile ID and current name separately from telemetry. Runtime
config must carry the same ID and dual canonical fingerprints. Command value 1 must never appear.
Keep a manually selected page through pause/resume and verify configuration changes reconcile within
60 seconds without changing the selected stable page.

Large snapshot dictionaries can exceed Android logcat's per-line display limit. When the recording
state tuple is truncated, use the bridge diagnostics or watch dashboard as the authoritative
observable state.

### Pause and resume

1. Open Controls and choose **Pause**.
2. Assert **Paused** in Locus, the bridge, and the watchapp.
3. Open Controls and choose **Resume**.
4. Assert **Recording** in all three places.

### Waypoints

While recording:

- **Quick waypoint** must create the normal `Pebble waypoint` immediately.
- On a microphone-capable watch, **Waypoints → Dictated waypoint** must open Pebble dictation, show
  the transcribed text for confirmation, and use the accepted text as the waypoint name.

QEMU can exercise the plain command. Dictation confirmation and phone transcription should also be
tested on physical microphone hardware.

### Stop and save

Only perform this step when saving a test track is intended:

1. Open Controls and choose **Stop & save**.
2. Confirm the destructive action on the watch.
3. Poll until Locus, the bridge, and the watchapp all report **Stopped**.
4. Verify that the test track was saved in Locus.

### Opt-in Android instrumentation test

The repository also has an opt-in, non-mutating Locus contract test. It refuses to run when a
recording is active, verifies numeric catalog identities, verifies obsolete Start rejection, and
leaves Locus stopped:

```sh
./tools/podman-test dev bash -c 'ANDROID_SERIAL=arc:5555 ./gradlew :android:app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.runLocusIntegration=true'
```

Normal verification compiles this test but never runs it.

## 9. Runtime smoke tests for both platforms

Repeat watch launch and settings opening on Emery and Gabbro. A successful `pebble build` is not a
runtime test. For each platform verify:

- the app reaches the dashboard without restarting or showing a system error;
- settings opens on the first attempt;
- all one- through six-metric layouts fit;
- German and English labels do not clip;
- Gabbro content remains inside round-screen safe insets;
- controls and confirmations are readable;
- the stopped instruction hides metrics and Select has no action;
- active page navigation wraps and preserves selection through pause/resume;
- the five-item layout emphasizes slot 1.

Large C arrays must remain in static storage. The repository's stack regression complements this
smoke test but cannot replace QEMU launch testing.

Finish with physical Pebble Time 2 testing. Verify Bluetooth reconnection, background restrictions,
battery behavior, real GPS updates, Locus foreground/background transitions, and microphone
dictation.

## 10. Troubleshooting

### Watchapp does not start

- Run `./tools/podman-test dev npm test --prefix watchapp`; the stack regression catches large
  function-local buffers.
- Confirm that the PBW contains the correct platform binary.
- Confirm that CoreApp finished transferring both binary and resources.
- Inspect CoreApp and QEMU logs for an app crash or restart.
- Launch and open settings in QEMU; compilation alone is insufficient.

### A second QEMU or “not responding” screen appears

An SDK command probably launched another managed emulator. List exact QEMU processes and port
owners. Stop only the unintended process. Keep the direct QEMU connected to CoreApp and its
persistent flash intact.

### Containerized Android Emulator exits with status 139

On an enforcing SELinux host, KVM validation can pass while policy denies the Android Emulator
render thread's `execheap` access. Follow the diagnosis, temporary A/B test, reviewed local-policy
fix, verification, and rollback procedure in
[Android Emulator exits 139 on an SELinux host](podman-testing.md#android-emulator-exits-139-on-an-selinux-host).

### CoreApp cannot connect

- Check `adb reverse --list` for `tcp:12344 tcp:12344`.
- Check that QEMU listens on host port 12344.
- Check that no other process already occupies the only protocol connection.
- Re-send the `ADD_QEMU_WATCH` broadcast and inspect `QemuSetupReceiver` logs.

### CoreApp APK will not install on an x86 emulator

Compare the APK's `native-code` list with Android's `ro.product.cpu.abilist`. Use ARCVM with ARM
translation, an ARM target, or a CoreApp source build containing the target ABI.

### Settings needs several clicks or profiles are unavailable

- Ensure the watchapp is open and PKJS is running in CoreApp.
- Ensure Locus is running and, for command tests, bring it to the foreground.
- Compare bridge and watchapp versions.
- Verify the bridge diagnostics show the exact Locus profiles.
- Distinguish a fresh response from a stale cached response.
- Look for a complete, single-transfer profile chunk sequence.

### Command state after bridge process restart

The command deduplication journal, snapshot-ordering epochs, and profile-transfer serials are
stored in-memory in the Android bridge process. If the Android process is forcefully killed or
crashes, this state is lost. Upon restarting the process, the bridge establishes a new epoch
baseline seeded from the system clock. Any command that was pending or retried while the bridge
was down may be executed by the fresh bridge without deduplication. The ordinary refresh
preference remains only on the current device; Android cloud backup and device transfer are
disabled for all TrackGlance data.

### ARCVM shows `PlaceholderActivity`

Refocus the intended app explicitly. For CoreApp:

```sh
adb -s "$ANDROID_SERIAL" shell am start -W \
  -a android.intent.action.VIEW \
  -d pebble://navbar/apps \
  -n coredevices.coreapp/.MainActivity
```

For command tests, refocus Locus using the command in section 6.

### Disk fills during SDK installation

Run `df -h`, then use `du` on the Android SDK, Pebble SDK, Gradle cache, and build directories. Keep
stable SDK packages. Clean generated project builds before deleting reusable downloads. An Android
SDK in `/tmp` is a configuration error, not a cache strategy.

### Network becomes very slow

Let resumable package downloads continue unless they have definitively failed. If a transfer is
stuck, restart that transfer while retaining complete packages and caches. Do not trigger an NDK
redownload unless the NDK is actually missing or corrupt.

## 11. Containerized acceptance automation

The containerization roadmap is implemented by `./tools/podman-test`. It builds a pinned Android
12L/API 32 emulator with Docker or rootless Podman, provisions Pebble App and a validated Locus Map
fixture into a golden data volume, injects Pebble QEMU buttons and heart rate through a transparent
relay, and runs the full static, instrumentation, and watch-to-Locus acceptance suites. A Google
WebRTC frontend supports interactive local bootstrap; fresh CI bootstrap is headless.

See [Containerized acceptance environment](podman-testing.md) for host requirements, pinned inputs,
commands, privacy boundaries, failure handling, artifact paths, and the Android-minimum upgrade
procedure. The ARCVM and direct-QEMU sections above remain useful for manual and physical-device
diagnostics; they are separate from the disposable API 32 automation.

GitHub-hosted acceptance may use the signed, digest-pinned public image set described in the
[published acceptance image documentation](podman-testing.md#published-acceptance-image-set).
Only toolchains, the emulator runtime, and the public prebuilt Pebble App fixture are published;
Locus and the current TrackGlance APK/PBW remain runtime inputs.
