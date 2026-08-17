# End-to-end development and testing

This guide describes a reproducible environment for building Locus Pebble Bridge and testing the
complete path from a PebbleOS watchapp to Locus Map. It records the setup that was proven on a
Chromebook with Linux, ChromeOS ARCVM, CoreApp, and PebbleOS QEMU.

The long-term goal is a containerized development environment. The build toolchains and PebbleOS
QEMU can be containerized. Locus Map and CoreApp still run on an Android target, such as ARCVM, an
Android emulator, or a physical device. A public container must not redistribute Play Store APKs,
signing material, private API keys, or other third-party artifacts.

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
| Pebble Tool | 5.0.39 |
| Pebble SDK | 4.33.1 |
| Android compile/target SDK | 36 |
| Android Build Tools | 36.0.0 |
| Bridge and watchapp | 0.1.7 |
| Wire protocol | v3 |
| CoreApp QEMU support | `coredevices/mobileapp` commit `38fd4c6892599d6a02b4b3ca0b3fd518a51d6170` |
| Watch targets | Emery (Pebble Time 2) and Gabbro (Pebble Round 2) only |

Pin tool versions in automation. An SDK directory named `latest`, a moving Git branch, or an
unversioned APK makes failures difficult to reproduce.

Allow at least 15 GB of free disk for this repository, Gradle caches, Android SDK packages, the
Pebble SDK, and an optional CoreApp source build. The known-good host used approximately 4.8 GB for
the Android SDK, 1.6 GB for Pebble SDK data, and 3.8 GB for Gradle caches. Building CoreApp adds an
NDK of approximately 2.2 GB and substantially more build output.

## 1. Install the host tools

On Debian or Ubuntu, install the base packages:

```sh
sudo apt update
sudo apt install git curl unzip zip bzip2 openjdk-17-jdk-headless \
  nodejs npm python3 python3-venv adb libsdl2-2.0-0 libasound2 \
  libpulse0 libx11-6 libfdt1
```

Confirm the tools before downloading large SDKs:

```sh
java -version
node --version
npm --version
adb version
df -h .
```

### Install the Android SDK

Use Android Studio's SDK Manager or the official Android command-line tools. Install at least:

- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- Android SDK Platform Tools

Use a permanent directory such as `$HOME/Android/Sdk` or `/opt/android-sdk`. Do not install the SDK
under `/tmp`: temporary cleanup, reboot, or a full temporary filesystem can invalidate Gradle's SDK
path and force large downloads to be repeated.

Create the untracked `local.properties` in the repository root:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

The Locus Bridge build does **not** require an Android NDK. NDK 28.2.13676358 and CMake 3.22.1 were
needed only when CoreApp itself was built from source. Reuse an existing complete NDK installation;
do not delete it merely because a build or network connection was interrupted.

### Install Pebble Tool and Pebble SDK

Install `uv`, Pebble Tool, and a pinned Pebble SDK:

```sh
curl -LsSf https://astral.sh/uv/0.12.4/install.sh | sh
uv tool install 'pebble-tool==5.0.39' --python 3.13
pebble sdk install 4.33.1
pebble sdk activate 4.33.1
pebble --version
pebble sdk list
```

Pebble Tool normally stores SDKs below `$XDG_DATA_HOME/pebble-sdk`, or
`$HOME/.local/share/pebble-sdk` when `XDG_DATA_HOME` is unset. Keep that directory on persistent
storage.

On an unreliable connection, preserve the Android, Gradle, npm, uv, and Pebble download caches.
Retry the failed download without deleting valid SDKs. Before restarting a large operation, check
`df -h` and identify the actual partial file instead of clearing the entire cache.

## 2. Build and test Locus Pebble Bridge

From the repository root:

```sh
./gradlew verifyPebbleTargets :android:app:testDebugUnitTest \
  :android:app:assembleDebug
./gradlew :android:app:compileDebugAndroidTestKotlin
cd watchapp
npm ci
npm test
pebble clean
pebble build
npm run verify:pbw
cd ..
```

