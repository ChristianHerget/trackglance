Android Bridge Settings
=======================

The Android bridge connects the watchapp to Locus and reports whether each part of that connection
is working.

Refresh Mode
------------

Refresh mode controls how often the bridge asks Locus for updated recording information.

* **Adaptive** sends an immediate update when the watchapp opens or a command is used. It checks
  every two seconds for the first 15 seconds, then every ten seconds to reduce battery use.
* **Every 5 seconds** provides consistently frequent updates.
* **Every 10 seconds** reduces update frequency and battery use.

Connection Status
-----------------

The main screen reports the Pebble App connection, Pebble watch connection, Locus availability, recording
state, current heart rate, and recent bridge errors. Use this page first when the watch is not
receiving updates or a recording command does not reach Locus.

Watch appearance, display profiles, metrics, and heart-rate forwarding are documented separately
under :doc:`watch-settings`.
