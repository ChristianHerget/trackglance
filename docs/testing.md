# Focused testing

Start with the group that owns the changed behavior, then run its completion checks. Run toolchain
commands through `./tools/podman-test dev`; dependencies stay in the version-pinned development
container. Docker is preferred, with rootless Podman as the fallback. `doctor static` checks the
engine, and `build-static` builds the image when needed. No device or private APK is needed for
static checks.

## Choose the changed area

| Changed area | Focused container command | Completion checks |
| --- | --- | --- |
| Kotlin formatting or wire scaling | `./tools/podman-test dev ./gradlew :android:app:testDebugUnitTest --tests '*SnapshotFormatterTest'` | `static`; protocol parity for wire changes |
| Other JVM behavior | `./tools/podman-test dev ./gradlew :android:app:testDebugUnitTest --tests '*WatchSessionAuthorityTest'` | `static`; runtime acceptance if behavior changes |
| Bridge runtime | Instrumentation class or method filter below | Compile instrumentation with `static`, execute on Android, then warm acceptance for runtime changes |
| Python shell harness or CI policy | `./tools/podman-test dev bash -c 'cd tools/podman && python3 -m unittest test_device_readiness.DeviceReadinessTest'` | Python discovery, then `static`; warm acceptance for harness behavior changes |
| Watch configuration parsing | `./tools/podman-test dev node watchapp/test/c_core.test.js configuration-parsing` | All C groups, `static`, Emery/Gabbro launch and settings |
| Watch startup persistence | `./tools/podman-test dev node watchapp/test/c_core.test.js configuration-storage` | All C groups, `static`, Emery/Gabbro launch and settings |
| Watch JavaScript settings | `./tools/podman-test dev node watchapp/test/config.test.js` | `./tools/podman-test dev npm test --prefix watchapp`, `static`, affected runtime acceptance |
| Protocol contract | `./tools/podman-test dev node watchapp/test/protocol_parity.test.js` | Update `protocol/README.md` and synchronized Kotlin/C/package constants; `static` and runtime acceptance |
| Documentation or screenshots | `./tools/podman-test documentation` | Check relative links; intentionally regenerate images only when changing them |
| Release packaging | `./tools/podman-test release-check` | `static`; preserve application ID, API 24 minimum, and absence of `DebugStatusProvider` |

JVM filters also accept a method, for example
`--tests '*SnapshotFormatterTest.speedPrecisionChangesOnlyAboveOneHundred'`. A JVM filter cannot
select an `androidTest` class.

## Android instrumentation

Runtime scenarios live under
`android/app/src/androidTest/java/io/github/christianherget/trackglance/bridge/core/`:
`BridgeRuntimeLifecycleTest`, `BridgeRuntimeCommandsTest`, `BridgeRuntimeConnectionAuthorityTest`,
`BridgeRuntimeProfilesContextTest`, `BridgeRuntimeHeartRateTest`, and `BridgeRuntimeStepsTest`.
Read the relevant class and `BridgeRuntimeTestSupport.kt`; specialized fakes stay with their tests.
These tests use fake Locus and transport collaborators but require the Android runtime.

Use an authorized, reachable Android device or emulator, with USB/network ADB configured for the
container. See [hardware and ARCVM setup](development.md) and the
[isolated acceptance environment](podman-testing.md). Set `ANDROID_SERIAL` to that device:

```sh
./tools/podman-test dev bash -c 'ANDROID_SERIAL=arc:5555 ./gradlew :android:app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.christianherget.trackglance.bridge.core.BridgeRuntimeLifecycleTest'
./tools/podman-test dev bash -c 'ANDROID_SERIAL=arc:5555 ./gradlew :android:app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.christianherget.trackglance.bridge.core.BridgeRuntimeLifecycleTest#openingAnotherWatchReplacesTheActiveLifecycle'
```

Omit the class argument to run all instrumentation. Compilation alone does not execute these tests.
Inspect fresh XML results for the expected methods and any failures or skips. The pinned AGP
stack can report a nonzero status despite complete passing XML; the acceptance wrapper handles
this through independent report validation. Record both the Gradle status and the report check
when this occurs. Missing or incomplete results are a failure.

