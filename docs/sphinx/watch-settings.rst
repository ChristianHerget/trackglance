Watch Settings
==============

Opening Settings
----------------

Open the LocusPebble watchapp settings through the Pebble App on the phone. The settings page reads
your recording-profile names from Locus. If the phone cannot get a fresh list, it shows the last
known profiles and tells you that they may be outdated. It also warns when the bridge and watchapp
versions do not match.

General Options
---------------

**Theme** selects the dark or light watchapp color scheme. The default is dark.

The page uses English or German according to the active watch language.

.. image:: _static/watch_settings_overview.png
   :alt: Watch Settings overview with theme, heart rate, and profiles
   :align: center
   :width: 390px

Heart-Rate Forwarding
---------------------

Pebble Time 2 provides:

* **Send watch heart rate to Locus**: enables watch-originated HR forwarding while Locus is
  recording.
* **Heart rate interval**: selects a forwarding interval from 1 to 60 seconds; the default is five
  seconds.

These controls are not available for Pebble Round 2, which cannot provide watch-originated
HR samples. ``Current HR`` can still be selected as a display metric on either watch because that
metric comes from Locus telemetry.

When forwarding is enabled on Pebble Time 2 but no valid sensor value is available, the watch reports
``Heart rate unavailable`` and sends no HR packet. Locus continues recording normally and retains
any heart-rate data supplied by its own sensors.

Display Profiles
----------------

The settings page requires between one and eight display profiles. A profile can be selected,
edited, copied, added, deleted, or reordered. The final remaining profile cannot be deleted.

Each profile contains:

* **Display name**: the unique name shown on the watch, limited to 20 characters.
* **Locus profile**: an exact mapping to one recording profile reported by Locus. Missing or stale
  mappings are marked and must be resolved before saving.
* **Metrics**: between one and six unique dashboard fields. Their order controls their placement on
  the watch screen.

.. image:: _static/watch_settings_profile.png
   :alt: Watch Settings profile editor with Locus profile and metrics
   :align: center
   :width: 390px

Available Metrics
-----------------

.. list-table::
   :header-rows: 1
   :widths: 34 33 33

   * - Time and distance
     - Motion and terrain
     - Sensors
   * - Elapsed time
     - Current speed
     - Average heart rate
   * - Moving time
     - Average speed
     - Maximum heart rate
   * - Total distance
     - Maximum speed
     - Current heart rate
   * - Moving distance
     - Current pace
     - Average cadence
   * -
     - Average pace
     - Maximum cadence
   * -
     - Altitude
     - Average power
   * -
     - Ascent
     - Maximum power
   * -
     - Descent
     - Energy
   * -
     - Vertical speed
     -
   * -
     - Slope
     -

Dashboard Layout
----------------

The number of selected metrics determines their placement. One to three metrics use full-width
rows. Four metrics use a two-by-two grid. With five metrics, the first spans the full width and the
remaining four form a two-by-two grid. Six metrics use two columns and three rows.

.. list-table::
   :widths: 50 50

   * - **One metric**
     - **Two metrics**
   * - .. image:: _static/screenshot_emery_layout_1.png
          :alt: Dashboard layout with one metric
          :width: 200px
     - .. image:: _static/screenshot_emery_layout_2.png
          :alt: Dashboard layout with two metrics
          :width: 200px
   * - **Three metrics**
     - **Four metrics**
   * - .. image:: _static/screenshot_emery_layout_3.png
          :alt: Dashboard layout with three metrics
          :width: 200px
     - .. image:: _static/screenshot_emery_layout_4.png
          :alt: Dashboard layout with four metrics
          :width: 200px
   * - **Five metrics**
     - **Six metrics**
   * - .. image:: _static/screenshot_emery_layout_5.png
          :alt: Dashboard layout with five metrics
          :width: 200px
     - .. image:: _static/screenshot_emery_layout_6.png
          :alt: Dashboard layout with six metrics
          :width: 200px

Saving and Applying Changes
---------------------------

**Save** checks every profile and sends the settings to the watch. Invalid, duplicate, or missing
entries are rejected without replacing the previous settings.
**Cancel** asks before discarding unsaved changes.

When Locus is stopped, valid settings are applied immediately. During recording or pause, the watch
stores them and applies them after the recording stops. If delivery is interrupted, the settings
page reports the problem and keeps the previous working configuration.