The outputs are:

```text
android/app/build/outputs/apk/debug/app-debug.apk
watchapp/build/watchapp.pbw
```

The final npm command verifies PBW targets, metadata, resources, and embedded PKJS. Check versions
and contents manually as needed before installation:

```sh
ANDROID_SDK_ROOT=/absolute/path/to/Android/Sdk
"$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt" dump badging \
  android/app/build/outputs/apk/debug/app-debug.apk | head
unzip -p watchapp/build/watchapp.pbw appinfo.json
unzip -l watchapp/build/watchapp.pbw
```

The APK and PBW versions must match. The PBW must contain the embedded `pebble-js-app.js` and only
the `emery/` and `gabbro/` platform directories. Protocol constants must remain synchronized across
Kotlin, C, `watchapp/package.json`, and `protocol/README.md`.

## 3. Prepare an Android target

The Android target must run all three apps:

1. CoreApp (`coredevices.coreapp`)
2. Locus Pebble Bridge (`app.locuspebble.bridge`)
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
  android/app/build/outputs/apk/debug/app-debug.apk
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
`38fd4c6892599d6a02b4b3ca0b3fd518a51d6170`. To reproduce the verified source build, check out that
exact revision:

```sh
git clone https://github.com/coredevices/mobileapp.git coreapp
cd coreapp
git checkout --detach 38fd4c6892599d6a02b4b3ca0b3fd518a51d6170
cp androidApp/src/google-services-dummy.json androidApp/src/google-services.json
printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
./gradlew :androidApp:assembleDebug
adb -s "$ANDROID_SERIAL" install -r \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk
cd ..
```

CoreApp's basic watch, QEMU, locker, and PebbleKit functionality does not require production API
tokens. Some account, bug-reporting, transcription, and online services do. Keep any such tokens,
Firebase configuration, and signing keys outside this repository and outside public container
images. A CoreApp source build is large and may install NDK 28.2.13676358 and CMake 3.22.1.

Confirm installed packages and versions:

```sh
adb -s "$ANDROID_SERIAL" shell dumpsys package coredevices.coreapp \
  | grep -E 'versionName=|versionCode='
adb -s "$ANDROID_SERIAL" shell dumpsys package app.locuspebble.bridge \
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
  -n app.locuspebble.bridge/.MainActivity
```

Expected values are:

- Pebble watchapp: Open
- Pebble/Core app: `coredevices.coreapp`
- Pebble watch: Connected
- Watchapp version: identical to the bridge version
- Locus Map: Available
- Recording: Stopped, Recording, or Paused as appropriate
- Locus profiles: the exact names returned by Locus

Return Locus to the foreground before sending recording commands:

```sh
adb -s "$ANDROID_SERIAL" shell am start -W \
  -n menion.android.locus/com.asamm.android.library.androidCore.features.startScreen.StartScreen
```

The delivered activity may be reported as
`menion.android.locus/com.asamm.locus.basic.features.mainActivity.MainActivityMap`; that is normal.

## 7. Configure profiles and verify settings

Open the watchapp settings from CoreApp while the watchapp is running. With a valid same-version
cache, the page opens immediately with a stale-data notice while a refresh is queued. Without a
valid cache, it waits for a fresh response for up to 500 ms and then opens with either fresh data or
an unavailable notice. A response received after a page is already open is stored atomically for
the next opening; the data URL cannot update the already-open page. A complete empty response also
replaces the old cache and is shown explicitly instead of retaining stale profile names.

Verify all of the following:

1. CoreApp, watchapp, and Android bridge versions match.
2. The settings page opens on the first click.
3. Every Locus dropdown contains the exact profile names from Locus.
4. No required mapping shows the warning prefix.
5. Saving closes the settings page and transfers all configuration chunks sequentially.
6. Reopening settings preserves names, mappings, order, metrics, and theme.

