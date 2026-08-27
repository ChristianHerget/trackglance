# Changelog

User-visible TrackGlance changes are recorded here. Release dates replace **Unreleased** when a
release is published.

## Unreleased

- Reduced idle watch wakeups by scheduling maintenance only when pending work reaches its deadline.

## 0.2.6 - 2026-08-27

- TrackGlance settings are no longer backed up to the cloud or transferred to another Android
  device.
- Release builds now verify that dependencies cannot add network permissions, backup or cleartext
  opt-ins, debug components, or unexpected exported Android components.
- Manual captions now remain legible in light and dark browser themes, and phone-side screenshots
  use one consistent light theme.
- TrackGlance's local-only privacy model, on-device data retention, third-party boundaries, and
  release-artifact scanning are now documented in the manual and Pebble app-store description.
- The Android Bridge now pauses screen-state collection while it is not visible and immediately
  catches up with the latest status when reopened.

## 0.2.5 - 2026-08-26

- Release notes now link to separate VirusTotal submissions for the signed Android APK and Pebble
  watch app. The links confirm successful submission and do not claim a clean scan verdict.

## 0.2.3 - 2026-08-25

- Restyled the Android Bridge with clearer grouped status and refresh controls, accessible state
  labels, and automatic light/dark presentation matching the phone. The documentation now follows
  the same appearance.
- Redesigned Watch Settings around an alphabetical activity list, with watch appearance and
  heart-rate forwarding moved to a separate General screen.
- Each activity now has four reorderable page slots. Adding or removing metrics activates or
  deactivates pages, with live zero-to-six metric counts and nested metric ordering.
- Fixed touch dragging for pages and metrics in the Pebble App, including moving metrics between
  pages and dragging across full pages to a later valid destination. Six-dot handles now also open
  a movement menu for dependable button-based ordering. Clarified the nested metric hierarchy and
  made full pages visibly stop accepting additional metrics.
- Labeled and separated General settings from the activity list so its app-wide scope is clear.
- Added automatic localized page names, separate activity and General resets, light/dark
  presentation, and accessible reorder controls. Existing page customization is preserved.
- Pages may now intentionally share a display name; stable page identity remains unique.
- Added French, Spanish, Italian, European Portuguese, Simplified Chinese, and Traditional Chinese
  to the watch, Watch Settings, and Android Bridge.
- Added clearer installation guidance and screenshots for the watch's missing-Bridge screen.
- Clarified waypoint support and preserved settings when a Locus activity is renamed.

## 0.2.2

- Hardened hosted acceptance, release signing checks, and GitHub Actions maintenance.
