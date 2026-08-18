# Architecture & Design Decisions

This document captures important design decisions made during the development of the Pebble Locus Bridge.

## 1. Single APK Architecture
**Decision:** The Pebble Locus Bridge is distributed as a single, standard Android APK (`app.locuspebble.bridge`) that functions both as a standalone Android app and as an optional Locus Map add-on.
**Rationale:** 
- Being an add-on does not grant special API access, better background execution, or automatic Locus lifecycle syncing. 
- Using a single APK with a lightweight `MAIN_FUNCTION` intent filter avoids the overhead of maintaining two separate apps while still providing the convenience of launching the bridge directly from Locus Map's function menu.
- When launched via the Locus menu, the bridge will attempt to auto-start the Pebble watchapp for seamless transition.

## 2. Track Recording Background Execution
**Decision:** The bridge's background service (`BridgePebbleListenerService`) relies purely on Locus API broadcasts (e.g., `ACTION_TRACK_RECORD_START`) to start and stop recordings, without attempting to force Locus Map to the foreground.
**Rationale:** 
- Modern Android versions severely restrict launching Activities from the background without special permissions (e.g., `SYSTEM_ALERT_WINDOW`). 
- This matches the behavior of the official `locus-addon-wearables` project. If Locus fails to start a recording from a background state (e.g., due to background location access denial), we accept this upstream limitation rather than employing invasive foregrounding workarounds.
