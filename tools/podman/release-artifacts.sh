#!/bin/bash
set -euo pipefail

mkdir -p "$HOME"
./gradlew :android:app:assembleRelease
release_apk=android/app/build/outputs/apk/release/trackglance-bridge-release.apk
test -s "$release_apk"

badging_file=$(mktemp)
manifest_file=$(mktemp)
cleanup_policy_inputs() {
  rm -f "$badging_file" "$manifest_file"
}
trap cleanup_policy_inputs EXIT
aapt2 dump badging "$release_apk" > "$badging_file"
aapt2 dump xmltree --file AndroidManifest.xml "$release_apk" > "$manifest_file"
expected_code=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' android/app/build.gradle.kts)
expected_version=$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' android/app/build.gradle.kts)
expected_target=$(sed -nE 's/^[[:space:]]*targetSdk[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' android/app/build.gradle.kts)
python3 tools/podman/check_release_manifest.py \
  --badging "$badging_file" \
  --manifest "$manifest_file" \
  --debug-manifest android/app/src/debug/AndroidManifest.xml \
  --version-code "$expected_code" \
  --version-name "$expected_version" \
  --target-sdk "$expected_target"

expected=$(tr -d '[:space:]' < trackglance-release-certificate.sha256)
actual=$(apksigner verify --verbose --print-certs "$release_apk" |
  sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
  tr -d ':[:space:]' | tr '[:lower:]' '[:upper:]')
test -n "$actual"
test "$actual" = "$expected"

npm ci --prefix watchapp
(cd watchapp && pebble clean && pebble build && npm run verify:pbw)
test -s watchapp/build/watchapp.pbw

bash docs/build_html.sh
test -s docs/sphinx/_build/html/index.html
