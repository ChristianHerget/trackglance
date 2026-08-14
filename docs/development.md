# Development setup

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

Install Locus Map 4 from Google Play. Install a Pebble/Core app version compatible with
PebbleKit Android 2 (Core 1.0.7.7 or newer), then install `watchapp/build/watchapp.pbw` through it.
The Android diagnostics screen reports Locus, Pebble/Core selection, watch connection, recording
state, refresh mode, and the last bridge error.

## Toolchain

```sh
sudo apt install openjdk-17-jdk-headless python3-pip python3-venv nodejs npm \
  libsdl1.2debian libfdt1
curl -LsSf https://astral.sh/uv/install.sh | sh
uv tool install pebble-tool --python 3.13
pebble sdk install latest
```

Use Android Studio to install Platform 36 and Build Tools 36.0.0. Create an untracked
`local.properties` containing `sdk.dir=/absolute/path/to/Android/Sdk` when Android Studio has not
already done so.

## Update lifecycle

The PebbleKit bound service starts polling when the watchapp opens and stops it when the watchapp
closes. Adaptive mode sends an immediate snapshot, polls every two seconds for 15 seconds after
opening or a command, and then every ten seconds. Fixed five- and ten-second modes are available
from the Android diagnostics screen.

## QEMU/Core integration

After Core and its QEMU transport are installed, launch the Basalt emulator and install the app:

```sh
cd watchapp
pebble install --emulator basalt
```

If Core expects its watch transport through ARCVM, expose the QEMU/Core TCP port with `adb
reverse`. Confirm the port used by the installed Core build before creating the tunnel; `12344`
is common in current development setups:

```sh
adb -s arc:5555 reverse tcp:12344 tcp:12344
```

End-to-end acceptance requires snapshots and all four controls to round-trip through QEMU, Core,
the Android bridge, and Locus. Physical Bluetooth, GPS, battery, and background-restriction tests
remain a later hardware smoke test.

