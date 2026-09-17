Limitations and Troubleshooting
===============================

Only Pebble Time 2 and Pebble Round 2 are supported. Each activity has at most four active pages and
each page at most six metrics. Each activity keeps four ordered metric-page slots; inactive slots
are not sent to the watch. Watch-originated heart rate is available only on Pebble Time 2; see
:ref:`heart-rate-settings`.

If the stopped screen remains after starting in Locus, open the Android bridge and confirm that
Locus, Pebble App, watch, and watchapp are connected and on release 0.2.8. Allow Locus and Pebble App
to run in the background.

``Preparing profile...`` normally clears after the catalog and page projection arrive. After 15
seconds the watch asks you to open Watch Settings; doing so forces a catalog refresh. The watch keeps
retrying configuration reconciliation once per minute. An empty or failed catalog is intentionally
non-destructive.

The APK and PBW must be upgraded together. Protocol v4 remains in use, but release strings are also
checked and mismatches are rejected.

Supervision boundaries
----------------------

Watchapp supervision protects only recordings with a learned sensor source in the current Bridge
process. It reacts only to explicit close callbacks from the Pebble App. Missing samples,
disconnections, crashes without a close callback, and unavailable sensors do not trigger recovery.
It cannot protect a recording before the first qualifying packet. Steps remain TrackGlance’s
best-effort recording value; a supervision alert does not mean steps were forwarded to Locus.
After reboot or force-stop, open the Bridge. Process recreation clears learned sources.
