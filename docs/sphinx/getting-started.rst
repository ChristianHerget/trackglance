Getting Started
===============

What You Need
-------------

* A phone running **Android 7.0 or newer** with a compatible Locus Map release. Automated acceptance
  uses Android 12L independently of the bridge installation minimum.
* An Android phone with `Locus Map on Google Play
  <https://play.google.com/store/apps/details?id=menion.android.locus>`_ and the `Pebble App on
  Google Play <https://play.google.com/store/apps/details?id=coredevices.coreapp>`_ installed.
* A Pebble Time 2 or Pebble Round 2 paired with the Pebble App.
* The Android bridge APK and Pebble watchapp PBW from the same Pebble Locus Map release.

Install the Apps
----------------

1. Install the Android bridge APK on the phone.
2. Open the PBW file on the phone and install the watchapp through the Pebble App.
3. Open Locus Map, then open **LocusPebble** on the phone.
4. Start the LocusPebble watchapp on the watch.

The bridge connects to the Pebble App automatically. Its status screen should show:

* **Pebble App** with the app package instead of ``Not selected``.
* **Pebble watch** as ``Connected``.
* **Pebble watchapp** as ``Open`` while the watchapp is visible.
* **Locus Map** as ``Available``.
* Matching bridge and watchapp versions.

Before starting a recording from the watch, bring Locus Map to the foreground and leave it visible
until its map has finished loading. Then select **Start recording** and leave Locus visible until the
recording has started. On some recent Android and Locus combinations, Locus receives a background
START command but Android prevents the new recording service from accessing location or sensors.
Once Locus shows that recording is active, you can use the phone normally.

Choose Watch Settings
---------------------

Open the watchapp settings through the Pebble App on the phone. Choose the watch theme, configure display
profiles, map each display profile to a Locus recording profile, and select one to six metrics. See
:doc:`watch-settings` for every option.

Start Using the Watch
---------------------

Start a recording from Locus or press **Select** on the watch and choose **Start recording**. The
dashboard then shows the recording state and the metrics selected for the active display profile.
See :doc:`watchapp-options` for recording controls, profiles, waypoints, and dictation.

If Something Is Missing
-----------------------

Keep Locus Map and the Pebble App allowed to run in the background. If **Start recording** does not
produce a working recording, open Locus in the foreground and retry. If the watch does not update,
open the Android bridge and use its connection status to identify whether the Pebble App, the watch,
the watchapp, or Locus is unavailable. See :doc:`configuration` for the status fields and refresh
modes.
