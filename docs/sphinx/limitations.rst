Limitations and Troubleshooting
===============================

Only Pebble Time 2 (Emery) and Pebble Round 2 (Gabbro) are supported. Each activity has at most four
active pages and each page at most six metrics. Each activity keeps four ordered metric-page slots;
inactive slots are not sent to the watch. Watch-originated heart rate is available only on Emery.

If the stopped screen remains after starting in Locus, open the Android bridge and confirm that
Locus, Pebble App, watch, and watchapp are connected and on release 0.2.4. Allow Locus and Pebble App
to run in the background.

``Preparing profile...`` normally clears after the catalog and page projection arrive. After 15
seconds the watch asks you to open Watch Settings; doing so forces a catalog refresh. The watch keeps
retrying configuration reconciliation once per minute. An empty or failed catalog is intentionally
non-destructive.

The APK and PBW must be upgraded together. Protocol v4 remains in use, but release strings are also
checked and mismatches are rejected.
