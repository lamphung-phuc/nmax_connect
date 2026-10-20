# Navigate — improvement roadmap

Living doc for the navigation feature (phone Navigate tab, guidance engine, dash map stream). Edit freely: add bugs
under **Bugs / field reports**, move items between phases, strike through what lands (`~~N1~~ ✅ v1.x`).
Last updated 2026-10-18 (v1.6.1). Overall project status stays in `docs/roadmap.md`.

Code map: `core/navigation/` (Route v4, `NavigationEngine`, `NavigationRepository`, `DashNavMapper`) ·
`core/map/` (`DashStreamer`, `VietMapDashRenderer`, `MapTileCache`) · `feature/navigate/` (phone screen) ·
`core/bike/tft/DashPlaces` (dash destination menu).

## Review: slow map after switching dash screens (field report 2026-10-18)

**Symptom.** Not navigating, dash switched to another screen (e.g. Music) and back: ~5 s before tiles around the
rider appear.

**Cause.** When the dash leaves the map it sends 56[1] → `TftSession` sets `streaming = false` →
`DashStreamer.run()`'s `collectLatest` cancels and `renderer.close()` destroys the whole offscreen map (MapView,
GL context, VirtualDisplay, ImageReader). Coming back (55[1]) builds a brand-new renderer from zero:
GL init → style.json download (~115 KB, not cached) → sprites/glyphs → tile decode from disk → first GPS fix →
camera eases 1 s from the last known position. The disk cache is already warm; the time goes on the cold start.

**The three proposals:**

| # | Proposal | Verdict |
|---|---|---|
| 1 | Fix the slow load | Real problem; fixed by #3 (N1). |
| 2 | Cache tile data | **Already built** in v1.6.1 (`MapTileCache`: 30-day max-age on tiles + glyphs, 256 MB disk cache, measured 0 tile requests on repeat visits). Network isn't the bottleneck now. Gaps left: style.json is re-downloaded per renderer (N2) and no prefetch along a route (N5). |
| 3 | Load in the background while on another screen | **Do it — it's the fix.** Keep the renderer alive for the whole bike link, not per stream; keep the camera following GPS while hidden so the map around the rider is already drawn; only stop sending frames. Back on the map = instant frame. |

## Phase 1 — Map stream responsiveness — ✅ built 2026-10-18, **needs a bike to verify**

Built as planned below (compile, detekt, ktlint, unit tests green). N4 done as "first real fix jumps, no ease from
the stale spot"; the 2 s fresh-fix wait was left as is (only the first stream after connect hits it now).
To verify on the bike: Map → Music → Map shows the map at once; day/night + scale label still right after
switching back; battery over a 30 min ride with the dash mostly on another screen; offline first connect still
shows a map after ~3 s (N3 fallback).

- ~~**N1. Warm dash renderer across screen switches.**~~ ✅ In `DashStreamer.run()`:
  - renderer lifetime keyed on `Ready && !paused` (display), not on `streaming`;
  - `pump()` runs only while `streaming` (inner `collectLatest` on the streaming flag);
  - `followGps` keeps running while hidden but moves the camera **without easing** (an ease renders ~60 fps
    for 1 s per fix = continuous GPU; a jump is one render per fix);
  - on each new stream, reset `DashOverlay.sentNight/label` so msg 31 + 14 are resent (the dash expects them
    per stream);
  - `heading`, zoom offset and route/guidance/stats state already survive.
  - Cost: GPS 1 Hz + 1 render/s while the link is up and the dash shows something else. Acceptable on a ride
    (`BikeLinkService` already holds the location FGS). Optional later: close the renderer after N min hidden.
  - Check: switch Map ↔ Music on the bike, time to first good frame (target < 0.5 s); battery over a 30 min ride.
- ~~**N2. Cache style.json.**~~ ✅ (1-day max-age; check the 115 KB request is gone on the phone) It has an ETag but is fetched in full every time (115 KB). Stamp a short max-age
  (e.g. 1 day) in `MapTileCache`'s interceptor for `/maps/styles/` or confirm a 304 is served. Helps cold start
  and the night/day `setStyle` switch.
- ~~**N3. No blank first frame.**~~ ✅ (frames wait for the first full render, 3 s fallback after style load) `pump()` sends the first ImageReader frame even before the style/tiles load
  (empty map). Skip frames until `onDidFinishRenderingMap(fully)` or the style is loaded; the dash keeps the
  previous picture meanwhile. Mostly matters for the first stream after connect once N1 lands.
- ~~**N4. Start camera on the freshest fix.**~~ ✅ partly (first fix jumps) `location.current()` can be a stale last-known fix (or wait ≤ 2 s for
  a fresh one). With N1 this only affects the first stream; measure before changing.

