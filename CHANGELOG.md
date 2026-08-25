# Changelog

User-visible TrackGlance changes are recorded here. Release dates replace **Unreleased** when a
release is published.

## 0.2.4 - Unreleased

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
