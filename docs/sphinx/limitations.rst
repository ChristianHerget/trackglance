Limitations
===========

While the app provides robust integration between Pebble and Locus Map, there are a few inherent limitations:

Hardware Limitations
--------------------
- **Supported Devices**: The watchapp only supports Pebble Time 2 and Pebble Round 2. Older Pebble
  models are not supported.
- **Watch-Originated HR**: Heart-rate forwarding to Locus is only available on Pebble Time 2.
  Pebble Round 2 users see bridge/Locus data from phone telemetry only; the watch cannot supply its
  own heart-rate readings.
- **Display Space**: The number of concurrently displayed metrics is limited by the physical screen size of the smartwatch. Up to six unique metrics can be configured per profile.

Software Limitations
--------------------
- **Data Delivery**: Locus command broadcasts do not immediately acknowledge the application. The bridge relies on bounded interval polling to confirm recording states.
- **Starting Locus in the Background**: Locus's public API defines START as a broadcast and does not
  require a foreground activity. Nevertheless, on some recent Android and Locus combinations,
  Android restricts the recording service's location or sensor access when START arrives while
  Locus is backgrounded. Bring Locus to the foreground before starting from the watch and leave it
  visible until recording begins. Locus and the Pebble App must also be allowed to continue running
  in the background; battery optimization may interrupt later communication.
- **Protocol Compatibility**: The bridge and watchapp use Protocol Version 3. The Android APK and the Pebble PBW must be upgraded together; mismatched versions are intentionally rejected.
- **Offline Availability**: Locus profile lists and data rely on the phone's availability. If the Bluetooth connection drops, the watch will show stale or unavailable (--) metrics until reconnected.
