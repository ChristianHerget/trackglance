#!/bin/bash

load_release_metadata() {
  local project_dir=$1 android_build watch_package protocol_source
  android_build="$project_dir/android/app/build.gradle.kts"
  watch_package="$project_dir/watchapp/package.json"
  protocol_source="$project_dir/android/app/src/main/java/io/github/christianherget/trackglance/bridge/protocol/BridgeProtocol.kt"

  RELEASE_ANDROID_VERSION=$(
    sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$android_build"
  )
  RELEASE_ANDROID_CODE=$(
    sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$android_build"
  )
  RELEASE_WATCH_VERSION=$(
    sed -nE 's/^[[:space:]]*"version":[[:space:]]*"([^"]+)".*/\1/p' "$watch_package"
  )
  RELEASE_PROTOCOL_VERSION=$(
    sed -nE 's/^    const val VERSION[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$protocol_source"
  )

  test -n "$RELEASE_ANDROID_VERSION"
  test -n "$RELEASE_ANDROID_CODE"
  test -n "$RELEASE_WATCH_VERSION"
  test -n "$RELEASE_PROTOCOL_VERSION"
  test "$RELEASE_ANDROID_VERSION" = "$RELEASE_WATCH_VERSION"
  export RELEASE_VERSION=$RELEASE_ANDROID_VERSION
}
