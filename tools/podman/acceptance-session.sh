#!/bin/bash
# Metadata stays on this runner and is parsed as data, never sourced as shell.
export ACCEPTANCE_STARTUP_ONLY=false
export SESSION_RETAIN_RUNTIME=false

select_warm_acceptance_images() {
  if { ! acceptance_image_exists "$BUILD_IMAGE" || ! acceptance_image_exists "$EMULATOR_IMAGE"; } \
    && acceptance_image_exists "$ACCEPTANCE_RUNNER_IMAGE" && acceptance_image_exists "$ACCEPTANCE_EMULATOR_IMAGE"; then
    require_published_ci_image_pins
    BUILD_IMAGE=$ACCEPTANCE_RUNNER_IMAGE
    EMULATOR_IMAGE=$ACCEPTANCE_EMULATOR_IMAGE
  fi
}

session_inputs() {
  (cd "$PROJECT_DIR" && git ls-files -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
}

session_read() { "$SCRIPT_DIR/acceptance-session-state" read "$SESSION_FILE" "$@"; }
session_patch() { "$SCRIPT_DIR/acceptance-session-state" patch "$SESSION_FILE" "$@"; }

session_restore() {
  "$SCRIPT_DIR/acceptance-session-state" validate "$SESSION_FILE" "$PROJECT_DIR" "$ACCEPTANCE_ENGINE" \
    "$BUILD_IMAGE" "$EMULATOR_IMAGE" "$ACCEPTANCE_RUNNER_IMAGE" "$ACCEPTANCE_EMULATOR_IMAGE"
  BUILD_IMAGE=$(session_read build_image)
  EMULATOR_IMAGE=$(session_read emulator_image)
  RUN_ID=$(session_read run)
  if [[ "$operation" == cleanup && "$(session_read runtime)" == null ]]; then return; fi
  if [[ "$(session_read runtime)" != null ]]; then
    local android watch
    android=$(session_read runtime.android)
    watch=$(session_read runtime.pod)-watch
    if [[ "$operation" == cleanup ]] \
      && ! acceptance_engine container inspect "$android" >/dev/null 2>&1 \
      && ! acceptance_engine container inspect "$watch" >/dev/null 2>&1; then
      session_patch runtime null
      return
    fi
    acceptance_engine inspect "$android" "$watch" \
      | "$SCRIPT_DIR/acceptance-session-state" compare-ids "$SESSION_FILE"
    # Assign cleanup targets only after ownership and immutable IDs are verified.
    ACTIVE_POD=$(session_read runtime.pod)
    ACTIVE_ANDROID_CONTAINER=$android
    ACTIVE_DATA_VOLUME=$(session_read runtime.data)
    ACTIVE_WATCH_VOLUME=$(session_read runtime.watch)
    ACTIVE_RUNTIME_VOLUME=$(session_read runtime.runtime)
    RUN_ARTIFACTS=$(session_read runtime.artifacts)
    mapfile -t ACTIVE_CONTAINERS < <(session_read runtime.containers lines)
    # shellcheck disable=SC2034 # consumed by the owning wrapper's cleanup trap
    [[ "$ACCEPTANCE_ENGINE" != podman ]] || ACTIVE_CONTAINERS=()
  fi
  [[ "$(session_read build_id)" == "$(acceptance_engine image inspect --format '{{.Id}}' "$BUILD_IMAGE")" \
    && "$(session_read emulator_id)" == "$(acceptance_engine image inspect --format '{{.Id}}' "$EMULATOR_IMAGE")" ]] \
    || die "Acceptance images were replaced"
}

acceptance_session() {
  local operation=${1:-} component=emery passes=1 pass=1 provisioning=warm locus_dir='' status platform started
  local -a locus_options=()
  started=$(date +%s)
  shift || die "acceptance-session requires an operation"
  SESSION_FILE=''
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --session|--component|--watch-passes|--pass|--locus-apks)
        [[ $# -ge 2 ]] || die "$1 requires a value"
        case "$1" in
          --session) SESSION_FILE=$2 ;;
          --component) component=$2 ;;
          --watch-passes) passes=$2 ;;
          --pass) pass=$2 ;;
          --locus-apks) locus_options=(--locus-apks "$2") ;;
        esac
        shift 2 ;;
      --fresh|--published)
        [[ "$provisioning" == warm ]] || die "Choose one provisioning mode"
        provisioning=${1#--}; shift ;;
      *) die "Unknown acceptance-session option: $1" ;;
    esac
  done
  [[ "$SESSION_FILE" == "$PROJECT_DIR/build/acceptance-sessions/"*.json \
    && "$SESSION_FILE" != *'/../'* ]] || die "Session must be an absolute JSON path under build/acceptance-sessions"
  [[ "$component" == emery || "$component" == gabbro ]] || die "Cross-step sessions require one watch"
  [[ "$passes" == 1 || "$passes" == 2 ]] || die "Invalid watch pass count"
  [[ "$pass" == 1 || "$pass" == 2 ]] || die "Invalid watch pass"
  case "$operation" in prepare|startup|behavior|cleanup) ;; *) die "Unknown session operation: $operation" ;; esac
  select_acceptance_engine
  if [[ "$operation" == prepare ]]; then
    assert_no_active_acceptance_runtime
    [[ ! -e "$SESSION_FILE" ]] || die "Session already exists; use a new session path"
    parse_locus_apk_options "${locus_options[@]}"
    locus_dir=$LOCUS_INPUT_DIR
    check_stage session-provisioning
    case "$provisioning" in
      published) clean; prepare_published_acceptance_images ;;
      fresh) clean; build_acceptance_all ;;
      warm)
        doctor_acceptance
        select_warm_acceptance_images
        require_images
        require_current_golden "$locus_dir" ;;
    esac
    if [[ "$provisioning" != warm ]]; then
      ACCEPTANCE_BOOTSTRAP_AUTOMATED=1 bootstrap --locus-apks "$locus_dir"
    fi
    new_run session
    mkdir -p "$(dirname "$SESSION_FILE")"
    "$SCRIPT_DIR/acceptance-session-state" create "$SESSION_FILE" "$PROJECT_DIR" \
      "$(git -C "$PROJECT_DIR" rev-parse HEAD)" "$(session_inputs)" "$ACCEPTANCE_ENGINE" "$component" "$passes" \
      "$RUN_ID" "$BUILD_IMAGE" "$EMULATOR_IMAGE" "$(golden_marker)" "$provisioning" "$started" \
      "$(acceptance_engine image inspect --format '{{.Id}}' "$BUILD_IMAGE")" \
      "$(acceptance_engine image inspect --format '{{.Id}}' "$EMULATOR_IMAGE")"
    return
  fi
  session_restore
  if [[ "$operation" == cleanup ]]; then
    if [[ -n "$ACTIVE_POD" ]]; then
      printf '%s\n' "$RUN_ARTIFACTS" >> "$CHECK_LOG_DIR/runs.txt"
      finish_e2e_platform "$(session_read active_platform)" "$RUN_ARTIFACTS"
    fi
    session_patch runtime null closed true
    return
  fi
  [[ "$(session_read commit)" == "$(git -C "$PROJECT_DIR" rev-parse HEAD)" \
    && "$(session_read inputs)" == "$(session_inputs)" ]] || die "Acceptance inputs changed between phases"
  [[ "$(session_read fixture)" == "$(golden_marker)" ]] || die "Acceptance fixture/golden identity changed"
  [[ "$(session_read closed)" != true ]] || die "Acceptance session is closed"
  platform=$(session_read component)
  export CHECK_PASS=$pass
  (( pass <= $(session_read passes) )) || die "Pass exceeds selected count"
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    (( $(date +%s) - $(session_read started) < 6600 )) || die "Acceptance session exceeded its 110-minute budget"
  fi
  case "$operation" in
    startup)
      [[ -z "$ACTIVE_POD" ]] || die "A watch pass is already active"
      [[ "$(session_read completed)" == "$([[ "$pass" == 1 ]] && echo '[]' || echo '[1]')" ]] \
        || die "Previous behavior pass did not complete"
      RUN_ARTIFACTS="$BUILD_ROOT/${RUN_ID}-${platform}-pass-${pass}"
      mkdir -p "$RUN_ARTIFACTS"
      printf '%s\n' "$RUN_ARTIFACTS" >> "$CHECK_LOG_DIR/runs.txt"
      start_e2e_platform "$platform" "$RUN_ARTIFACTS"
      session_patch active_platform "\"$platform\"" active_pass "$pass"
      acceptance_engine inspect "$ACTIVE_ANDROID_CONTAINER" "${ACTIVE_POD}-watch" \
        | "$SCRIPT_DIR/acceptance-session-state" runtime "$SESSION_FILE" "$ACTIVE_POD" "$ACTIVE_ANDROID_CONTAINER" \
          "$ACTIVE_DATA_VOLUME" "$ACTIVE_WATCH_VOLUME" "$ACTIVE_RUNTIME_VOLUME" "$RUN_ARTIFACTS"
      run_e2e_phase "$platform" "$RUN_ARTIFACTS" startup
      session_patch startup_complete true
      export SESSION_RETAIN_RUNTIME=true ;;
    behavior)
      [[ -n "$ACTIVE_POD" && "$(session_read startup_complete)" == true \
        && "$(session_read active_pass)" == "$pass" ]] || die "No completed startup for this pass"
      printf '%s\n' "$RUN_ARTIFACTS" >> "$CHECK_LOG_DIR/runs.txt"
      set +e
      run_e2e_phase "$platform" "$RUN_ARTIFACTS" behavior
      status=$?
      check_result "$status"
      finish_e2e_platform "$platform" "$RUN_ARTIFACTS" "$status"
      status=$?
      set -e
      (( status == 0 )) || return "$status"
      "$SCRIPT_DIR/acceptance-session-state" complete "$SESSION_FILE" "$pass" ;;
  esac
}

startup_suite() {
  local option
  for option in "$@"; do
    [[ "$option" != android && "$option" != --watch-passes ]] || die "startup accepts watches and one pass only"
  done
  acceptance_suite "$@"
}
