# Watchapp supervision validation

The Bridge learns heart rate and available step deltas only from accepted packets while supervision
is enabled and Locus identifies the recording. Only a trusted explicit close starts recovery work;
traffic, a lost connection, and successful launch dispatch do not confirm recovery. Defaults remain
Off and 30 seconds. Android's minimum remains API 24; version 0.2.8 uses versionCode 20.

The supervisor keeps learning, retry budgets, notification dismissal, and recording identity in
memory. Only mode and delay persist. Its service uses monotonic deadlines, bounded wake locks,
recording rechecks, and generation checks before effects. An open callback, pause, stop, recording
replacement, or Off invalidates pending work as specified in issue #67. Unknown Locus state suspends
new actions and preserves the outage budget.

## Local checks

All commands ran through the pinned container with rootless Podman on 2026-09-17.

- `static` passed Android lint, JVM tests, instrumentation compilation, Python helpers, JavaScript,
  C analysis and sanitizers, protocol checks, and Emery/Gabbro packaging.
- The final focused JVM run passed 115 tests, including recovery during launch lookup.
- Python discovery passed 230 tests after the API 34 permission and image-publication changes.
- `documentation` passed with intentionally regenerated light, dark, and supervision screenshots.
- `release-check` passed, including the exact permission allowlist and non-exported service/action
  handler. The signed hosted release remains a separate check.

## Android 14

The full API 34 instrumentation report passes all 71 tests with no failures or skips.

The candidate single acceptance baseline is Android 14 (API 34), Google APIs x86_64 revision 14,
using pinned emulator 37.1.11. The app minimum does not change. A temporary API 34 emulator also ran
nine focused supervision instrumentation tests and the real permission/service checks successfully;
that temporary emulator was removed after screenshot capture.

The permission checks cover Off without a prompt, Notify denial switching Off, Auto-start denial
retaining recovery, `specialUse` startup, sticky restart after a plain process kill, settings-only
restoration, and later notification revocation. The existing AGP runner can return nonzero after
complete passing instrumentation; the acceptance wrapper independently validates the fresh report.
Missing tests or any test failure remain failures.

The warm acceptance suite also exercises real watch closure and reopening with the Bridge hidden
and phone screen off for 40 seconds. It performs no debug-provider queries during that interval.
Emery covers learned heart rate and steps together; Gabbro covers steps.

## Baseline rollout

The existing protected hosted gate keeps its certified published emulator until the API 34 image
has been built, signed, and attested by `Publish CI images` on main. The image metadata records the
API and system-image checksum alongside its immutable digest, so bootstrap verifies the actual
runtime. Adopt replacement pins only after comparing source and published acceptance. This is a
single-baseline replacement; it does not add a permanent emulator matrix.
