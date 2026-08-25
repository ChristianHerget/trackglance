Watch Settings
==============

Open Watch Settings in the **Pebble App**. The overview lists detected Locus activities
alphabetically and shows the enabled-page count for each. A notice says whether the catalog was just
updated or comes from the saved cache. A separate, labeled **General settings** row above Activities
contains watch theme and supported heart-rate forwarding controls.

Editing an activity
-------------------

Select an activity to edit its four ordered page slots. The Locus activity name is read-only. An
active page has a custom-name field, a ``1/6`` through ``6/6`` metric counter, and ordered metric
rows. Metrics are indented beneath the page with a vertical guide. Leave the name blank for localized
``Page N``. Display names may repeat.

An inactive slot shows ``Inactive page`` and ``0/6``. Its first metric activates it. Removing the
last metric deactivates a page and preserves its hidden custom name, but at least one page must stay
active. Metrics are unique within a page but may repeat on different pages. A full ``6/6`` page keeps
its Add Metric action visible but disabled. Six-dot handles drag page slots and metrics; focused
handles also reorder directly with the keyboard's Up and Down keys.

**Done** updates the main unsaved draft. A subpage **Cancel** discards only that screen's changes.
The overview's final **Save** or **Cancel** closes Watch Settings. Activity reset restores its
heuristic first page and three inactive slots; General reset affects only General settings.

.. image:: _static/watch_settings_overview.png
   :alt: Alphabetical Watch Settings activity list

.. image:: _static/watch_settings_profile.png
   :alt: Four ordered page slots with metric counters
