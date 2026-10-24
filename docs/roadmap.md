# NmaxConnect — status & roadmap

Living doc: update it when a phase lands. Last updated 2026-10-24 (v1.6.13 released; v1.6.14 in progress on branch `v.1.6.14`).
Specs: `docs/bike-protocol.md` (bike link) · `docs/ui-spec.md` (screens) · `docs/setup-keys.md` (keys, Cloudflare) ·
`docs/vietmap-agent-docs/` + `vietmap` skill. Research: `apk/*.md` (Y-Connect, StreetCross), `docs/vietmap-live-research.md`.
Navigation details and its open items: `docs/navigate-roadmap.md`. In-app help design: `docs/help-roadmap.md`.

## Done

Everything below is verified on the owner's bike unless the Verified column says otherwise.

| Area | What | Verified |
|---|---|---|
| Project | Hilt via KSP 2.3.12, package `com.lampp.nmaxconnect`, `NmaxConnectApplication`, injected dispatchers, theme tokens | builds |
| TFT protocol | `core/protocol/tft`: framing, CRC-32/MPEG-2, resyncing decoder, phone→TFT builders, validated TFT→phone events, display model from part number | 12 tests (spec vectors) |
| Bluetooth link | `core/bike`: `TftSession` (handshake, initial state, one-frame-in-flight ACK), `TftConnector` (listen + dial race, MAC whitelist), `BikeLinkRepository` (reconnect backoff), `BikeLinkService` (FGS connectedDevice) | 5 tests vs fake TFT; bike |
| CCU link | `core/protocol/ccu` + `core/bike/ccu`: Y-Connect-traced SPP → BLE → auth → 100 ms vehicle-data subscription (spec: `docs/bike-protocol.md` §6). Bike tab: ODO, battery, temperatures, rpm, instant fuel economy (CAN 315/276), both link chips. Phone services on the dash: chat/call notifications (303), now playing + music buttons (278/279), phone status (316), weather (3/4/5, Open-Meteo), incoming calls + answer/hang-up (280/281/308) | 98 unit tests; bike (link + vehicle data 2026-10-16, phone services and fuel economy later) |
| VietMap proxy | `proxy/vietmap/`: Cloudflare Worker holding every VietMap key (the app ships none); whitelists v4 endpoints/params, forces motorcycle, requires `X-App-Token`. Key rotation: API + tile key lists in KV `keys` (`proxy/vietmap/README.md`), rejected keys (401/403 → VN midnight, 429 → 5 min) parked in KV `dead`. Map: the SDK loads `{proxy}/maps/{styles,tiles,fonts}/…`; the Worker adds a tile key, scrubs keys from style.json, stamps max-age, edge-caches | 12 tests (`node --test proxy/vietmap/test/index.test.mjs`); deployed (`*.nmaxconnect.workers.dev`), 3 API + 3 tile keys each checked live (200) |
| App shell + screens | Spec `docs/ui-spec.md`; shared components in `ui/components`. Onboarding (welcome → required/optional permissions → choose bike, then connect + auto connect). Tabs Home, Bike, Navigate, Dash, Settings (Debug screen under Settings). Splash with the wordmark, dark launch window, M3 fade-through between tabs. en + vi strings | owner's phone |
| Destination search | `core/vietmap` (`PlaceSearchApi` → `VietMapProxyClient`, cancellable OkHttp calls through the Worker), `core/places` (Home/Work/favorites/recents in DataStore, coordinates only), `core/location`. Navigate: debounced Autocomplete v4 → Place v4 on pick → place card | 8 tests; owner's phone |
| Routing + guidance | `core/navigation`: Route v4 client (via proxy, heading on reroute), `NavigationEngine` (snap, turns, remaining distance/time, heading-aware off-route detection), `ReroutePacer`, rejoin a known alternative offline, main-road routing (`MainRoads`, N27), `DashNavMapper` (turn icon, distance, ETA, road to the dash), `NavigationRepository`, `NavigationService` (FGS location) | `NavigationTest`, `MainRoadsTest`; bike |
| Map-first Navigate | `feature/navigate`: Google Maps-style screen on an interactive VietMap: alternatives with traffic colours, long-press pin (Reverse v4), From/To + swap, floating Home/Work/Favorites chips, phone map follows the rider while guiding without the dash | VM + route tests; owner's phone; bike |
| VietMap renderer | `core/map/VietMapDashRenderer`: private VirtualDisplay → Presentation → VietMap MapView → ImageReader → cached JPEG at dash size; styles through the proxy (`VietMapStyle.url`). SDK 4.1.1 (16 KB-aligned) | bike |
| Map streaming | `core/map/DashStreamer`, run by `BikeLinkService`: renders VietMap with a track-up GPS camera while the dash shows the map, ACK-paced frames, renderer kept warm across dash screen switches (N1–N4), route line, imageType 0 while guiding else 3, day/night style + msg 31 from sun elevation, scale label (msg 14), dash zoom buttons (51/52). Frames carry a position puck and, while guiding, a turn panel and an optional bike-stats column (switches on the Dash tab, `DashMockup` preview). No speed-limit sign: VietMap has no speed data | compile + zoom/sun/scale tests; bike |
| Dash destination menu | `core/bike/tft/DashPlaces`, StreetCross-style: Home/Work (10/11, 53/54), Favourites (55[3] → 7 + 98), Gas stations within 5 km (55[4] → 8 + 99, VietMap POI 10009), pick (48) previews + starts guidance, Stop route (49) | 3 unit tests; bike |
| Offline maps | `core/map/OfflineMaps`: route corridor prefetched on start and after each reroute (z12–15, day + night, newest 10 regions kept); Settings → "Offline map of my area" (30 × 30 km). Offline reroute keeps guiding on the old route with a "no signal" banner | `RouteCorridorTest`; bike |
| Map tile cache | `core/map/MapTileCache`: SDK OkHttp client sends the app token to the Worker, 20 requests per host, 256 MB ambient cache; wipes the pre-1.6.14 SDK database once (its URLs held the old key) | owner's phone: repeat city visit 0 tile requests; no key left on the phone (checked 2026-10-24) |
| Share from Google Maps | `core/places/SharedLocation`: `ACTION_SEND` text and `geo:` links open the place card; `maps.app.goo.gl` expanded via Google redirects only; name-only links open search prefilled | 8 tests; **not yet tried with a real share** (N15) |
| Bike maintenance | `core/maintenance/MaintenanceRepository` + Bike tab: ODO card, compact maintenance rows (km interval, progress bar, reset with Undo, settings dialog with validation). Local only (Y-Connect keeps this in Yamaha's cloud) | `BikeUiStateTest`; owner's phone |
| In-app help | `?` + Quick Share-style tips sheet on Home, Bike, Navigate, Dash and Debug; Settings → Help lists all of them; graphic pages with numbered markers (`docs/help-roadmap.md`) | owner's phone (en + vi) |
| Repo audit | 2026-10-18 whole-repo audit, A1–A15 (frozen-map reconnect, traffic-span crash, reroute latency, shared link loop, BLE leak, screen-off CCU scan, renderer close race, …): all fixed and released (`CHANGELOG.md` 1.6.5) | tests + bike |
| UX review + pre-release polish | 2026-10-19 UX review and the owner's polish list R1–R9: Navigate guiding without the dash, "Màn hình xe" wording, permission split, CCU ID + nickname, tab motion, select-on-focus inputs, help Done flow, Settings bike sub-options, donate QR | owner's phone; released in 1.6.12–1.6.13 |
| Release | **v1.6.13** (versionCode 24), `CHANGELOG.md`. Release build signed with the debug key and R8 off (both shortcuts, see `app/build.gradle.kts`): partial R8 optimization crashed at launch (`IllegalAccessError`, repackaged `kotlin.sequences` class) | release APK on the owner's phone |

## Open

### Before a wide public release
- Real release keystore and R8 with keep rules, checked on a device.
- Contact the author row (R10): opens a Messenger chat (`AUTHOR_CONTACT_URL` in `feature/settings/SettingsScreen.kt`).

### UX (from the 2026-10-19 review)
- Navigate's map is blank for a moment on first entry.
- Navigate: Home/Work "Not set" can't be tapped (should open search to set it).
- Navigate: four unlabeled icons on the place card; long names wrap into a narrow column.
- Navigate: "Chỉ đường" vs "Bắt đầu" unclear.
- Navigate: tab bar stays visible while guiding.
- Dash tab: stat rows only 16 dp indented under "Thông số xe".
- Home: empty space under the cards (N14, active trip on Home, would fill it).

### Features
- Navigation: voice prompts (N12), active trip on Home (N14), a real share from the Google Maps app (N15), dash map
  visuals: label density/DPI, scale bar length, attribution (N16). Faster reroutes N22/N23/N25 only if rides show
  they're still slow. Details: `docs/navigate-roadmap.md`.
- Bike tab: "service due" alert on the dash (CCU 303 notification); trip log (distance/time/avg speed from ODO + GPS);
  service history per item.
- Average fuel consumption: blocked, the unit of CCU 288 is unknown (Y-Connect shows Yamaha cloud numbers).
- Speed limit / cameras: blocked, VietMap has no API for them (`docs/vietmap-live-research.md`).

### Help
- Vietnamese review of the help graphics pass captions (owner).
