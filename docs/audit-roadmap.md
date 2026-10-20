# Repo audit — roadmap

Whole-repo audit of v1.6.4 (2026-10-18). Load assumed: one rider, one phone; the VietMap proxy shared by a
handful of installs. Order: correct, safe, holds under load, tested, fast, lean. Update the status column when
an item lands.

Overall: healthy — protocol code is tested and careful, no secrets committed. Do A1 and A2 first (one line each).

| # | Item | Group | Status |
|---|---|---|---|
| A1 | Frozen dash map never triggers a reconnect | Should fix | done |
| A2 | Bad traffic span crashes the Navigate map | Should fix | done |
| A3 | Main-road routing slows down rerouting | Should fix | done |
| A4 | Main-road fallback logic has no test | Should fix | done |
| A5 | TFT and CCU link loops are near-copies | Should fix (lean) | done |
| A6 | Template tests left in the project | Nice to have (lean) | done |
| A7 | "Is this Google?" check accepts look-alike hosts | Nice to have | done |
| A8 | Disconnecting during a CCU connect leaks the BLE connection | Should fix | done |
| A9 | CCU scan finds nothing while the phone screen is off | Should fix | done |
| A10 | Closing the dash renderer can crash the capture thread | Should fix | done |
| A11 | "Call volume stream" rule written twice | Nice to have (lean) | done |
| A12 | Bike-test logging ships in release builds | Nice to have (lean) | done |
| A13 | Approximate location breaks the CCU scan on Android 10–11 | Nice to have | done |
| A14 | Bluetooth permission rule lives in three places | Nice to have (lean) | done |
| A15 | All stats switched off leaves an empty column over the dash map | Nice to have | done |

Lean: −125 lines possible (A5, A6, A11, A12, A14).

A1–A7: first pass (2026-10-18). A8–A14: second pass over the first pass's "Not checked" list (2026-10-18). Do A8
and A9 first of the second pass: both stop the CCU from reconnecting with the phone in a pocket. A15: third pass over
the second pass's "Not checked" list (2026-10-18).

## Should fix

### A1. Frozen dash map never triggers a reconnect
`core/map/DashStreamer.kt:229-236`, `core/bike/tft/TftSession.kt:55-66`
- **What:** `pump` sends one map frame and waits for the dash's ACK; `sendFrame` throws after 2 s without one.
- **Problem:** `TftSession` says that error means the link is dead and the owner should reconnect, but `pump`
  only logs and retries. If the dash stops ACKing while the socket stays open, the map stays frozen and the link
  is never rebuilt. The link should drop after 2 s without an ACK.
- **Fix:** in `sendFrame`, `runCatching { input.close() }` right before throwing, so `run()` fails and the
  existing reconnect loop starts a new session. One line.
- **If skipped:** a stuck dash needs a manual disconnect mid-ride. Not seen on the bike yet.

### A2. Bad traffic span crashes the Navigate map
`feature/navigate/NavigateMap.kt:400-402`
- **What:** jam colouring uses index ranges into the route's points, from VietMap.
- **Problem:** only the end of the range is clamped, not the start. A span starting at point 40 on a 38-point
  route calls `subList(40, 38)` → crash. `Route.trafficShare` already clamps both ends: same data, two rules.
- **Fix:** `span.first.coerceIn(0, chosen.geometry.size)`.
- **If skipped:** one bad congestion answer crashes the app instead of skipping one coloured segment.

### A3. Main-road routing slows down rerouting
`core/vietmap/VietMapProxyClient.kt:62-66`
- **What:** since v1.6.4 a reroute waits for the car route, then the motorbike check route (the plain motorbike
  route runs in parallel).
- **Problem:** two requests in a row instead of one; the trip loop handles no GPS fixes meanwhile, and a reroute
  costs up to 6 VietMap transactions instead of 1. Works against N18–N21 (fast reroute), worst in alley mazes.
- **Fix:** `withTimeoutOrNull(1_500) { mainRoads(...) } ?: moto.await()` in `route()`.
- **If skipped:** slower reroutes on weak signal. Not measured on a ride yet.

