#!/bin/bash
set -euo pipefail

source tools/podman/versions.env

mkdir -p "$HOME"
./gradlew :android:app:assembleRelease :android:app:cyclonedxDirectBom :android:app:spdxSbomForRelease
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

version="$expected_version"
source_timestamp=$(git show -s --format=%cI HEAD)
sbom_dir=build/release-sboms
mkdir -p "$sbom_dir"
android_cdx="$sbom_dir/trackglance-bridge-$version.cdx.json"
android_spdx="$sbom_dir/trackglance-bridge-$version.spdx.json"
watch_cdx="$sbom_dir/trackglance-watch-$version.cdx.json"
watch_spdx="$sbom_dir/trackglance-watch-$version.spdx.json"

tools/release-sbom android \
  --version "$version" \
  --artifact "$release_apk" \
  --timestamp "$source_timestamp" \
  --input android/app/build/reports/cyclonedx-direct/release.json \
  --output "$android_cdx"
tools/release-sbom watch \
  --version "$version" \
  --artifact watchapp/build/watchapp.pbw \
  --timestamp "$source_timestamp" \
  --package watchapp/package.json \
  --pebble-sdk-version "$PEBBLE_SDK_VERSION" \
  --output "$watch_cdx"
tools/release-sbom android-spdx \
  --version "$version" \
  --artifact "$release_apk" \
  --timestamp "$source_timestamp" \
  --input android/app/build/reports/spdx/release.spdx.json \
  --output "$android_spdx"
tools/release-sbom watch-spdx \
  --version "$version" \
  --artifact watchapp/build/watchapp.pbw \
  --timestamp "$source_timestamp" \
  --package watchapp/package.json \
  --pebble-sdk-version "$PEBBLE_SDK_VERSION" \
  --output "$watch_spdx"

for stem in trackglance-bridge trackglance-watch; do
  cyclonedx validate \
    --input-file "$sbom_dir/$stem-$version.cdx.json" \
    --input-format json \
    --input-version v1_6 \
    --fail-on-errors
  tools/release-sbom verify-pair \
    --cyclonedx "$sbom_dir/$stem-$version.cdx.json" \
    --spdx "$sbom_dir/$stem-$version.spdx.json"
done
