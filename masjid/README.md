# Masjid Finder

Find masjids near you on a map and see jamat times kept up to date by a volunteer for each masjid.

## Run the server (DigitalOcean droplet)
```
git clone https://github.com/mazedcu/Homedesign.git
cd Homedesign/masjid/server
sudo ./setup.sh
```
It asks for the admin name, phone and PIN, picks a free port (3200+), and prints the address.
Open it in a browser to test, then enter the same address in the Android app once.
To skip that screen, put the address in `masjid/src/main/res/values/strings.xml` (`default_server`) and rebuild the APK.

**Update:** `cd Homedesign && git pull && cd masjid/server && docker compose up -d --build`
**Backup:** copy `masjid/server/data` (one SQLite file).

## How it works
- Masjids come from OpenStreetMap (imported on demand and cached in your database). People can add missing ones; the admin approves.
- A volunteer applies for a masjid, the admin approves, and then the volunteer updates the five jamat times (and Jumu'ah) about once a week, or taps "Same as last week".
- Times show a freshness badge: green up to 7 days, amber up to 14, grey older. A masjid with no times shows calculated (estimated) prayer times, clearly labelled.

## Switching the map later
The app uses a small map-provider layer (`MapProviders` in `server/public/index.html`). Today only `osm` exists.
To add Google Maps: implement `MapProviders.google` with the same methods (`init`, `setMarkers`, `setMe`, `fly`, `center`, `resize`), put your key in `.env` as `GOOGLE_MAPS_KEY`, and set `MAP_PROVIDER=google`.
To use a different tile server (e.g. MapTiler) set `TILE_URL` and `TILE_ATTRIBUTION` in `.env`.
