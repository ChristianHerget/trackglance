Getting Started
===============

What You Need
-------------

* A phone running **Android 7.0 or newer** with Locus Map and the Pebble App.
* A Pebble Time 2 or Pebble Round 2.
* The Android APK and PBW from the same release.

Install the Android bridge, install the PBW through the Pebble App, open Locus Map once, and launch
TrackGlance on the watch. The bridge status screen should report Locus as available, the watch as
connected, the watchapp as open, and matching bridge/watch versions.

Start in Locus
--------------

Recording is owned by Locus Map. Start the desired recording profile in Locus. While no recording
is active, the watch deliberately hides all metrics and controls and says **No recording. Start
recording in Locus Map.** Select does nothing on this screen.

.. list-table:: Stopped screen on both supported watches
   :widths: 50 50

   * - .. image:: _static/screenshot_emery_stopped.png
          :alt: Stopped instruction on Pebble Time 2
          :width: 240px
     - .. image:: _static/screenshot_gabbro_stopped.png
          :alt: Stopped instruction on Pebble Round 2
          :width: 240px

As soon as Locus starts recording, the bridge identifies its numeric recording-profile ID and the
watch loads the pages for that activity. The first page is selected for every new recording. If
Locus itself is unavailable, the watch gives a separate instruction to open Locus on the phone.

Automatic Activity Pages
------------------------

Open Watch Settings once after installation. Every installed Locus activity receives a localized
**Default** (English) or **Standard** (German) page. The six initial metrics are chosen from the
activity name:

* walking or hiking: elapsed, distance, altitude, ascent, current speed, current HR;
* running: elapsed, distance, current pace, average pace, ascent, current HR;
* cycling: elapsed, distance, current, average and maximum speed, current HR;
* all other activities: elapsed, distance, current and average speed, altitude, current HR.

English and German walking, hiking, running, jogging, cycling and bicycle keywords are recognized.
Generated page names are saved as ordinary data and are not translated later when the watch
language changes.

Using the Watch
---------------

During recording or pause, **Up** and **Down** switch between one to four pages and wrap at the
ends. The header always shows the state and position, for example ``Recording · 2/4``. The page
name appears briefly after a switch. **Select** opens recording controls. See
:doc:`watchapp-options` and :doc:`watch-settings`.

If a new Locus profile has not synchronized yet, the watch shows ``Preparing profile...``. After
15 seconds it asks you to open Watch Settings, while synchronization continues once per minute.
