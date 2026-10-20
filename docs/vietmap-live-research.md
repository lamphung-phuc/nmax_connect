# Vietmap Live: how it gets speed limits and traffic cameras

Research date: 2026-10-17. The goal was to find out whether the public VietMap API can give us **max speed** and **traffic cameras**, our two most-requested features.

## Summary

- The public VietMap API (`maps.vietmap.vn`) exposes **no speed-limit and no speed-camera data**.
- Vietmap Live gets both from a **private, paid, offline data pack** ("Omni", locked to the "Ultra" tier). It is served by Live's own account-bound backend, so we can't use it.
- Ways forward: ask VietMap for data access, use Google Roads Speed Limits, or build our own camera list (see [Options](#options)).

## Target

| | |
|---|---|
| Package | `vn.vietmap.live` (pulled from the phone with REA / adb) |
| Base APK SHA-256 | `46fb18b200d6265d6585de6977ff4f9fbc1e8c71757c4347020331d843783d63` |
| Stack | Flutter (Dart AOT `libapp.so`), Mapbox Maps SDK, ONNX runtime, native `libmmsc.so` / `libmmtl.so` |
| Method | String extraction from `libapp.so` only. Dart AOT was not decompiled. |

## How Vietmap Live does it

### Speed limits and camera alerts: the "Omni" offline engine
- Data pack per vehicle type (`omniEngineCar`, `omniEngineTruck`, `omniEngineCoach`), downloaded from `https://api.vietmap.live/production/p3/v2/o/omni` (config at `.../omni_config`).
- Holds `quadtreeCameras`, a spatial index of cameras that is queried on the device.
- Map matching runs on the device (`MapMatchingEvent.updateCoordinate`, `RoadEventState(currentSpeedLimit…)`, `nextSpeedLimit`), probably inside `libmmsc.so` / `libmmtl.so`.
- Locked to the paid **Ultra** tier (`ActivateFailure.ineligibleForOmni`).

**Alert taxonomy** (`AlertEvent.*`):
- **Speed and cameras:** `speedLimit`, `speedLimitCamera`, `trafficCamera`, `penaltyCamera`, `cameraAI`.
- **Camera types:** `RedLightCamera`, `SpeedCamera`, `RegularSpeedCheckpoint`.
- **Road rules:** `trafficLight`, the `noEntry` / `noLeftTurn` / `noUTurn*` / `*TurnOnly` families, `stopLane`, `laneConnectivity`.
- **Places:** `toll`, `tunnel`, `railway`, `residentalAreaIn/Out`, `noPassingIn/Out`, `highwayRestArea`, `slowDown`.

**Speed-limit fields:**
- **Per lane:** `laneMaxSpeed`, `positiveMaxSpeed` / `negativeMaxSpeed` (one per travel direction).
- **Time-based:** `speedByTime`, `maxSpeedLimitByTime`.
- **Over-speed warning:** `overSpeedWarningType`, `adjustOverspeedWarning`.
- **Speed-limit sign:** `NavigationSpeedLimitSign`.

### Private backend: `api.vietmap.live/production/v20/*`
- Login is by phone number, device or IMEI (`/v1/user/LoginByPhone`, `/v20/user/RefreshTokens`, …).
- Camera and speed endpoints:
  - `/v20/place/GetCameras`, `/v20/place/GetCameraById`: live CCTV street cameras (`CCTVDetailScreen`), not speed cameras.
  - `/v20/tracking-report/OverSpeedReport`: post-trip over-speed report.
  - `/v20/traffic-violation/*`: lookup of fines issued from camera footage (phạt nguội).
- This backend is **not a developer API**. Using it would mean impersonating Live's client (ToS issue, user accounts, paid tier), so it's not an option.
- Live ships a VietMap public key for its raster tiles (`maps.vietmap.vn/tm/...`, `/api/dm/...`). **Don't reuse it.** Use our own key through `proxy/vietmap/`.

## What the public VietMap API offers

Probed Route v4 through our proxy (`vehicle=motorcycle`, a short HCMC route):

| `annotations=` | Result |
|---|---|
| `maxspeed` | `ERROR_UNKNOWN`: "Cannot find the path details: maxspeed (Not yet ported?)" (GraphHopper error, so annotation names reach GraphHopper `details`) |
| `max_speed`, `speed_limit`, `average_speed`, `speed` | silently dropped, no data |
| `congestion` | `annotations.congestion`, `annotations.congestion_distance`, `annotations.history_speed` |
| `toll` | `toll_cost`, `tolls[]` |

The [overview page](https://maps.vietmap.vn/docs/map-api/overview/) claims a dataset of 200,000+ traffic signs, "Traffic signs, speed limits, and speed-camera locations — surfaced through routing and navigation". No documented endpoint exposes it.

## Options

1. **Ask VietMap (recommended).** They have the data. Ask for an enterprise speed-limit/camera annotation or layer: maps.info@vietmap.vn, or the Zalo OA "Vietmap Solutions".
2. **Google Roads API Speed Limits.** Works with our other map provider, but Google restricts access to approved customers, and coverage and accuracy in Vietnam are unverified.
3. **Our own camera dataset.** Crowdsourced or curated points; alert on distance and heading. No dependency on VietMap, but we own the upkeep.

### Public VietMap features we can add now
- Live traffic tile overlay (the "Traffic Layer" product, `maps.vietmap.vn/tm/{z}/{x}/{y}`).
- Congestion colouring on the route (`annotations=congestion`).
- Toll cost and stations on the route (`annotations=toll`).
- Nearby gas stations and POIs through search categories.
- Isochrone ("where can I reach in N minutes").

## Open questions
- Format of the Omni data pack and how cameras are matched to the rider (needs native or Dart AOT decompilation).
- Whether VietMap will license the traffic-sign dataset, and at what tier or cost.

## Sources
- [VietMap Map API overview](https://maps.vietmap.vn/docs/map-api/overview/)
- [VietMap Route v4](https://maps.vietmap.vn/docs/map-api/route-version/route-v4/)
- [VietMap usage limits & contacts](https://maps.vietmap.vn/docs/map-api/console/usage-limitation/)
- [Google Roads API Speed Limits](https://developers.google.com/maps/documentation/roads/speed-limits)
