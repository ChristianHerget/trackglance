# TrackGlance style guide

TrackGlance uses an activity-focused interface. New screens use offline system fonts, plain-language
labels, inline icons, and controls that remain understandable without color.

## Brand tokens

| Token | Light | Dark |
| --- | --- | --- |
| Background | `#F4FBF6` | `#0F172A` |
| Surface | `#FFFFFF` | `#1E293B` |
| Text | `#1E293B` | `#F1F5F9` |
| Border | `#CBD5E1` | `#475569` |
| Primary | `#006C4C` | `#34D399` |

Use `prefers-color-scheme`; light is the fallback when the Pebble App WebView does not expose an OS
preference. Never fetch presentation assets from the network.

## Typography, spacing, and components

Use the platform system-sans stack and a 16px body size. Space elements on a 4px scale. Cards use a
12px radius and visible one-pixel border. Touch targets are at least 44px in both dimensions.

Primary actions use the primary token with adequate contrast. Disabled controls remain legible.
Show stale and unavailable states without hiding cached content. Confirm resets.

Every control needs an accessible name, visible keyboard focus, and a non-color state cue. Reorder
controls use a dedicated six-dot handle and support touch, pointer, and mouse dragging. Show a
floating item and an outlined insertion point without making color the only status cue. Long lists
must edge-scroll during a drag, and unavailable destinations must remain traversable and explain why
they cannot accept a drop. Keyboard reordering uses a grab mode: Space or Enter picks up and drops,
Up and Down move, and Escape cancels, with every state and position announced. Respect
`prefers-reduced-motion`; animation must never be required to understand a change.

## Platform constraints

Settings run offline inside the Pebble App WebView. Layouts must tolerate narrow screens, long
translations, both color modes, Emery, and Gabbro. Gabbro does not expose unsupported watch
heart-rate controls. This guide is app-wide; Watch Settings is the first restyled surface.
