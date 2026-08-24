Watch Settings
==============

Activity Groups
---------------

Watch Settings refreshes the Locus recording-profile catalog when it opens. Activities are fully
expanded and sorted alphabetically. There is no global activity limit; each installed activity has
one to four pages.

Within a group:

* tap a page name to edit it;
* use ``⧉`` to clone it;
* use ``−`` to delete it;
* drag ``☰`` to change page order and therefore page priority.

There is no activity-level add button. Deleting the last page requires confirmation and creates a
fresh heuristic Default/Standard page immediately. Display names need only be unique within their
activity.

.. image:: _static/watch_settings_overview.png
   :alt: Alphabetical expanded activity groups with page ordering controls
   :align: center
   :width: 390px

Editing Pages
-------------

Edit changes the display name, metric list, or Locus activity mapping. The same accessible ``☰``
handle orders metrics. Moving the final source page recreates its Default page; moving into an
activity that already has four pages is rejected. Pages keep stable IDs so an in-progress watch
refresh can preserve the selected page.

.. image:: _static/watch_settings_profile.png
   :alt: Direct page editor with activity mapping and metric drag handles
   :align: center
   :width: 390px

The page can contain one to six unique metrics. One to three use full-width rows, four use a two by
two grid, five use one full-width row plus a two by two grid, and six use two columns by three rows.
Units come from Locus rather than a second setting.

Synchronization and Reset Behavior
----------------------------------

The phone owns the complete canonical library and saves it immediately. Only global settings and
the active activity's pages are sent to the watch. The watch caches that most recent activity and
reconciles its canonical fingerprint once per minute.

A successful, nonempty fresh Locus catalog updates retained names, creates missing defaults, and
automatically deletes groups whose numeric IDs disappeared. Failed, empty, or malformed responses
never delete settings. Removing and later recreating a Locus profile therefore creates a new
Default page if its ID changed.

Version 0.2.0 name mappings are migrated against the first confirmed fresh catalog. The numeric
Locus profile ID is primary identity and the name is display data. The official
`TrackRecordProfileSimple model
<https://github.com/asamm/locus-api/blob/0.10.1/locus-api-android/src/main/java/locus/api/android/objects/TrackRecordProfileSimple.kt>`_
calls the field ``Profile ID`` but does not explicitly guarantee that an ID survives a rename; an
ID change is handled deterministically as removal plus addition.

Storage failure keeps the previous canonical configuration and reports a localized error. The
**Reset** button removes all page customization and restores global settings; the current confirmed
catalog immediately recreates one heuristic Default/Standard page per activity.