Fresh defaults use `Gehen` and `Radfahren` for German, and `Walking` and `Cycling` for English.
Existing profile names are user data and are never translated automatically.

If settings says no profile response has arrived:

```sh
adb -s "$ANDROID_SERIAL" shell am start -W \
  -n app.locuspebble.bridge/.MainActivity
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

### Start recording

1. Select a watch profile whose exact Locus mapping exists. In the known-good German setup this was
   `Gehen`.
2. Bring Locus to the foreground.
3. Clear logcat so the command is easy to identify:

   ```sh
   adb -s "$ANDROID_SERIAL" logcat -c
   ```

4. On the QEMU watch dashboard, press Select once to open **Controls**.
5. Press Select on **Start recording**.
6. Inspect the command and response:

   ```sh
   adb -s "$ANDROID_SERIAL" logcat -d -v time \
     | grep -E 'AppMessagePush|PebbleProtocol|Locus'
   ```

The command dictionary must contain protocol v3, message type 2, command 1, a command ID, a session
ID, the matching release version, and the exact Locus profile name. The result dictionary must use
message type 3 with result 0. Finally, the bridge diagnostics must report **Recording: Recording**.
An OK command result without the later recording state is not a complete pass.

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

- **Add waypoint** must create the normal `Pebble waypoint` immediately.
- On a microphone-capable watch, **Add waypoint + note** must open Pebble dictation, show the
  transcribed text for confirmation, and use the accepted text as the waypoint name.

QEMU can exercise the plain command. Dictation confirmation and phone transcription should also be
tested on physical microphone hardware.

### Stop and save

Only perform this step when saving a test track is intended:

1. Open Controls and choose **Stop & save**.
2. Confirm the destructive action on the watch.
3. Poll until Locus, the bridge, and the watchapp all report **Stopped**.
4. Verify that the test track was saved in Locus.

### Opt-in Android instrumentation test

The repository also has an opt-in Locus test. It refuses to run when recording is already active,
then creates and saves a short track:

```sh
ANDROID_SERIAL=arc:5555 ./gradlew :android:app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.runLocusIntegration=true \
  -Pandroid.testInstrumentationRunnerArguments.observationDelayMillis=3000
```

The observation delay is optional and capped at ten seconds. Normal verification compiles this test
but never runs it.

## 9. Runtime smoke tests for both platforms

Repeat watch launch and settings opening on Emery and Gabbro. A successful `pebble build` is not a
runtime test. For each platform verify:

- the app reaches the dashboard without restarting or showing a system error;
- settings opens on the first attempt;
- all one- through six-metric layouts fit;
- German and English labels do not clip;
- Gabbro content remains inside round-screen safe insets;
- controls and confirmations are readable;
- profile selection is locked while recording;
- the five-item layout emphasizes slot 1.

Large C arrays must remain in static storage. The repository's stack regression complements this
smoke test but cannot replace QEMU launch testing.

Finish with physical Pebble Time 2 testing. Verify Bluetooth reconnection, background restrictions,
battery behavior, real GPS updates, Locus foreground/background transitions, and microphone
dictation.

## 10. Troubleshooting

### Watchapp does not start

- Run `npm test`; the stack regression catches large function-local buffers.
- Confirm that the PBW contains the correct platform binary.
- Confirm that CoreApp finished transferring both binary and resources.
- Inspect CoreApp and QEMU logs for an app crash or restart.
- Launch and open settings in QEMU; compilation alone is insufficient.

### A second QEMU or “not responding” screen appears

An SDK command probably launched another managed emulator. List exact QEMU processes and port
owners. Stop only the unintended process. Keep the direct QEMU connected to CoreApp and its
persistent flash intact.

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

## 11. Containerization roadmap

### Phase 1: reproducible build image

Create a pinned Linux image containing:

- JDK 17;
- Node.js and npm;
- Python plus `uv` and Pebble Tool;
- Pebble SDK 4.33.1;
- Android command-line tools, Platform 36, Build Tools 36.0.0, and Platform Tools;
- SDL2/X11 runtime libraries for QEMU;
- Git, curl, unzip, zip, and bzip2.

Run as a non-root build user. Put SDKs in stable paths such as `/opt/android-sdk` and
`/opt/pebble-sdk`. Set `ANDROID_SDK_ROOT` and `XDG_DATA_HOME` explicitly. Pin downloads by version
and checksum. Accept Android licenses during image construction where their terms permit it.

Use persistent caches rather than rebuilding them on every invocation:

```text
/home/developer/.gradle
/home/developer/.npm
/home/developer/.cache/uv
/opt/pebble-sdk
```

The first container milestone should run these commands without Android or GUI access:

```sh
./gradlew verifyPebbleTargets :android:app:testDebugUnitTest \
  :android:app:assembleDebug :android:app:compileDebugAndroidTestKotlin
