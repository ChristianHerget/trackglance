# TrackGlance style guide

This is the canonical visual and interaction guide for TrackGlance. It applies to the Android
Bridge, Watch Settings inside the Pebble App, and the public documentation. TrackGlance connects
live Locus Map recording data and controls with supported Pebble watches. Its tone is professional,
adventurous, dependable, and direct.

## Relationship to the original guide

The original “LocusPebble Bridge” guide established the pebble, map, location, and bridge metaphor;
the professional, adventurous, reliable tone; and the slate-and-green visual direction. Those
principles still apply.

The product is now named **TrackGlance**, with **TrackGlance Bridge** used for the Android companion.
The former LocusPebble name, legacy color values, Ameratorint and Highlight sohem typefaces, and
network-loaded font substitutions are superseded by the rules below. They must not be introduced on
new surfaces.

## Brand identity

The primary mark combines an organic pebble, map lines, and a green location pin inside a dark-slate
rounded square. Use the repository-provided mark and launcher assets; do not redraw or fetch them
from the network. A responsive wordmark pairs the mark with live “TrackGlance” and “Bridge” text so
the text and contrast can adapt to its surface.

- Keep a clear exclusion zone of at least 25% of the mark's height on every side.
- Do not render the full lockup below 40px high or the mark alone below 20px high.
- Keep the original aspect ratio, colors, and internal spacing.
- Use **Pebble App** in user-facing text. CoreApp is reserved for source and developer material that
  identifies the upstream package.

## Color and themes

| Token | Light | Dark |
| --- | --- | --- |
| Background | `#F4FBF6` | `#0F172A` |
| Surface | `#FFFFFF` | `#1E293B` |
| Text | `#1E293B` | `#F1F5F9` |
| Border | `#CBD5E1` | `#475569` |
| Primary | `#006C4C` | `#34D399` |

Green is the primary action and positive-state color. Warning and error colors may extend the core
palette only when their foreground, container, and border combinations meet WCAG AA contrast.
Every semantic state also needs text, an icon, or a shape; color alone is never sufficient.

Android follows the system theme through `isSystemInDarkTheme()`. Web surfaces use
`prefers-color-scheme`, with light as the fallback when the host does not expose a preference.
TrackGlance uses its fixed brand palette and does not substitute Material You dynamic colors.

## Typography and layout

Use the offline platform system-sans stack. Body text starts at 16px or the native platform
equivalent and must respect the user's font scale. Use weight and size for hierarchy rather than a
second font family.

Space and size elements on a 4px/dp scale. Cards use a 12px/dp radius, a visible one-unit border,
and restrained or no elevation. Interactive targets are at least 44px square on web and 48dp square
on Android. Layouts must tolerate narrow phones, long translations, large text, and both supported
round and rectangular Pebble displays.

## Components and states

Primary actions use the primary token with adequate foreground contrast. Disabled controls remain
legible and visibly unavailable. Cards and sections use plain-language headings. Status summaries
pair their text with an icon or shaped label. Stale and unavailable states retain useful cached
content and explain their condition.

Every control needs an accessible name, logical traversal order, and visible keyboard focus. Links,
buttons, dropdowns, dialogs, and menus must expose their role and state to assistive technology.
Confirm destructive resets. Respect reduced-motion preferences; animation must never be required to
understand a state change.

Reorder controls use a dedicated six-dot handle and support touch, pointer, mouse, and keyboard.
Show a floating item and outlined insertion point during dragging. Long lists edge-scroll, invalid
destinations remain traversable and explain why they are unavailable, and a handle tap opens one
inline movement fallback at a time. Keyboard grab mode uses Space or Enter to pick up and drop, Up
and Down to move, and Escape to cancel, with every state and position announced. Clear transient
reorder state when changing screens or resetting content.

## Platform application

- **Android Bridge:** use the native TrackGlance theme, 48dp controls, safe system insets, grouped
  status information, and system-selected light or dark mode.
- **Watch Settings:** remain offline inside the Pebble App WebView, use responsive web controls, and
  hide unsupported heart-rate settings on Gabbro.
- **Documentation:** use local assets and system fonts, provide visible focus, and follow the
  browser or OS color preference.
- **Pebble watchapp:** respect Emery and Gabbro geometry, Pebble SDK constraints, and watch-native
  interaction patterns rather than reproducing phone components.
