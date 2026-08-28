#!/bin/bash
set -euo pipefail

: "${CORE_APP_COMMIT:?CORE_APP_COMMIT is required}"
workspace=${1:-/workspace}
source_dir="$workspace/build/podman/coreapp-src"
output_dir="$workspace/build/podman/images"
patch_file="$workspace/tools/podman/coreapp-x86_64.patch"

mkdir -p "$source_dir" "$output_dir"
if [[ ! -d "$source_dir/.git" ]]; then
  git clone --filter=blob:none https://github.com/coredevices/mobileapp.git "$source_dir"
fi
git -C "$source_dir" fetch --depth 1 origin "$CORE_APP_COMMIT"
git -C "$source_dir" restore --source="$CORE_APP_COMMIT" --staged --worktree .
git -C "$source_dir" checkout --detach "$CORE_APP_COMMIT"
test "$(git -C "$source_dir" rev-parse HEAD)" = "$CORE_APP_COMMIT"
git -C "$source_dir" apply --check "$patch_file"
git -C "$source_dir" apply "$patch_file"
cp "$source_dir/androidApp/src/google-services-dummy.json" "$source_dir/androidApp/src/google-services.json"
printf 'sdk.dir=%s\nLOCAL_RELEASE_BUILD=true\n' "$ANDROID_SDK_ROOT" > "$source_dir/local.properties"
(
  cd "$source_dir"
  ./gradlew --no-daemon --quiet --warning-mode=none :androidApp:assembleDebug
)
core_apk=$(find "$source_dir/androidApp/build/outputs/apk/debug" -maxdepth 1 -type f -name '*.apk' -print -quit)
test -n "$core_apk"
cp "$core_apk" "$output_dir/pebble-app-x86_64-debug.apk"
