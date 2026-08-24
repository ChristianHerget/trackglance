Features
========

The TrackGlance bridge connects your Pebble smartwatch to the Locus Map app on your Android phone, bringing recording data and controls directly to your wrist.

Pebble Time 2
-------------

The dashboard shows the recording state and the metrics selected for the active profile.

.. raw:: html

   <div class="watch-hero watch-hero--emery">
     <div class="watch-hero__strap">
       <div class="watch-hero__case">
         <img src="_static/screenshot_emery_dashboard.png" alt="Locus recording dashboard running on Pebble Time 2">
         <span class="watch-hero__button watch-hero__button--left" aria-hidden="true"></span>
         <span class="watch-hero__button watch-hero__button--right-top" aria-hidden="true"></span>
         <span class="watch-hero__button watch-hero__button--right-middle" aria-hidden="true"></span>
         <span class="watch-hero__button watch-hero__button--right-bottom" aria-hidden="true"></span>
       </div>
     </div>
     <p>Pebble Time 2 showing an active Locus recording</p>
   </div>

Pebble Round 2
--------------

.. raw:: html

   <div class="watch-hero watch-hero--gabbro">
     <div class="watch-hero__strap">
       <div class="watch-hero__case">
         <img src="_static/screenshot_gabbro_dashboard.png" alt="Locus recording dashboard running on Pebble Round 2">
         <span class="watch-hero__button watch-hero__button--left" aria-hidden="true"></span>
         <span class="watch-hero__button watch-hero__button--right-top" aria-hidden="true"></span>
         <span class="watch-hero__button watch-hero__button--right-middle" aria-hidden="true"></span>
         <span class="watch-hero__button watch-hero__button--right-bottom" aria-hidden="true"></span>
       </div>
     </div>
     <p>Pebble Round 2 showing an active Locus recording</p>
   </div>

Watchapp Capabilities
---------------------
- **Real-time statistics**: View metrics like elapsed time, speed, distance, altitude, heart rate, cadence, power, and slope.
- **Heart Rate Transfer (Watch to Locus)**: On supported watches, the watchapp reads heart rate
  during an active recording and asks the Android Bridge to forward valid values to Locus. Locus
  does not acknowledge this interface, so forwarding is best effort.
- **Watch support**: Pebble Time 2 can provide watch-originated heart-rate samples. Pebble Round 2
  cannot send watch-originated heart rate.
- **No HR sensor scenario**: On Pebble Time 2, if the watch cannot produce valid heart-rate samples,
  the watch shows `Heart rate unavailable` and no watch-originated sample is forwarded to Locus.
  Session HR data remains whatever Locus provides in its own telemetry stream.
- **Activity pages**: Automatically choose one to four pages for the recording profile started in Locus.
- **Navigation Waypoints**: Open a waypoint submenu to save a quick point or dictated note.
- **Locus Commands**: Pause, Resume, and Stop & save the recording started in Locus Map.

See :doc:`watchapp-options` for every watch control and :doc:`watch-settings` for the settings
available through the phone.

Android Bridge App
------------------
- Acts as the intermediary, transforming Pebble AppMessages into Locus Map intents.
- Provides a simple UI to check connection status, current heart rate, and permissions.
- Automatically handles reconnection and offline states seamlessly.

Locus Map Integration
---------------------
- Fully integrated with Locus Map's native GPS recording engine.
- Supports mapping, active tracks, and waypoint drops seamlessly via intent broadcasts.

.. _locus-units:

Locus units and formatting
--------------------------

The Android bridge reads Locus Map's separate preferences for distance, altitude, speed, slope,
and energy. The watch therefore uses the same unit families you selected in Locus, including
metric, feet/miles, yards/miles, and nautical distance; metres or feet for altitude and vertical
speed; km/h, mi/h, nmi/h, or knots; percent or degrees; and kJ or kcal. There is no second unit
setting in TrackGlance.

The bridge also mirrors Locus's compact medium-precision display rules: short distances remain in
metres, feet, or yards, longer distances change to kilometres, miles, or nautical miles at Locus's
thresholds, decimals decrease as values grow, and speed drops its decimal only above 100. Total and
moving distance choose their formats independently. Pace follows the selected length family.

.. list-table:: The same watch profile with different Locus unit preferences
   :widths: 50 50

   * - **Feet, miles, mi/h, and kcal**
     - **Nautical miles and knots**
   * - .. image:: _static/screenshot_emery_units_imperial.png
          :alt: Pebble Time 2 dashboard displaying imperial units read from Locus
          :width: 260px
     - .. image:: _static/screenshot_emery_units_nautical.png
          :alt: Pebble Time 2 dashboard displaying nautical units read from Locus
          :width: 260px

Locus preference changes are picked up within about 60 seconds. If Locus cannot be queried, the
last valid choices remain active; a cold start or an invalid individual preference uses the metric
default for that preference.
