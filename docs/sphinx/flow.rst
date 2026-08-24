How the Apps Work Together
==========================

Pebble Locus Map connects three parts: Locus Map records the activity, the Android bridge exchanges
information with Locus, and the watchapp displays that information and provides controls.

Recording Updates
-----------------

Locus supplies the current recording state and available activity statistics. The bridge forwards
fresh updates to the watch, where the watchapp displays the metrics selected for the active profile.
Unavailable values appear as an em dash. If updates stop arriving, the watch marks the information
as stale instead of presenting it as current.

Recording Controls and Waypoints
--------------------------------

When you start, pause, resume, or stop a recording from the watch, the bridge asks Locus to perform
the action and checks whether the recording state changed as expected. The watch shows progress
while this happens and reports an error if the phone, bridge, or Locus cannot complete the action.

Waypoint actions follow the same path. A quick waypoint uses the default Pebble waypoint name;
dictation uses the text you confirm on the watch.

Heart Rate from the Watch
-------------------------

Pebble Time 2 can send its heart-rate readings while Locus is actively recording and watch-to-Locus
forwarding is enabled. The bridge rejects readings that are invalid, outdated, or received after
recording has stopped.

Forwarding is best effort because Locus does not acknowledge this heart-rate interface. The bridge
can report that it sent a reading and can request updated Locus information, but it cannot guarantee
that Locus stored the reading. If no valid watch sensor value is available, no reading is sent.
Pebble Round 2 cannot provide watch-originated heart rate.

Applying Watch Settings
-----------------------

Settings selected through the Pebble App are validated before the watch stores them. When Locus is
stopped, the watch applies valid changes immediately. During recording or pause, it keeps the new
settings ready and applies them after the recording stops. If delivery or validation fails, the
previous working settings remain in use.