cd watchapp && npm ci && npm test && pebble clean && pebble build && npm run verify:pbw
```

### Phase 2: QEMU service

Add a QEMU service using the direct command from section 4. Persist one flash volume per SDK and
platform. Expose ports 12344-12347 only to the test network. For interactive tests, forward X11 or
use Xvfb plus VNC/noVNC. Audio and microphone forwarding are optional until dictation automation is
in scope.

Do not run the standard Pebble phone simulator in this service. CoreApp is the phone-side protocol
and PKJS runtime.

### Phase 3: external Android target

Initially, keep Android outside the container:

- connect to Chromebook ARCVM;
- connect to a physical Android device; or
- run a separately managed Android emulator with KVM.

The container can use the host ADB server or host networking, subject to local security policy. Do
not expose an unauthenticated ADB server to an untrusted network.

For a containerized Android emulator, pass `/dev/kvm`, reserve substantial RAM and disk, and choose
an ABI compatible with CoreApp and Locus. A Play-enabled emulator introduces account, licensing,
and provisioning concerns. Locus and Play-distributed CoreApp APKs should be mounted from a private
artifact directory or installed interactively; they should not be copied into a public image.

### Phase 4: orchestration and automation

A practical Compose layout is:

```text
builder       builds APK/PBW and runs unit/static tests
pebble-qemu   runs one pinned Emery or Gabbro QEMU with persistent flash
e2e-runner    controls ADB, installs bridge/PBW, gathers logs, and asserts state
android       external ARCVM/device at first; optional KVM emulator later
```

Automate these readiness checks in order:

1. APK and PBW built with matching versions.
2. Android target visible through ADB.
3. CoreApp, bridge, and Locus installed.
4. QEMU ports listening.
5. ADB reverse active.
6. CoreApp QEMU watch connected.
7. PBW installed and watchapp open.
8. PKJS/bridge handshake reports matching versions and protocol v3.
9. Fresh Locus profile list received.
10. Start/pause/resume/waypoint/stop transitions observed.

The remaining automation gap is button injection while CoreApp owns QEMU's only protocol socket.
Possible solutions are a CoreApp test API that relays QEMU control frames, a dedicated test proxy
that multiplexes control without corrupting Pebble Protocol, or UI-level keyboard automation in a
VNC session. Until one is implemented, keep the two watch button presses as an explicit human test
step and automate every assertion around them.

### Definition of done for the container

The containerized environment is complete when a new machine can, from pinned inputs:

- build both artifacts without downloading tools into temporary directories;
- run JVM, JS, stack, packaging, and Android-test compilation checks;
- launch Emery and Gabbro QEMU and smoke-test app startup/settings;
- connect to a documented Android target through ADB;
- install the PBW through CoreApp;
- obtain a fresh Locus profile list;
- exercise and assert all recording commands end to end;
- retain logs, screenshots, APK, and PBW as test artifacts;
- perform all destructive recording operations only with explicit opt-in.
