Privacy, legal, and trademarks
==============================

Privacy
-------

TrackGlance is local-only by design. It has no TrackGlance server or account, analytics, or hosted
crash reporting, and it does not collect or transmit user data to a TrackGlance service. The
release Android Bridge has no network permission for runtime operation: it declares neither
``android.permission.INTERNET`` nor ``android.permission.ACCESS_NETWORK_STATE``. Recording metrics
and commands remain on the local path between Locus Map, the Android Bridge, the Pebble App,
Bluetooth/AppMessage, and the watch.

The Android Bridge refresh preference is stored only on the device and excluded from Android backup
and device transfer. Its recent diagnostic history is limited to at most 20 entries in process
memory and disappears when that process ends. The Pebble App stores the watch configuration and
last Locus profile catalog locally and builds the settings screen as an offline page. The watch
persists only the active activity's small configuration projection and ordering counters, not the
full profile catalog or recorded track. Locus Map owns the recording itself.

These statements describe TrackGlance, not its required third-party applications. Locus Map and
the Pebble App have their own storage, synchronization, network, and privacy behavior, and neither
is promised to work fully offline. Installing the applications and downloading documentation use
their normal external services. Tapping the legal link in the Android Bridge is an explicit user
action that opens this page in an external browser. Maintainers submit release APK and PBW build
artifacts to VirusTotal for malware scanning; those artifacts contain no user runtime data.

Legal and trademarks
--------------------

TrackGlance is an independent, unofficial project. It is not affiliated with, endorsed by,
sponsored by, or associated with Core Devices LLC, Pebble Technology Corp., or Asamm Software,
s.r.o. Pebble is a trademark of Pebble Technology Corp. Locus Map is a brand of Asamm Software,
s.r.o. Those names are used solely to identify compatibility: TrackGlance connects supported
Pebble smartwatches with Locus Map. No ownership of those names or marks, or endorsement by their
owners, is claimed.

The compatibility wording follows the published `Pebble trademark guidelines
<https://developer.repebble.com/legal/pebble-trademark/>`_. The project also respects the
`Locus Map EULA <https://www.locusmap.app/eula/>`_. These references do not constitute formal
trademark clearance.
