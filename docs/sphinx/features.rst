Features
========

The Pebble Locus Map bridge connects your Pebble smartwatch to the Locus Map app on your Android phone, bringing recording data and controls directly to your wrist.

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
- **Navigation Waypoints**: Send a quick waypoint or dictate a note for a waypoint directly from the watch.
- **Locus Commands**: Start, Pause, Resume, and Stop recording in the Locus Map application.
- **Profiles**: Select between different recording profiles configured within Locus Map.

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
