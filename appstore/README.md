# Pebble app-store submission kit

`listing.json` contains the English listing copy and references the checked 48x48 and 144x144
icons, 720x320 marketing banner, and one screenshot for each supported watch. Review the
translations and release notes, then copy these values into the Pebble developer portal when
TrackGlance is ready to publish.

The 25x25 watch menu icon is already embedded in the PBW. Phone-side icons, banner, description,
and screenshots are app-store data, so they do not appear for a sideloaded PBW. Running
`python3 tools/validate-appstore-kit.py` checks this kit; it does not upload anything.