## Phase 1b — Fast rerouting (field report 2026-10-18) — ✅ N18–N21 + N24 built 2026-10-18, **needs a ride to verify**

Built: N18 (logcat only: `adb logcat -s Navigation` shows `reroute: confirmed X ms after leaving the line … request Y ms` or `rejoined a known route`; no debug-screen panel yet), N19 (heading 50° / 12 m / 2 fixes / ≥ 2 m/s, accuracy-aware; `NavigationEngine`), N20 (rejoin a preview route within 20 m and 45°), N21 (`ReroutePacer`: 0 → 3 → 6 → 10 s, failure retry 2 s, ≤ 6/min), N24 (dash turn updated in the same fix; dash route line all grey while rerouting; msg 15 skipped — StreetCross never sends it, unknown what the dash shows). Tests in `NavigationTest` (turn away, drift, slow/inaccurate, straight past a corner, pacer). Not built: N22, N23, N25 — they wait for N18 numbers from a ride.

On the ride: take a few wrong turns in alleys, then read the log; watch for false reroutes on curvy roads and roundabouts (heading rule) and note the VietMap request count.

**Symptom.** On the bike, a wrong turn takes up to ~10 s before the new route shows. Too slow in alley-dense
areas, where the next junction comes before the new route does.

**Phase 2 doesn't fix this.** Phase 2 is about map tiles and having no network. This delay comes from how a wrong
turn is detected and how often the app may reroute.

**Where the time goes** (from the code; measure with N18 to confirm):

| Step | Code | Time |
|---|---|---|
| Ride until > 30 m from the route line | `NavigationEngine.offRouteMeters = 30.0` | ~5 s at 20 km/h (5.5 m/s); longer in a slow alley |
| Stay off the route for 3 s more | `offRouteMs = 3_000` | 3 s |
| GPS fixes come once a second, plus receiver lag | `UPDATE_INTERVAL_MS`, `requestLocationUpdates` | ~1 s |
| Route request phone → Cloudflare Worker → VietMap Route v4 → back | `routes.route(...)` in `NavigationRepository.follow` | ~0.5–1.5 s on mobile data, more if the connection has to be set up again |
| Dash shows it: route line on the next frame; turn panel on the **next GPS fix** | `DashStreamer.drawRoute` / `pushToDash` | ≤ 1 s |
| **Total for one wrong turn** | | **~8–11 s** |
| A second wrong turn within 10 s of a reroute waits for the gap to run out | `REROUTE_MIN_INTERVAL_MS = 10_000` | + up to 10 s |
| A failed request (`IOException`) is only retried after that same gap | same | + 10 s |

**Solutions, in the order to do them:**

- ~~**N18. Measure first.**~~ ✅ (log) Log timestamps per reroute: first fix > 15 m off, off-route confirmed, request sent,
  response, new route on the dash. Show the last reroute's breakdown on the debug screen. One ride shows whether
  detection or the network dominates.
- ~~**N19. Detect a wrong turn sooner (biggest win, free).**~~ ✅ Today it waits for 30 m + 3 s. Add a heading check:
  off-route when moving > ~2 m/s, > 15 m off the line, **and** the GPS bearing differs from the route
  segment's direction by > ~50° for 2 fixes in a row. A real turn onto another street matches that within
  ~2–3 s; GPS drift along the right road doesn't (its bearing still matches). Keep 30 m + 3 s as the fallback
  for a slow or stopped bike. Also use the fix's accuracy (`Location.accuracy`, not in `Fix` yet) so a
  ±25 m fix in a narrow alley doesn't count as off. Pure Kotlin in `NavigationEngine` → unit tests with
  recorded fixes.
- ~~**N20. Rejoin an alternative without the network.**~~ ✅ The preview already fetched alternatives
  (`alternative=true`). If the rider is off the chosen route but snapped onto an alternative within ~20 m and
  heading the same way, switch to it at once: 0 s network, 0 cost. Covers "took the other road the app also
  knew about".
- ~~**N21. Shorter, smarter gap between reroutes.**~~ ✅ Replace the fixed 10 s with a backoff: first reroute
  immediately, then 3 s → 6 s → 10 s if they keep coming, back to immediate after 60 s without one. A failed
  request retries after 2 s, not 10. Cap at e.g. 6 reroutes/min so one bad GPS stretch can't burn the VietMap quota.
- **N22. Ask for the route early, use it only if needed.** When the rider first goes > 15 m off (before it's
  confirmed), send the reroute request in the background. If off-route is confirmed, the new route is
  already there (saves the ~1 s network time). If they come back onto the route, throw it away. Costs some
  wasted requests; do it only if N18 shows the network is a big share.
