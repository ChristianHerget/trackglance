#!/bin/bash
set -euo pipefail
source /workspace/tools/podman/device-lib.sh
source /workspace/tools/podman/check-report.sh
capture_synthetic_diagnostics() {
  local status=$?
  trap - EXIT
  set +e
  adb_device_timeout 10 exec-out screencap -p > /artifacts/android.png
  adb_device_timeout 15 logcat -d -t 20000 > /artifacts/android-logcat.txt
  cp -a /workspace/android/app/build/outputs/androidTest-results /artifacts/
  exit "$status"
}
trap capture_synthetic_diagnostics EXIT
# Build while the clean emulator boots. No third-party APKs or golden state are used.
check_stage android-build
python3 tools/podman/check-gradle.py ./gradlew --build-cache -PsyntheticCi=true verifyPebbleTargets \
  :android:app:testDebugUnitTest :android:app:lintDebug :android:app:detekt \
  :android:app:assembleDebug :android:app:assembleDebugAndroidTest :android:app:assembleRelease \
  :android:app:cyclonedxDirectBom
check_stage android-readiness
wait_for_android 180 disabled
for package in menion.android.locus coredevices.coreapp; do
  if adb_device shell pm path "$package" | grep -q '^package:'; then
    echo "Synthetic emulator unexpectedly contains $package" >&2
    exit 1
  fi
done
install -m 600 "$EMULATOR_CONSOLE_TOKEN" /root/.emulator_console_auth_token
results=/workspace/android/app/build/outputs/androidTest-results/connected
rm -rf "$results"
set +e
check_stage android-instrumentation
ANDROID_SERIAL="$ADB_SERIAL" timeout 15m ./gradlew :android:app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=io.github.christianherget.trackglance.bridge.locus.LocusEndToEndTest
gradle_status=$?
set -e
python3 tools/podman/assert-instrumentation-results.py "$results" --synthetic
if (( gradle_status != 0 )); then
  echo "AGP status $gradle_status; complete passing synthetic XML was independently verified."
fi
