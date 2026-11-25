#!/bin/bash
# Synthetic checks share the existing container lifecycle and diagnostics.
prepare_synthetic_tools() {
  check_stage synthetic-tools
  doctor_static
  if [[ "${CI_PUBLISHED_TOOLS:-false}" == true ]]; then
    : "${GH_TOKEN:?GH_TOKEN is required for image verification}"
    local image=${CI_TOOLCHAIN_IMAGE:-$CODEQL_KOTLIN_IMAGE}
    DEV_CONTAINER_ENGINE=$STATIC_ENGINE "$SCRIPT_DIR/verify-ci-image" "$image" "$CI_IMAGE_SIGNER_WORKFLOW"
    "$STATIC_ENGINE" pull "$image"
    export BUILD_IMAGE=$image
  else
    static_image_exists || build_static_image
  fi
}

synthetic_helpers() {
  prepare_synthetic_tools
  run_static_container bash -euo pipefail -c '
    source tools/podman/check-report.sh
    check_stage helper-policy
    bash -n tools/podman-test tools/ci-watch-phase tools/podman/*.sh docs/*.sh
    actionlint .github/workflows/*.yml
    shellcheck --severity=warning tools/podman-test tools/ci-watch-phase tools/podman/*.sh docs/*.sh
    ruff check tools tools/resolve-ci-image
    python3 -m unittest discover -s tools/podman -p "test_*.py"
    python3 tools/validate-appstore-kit.py
    ./tools/bump-version --check
    check_stage format
    ./tools/check-format
  '
}

synthetic_watch() {
  prepare_synthetic_tools
  run_static_container bash -euo pipefail -c '
    source tools/podman/check-report.sh
    retain_watch_diagnostics() {
      local status=$?
      if [[ -n "${CHECK_LOG_DIR:-}" && -d build/watch-smoke ]]; then
        mkdir -p "$CHECK_LOG_DIR/diagnostics"
        cp -a build/watch-smoke "$CHECK_LOG_DIR/diagnostics/"
      fi
      exit "$status"
    }
    trap retain_watch_diagnostics EXIT
    check_stage watch-core
    ./tools/check-c
    npm ci --prefix watchapp
    npm test --prefix watchapp
    npm run lint --prefix watchapp
    /opt/uv-tools/pebble-tool/bin/python watchapp/test/smoke_harness_test.py
    check_stage watch-build
    (cd watchapp && pebble clean && pebble build && npm run verify:pbw)
    for platform in emery gabbro; do
      check_stage "$platform-smoke"
      /opt/uv-tools/pebble-tool/bin/python tools/podman/watch-smoke.py "$platform"
    done
  '
}

synthetic_android() {
  prepare_synthetic_tools
  check_stage android-dependency-compatibility
  run_static_container bash -euo pipefail -c '
    python3 tools/podman/check-gradle.py ./gradlew --build-cache \
      :android:app:checkDebugAarMetadata :android:app:checkDebugAndroidTestAarMetadata \
      :android:app:checkReleaseAarMetadata
  '
  check_stage android-provisioning
  select_acceptance_engine
  ensure_cache_volumes
  if [[ "${CI_PUBLISHED_TOOLS:-false}" == true ]]; then
    DEV_CONTAINER_ENGINE=$STATIC_ENGINE "$SCRIPT_DIR/verify-ci-image" "$ACCEPTANCE_EMULATOR_IMAGE" "$CI_IMAGE_SIGNER_WORKFLOW"
    acceptance_engine pull "$ACCEPTANCE_EMULATOR_IMAGE"
    EMULATOR_IMAGE=$ACCEPTANCE_EMULATOR_IMAGE
  fi
  acceptance_image_exists "$EMULATOR_IMAGE" || die "Missing emulator image; run build-acceptance first."
  new_run synthetic-android
  ACTIVE_POD="trackglance-${RUN_ID}"
  ACTIVE_DATA_VOLUME="${ACTIVE_POD}-android"
  ACTIVE_RUNTIME_VOLUME="${ACTIVE_POD}-runtime"
  acceptance_engine volume create "$ACTIVE_DATA_VOLUME" >/dev/null
  acceptance_engine volume create "$ACTIVE_RUNTIME_VOLUME" >/dev/null
  create_test_pod "$ACTIVE_POD"
  start_android "${ACTIVE_POD}-android" "$ACTIVE_DATA_VOLUME" "$ACTIVE_RUNTIME_VOLUME"
  runner_in_pod "$RUN_ARTIFACTS" bash /workspace/tools/podman/synthetic-android-stage.sh
}

synthetic_tests() {
  local component=${1:-all}
  case "$component" in
    android) synthetic_android ;;
    watch) synthetic_watch ;;
    helpers) synthetic_helpers ;;
    all) synthetic_helpers; synthetic_watch; synthetic_android ;;
    *) die "synthetic requires all, android, watch, or helpers" ;;
  esac
}
