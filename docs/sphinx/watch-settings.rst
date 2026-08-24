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
handle orders metrics. Moving the final page recreates a default page; moving into an activity
that already has four pages is rejected. Updating settings keeps the selected page when it still
exists.

.. image:: _static/watch_settings_profile.png
   :alt: Direct page editor with activity mapping and metric drag handles
   :align: center
   :width: 390px

The page can contain one to six unique metrics. One to three use full-width rows, four use a two by
two grid, five use one full-width row plus a two by two grid, and six use two columns by three rows.
Units come from Locus rather than a second setting.

Synchronization and Reset Behavior
----------------------------------

Settings are saved on the phone and the active activity's pages are sent to the watch. TrackGlance
automatically adds newly created Locus activities and removes activities that no longer exist. If
Locus is temporarily unavailable, saved settings are kept. Renaming a Locus activity keeps its
TrackGlance pages and settings.

Storage failure keeps the previous canonical configuration and reports a localized error. The
**Reset** button removes all page customization and restores global settings; the current confirmed
catalog immediately recreates one heuristic Default/Standard page per activity.