- **N23. Start the new route where the rider will be.** The request leaves from the fix position, but the
  rider keeps moving for ~1 s while it's in flight. Send a point ~speed × 1.5 s ahead along the bearing
  (or use `bearing` + the API's heading option, already sent) so the new route doesn't start behind them.
- ~~**N24. Update the dash at once after a reroute.**~~ ✅ (without msg 15) Run `engine.update(fix)` on the new route and
  `pushToDash` right away instead of waiting for the next fix (≤ 1 s saved). While rerouting, tell the dash
  (msg 15 `ROUTE_CALC_PROGRESS`, built in `TftMessages` but not used yet) and grey out the old line, so the
  rider sees that the app noticed.
- **N25. Keep the connection warm.** The OkHttp client is shared (`NetworkModule`); check that HTTP/2
  keep-alive to the Worker survives between reroutes (the first request after a long idle pays a new TLS
  handshake, ~0.3–0.6 s on mobile). Only if N18 shows it.

Target: wrong turn → new route on the dash in **≤ 3–4 s** (N19 + N21 + N24), with N20 often making it instant.
N11 (tuning on real GPS) folds into N19.

## Phase 2 — Offline & data robustness — ✅ N5 + N7 built 2026-10-18, **needs a phone test**

N5: `core/map/OfflineMaps.prefetch`, called on start and after each reroute. VietMap tiles stop at z15 and day/night share them (style.json checked 2026-10-18), so it saves z12–15 over ~1 km boxes padded 300 m (`corridorBoxes`, tested), for both styles (style + sprite + glyphs), pixel ratio 1. Keeps the newest 10 regions (5 routes) so glyph ranges (6 fonts) aren't re-downloaded every trip. Logs `OfflineMaps: route map saved: N resources, K KB`.
N7: `NavState.Active.offline` (off route + last reroute failed without a server answer); the phone banner says "Off route, no signal…". `ReroutePacer`: failures retry 2 → 4 → 8 → 10 s and no longer count toward the 6/min cap. The dash shows nothing extra (no text channel).

Phone test: start a trip, `adb logcat -s OfflineMaps` → note resources/KB for a ~10 km route (first trip includes glyphs); then airplane mode + GPS replay along the route → dash/debug preview still draws; leave the route → "no signal" banner; airplane off → new route within ~10 s.

- ~~**N5. Prefetch tiles along the route.**~~ ✅ On `Navigator.start`, download an `OfflineManager` region around the
  route corridor (zoom 14.5–17, the auto-zoom range) so guidance survives dead mobile zones. Watch the VietMap
  tile quota and the ambient cache size.
- ~~**N6. Optional "download my area" in Settings.**~~ ✅ built 2026-10-18, needs a phone test. Settings → Map → "Offline map of my area": saves a 30 × 30 km square around the current position (`OfflineMaps.saveArea`, z12–15, day + night; ~860 tiles ≈ 35 VietMap transactions, size unknown until tried), shows progress then size; tap re-centres, the bin deletes. Kept apart from route regions (metadata `area`), never pruned. Resumes after a restart; a complete area isn't re-activated (that would revalidate every tile). Test: save at home on Wi-Fi, note the size, airplane mode, ride/replay inside the square → dash preview draws.
- ~~**N7. Route request failures offline.**~~ ✅ Reroute when there is no network: keep guiding on the old route,
  show "offline" instead of failing; retry when back.

## Phase 3 — Guidance quality (needs rides)

- **N8. Ride the new Navigate screen end to end** (preview → start → reroute → arrive) with the dash. Not done yet.
- **N9. Confirm on the bike:** turn-icon codes, distance text, ETA, message pacing next to map frames, that the
  dash's own panel doesn't cover the route with imageType 0.
- **N10. Reroute offers alternatives** (today reroutes ask for one route) — only if riders want to choose mid-ride;
  likely not.
- **N11. Tune off-route detection** (30 m for 3 s, reroute ≥ 10 s apart) against real GPS on the bike: false
  reroutes in dense streets/flyovers? → now part of N19/N21.
- **N12. Voice prompts** (Android TTS, vi + en) through the helmet headset. Not built.
- **N13. Speed limit / cameras:** blocked — VietMap has no API for them (probed 2026-10-17). Revisit only if a data
  source appears.

## Phase 3b — Saved places & main-road routing (requested 2026-10-18) — ✅ built 2026-10-18, **needs a ride to verify** N27

- **N26. Home / Work toggle on the place card**, like the bookmark: tapping Home on the current Home clears it.
  `PlacesRepository.setHome/setWork(SavedPlace?)` (null removes the key); VM `SaveAsHome/SaveAsWork` →
  `ToggleHome/ToggleWork`; a11y labels `navigate_remove_home` / `navigate_remove_work` (en + vi). One Home / one
  Work slot: setting another place still replaces it. Clearing only from the card (not the Saved list).
- **N27. Prefer main roads, always on** (bigger bikes, e.g. XMAX 300: VietMap's motorcycle profile threads
  through small hẻm/ngõ to save seconds). Route v4 has no road-class knob, only `vehicle` and `avoid`.

  **Probe 2026-10-18** (direct Route v4, 14 trips HCM + HN; alley share = distance on streets named
  Hẻm/Ngõ/Ngách/Kiệt or unnamed):

  | Approach | Alleys | Verdict |
  |---|---|---|
  | `motorcycle` (today) | 1–15 % | the complaint |
  | `motorcycle` + `alternative=true`, pick the least-alley path | path 0 already least in 4/5 trips; best gain 15 → 11 % | too weak alone |
  | `car` | 0–5 % | uses expressways (Pháp Vân–Cầu Giẽ, HCM–Long Thành–Dầu Giây) |
  | `car` + `avoid=motorway` | 0–5 %, no expressways | **unsafe**: still uses elevated Vành Đai 3 (Mai Dịch → Nam Thăng Long, motorbikes banned); car-only bans make odd detours (Vĩnh Tuy → Ngã Tư Sở 15.5 km vs 6.5 km); ETAs at car speed (Phủ Lý 73 vs 97 min) |
  | hybrid: `car`+`avoid=motorway` geometry, sampled every 1.5 km → `motorcycle` route through those vias | Bình Thạnh→Q10 15 → 1 %; Ba Đình→HBT 1 → 0 %; Q4→Q11, Mỹ Đình→Linh Đàm unchanged; Đống Đa→Cầu Giấy 11 → 15 % (worse) | legal by construction (motorbike profile), motorbike ETA; gains inconsistent |

  `avoid=motorway` is honoured (an unknown `avoid` value is ignored, `tunnel` isn't a value). The motorcycle
  profile itself uses Hầm Thủ Thiêm, so that tunnel is not a car-only problem.

  The hybrid was dropped when built: with ≤ 2 vias (1 transaction) at main-road midpoints, alleys were unchanged
  on all 7 trips — the motorcycle profile still cuts hẻm between vias. But a via on a road closed to motorbikes
  fails the call ("Connection between locations not found": Vành Đai 3 at Mai Dịch, Vĩnh Tuy elevated), so the
  motorcycle profile works as a **legality check** of the car route instead.

  **Built** (`core/navigation/MainRoads.kt`, `VietMapProxyClient`, 6 tests in `MainRoadsTest`):
  1. Proxy: `vehicle` ∈ {`motorcycle`, `car`}, anything else → `motorcycle` (deployed 2026-10-18).
  2. Preview and reroute: `motorcycle` (+ alternatives on preview) in parallel with `car`+`avoid=motorway`. Then
     `motorcycle` through a checkpoint in the middle of every non-alley car stretch ≥ 100 m (max 13 = 4 transactions).
  3. Ride the **car line** (timed at motorbike speed from the check) as route 1 when the check succeeds and
     stays ≤ 1.1 × the car distance, the car route is ≤ 1.25 × the motorcycle one, and it has fewer alleys. Any
     failure / too many checkpoints → today's motorcycle routes. Quick Start takes it over a faster alley route.
  4. No via instructions reach guidance (the check route is never ridden).
  Cost: preview 3–6 transactions (was 1), reroute 3–6 with one extra round trip. Phone check: Đào Tấn → Duy Tân
  previewed main-road 3.8 km/8 min via Cầu Giấy + Trần Thái Tông above the 3.4 km alley-ish fastest.
  On a ride: missed closed roads (short flyovers < 100 m aren't checked), reroute latency, the 1.25 detour cap.

## Phase 4 — Phone app & polish

- **N14. Home tab shows the active trip** (next turn, ETA, Stop).
- **N15. Try a real share from the Google Maps app** (parser is tested, never tried end to end).
- **N16. Dash map visuals:** label density / DPI (160 dpi shortcut in `VietMapDashRenderer`), scale bar length
  behind msg 14 (100 px guess in `DashScale.kt`), map attribution requirements.

## Bugs / field reports

| Date | Report | Status |
|---|---|---|
| 2026-10-18 | Dash map takes ~5 s to show tiles after returning from another dash screen (not navigating) | Fixed in code (N1–N4), not yet tried on the bike |
| 2026-10-18 | Reroute after a wrong turn takes up to ~10 s; too slow in alleys | Fixed in code (N18–N21, N24), not yet ridden |
