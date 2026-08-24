How the Apps Work Together
==========================

Locus owns recording start and exposes recording state, statistics, the active profile name, and a
catalog of numeric profile IDs and names. The Android bridge polls telemetry, resolves the active
name through the latest catalog, and sends telemetry and recording context as separate messages so
a maximum-length name cannot enlarge a 512-byte snapshot.

The watch requests a catalog at launch. Watch Settings requests another catalog when opened; an
unresolved active profile triggers an immediate refresh. There is no periodic catalog timer.

PKJS owns the complete canonical page library. After the watch reports an active numeric ID, PKJS
sends a projection containing global options and that activity's one to four pages. Two independent
32-bit fingerprints identify the canonical library. The watch requests reconciliation every 60
seconds and persists only the latest activity projection.

Pause/resume, stop/save, quick waypoint, and dictated waypoint travel from watch to Android and then
to Locus. Locus broadcasts do not acknowledge application, so the bridge polls recording-state
changes before reporting success. Obsolete Start command 1 is rejected.
