# NmaxConnect — status & roadmap

Living doc: update it when a phase lands. Last updated 2026-10-20 (v1.6.13).
Specs: `docs/bike-protocol.md` (bike link) · `docs/setup-keys.md` (keys, Cloudflare) · `docs/vietmap-agent-docs/` + `vietmap` skill.

## Done

| Area | What | Verified |
|---|---|---|
| Research | Garmin StreetCross + Y-Connect reverse-engineering (`apk/*.md`), protocol spec, setup guide, VietMap docs + skill | – |
| VietMap proxy | `proxy/vietmap/`: Cloudflare Worker holding the API key; whitelists v4 endpoints/params, forces motorcycle, requires `X-App-Token` | 4 tests (`node --test proxy/vietmap/test/index.test.mjs`); deployed (`*.nmaxconnect.workers.dev`), live-checked |
| Project | Hilt via KSP 2.3.12, package `com.lampp.nmaxconnect`, `NmaxConnectApplication`, injected dispatchers, theme tokens | builds |
| TFT protocol | `core/protocol/tft`: framing, CRC-32/MPEG-2, resyncing decoder, phone→TFT builders, validated TFT→phone events, display model from part number | 12 tests (spec vectors) |
| Bluetooth link | `core/bike`: `TftSession` (handshake, initial state, one-frame-in-flight ACK), `TftConnector` (listen + dial race, MAC whitelist), `BikeLinkRepository` (reconnect backoff), `BikeLinkService` (FGS connectedDevice) | 5 tests vs fake TFT; **never run on a bike** |
| Debug screen | `feature/debug`: permissions, bike picker, start/stop, live state, source picker (Off / Pattern / VietMap), frame preview, fps/ACK stats, event log | emulator smoke test |
| App shell + screens | Spec `docs/ui-spec.md`. Shared components in `ui/components` (AppScaffold, grouped lists, status chip, empty state, info card, permission helper). Onboarding (welcome → permissions → choose bike) stored in DataStore. Tabs Home (connect/disconnect, link status, conflict tip after 3 failures), Navigate (placeholder), Bike (live speed from TFT msg 65), Settings (bike picker, map provider, debug, version). Debug screen moved under Settings. en + vi strings | emulator walkthrough (fresh install, all tabs, dialog, vi locale) |
| Destination search | `core/vietmap` (`PlaceSearchApi` → `VietMapProxyClient`, cancellable OkHttp calls through the Worker), `core/places` (Home/Work/recents in DataStore, coordinates only), `core/location` (cached fix, else one fresh fix ≤ 2 s, for `focus`). Navigate tab: debounced Autocomplete v4 (≥ 2 chars, 250 ms) → Place v4 once on pick → place card (save as Home/Work; Navigate button waits for step 3) | 8 tests (documented JSON, URL building, recents, VM debounce/cancel/resolve); **proxy deployed and verified live** (401 without token, 200 with); emulator: live search + pick + save Home worked |
| Routing + guidance | `core/navigation`: Route v4 client (via proxy, `points_encoded=false`, heading on reroute), `NavigationEngine` (snap to line, current/next turn, remaining distance/time, off-route > 30 m for > 3 s, arrival), `DashNavMapper` (sign→TFT icon, dash distance, ETA, road; sends only changes; resends on a new TFT session), `NavigationRepository` (preview → start → follow GPS → reroute ≥ 10 s apart → arrive), `NavigationService` (FGS type location). Navigate tab: route preview, live guidance card, arrived/failed states | 7 tests on a real Route v4 fixture; emulator GPS-replay ride: preview, guidance, reroute after a 120 m detour, arrival, GPS + service released |
| VietMap renderer | `core/map/VietMapDashRenderer`: private VirtualDisplay → Presentation → VietMap MapView → ImageReader → cached JPEG at dash size; day style `tm`. `VIETMAP_TILE_KEY` from `local.properties` → `BuildConfig`. SDK 4.1.1 (16 KB-aligned). The debug screen orbits Bến Thành as a demo camera | emulator: renders, updates while orbiting, source switching is clean; **not yet on the dash** |
| CCU link | `core/protocol/ccu` + `core/bike/ccu` (see `docs/ccu-roadmap.md`): Y-Connect-traced SPP → BLE → auth → 100 ms vehicle-data subscription; Bike tab shows ODO, battery, temperatures, rpm, instant fuel economy (not bike-tested) and both link chips. Phone services on the dash: chat/call notifications (303), now playing + music buttons (278/279), phone status (316), weather (3/4/5, Open-Meteo), incoming calls + answer/hang-up (280/281/308) | 98 unit tests; **link + vehicle data verified on the bike 2026-10-16**; phone services not yet bike-tested |
| Map-first Navigate | `feature/navigate`: Google Maps-style screen on an interactive VietMap (`NavigateMap`: lifecycle-forwarded MapView, puck, pin, camera fits all routes / follows while guiding). Route v4 `alternative=true` + `annotations=congestion` (proxy already whitelisted both): `NavState.Preview(routes, selected)`, `Navigator.selectRoute`, picked route drawn with traffic colors, alternatives tappable on the map. Long-press pin via Reverse v4. Reroutes still ask for one route | VM + route tests (live alternatives fixture `route_alternatives.json`, traffic share, pins, favorites, From/To, swap, choose on map); owner's phone: map, search, favorites, alternatives, custom start, swap, tab switches, back handling. Guidance on the new screen not yet ridden |
| Release | **v1.6.13** (versionCode 24; v1.6.12 = 23, v1.6.11 = 22, v1.6.10 = 21, v1.6.9 = 20, v1.6.8 = 19, v1.6.7 = 18, v1.6.6 = 17, v1.6.5 = 16, v1.6.4 = 15, v1.6.3 = 14, v1.6.2 = 13, v1.6.1 = 12, v1.6.0 = 11, v1.5.4 = 10, v1.4.0 = 5, v1.3.0 = 4, v1.2.0 = 3, v1.1.0 = 2, v1.0.0 = 1), `CHANGELOG.md`. Release build signed with the debug key and R8 off (both shortcuts, see `app/build.gradle.kts`): partial R8 optimization crashed at launch (`IllegalAccessError`, repackaged `kotlin.sequences` class) | release APK installed on the owner's phone: launches, all four tabs render, no crash |
| Map streaming | `core/map/DashStreamer`, run by `BikeLinkService` (FGS connectedDevice + location when permitted): while the dash requests NAVI_IMAGE, renders VietMap with a track-up GPS camera (eased 1 s, zoom from speed 17 → 14.5), draws the preview/active route line (GeoJSON line layer, redrawn after `setStyle`), sends ACK-paced frames (unchanged map resent ≤ 1/s), imageType 0 while guiding else 3. Day/night from sun elevation at the GPS position: switches style `tm`↔`dm` and sends msg 31. Scale label (msg 14) from zoom + latitude (1/2/5 steps of a 100 px bar), sent on change. Dash zoom buttons (51/52) shift the auto-zoom −4…+2 steps. Frames carry a position puck (view centre) and, while guiding, a next-turn panel (arrow, distance, street) since the dash doesn't draw its own on every model; the passed route is grey. The Dash tab (was Bike; gauge icon) picks what is streamed (`DashMockup` preview measured from a head-on bike photo: status bar with clock/map/signal/battery/App/T, frame drawn 7 % wider as the dash shows it, grey triangle zoom buttons, ETA + road bar over the frame bottom; switches in DataStore): turns on/off (stats off: a 184 px turn column, big arrow + distance + street centred left of the zoom buttons, like the dash photo), and while guiding a 184 px bike-stats column under the dash's zoom buttons (turn on top: arrow + distance on one row, street left-aligned under it, ending ~y 70 above the + button at ~y 84; per-stat switches for air/engine temp, battery, km/L pinned above the ETA bar that covers the frame's bottom ~38 px, coloured icons, "--" while the CCU is down); the camera and puck are padded so the map centres right of the column. imageType stays 0 whenever guiding (it switches the dash into its navigation layout). Bike photos show the dash draws no turn column of its own here; the frame is full width under the status and ETA bars. No speed-limit sign: VietMap Route v4 has no speed data. Paused while the debug screen streams | compile + zoom/sun/scale tests; **needs a TFT to exercise** |

| Dash destination menu | `core/bike/tft/DashPlaces` (run by `BikeLinkService`), StreetCross-style: Home/Work entries enabled from the app's saved Home/Work (10/11, resent per session and on change), Go Home/Work (53/54), Favourites list (55[3] → 7 + 98) from the app's favorites, Gas stations (55[4] → 8 + 99) from VietMap POI category 10009 within 5 km; picking an entry (48) previews + starts guidance, Stop route (49) stops it | 3 unit tests (arrow sectors, packets); gas query probed live; **needs a TFT to exercise** |
| Share from Google Maps | `core/places/SharedLocation`: MainActivity (singleTask) takes `ACTION_SEND` text and `geo:` links into `SharedLocationInbox`; the app switches to Navigate, which opens the place card. Coordinates used as-is (Google and VietMap are both WGS-84 in Vietnam). Precision order: place pin `!3d!4d` → `q=/ll=/destination=` / `/maps/search/` / `geo:` → camera `@` only without a name. `maps.app.goo.gl` links expanded by reading Google's redirect headers (≤ 5 hops, Google hosts only, no page download). Name-only links (`?q=<name>&ftid=…`) open VietMap search prefilled instead of guessing a POI | 8 tests (parser formats, VM card + search fallback); **not yet tried with a real share from the Google Maps app** |
| Map tile cache | `core/map/MapTileCache` (from `NmaxConnectApplication`): VietMap tiles/glyphs carry no cache headers, so the SDK re-fetched them; its OkHttp client (`HttpRequestUtil`) now stamps `max-age` 30 d on `/maps/tiles/` (versioned URLs) and `/maps/fonts/`, ambient cache raised 50 → 256 MB. Shared by the Navigate map and the dash renderer | owner's phone: repeat city visit 6 tile requests → 0; after an app restart 0 tiles (style.json still 115 KB per launch) |
| Bike maintenance | `core/maintenance/MaintenanceRepository` (DataStore, JSON like `PlacesRepository`): last CCU ODO (saved by `CcuLinkRepository` once per 100 m), service records for engine oil / gear oil / air filter / CVT belt / spark plug (km interval only; dates were dropped). New **Bike** tab (`feature/bike`, two-wheeler icon) between Navigate and Dash: ODO card, Maintenance group: compact two-line rows (icon, name + km left, progress bar from the last change, or 0 km if never recorded, with the current ODO as a dot); reset button per row (current ODO) with an Undo snackbar; tap → settings dialog (interval, last changed at). A fuel log was built and removed (owner won't use it). Y-Connect does all of this in Yamaha's cloud (`maintereco`, `fuel_history`, `monthly_fuel_consumption_info`, Gigya login), so this is local | `BikeUiStateTest` (due by km/age, unknown ODO, progress clamping); owner's phone: record service, bars (normal + overdue, with a temporary fake ODO) |

## Navigation
Improvements, bugs and next steps for Navigate + the dash map stream: `docs/navigate-roadmap.md`.

## In-app help
`?` button + Quick Share-style tips sheet on Home, Bike, Navigate, Dash and Debug, plus Settings → Help listing all of them; shipped in v1.6.11. Plan and status: `docs/help-roadmap.md`.

## Repo audit
Findings from the 2026-10-18 whole-repo audit (bugs, tests, duplication), A1–A14: `docs/audit-roadmap.md`.

## UX review
Whole-app UX review of 2026-10-19 (first-run flow, wording, motion, small details) and its fix status: `docs/ux-review.md`.
Done on branch `fix/ux-review`: phone map while guiding without the dash, "màn hình xe" wording, connect + auto connect
after onboarding, fewer up-front permissions, bike shown by its CCU ID with an optional nickname, M3 fade-through
timing, Choose bike opens the picker, Settings help button, dark launch window, wordmark splash screen,
Bike tab hints/validation.
Open: Navigate's first-entry blank map, Home/Work "Not set" tappable, place-card icon labels, Directions vs Start,
tab bar while guiding, Dash stat-row indent.

## Pre-public release
Owner's polish list of 2026-10-19 (welcome logo, permission sections, select-on-focus inputs, Navigate header + floating
chips, help Done flow, Dash blank space, Settings bike sub-options, donate + Zalo in About), R1–R10: `docs/release-roadmap.md`.
R1–R9 shipped in v1.6.13, with the graphic help pages; R10 (Zalo) waits for the group link in `ZALO_GROUP_URL`.

## Not built yet
- CCU follow-ups (`docs/ccu-roadmap.md` → "Later"): instant fuel economy (CAN 315/276) built, **needs a ride to verify**; average consumption blocked (288 unit unknown).
- Bike tab follow-ups: "service due" alert on the dash (CCU 303 notification); trip log (distance/time/avg speed from ODO + GPS); service history per item; ODO-less date-only due text.
- A real release keystore and R8 with keep rules (checked on a device) before any public release.

## Blocked
- **Stream tuning** (fps, JPEG quality, 2050 B padding shortcut in `TftSession`): needs one real-bike session with the debug screen.

## Next, in order
1. ~~VietMap renderer~~ ✅ done. Follow-ups after the bike test:
   - label density/DPI (160 dpi shortcut);
   - ~~night style `dm`~~ ✅ (auto by sun elevation);
   - scale bar length behind msg 14 (100 px guess in `DashScale.kt`);
   - ~~follow GPS~~ ✅ (production streamer);
   - attribution requirements.
2. **Real-bike test** with the debug screen (Pattern first, then VietMap). Run it as soon as possible, in parallel with everything else:
   - force-stop Y-Connect/StreetCross/DashConnect first
   - record fps, ACK ms and frame size
   - note whether the padding and crop are OK
3. ~~Routing → dash messages~~ ✅ done. Follow-ups:
   - ~~production map streaming~~ ✅ `DashStreamer`. On the bike: check the dash turn panel doesn't cover the route with imageType 0 (may need a camera padding/offset).
   - Home tab: show the active trip.
   - On the bike: confirm turn-icon codes, distance text, ETA, and message pacing next to map frames.
4. ~~Destination search~~ ✅ done.
5. ~~App shell + real screens~~ ✅ done. Follow-ups:
   - ~~production map streaming~~ ✅ Home's Connect now streams too.
6. ~~Google Maps renderer~~ dropped 2026-10-18: Google Maps Platform billing blocks Vietnamese accounts. VietMap is the only map provider.
7. ~~CCU link~~ ✅ done (phases 0–6 + notifications, music, phone status, weather); released in v1.0.0.

## Open questions to settle on the bike
- Does the TFT accept JPEGs ≤ 2048 B without padding?
- Real fps / ACK latency at 480×240 and quality 80.
- AppVersion 1870 accepted? (1660 is reported to work.)
- Map-scale message (14) fields (current/min/max/show).
