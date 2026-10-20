---
name: vietmap
description: VietMap integration rules for this project. Covers map display via the Android SDK and style URLs, Route v4 for motorcycles, Autocomplete/Search/Place/Reverse v4, API keys and billing, and mapping VietMap routes onto the Yamaha TFT nav messages. Trigger whenever code touches VietMap: map rendering, routing, destination search, geocoding, API keys, or the VietMap renderer/route engine in the bike-streaming pipeline.
---

# VietMap (project reference)

**Source of truth:** `docs/vietmap-agent-docs/`. Before writing or changing VietMap code, read the file for the area you're touching, and don't guess endpoints or parameters from memory.

| Task | Read |
|---|---|
| Map display, style URLs, Android SDK, traffic overlay | `map-display.txt` |
| Routing (Route v4, tolls) | `routing.txt` |
| Destination search, geocoding, reverse | `search-geocoding.txt` |
| Keys, quotas, billing | `console.txt` |
| Matrix / TSP / VRP / boundaries | `logistics.txt` (not used by the nav app) |
| Address migration / map-data reports | `data-tools.txt` (not used by the nav app) |

How VietMap fits the bike link (frames, turn icons, ETA messages): see `docs/bike-protocol.md` §5.

## Project decisions
- **VietMap is the only map provider.** Google Maps Platform was dropped on 2026-10-18 (its billing blocks Vietnamese accounts). No OpenStreetMap, OpenFreeMap or Valhalla.
- Routing always uses **Route v4** with `vehicle=motorcycle`. Route v3 is legacy, so never add it.
- Destination search goes **Autocomplete v4 → Place v4**: autocomplete while the user types, Place v4 once on selection. Always pass `focus=lat,lng` (device GPS) and `display_type=6`.

## Hard rules
1. **Two keys.**
   - The *tilemap key* ships in the app inside the style URL, so it must be domain+IP restricted with a quota.
   - The *API key* (routing/search) lives **only** in the Cloudflare Worker `proxy/vietmap/` (secret `VIETMAP_API_KEY`). It must never be in the APK.
   - The app calls `GET {VIETMAP_PROXY_URL}/{route|autocomplete|search|place|reverse}?<same params as VietMap, minus apikey>` with header `X-App-Token: {VIETMAP_PROXY_TOKEN}`. The Worker forwards to `/api/<endpoint>/v4`, adds the key, and forces `vehicle=motorcycle` on `/route`.
   - Allowed params are whitelisted in `proxy/vietmap/src/index.js` (`ENDPOINTS`). A new param or endpoint needs an edit there plus a test (`node --test proxy/vietmap/test/index.test.mjs`).
   - Read the tilemap key, proxy URL and token from build config / `local.properties`, and never commit them.
2. **Coordinate order flips at every boundary:**
   - REST query params: `point=lat,lng`, `focus=lat,lng`.
   - `points_encoded=false` output and toll bodies: `[lng,lat]`.
   - Android SDK: `LatLng(lat, lng)`.
   - Route `bbox`: `[minLng, minLat, maxLng, maxLat]`.
3. **Units:**
   - Route `distance` is in metres and `time` is in **milliseconds**.
   - Autocomplete `distance` is in kilometres.
4. **Polyline:** `points` is Google Polyline precision 5 unless `points_encoded=false`. Use one decoder and unit-test it.
   - The app requests `points_encoded=false`. **Checked live:** `points` is then a **GeoJSON LineString** `{"type":"LineString","coordinates":[[lng,lat],…]}`, not the bare array the docs show.
   - **Don't use `instructions[].interval`.** Live responses have intervals that don't index the geometry (e.g. `[0,0]` for a 141 m leg). Place each instruction by summing `distance`, which adds up to the path distance (`core/navigation/Route.kt`).
   - A real response is kept as a test fixture: `app/src/test/resources/route_ben_thanh.json`.
5. **`ref_id` is opaque.** Pass it to Place v4 verbatim. Search/Autocomplete results have no lat/lng; Reverse v4 results do.
6. **Styles:**
   - `https://maps.vietmap.vn/maps/styles/{code}/style.json?apikey=…`, where `tm` = street (day), `lm` = light, `dm` = dark (night), `hm` = hybrid.
   - Traffic `tf/style.json` is an **overlay**: merge its sources and layers after the base style loads, and re-add them after every `setStyle`. Never pass it as the base style.
7. **Android SDK lifecycle:**
   - Call `Vietmap.getInstance(context)` before `super.onCreate`.
   - Forward every lifecycle callback to `MapView`.
   - Set the style with `Style.Builder().fromUri(url)`.
   - Add sources/layers only after the style has loaded.
   - Check `gradle/libs.versions.toml` before adding SDK artifacts. Coordinates are in `map-display.txt` §4 (JitPack repo required).
   - The project uses **`maps-sdk-android` 4.1.1**, not the 2.x the docs pin. It has the same `vn.vietmap.vietmapsdk` API, and its `libmapbox-gl.so` is 16 KB-aligned, which Play requires. 2.6.0 is not.
   - Its JitPack POM declares **no dependencies**. Add `vietmap-services-geojson-android`, `vietmap-services-turf-android` (the location puck calls `com.mapbox.turf`; missing = runtime crash, not a compile error), OkHttp 4.x and Gson yourself. Don't add Timber: it is bundled in the AAR and duplicates the class.
   - The offscreen dash renderer is `core/map/VietMapDashRenderer.kt` (VirtualDisplay → Presentation → MapView → ImageReader → JPEG).
8. **Cost:**
   - Route = `⌊points/5⌋+1` transactions; tiles = 25 per transaction.
   - Don't re-route on every GPS fix. Only re-route when off-route (> ~30 m for > 3 s) and pass `heading`.
   - Keep SDK tile caching on. Debounce autocomplete 200–300 ms, minimum 2 characters, and cancel the in-flight request.
9. **Render offscreen at the dash size** (480×240 or 480×234), inside the `Presentation`/`VirtualDisplay` pipeline. Strip diacritics before sending any text to the TFT.

## Route v4 → TFT turn icon (cmd 4)
| `sign` | meaning | icon |
|---|---|---|
| -3 | sharp left | 32 |
| -2 | left | 34 |
| -1 | slight left | 6 |
| 0 | continue | 8 |
| 1 | slight right | 7 |
| 2 | right | 35 |
| 3 | sharp right | 33 |
| 4 | finish | 0 |
| 5 | via reached | 0 |
| 6 | roundabout | 14 |

U-turn has no `sign` value: when `text` contains "Quay đầu", send 36. Next road = `street_name`; ETA = now + remaining `time` (ms).

## Verify
- Check a key and its parameters in the playground (https://tools.vietmap.vn/playground) before wiring code.
- Unit-test the polyline decoder, the sign→icon mapping and the lat/lng flips.
- Compile check: `./gradlew compileDebugKotlin`.
