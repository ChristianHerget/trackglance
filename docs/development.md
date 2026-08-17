# Development setup

For the complete installation, QEMU/CoreApp setup, end-to-end acceptance procedure, troubleshooting,
and containerization roadmap, see [End-to-end development and testing](end-to-end-testing.md).

## Chromebook Linux and ARCVM

Enable ChromeOS **Develop Android apps / ADB debugging**, then connect from the normal Linux
terminal. This does not require full ChromeOS Developer Mode.

```sh
adb connect arc
adb devices -l
adb -s arc:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk
```

If both `arc:5555` and `emulator-5554` appear, use an explicit `-s` selector. ADB daemon socket
errors inside a managed coding sandbox do not imply an ARCVM configuration problem.

Install Locus Map 4 from Google Play. Install a current CoreApp build compatible with PebbleKit
Android 2, then install `watchapp/build/watchapp.pbw` through it. QEMU testing requires CoreApp's
direct transport from `coredevices/mobileapp` commit `38fd4c6` or later.
The Android diagnostics screen reports Locus, Pebble/Core selection, watch connection, recording
state, refresh mode, and the last bridge error.

## Toolchain

```sh
sudo apt install openjdk-17-jdk-headless python3-pip python3-venv nodejs npm \
  libsdl2-2.0-0 libasound2 libpulse0 libx11-6 libfdt1
curl -LsSf https://astral.sh/uv/install.sh | sh
uv tool install pebble-tool --python 3.13
pebble sdk install 4.33.1
pebble sdk activate 4.33.1
```

Use Android Studio to install Platform 36 and Build Tools 36.0.0. Create an untracked
`local.properties` containing `sdk.dir=/absolute/path/to/Android/Sdk` when Android Studio has not
already done so. Node.js 18 or newer is required for the watchapp verification suite.

## Update lifecycle

The PebbleKit bound service starts polling when the watchapp opens and stops it when the watchapp
closes. Adaptive mode sends an immediate snapshot, polls every two seconds for 15 seconds after
opening or a command, and then every ten seconds. Fixed five- and ten-second modes are available
from the Android diagnostics screen.

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
./gradlew verifyPebbleTargets :android:app:testDebugUnitTest :android:app:assembleDebug
./gradlew :android:app:compileDebugAndroidTestKotlin
cd watchapp
npm ci
npm test
pebble clean
pebble build
npm run verify:pbw
```

`verifyPebbleTargets` checks stack/scheduler invariants plus cross-language protocol and packaging
metadata. `verify:pbw` must run after `pebble build`; it inspects the generated archive rather than
assuming package declarations were honored.

The real Locus contract test is deliberately opt-in. It requires an idle Locus Map installation,
creates a short recording using Locus's active profile, and saves the recording. It refuses to run
if a recording is already active:

```sh
adb connect arc
ANDROID_SERIAL=arc:5555 ./gradlew :android:app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.runLocusIntegration=true \
  -Pandroid.testInstrumentationRunnerArguments.observationDelayMillis=3000
```

The test waits for and asserts every observable state transition: start, pause, resume, and stop.
The optional observation delay (capped at ten seconds) keeps each confirmed state visible in the
Locus UI for manual inspection.
The watch's plain waypoint command saves a point named `Pebble waypoint` immediately
(`autoSave=true`). On microphone-capable watches, the second waypoint command uses Pebble
dictation confirmation and saves the accepted text as the waypoint name. Dictation needs the
phone transcription service; the plain command remains the fallback. The public update container
does not expose the active recording's waypoint count.