The opt-in real Locus contract test requires idle Locus with a recording profile. It checks numeric
profile identities and rejects obsolete Start command `1` without changing recording state. Never
run it against an active user recording. Its opt-in command and manual recording lifecycle checks
are in [development guidance](development.md#locus-integration-tests) and
[end-to-end testing](end-to-end-testing.md).

## Python groups

Run modules, classes, or individual methods with native unittest selection:

```sh
./tools/podman-test dev bash -c 'cd tools/podman && python3 -m unittest test_cleanup'
./tools/podman-test dev bash -c 'cd tools/podman && python3 -m unittest test_cleanup.CleanupScopeTest.test_cleanup_does_not_mask_a_failed_artifact_deletion'
./tools/podman-test dev python3 -m unittest discover -s tools/podman -p 'test_*.py'
```

The script-policy groups are `test_action_pin_policy`, `test_release_workflows`, `test_ci_policy`,
`test_published_images`, `test_ci_image_publication`, `test_device_readiness`, `test_cleanup`, `test_static_preflight`,
`test_acceptance_orchestration`, and `test_manual_harness`. Fixture validation and fingerprinting
live together in `test_locus_fixture`. Shared policy helpers contain no test classes, so discovery
executes each scenario once. Read the selected module and the shell function or workflow it covers.
Shell fakes execute permission, location, and cleanup behavior without touching a device or engine.
Security and packaging source checks remain explicit; UI orchestration checks that need the full
Android/watch environment are complemented by acceptance.

## Watch groups and Node entry points

```sh
./tools/podman-test dev node watchapp/test/c_core.test.js --list
./tools/podman-test dev node watchapp/test/c_core.test.js transfers-ordering
./tools/podman-test dev node watchapp/test/c_core.test.js
./tools/podman-test dev npm test --prefix watchapp
```

The C groups are `maintenance`, `steps`, `metrics-localization`, `persistence-boundaries`,
`persistence-recovery`, `configuration-parsing`, `transfers-ordering`, and `configuration-storage`.
No argument runs all groups; one name runs one executable; unknown names or extra arguments fail.
Read `watchapp/test/core_<group_with_underscores>_test.c` and the production module it exercises.
Persistence groups share a failure-injecting store in `core_test_support.c`. Each scenario resets
that store; recovery and transfer sequences remain explicit. Retired queued-replacement tests
exercise composed blob storage and do not describe current startup behavior.

Each invocation compiles production modules with 512-byte frame/stack limits, runs ASan and UBSan,
and proves UBSan terminates on undefined behavior. Failures identify the group and test function;
named boundary tables report the input and expected result. Large production buffers must stay
static. C analysis and both Pebble builds include the storage module.

Other independent Node entry points under `watchapp/test/` are `runtime_events.test.js`,
`watch_stack.test.js`, `protocol_parity.test.js`, and `pbw.test.js`. PBW checks require a current
watch build; `static` creates it. `watch_stack.test.js` retains static-storage and UI integration
invariants that need a broader window/AppMessage harness for direct execution. A successful
Pebble build does not replace Emery and Gabbro QEMU launch and settings checks.

## Completion

```sh
./tools/podman-test static
./tools/podman-test documentation
./tools/podman-test release-check
```

`static` runs Gradle/JVM, Android assembly and instrumentation compilation, JavaScript, Python,
shell, C sanitizers/analysis, protocol, both Pebble builds, and PBW checks. The checks retain the
per-check diagnostics described in [container testing](podman-testing.md). `documentation`
validates committed screenshots without regenerating them. Intentional screenshot updates use
`./tools/podman-test dev ./gradlew regenerateDocumentationScreenshots`; see the existing
[screenshot procedures](development.md#verification-and-screenshots).

For Android/watch runtime or acceptance-harness changes, run the warm suite with the validated
private fixture:

```sh
./tools/podman-test acceptance-suite --locus-apks /absolute/private/path
```

The [container acceptance guide](podman-testing.md) owns fixture setup, provisioning, cleanup,
image publishing, and the documented local private fixture path. Use `--published --cleanup` to
reproduce hosted provisioning; use `--fresh --cleanup` for provisioning changes or replacement
image validation. The protected pull-request workflow remains the authoritative acceptance gate.
Documentation and workflow changes that cannot affect runtime do not need duplicate full
acceptance. Record commands, results, devices/platforms, and any unexecuted checks in the review.