### A4. Main-road fallback logic has no test
`core/vietmap/VietMapProxyClient.kt:62-90`
- **What:** the branches that choose between the main-road and motorbike routes when a request fails.
- **Problem:** `MainRoadsTest` covers only the pure scoring. Breaking the error handling would silently give
  alley routes, or fail routing when only the check fails.
- **Fix:** take the request as a parameter (`suspend (points, params) -> List<Route>`) and add one test with a
  fake that fails the check call and expects the motorbike route.
- **If skipped:** the newest, least-ridden logic can break unnoticed.

### A5. TFT and CCU link loops are near-copies
`core/bike/BikeLinkRepository.kt:87-151`, `core/bike/ccu/CcuLinkRepository.kt:79-149`
- **What:** both run connect → session → close socket on cancel (unblocks a stuck read) → mirror state → back
  off and retry.
- **Problem:** ~60 copied lines, including the close-on-cancel trick and the backoff; a fix to one (like A1) must
  be remembered for the other.
- **Fix:** one shared `superviseLink(connect, serve, onState)` in `core/bike` that keeps the loop and
  close-on-cancel; each repository passes its own connect/serve steps.
- **If skipped:** the two links drift apart.

### A8. Disconnecting during a CCU connect leaks the BLE connection
`core/bike/ccu/CcuConnector.kt:80-89`
- **What:** `connect()` opens a GATT connection (Android's handle for one BLE link) and waits up to 10 s for the
  CCU's services.
- **Problem:** it's closed only on timeout. Disconnect (or a link restart) during those 10 s cancels the wait and
  `gatt.close()` never runs. Android allows few GATT clients per app, and leaked ones tend to make later connects
  fail with "status 133" until Bluetooth is toggled.
- **Fix:** `try { …wait… } catch (e: Throwable) { gatt.close(); throw e }`, so cancellation closes it too.
- **If skipped:** after a few cancelled connects the CCU stops connecting until Bluetooth is toggled.

### A9. CCU scan finds nothing while the phone screen is off
`core/bike/ccu/CcuConnector.kt:92-111`
- **What:** when the saved CCU address fails, the app scans for `YCCU_<id>`.
- **Problem:** `startScan(null, …)` has no filter; since Android 8.1, unfiltered scans get no results with the
  screen off. Phone in pocket + saved address not working → the CCU is never found until the rider wakes the phone.
- **Fix:** one `ScanFilter.Builder().setDeviceName(…)` each for `YCCU_$ccuId` and `YSCCU_$ccuId`; keep the
  existing match in `onScanResult`.
- **If skipped:** in-pocket CCU reconnects fail whenever the saved address doesn't work.

### A10. Closing the dash renderer can crash the capture thread
`core/map/VietMapDashRenderer.kt:218-226` (`copyFrame`), `:242-250` (`close`)
- **What:** each new map image is copied on its own thread; `close()` runs on the main thread when a stream ends
  (bike disconnects, dash model changes, debug stream stops).
- **Problem:** `close()` closes the image reader while `copyFrame` may still read the current image; reading a
  closed image throws on that thread and crashes the app. Short window, hit on every stream end while the map
  moves. Not seen yet.
- **Fix:** in `close()`: `synchronized(lock) { reader.setOnImageAvailableListener(null, null) }`,
  `captureThread.quitSafely()`, `captureThread.join()`, then `reader.close()`.
- **If skipped:** a rare crash when the bike disconnects mid-stream.

## Nice to have

### A6. Template tests left in the project
`app/src/test/.../ExampleUnitTest.kt`, `app/src/androidTest/.../ExampleInstrumentedTest.kt`
- **What / problem:** Android Studio's generated samples (2 + 2 = 4, package name); test nothing, the
  instrumented one needs a device.
- **Fix:** delete both (~40 lines).

### A7. "Is this Google?" check accepts look-alike hosts
`core/places/SharedLocation.kt:183-184`
- **What:** shared `goo.gl` links are expanded by following redirects only while they stay on Google hosts.
- **Problem:** `(^|\.)google\.[a-z.]+$` also matches `google.attacker.com`; an old generic `goo.gl` link
  pointing there makes the phone request that site (headers only, so it leaks little more than the IP).
- **Fix:** an explicit host list: `google.com`, `google.com.vn`, `maps.app.goo.gl`, `goo.gl`.

### A11. "Call volume stream" rule written twice
`core/bike/ccu/DashMusic.kt:130,163`, `core/bike/ccu/DashPhoneStatus.kt:57,78`
- **What / problem:** both pick the Bluetooth headset stream during a headset call, else the call stream, each with
  its own `STREAM_BLUETOOTH_SCO = 6`. Dash volume buttons (DashMusic) and the volume shown on the dash
  (DashPhoneStatus) must agree; changing one copy makes the dash show a level the buttons don't move.
- **Fix:** one `internal fun callStream(audio: AudioManager)` and one constant, used by both (~6 lines less).

### A12. Bike-test logging ships in release builds
`core/bike/ccu/DashMusic.kt:71-77`
- **What / problem:** a "bike-test aid" collector logs every CCU event except the two most frequent. R8 is off, so
  `Log.d` stays in release and fills the log for the whole ride.
- **Fix:** delete it after the bike test, or wrap it in `if (BuildConfig.DEBUG)` until then.

### A13. Approximate location breaks the CCU scan on Android 10–11
`ui/components/Permissions.kt:38,50`, `core/bike/ccu/CcuLinkRepository.kt:151`
- **What / problem:** on API 29–30 a BLE scan returns results only with precise location (`ACCESS_FINE_LOCATION`),
  but the app counts approximate as granted. A rider who picked "approximate" sees everything allowed, yet the CCU
  scan silently finds nothing. Owner's phone (API 37) unaffected.
- **Fix:** on API 29–30 also require `ACCESS_FINE_LOCATION` in `CcuLinkRepository.hasPermissions()` (it then
  reports MissingPermission) and ask for it in onboarding.

### A14. Bluetooth permission rule lives in three places
`ui/components/Permissions.kt:46`, `core/bike/BikeLinkRepository.kt:147`, `core/bike/ccu/CcuLinkRepository.kt:151`
- **What / problem:** three functions decide whether Bluetooth may be used, each with a slightly different list.
  They agree today, but A13 already needs the same change in two of them.
- **Fix:** keep the UI one; both repositories call one shared function in `core/bike` (~15 lines less).

### A15. All stats switched off leaves an empty column over the dash map
`core/map/DashStreamer.kt:203`, `feature/vehicle/DashStreamSection.kt:119`
- **What / problem:** Vehicle → "Bike stats" has a switch per stat. With the main switch on and all four stats off,
  the stream still sends an (empty) stats record, so `DashHud` paints its 184 px dark column with nothing in it and the
  map centre moves right of it. The in-app dash preview shows the same empty column.
- **Fix:** treat "no stats chosen" as stats off, in the stream and the preview.

## Left out (no real cost at this load)
- `InstantFuelEconomy` speed list keeps filling while no injection data arrives.
- Two `OfflineMaps.prefetch` calls can overlap and briefly keep one route region too many.
- CCU SPP events queue unread after BLE is up (the CCU uses SPP only for the bootstrap).
- Seeking inside a song doesn't update the dash position until the track or play state changes (DashMusic).
- Choosing another bike in Settings while connected keeps the old link until the next reconnect.
- `VietMapDashRenderer` composes the overlay on every map frame (up to 60 fps while the map moves) though the dash takes
  ~10; a 480×240 copy plus a few texts, under a millisecond each.
- Back on the Navigate, Dash and Settings tabs does nothing (by design in `NmaxApp`: tabs are not a history).

## Not checked
After the third pass: nothing left on the list. Checked in it: `DashHud`, `DashMockup` (main thread only, redrawn on
change), `TestPattern`, `MapTileCache`, the `NmaxApp` navigation graph, and every screen against the CLAUDE.md UI rules
(`@PreviewLightDark` on each, no card shadows, no hex colours or hard-coded text, `values`/`values-vi` keys match).
How the screens look on the phone and anything against the real bike are still unverified.
