# Yamaha TFT + CCU — Connection & Streaming Protocol (implementation spec)

How to connect an Android phone to a Yamaha bike with the Garmin-StreetCross-capable TFT (NMAX 155, XMAX 300, R9…), stream a navigation map rendered from **VietMap**, and read **vehicle data from the CCU** the way Y-Connect does.

## Confidence labels

Every rule carries one of these tags:

| Tag | Source | Trust |
|---|---|---|
| **[OFFICIAL]** | Garmin StreetCross 1.87: Java enums + native `libnaviliteprotocol.so` packer/parser. See `apk/StreetCross-1.87-NaviLite-protocol.md` | Source of truth |
| **[YCONNECT]** | Yamaha Y-Connect 3.9.0: the `motoconsdk` CCU SDK (decompiled Java + protobuf field numbers). See `apk/Y-Connect-3.9.0-CCU-protocol.md` | Source of truth for the CCU |
| **[FIELD]** | Values reported to work on real bikes, not found in the official sources | Works in the field; used only where the official apps weren't traced |
| **[DESIGN]** | My recommendation for this app | Not a protocol fact |

Nothing here has been captured from a real bike yet. Section 9 lists what to verify first.

---

## 1. System overview

The bike has two independent radios/endpoints. Run them as two separate sessions; either one can be up without the other.

```
                    ┌───────────────────────── Phone ─────────────────────────┐
                    │  MapRenderer (VietMap) → JPEG encoder                    │
                    │  RouteEngine (VietMap) → turn/ETA state                  │
                    │                                                          │
   Link A  ◄────────┤  TftSession  (RFCOMM 0x7220, "NaviLite")                 │
   TFT dash         │     handshake → map frames, turn arrows, POI menus       │
                    │                                                          │
   Link B  ◄────────┤  CcuSession  (SPP bootstrap → BLE GATT, "ClusterLink")   │
   CCU              │     auth → time, weather, notifications, vehicle data    │
                    └──────────────────────────────────────────────────────────┘
```

| | Link A: TFT (map display) | Link B: CCU (Y-Connect features) |
|---|---|---|
| Radio | Bluetooth Classic RFCOMM | SPP once, then BLE GATT |
| Byte order | little-endian | **big-endian** |
| Framing | `nAl@` header + CRC-32/MPEG-2 | ctrl: `[cmd u16 BE][data]`; bulk: DDT chunks |
| Who connects | both directions (phone listens **and** dials) | phone dials |
| Spec source | [OFFICIAL] | [YCONNECT] |

**Prerequisite:** the bike is paired in Android Bluetooth settings. On the dash, choose the phone-connection item and pair.
**[FIELD]** The bike accepts only one app at a time per link. Force-stop Y-Connect, StreetCross and DashConnect first, or they will hold the session.

---

## 2. Link A — TFT transport [OFFICIAL]

- Service UUID **`00007220-0000-1000-8000-00805F9B34FB`**.
- Run both of these and keep whichever socket connects first:
  - **Server:** `adapter.listenUsingRfcommWithServiceRecord("NaviBluetoothSecure", UUID)`. Accept only the paired bike's MAC (Garmin keeps an accept-whitelist).
  - **Client:** `device.createRfcommSocketToServiceRecord(UUID).connect()`.
- [DESIGN] An insecure listener (`listenUsingInsecureRfcommWithServiceRecord("NaviBluetoothInsecure", …)`) can be added as a fallback, only if secure fails on some phones.
- Use one reader thread. Writes must be serialized (one `synchronized` writer), because frames are large.

### 2.1 Packet format [OFFICIAL]

```
off  size  field
0    4     magic           6E 41 6C 40   ("nAl@")
4    1     version         01
5    1     frameType       see 2.2
6    1     serviceType     see §3
7    4     payloadSize     u32 LE
11   1     payloadDataType 0 = VALUE, 1 = POINTER
           ── header = 12 bytes ──
12   4     crc32           u32 LE
16   N     payload
```
- **CRC-32/MPEG-2**: poly `0x04C11DB7`, init `0xFFFFFFFF`, MSB-first, no reflection, no final XOR. It is computed over **header[0..12) and then the payload**; the CRC field itself is excluded. Check value: `crc("123456789") = 0x0376E6E7`.
- **VALUE** messages (`payloadDataType = 0`) carry exactly **2 bytes**: int16 LE. Booleans are `[v, 00]`. Never send 1 byte here.
- **POINTER** messages (`payloadDataType = 1`) carry the structured payload from §3.
- Strings: ASCII only. Strip Vietnamese diacritics (`đ→d`, NFD with marks removed) before sending. Max lengths: 144 generic, 64 road name, 112 TBT description, 144 POI name.
- Receiver: scan for the magic, read 12 bytes, read the CRC + payload, verify the CRC, and drop the packet on any mismatch. Several packets can arrive in one read (Garmin uses `unpackMultiple`).

### 2.2 Frame type is fixed by direction [OFFICIAL]
| frameType | Direction | Services |
|---|---|---|
| **6** (MCU_UPDATE) | phone → TFT | everything the phone sends |
| **1** (MOBILE_REQUEST) | TFT → phone | 48-56 |
| **3** (MOBILE_UPDATE) | TFT → phone | 65, 66, 69, 70, 80, 82, 83 |

### 2.3 Test vectors (CRC verified)
```
ESN_ACK(81)=1          6e 41 6c 40 01 06 51 02 00 00 00 00 1a 4d 1c ec 01 00
AUTH_REQUEST(33) 1870  6e 41 6c 40 01 06 21 08 00 00 00 01 6f 6d 5a e2 4e 07 00 01 00 00 00 00
NAV_STATUS(2)=0        6e 41 6c 40 01 06 02 02 00 00 00 00 11 ea df c1 00 00
DAY_NIGHT(31)=DAY      6e 41 6c 40 01 06 1f 02 00 00 00 00 6d e3 75 5b 01 00
← IMAGE_ACK(80) seq=5  6e 41 6c 40 01 03 50 02 00 00 00 00 5e 95 d8 c5 05 00
← START_CONTENT(55)=1  6e 41 6c 40 01 01 37 02 00 00 00 00 2e 00 08 07 01 00
```

---

## 3. Link A — message catalog [OFFICIAL]

`V` = VALUE (2 B int16 LE), `P` = POINTER. All multi-byte fields are LE.

### 3.1 Phone → TFT
| # | Name | T | Payload |
|---|---|---|---|
| 0 | IMAGE_FRAME_UPDATE | P | `[imageType u8][seq i16][JPEG]`. imageType: **0** = NAVIGATION (map with the dash's turn panel visible), **3** = NAVIGATION_EXPANDED (full map), 1 = JCV junction view, 2 = throughput test, 4 = other |
| 1 | ETA_UPDATE | P | `[hour*60+minute i32]` |
| 2 | NAVIGATION_STATUS_UPDATE | V | `isNavigating` |
| 3 | CUR_ROAD_NAME_UPDATE | P | ASCII road name ≤ 64 |
| 4 | NEXT_TURN_DIST_UPDATE | P | `[turnIcon u8][dist f32][unitLen u8][roadLen u8][unit][nextRoad]` |
| 5 / 97 / 6 | TBT list header / item / active index | P/P/V | header `[count i16][hasMore u8]`; item (index, icon, dist, unit, text ≤ 112) |
| 7 / 8 | FAV / GAS POI list header | P | `[count i16][hasMore u8]` |
| 98 / 99 | FAV / GAS POI item | P | `[itemIdx i16][listIdx i16][dir u8][nameLen u8][unitLen u8][dist f32][unit][name]`. dir: 1 front, 2 front-right, 3 right, 4 back-right, 5 back, 6 back-left, 7 left, 8 front-left |
| 9 | NAVI_EVENT_TEXT_UPDATE | P | text + type (0 traffic, 1 speed, 2 camera, 3 border, 4 school, 5 other) |
| 10 / 11 | HOME / OFFICE_SETTING_UPDATE | V | `isSet` (enables Home/Work in the dash menu) |
| 12 | APP_SETTING_UPDATE | V | init-setup-complete (send `1`) |
| 13 | GPS_STATUS_UPDATE | V | `gpsFixed` |
| 14 | MAP_ZOOM_LEVEL_UPDATE | P | `[b0][b1][labelLen i8][b3][label]`; label = map scale text such as `"300 m"`. [FIELD] working value: `07 1A len 00 "300 m"` |
| 15 | ROUTE_CALC_PROGRESS_UPDATE | V | 0..100 (255 = invalid) |
| 16 | BT_THROUGHPUT_TIMEOUT_UPDATE | V | throughput timeout value |
| 17 | SPEED_LIMIT_UPDATE | P | `[limit f32][unitLen u8][unit]`, e.g. `60.0, 4, "km/h"` |
| 18 | VIA_POINT_COUNT_UPDATE | V | count |
| 19 | NAVIGATION_INFO_UPDATE | P | combined turn + remaining distance + lanes (layout not traced; see §9) |
| 20 | IMAGE_STOPPED_UPDATE | V/empty | reply when the dash pauses video |
| 31 | DAY_NIGHT_MODE_UPDATE | V | **1 = day, 2 = night** (0 is rejected) |
| 33 | AUTH_REQUEST | P | `[appVersion i16][00][01][extra u32]` (8 B) |
| 81 | MCU_ESN_UPDATE_ACK | V | `validated` |
| 84 | AUTH_REQUEST_SEC_DATA_ACK | P | key bytes (see 4.1) |

### 3.2 TFT → phone
| # | Name | Payload | Phone must… |
|---|---|---|---|
| 48 | APP_START_ROUTE_REQUEST | `[itemIdx i16][listIdx i16][routeOption u8]` | route to POI `list[listIdx][itemIdx]`; option 1 new route, 2 next stop, 3 last stop |
| 49 | APP_STOP_ROUTE_REQUEST | – | stop navigation |
| 50 | APP_SKIP_NEXT_WAYPOINT_REQUEST | – | skip via point |
| 51 / 52 | APP_MAP_ZOOM_IN / OUT_REQUEST | – | zoom ±1 step, then send 14 |
| 53 / 54 | APP_GO_HOME / GO_OFFICE_REQUEST | V | route to saved Home/Work |
| 55 | APP_START_CONTENT_UPDATE_REQUEST | V contentType | 1 → **start map stream**; 2 → send TBT list; 3 → send favourites (7 + 98…); 4 → send gas stations (8 + 99…); 11 → stream in throughput mode |
| 56 | APP_STOP_CONTENT_UPDATE_REQUEST | V contentType | 1 → stop map stream and reply **20**; 3/4 → list closed |
| 65 | MCU_VEHICLE_SPEED_UPDATE | `[unit u8][speed i16]` (0 km/h, 1 mph) | **bike speedometer value**; use it for the speed overlay. **Not** the speed limit |
| 66 | MCU_ESN_UPDATE | 10 ASCII bytes | start auth (§4) |
| 69 | MCU_SYSINFO_UPDATE | string | log it |
| 70 | MCU_DIALOG_USER_SELECT_UPDATE | `[dialogId u8][button u8]` (0 dismiss, 1 OK, 2 cancel, 3 yes, 4 no) | act on a dialog you sent with 25/32 |
| 80 | IMAGE_FRAME_UPDATE_ACK | V seq | release the next frame |
| 82 | AUTH_REQUEST_ACK | V ok | `1` = authenticated |
| 83 | AUTH_REQUEST_SEC_DATA | XOR-0x0A blob | see 4.1 |

### 3.3 Turn icon codes for cmd 4 [FIELD]
Garmin's turn-image enum wasn't recovered. These values are known to work:

| Code | Maneuver | Code | Maneuver |
|---|---|---|---|
| 8 | straight / continue (default) | 32 | sharp left |
| 6 | slight left / keep left | 33 | sharp right |
| 7 | slight right / keep right | 34 | left (incl. ramp/exit left) |
| 14 | roundabout enter/exit | 35 | right (incl. ramp/exit right) |
| 0 | arrive (ahead) | 36 | U-turn left |
| 1 | arrive on left | 37 | U-turn right |
| 2 | arrive on right | | |

Lane codes for msg 19 [OFFICIAL]: 1 straight, 2/3 45° right/left, 4/5 90° right/left, 6 truncate; +6 = the same lane marked "not valid for route".

---

## 4. Link A — session sequence

### 4.1 Handshake [OFFICIAL]
```
phone                                   TFT
  │◄──────── 66 ESN (10 ASCII) ──────────│  Step 0
  │  validate ESN *                      │
  │───────── 81 ESN_ACK [1,0] ──────────►│  Step 2
  │───────── 33 AUTH_REQUEST ───────────►│  Step 3: [1870][00][01][00000000]
  │◄──────── 83 SEC_DATA ────────────────│  Steps 5-6
  │  d = each byte XOR 0x0A               │
  │  partNo = ascii(d[0..12)), key = d[12..]
  │───────── 84 SEC_DATA_ACK [key] ─────►│  key sent back unchanged
  │◄──────── 82 AUTH_ACK [1,0] ──────────│  authenticated
```
- \* Garmin validates the ESN with Garmin's servers the first time, then caches it in a local DB. [FIELD] Skipping this and always ACKing `1` is accepted by the bike. [DESIGN] Store accepted ESNs per bike MAC and ACK `1`.
- appVersion: Garmin sends **1870**; 1660 is also reported to work in the field. [DESIGN] Use 1870.
- **Screen size from `partNo`** [OFFICIAL]:

| partNo contains | Model | Frame size |
|---|---|---|
| `006-B4160` or `006-B4920` | IMWW23 | **480 × 240** |
| `006-B3952` | IXWW22 | **480 × 234** (Garmin skips next-turn info on this model) |
| other | – | 480 × 234 |

### 4.2 Initial state, right after `82 = 1` [OFFICIAL order + FIELD values]
```
2  NAV_STATUS        [isNavigating,0]
10 HOME_SETTING      [homeSaved,0]
11 OFFICE_SETTING    [workSaved,0]
13 GPS_STATUS        [gpsFix,0]
12 APP_SETTING       [1,0]            init setup complete
31 DAY_NIGHT         [1|2,0]
14 ZOOM_LEVEL        scale label
17 SPEED_LIMIT       0 / unit          optional
3  CUR_ROAD_NAME     " "               optional
```
Then wait for **55 [1]**.

### 4.3 Map stream loop [OFFICIAL messages, FIELD timing]
```
on 55[1]:  streaming = true; seq = 0
loop while streaming:
    bmp  = renderer.capture(W, H)                   // W×H from 4.1
    jpeg = encode(bmp, q)                           // q adaptive, start ~80
    if jpeg.size <= 2048: re-encode q=95; pad with zeros to 2050 B   [FIELD]
    send 0  [navActive ? 0 : 3][seq][jpeg]
    wait for 80 with matching seq   (timeout 2 s → drop link, reconnect)   [FIELD]
    send 16 [rtt/100 ms]            [FIELD; semantics unverified, optional]
    seq = (seq + 1) & 0xFFFF
    sleep to hold target FPS (1..15; lower q/FPS when RTT grows)
on 56[1]:  streaming = false; send 20
```
- Keep **one frame in flight**.
- Skip sending a frame when the JPEG is byte-identical to the last one, but still re-send at least once per second [DESIGN].
- Whenever navigation state changes (not per frame), push nav overlays: **4** next turn, **1** ETA, **3** road name, **17** speed limit, **2** nav status. When nav stops, send `2=0` and switch imageType to 3.

### 4.4 Dash menu (POI lists) [OFFICIAL]
On `55[3]` (favourites) or `55[4]` (gas stations):
1. Send `7`/`8` with `[count][hasMore=0]`.
2. Send `count` × `98`/`99` items, distances from the current GPS fix, at most 30 items [FIELD].
3. Keep that list under an id (`listIdx`). On `48 [item][list][opt]`, start routing to that entry.

Home/Work (`53`/`54`) route to the saved places. Report them as available via `10`/`11`.

Implemented in `core/bike/tft/DashPlaces`: `listIdx` alternates 0/1 so a pick from the previous list still resolves; arrows are relative to the dash map's track-up heading. Gas stations = VietMap Autocomplete v4 `text=xăng&cats=10009&circle_radius=5000` (category `10009` = gas station, probed live 2026-10-17), then one Place v4 per station (≤ 10) for coordinates. Route options 2/3 (via points) are treated as a new route.

### 4.5 Reconnect [FIELD]
- If the TFT drops the socket, return to listen + dial.
- Back off 3 s for the first 3 attempts, then 5–15 s.
- Auto-start when the bike appears: `CompanionDeviceManager` presence plus an `ACL_CONNECTED` receiver, then start a foreground service of type `connectedDevice|location`.

---

## 5. Map rendering [DESIGN]

Provider: **VietMap** only. OpenStreetMap was dropped (decision 2026-10-15); Google Maps Platform was dropped on 2026-10-18 (its billing blocks Vietnamese accounts).

Garmin renders its map into an Android **`Presentation` on a `VirtualDisplay`** sized to the dash (`layout_imww23_map` / `layout_linkcard_map`), then grabs frames. Do the same:

```
VirtualDisplay(W×H, dpi≈160) ← ImageReader(RGBA_8888, maxImages=2)
  └─ Presentation(content = VietMap MapView, overlays drawn in-view)
ImageReader.onImageAvailable → Bitmap → JPEG → §4.3
```
- Camera: follow the GPS bearing (track-up). Tilt 0–45°, zoom from speed (e.g. 17 when slow, 15.5 at 30 km/h, 15 at 50 km/h, 14.5 at 70 km/h). Keep the dash's own turn panel area clear when imageType = 0.
- Day/night: switch the style and send **31**.
- Don't draw a speed limit sign into the image. The dash draws its own from **17**.

| Provider | Renderer | Style / data | Routing | Search | Notes |
|---|---|---|---|---|---|
| **VietMap** | VietMap Android SDK `com.github.vietmap-company:maps-sdk-android` (MapLibre fork; JitPack). `Vietmap.getInstance(context)` before `super.onCreate`, `MapView.getMapAsync`, `setStyle(Style.Builder().fromUri(url))` | Vector styles `https://maps.vietmap.vn/maps/styles/{tm\|lm\|dm\|hm}/style.json?apikey=TILE_KEY`: **day = `tm`** (or `lm`), **night = `dm`**. Traffic: merge sources/layers of `…/styles/tf/style.json` after the base style loads (re-add after every `setStyle`) | **Route v4** `GET /api/route/v4?point=lat,lng&point=lat,lng&vehicle=motorcycle&heading=…` (v3 is legacy) | Autocomplete v4 (debounced) → **Place v4** on selection; Reverse v4 for "what's here" | See the `vietmap` skill and `docs/vietmap-agent-docs/`. Two keys: **tilemap key** (in the app, domain+IP restricted, quota) and **API key** (routing/search), which is held only by the Cloudflare Worker `proxy/vietmap/`. The app calls `{proxy}/route`, `/autocomplete`, `/place`, `/reverse` with `X-App-Token`. |
| Phone mirror (optional) | MediaProjection VirtualDisplay | – | – | – | Needs the user's screen-capture consent on every session. |

**Route → dash mapping:**

| Route field | VietMap Route v4 | Message |
|---|---|---|
| maneuver → icon | `instructions[i].sign` (table below) | 4 `turnIcon` |
| distance to the maneuver (m < 1000, else km with 1 decimal) | remaining distance along `points` to `interval[0]` of the next instruction | 4 `dist` + unit |
| next street | `instructions[i].street_name` | 4 `nextRoad` |
| arrival clock | now + remaining `time` (**ms**) | 1 |
| current street | `street_name` of the active instruction | 3 |
| speed limit | not provided by VietMap Route v4 | 17: skip, or send "no limit" |
| off-route | snap GPS to the polyline; if > ~30 m for > 3 s, re-request with `heading` | refresh 4/1 |

VietMap `points` is Google Polyline precision 5 by default (decode it, or request `points_encoded=false` to get `[lon,lat]` pairs). Distances are in **metres** and times in **milliseconds**. Billing: one route request = `⌊points/5⌋+1` transactions; tiles = 25 per transaction.

**Turn icon mapping → cmd 4** (icon codes from §3.3):

| VietMap `sign` | TFT icon |
|---|---|
| -3 sharp left | 32 |
| -2 left | 34 |
| -1 slight left | 6 |
| 0 continue | 8 |
| 1 slight right | 7 |
| 2 right | 35 |
| 3 sharp right | 33 |
| 4 finish | 0 |
| 5 via point reached | 0 |
| 6 roundabout | `roundabout-left` / `roundabout-right` | 14 |
| text contains "Quay đầu" (no sign code) | `uturn-left` / `uturn-right` | 36 / 37 |

---

## 6. Link B — CCU (Y-Connect features) [YCONNECT]

> Source: Yamaha's `motoconsdk` inside Y-Connect 3.9.0. See `apk/Y-Connect-3.9.0-CCU-protocol.md` (class names, evidence, unknowns). Items tagged **[FIELD]** were not re-traced in Y-Connect. Byte order is **big-endian** throughout. Nothing here has been run against a bike yet.

### 6.1 Bootstrap over SPP [YCONNECT]
1. Find the bike by classic discovery (device name contains the saved CCU id, device type ≠ LE-only), or use the bonded MAC.
2. RFCOMM UUID **depends on the bike's name**: if `name.length < 16` it's an error; if `name[9..16) == "11111BB"` use SPP **`00001101-…`**, otherwise **`00008911-0000-1000-8000-00805f9b34fb`**. Y-Connect does **not** fall back from one to the other.
3. Y-Connect also listens: `listenUsingRfcommWithServiceRecord(<packageName>, 8911)`, so the bike can dial the phone. [DESIGN] Race dial + listen, as `TftConnector` does.
4. SPP frame: `01 | len u32 BE | payload | 04`, where payload = `[cmd u16 BE][data]`.
5. Send `01 00000002 0139 04` (cmd 313, no body).
6. Expect cmd **314**: `[cmd 2][CCU id 14 ASCII][passkey 6 ASCII]`.
7. **Keep the socket open.** It supervises the BLE side: if BLE drops while SPP is up, request 313 again.

### 6.2 BLE [YCONNECT]
- If the CCU id equals the one saved last time, connect straight to the saved device. Otherwise LE-scan for name **`YCCU_<CCU id>`** (or `YSCCU_…`; always 19 chars).
- If bond state is NONE, call **`createBond`** and wait for `BOND_STATE_CHANGED` → BONDED (30 s). Then `connectGatt(ctx, autoConnect, cb, TRANSPORT_LE)`.
- After `onServicesDiscovered`, run as **one GATT op at a time**: `requestMtu(512)` → enable notify on `9C81, 75AB, 7780, 3474, E974` → enable indicate on `56DC, AB8D`. The link is ready when that queue drains.
- Service **`AFA2CDF4-ECCF-46A7-A5EA-9DA428C0157A`**:

| UUID | Use |
|---|---|
| `B606C7F9-E5A1-4E75-B313-2A920054A8EB` | control **write** `[cmd u16][body]` |
| `9C810D26-B605-4306-8C1C-755A1BA3066C` | control **notify** |
| `75AB2788-87F6-4F61-BA9C-6F85A12600D1` | DDT-out status (read + notify) |
| `56851826-A090-4F6A-A5DF-D1776520F74A` | DDT-out header write `[reqId][len u24]` |
| `E998AEAB-EA8F-4063-83AA-2017C1A44484` | DDT-out data write `[reqId][off u24][chunk]` |
| `77808451-E42F-4F6B-83ED-3AB44AA3D16A` | DDT-in header notify `[id][len u24]` → write `1A1A7A79` `[01 id]` |
| `347456EE-A588-4DD5-BD98-62BE3672E27A` | DDT-in data notify `[id][off u24][data]` → ack `[id]` written to **this same characteristic** (not `1A1A`); when all `len` bytes are in, write `1A1A7A79` `[00 00]` |
| `1A1A7A79-3D72-42A1-B084-843058453068` | DDT-in ack write |
| `E974B9A1-FA31-43C6-91AE-09931F3F6EF1` | DDT v2 notify `[seq u16][part 1..n][total][data]`: join parts of one seq in order → a full `[cmd][body]` message; drop unfinished seqs after 3 s |
| `56DC2D58-CBE1-4BA9-A104-E9B8EB4DA95C` | indicate: DDT v3-in, same format as v2 |
| `AB8D233A-CC58-4932-B397-A3751911E021` | indicate; purpose unknown |
| `7690EEF4-F3A1-11EC-B939-0242AC120002` | DDT **v3** out (photo frame, YRC). Not subscribed, not needed |

- **Request id**: a rolling 1..255 byte (skip 0). It's the first body byte of most control commands and the `reqId` of each DDT session. Replies are matched on it.

### 6.3 DDT out (phone → CCU, large payloads) [YCONNECT]
Body: `[cmd u16][len u24][protobuf]` for protobuf commands, or `[cmd u16][fields…]` for 257/278/280.
```
1  read   75AB            expect [00 ..]
2  write  5685            [reqId][len u24]
3  notify 75AB            [01 reqId]
4  write  E998            [reqId][offset u24][chunk]       chunk = min(MTU-3, 180) - 4 bytes
5  after each write ack:  more left → read 75AB, expect [01 reqId], go to 4
6  last chunk sent →      notify 75AB [00 00] = success
   anything else = failure; 3 s with no progress = timeout
```
Only **one DDT-out session at a time**. Queue the others.

### 6.4 Authenticate [YCONNECT]
```
ctrl  2     [00 02][reqId][00 00 12 34]            ConnectAppId (APP_ID 4660)
DDT   257   [01 01][CCU id 14][passkey 6]          AuthenticationV2, sent right after (no delay needed)
ctrl ← 258  [01 02][01]                            1 = OK
keep-alive: ← 309 [01 35][x] (3 B) → ctrl 310 [01 36][x]  (echo x, no reqId)
```
Pairing timeouts, as state (timeout s, retries): SPP connect (10, 3), 313/314 (5, 5), LE scan (5, 2), bond (30, –), GATT connect (10, 5), auth (10, 5).

### 6.5 Vehicle data [YCONNECT]
Y-Connect **subscribes**; it doesn't poll.
```
ctrl  259                         → ← 260  {3: repeated server id}
for each server:
ctrl  294 [server u16]            → ← 295  {3 server, 4 packed idList}
DDT   272 {3 server, 4 ids, 5 allId=false, 6 interval=100}   ids = idList ∩ wanted
←     271 {3 server, 4 repeated {1 id, 2 bytes}}             pushed every interval
```
- Wanted ids per server: **1248 (0x4E0) → [1, 72]**; any other server → [1].
- If 271 stops arriving, subscribe again. [DESIGN] Do it after 5 s of silence.
- One-shot alternative: DDT 270 `{3 server, 4 ids}` → ← 271 (with 287 as a poll trigger).

| dataId | byte(s) | Field | Formula |
|---|---|---|---|
| 1 | 0–1 | engine speed | rpm |
| 1 | 2 | vehicle speed | km/h |
| 1 | 4 | ignition timing | − 30 ° |
| 1 | 6 | battery voltage | × 18.75 / 256 V |
| 1 | 8 | relative throttle | × 125 / 256 % |
| 1 | 14 | coolant / engine temp | − 30 °C |
| 1 | 15 | air temp | − 30 °C |
| 1 | 16 | intake pipe pressure | × 127 / 256 |
| 1 | 18 | atmospheric pressure | × 127 / 256 |
| 1 | 23 | DTC count | raw |
| 72 | all | total mileage | units of 100 m → km = value / 10 |

Other values:
- **Ignition:** ctrl 311 → ← **312** `[01 38][state]` (exactly 3 B; 0 off, 1 on).
- **Live speed:** ctrl 6 `[reqId][interval u16 ms]` (default 200) subscribes to ← **7** `[00 07][front i16][rear i16][limit i16][ign u8][unit u8]` (10 B). [DESIGN] Use it when the TFT link (msg 65) is down.
- **Fuel:** ctrl 287 → ← 288 `{quantity}` = cumulative injection; unit unknown, Y-Connect doesn't use it on CCU bikes.
- **Instant fuel economy** (Y-Connect revs dashboard, `SubMeterStoreYmsl`): DDT **315** `{3 packed CAN ids, 4 interval ms}` → ← **276** `{3 repeated {1 CAN id, 2 bytes}}` pushed every interval. Injection rate = CAN **574** (0x23E) or **899** (0x383) bytes 2-3 × 0.01 (which id depends on the CCU generation; we ask for both at 100 ms, best effort). Per 3 s block: L/h = 3.6 × mean rate, km/L = mean 271 speed ÷ L/h; none at ≤ 10 km/h, capped at 80, a block with zero injection keeps the last value.
- **Device type** = the first 4 chars of the CCU id: `0000` SCCU1, `0008` SCCU2, `0020` SCCU2EV, `0004` 09X, `0006`/`0010` LinkCard, `0015` LinkBox2.
- **Errors:** ← **0xFF01** `[cmd u16][err u8][msg ≤ 15]`; err 0 ok, 1 bad cmd, 2 missing param, 3 range, 4 unsupported, 5 timeout, 6 rejected, 7 format.
- **Never send** 317 (EnterFactoryMode) or 289–302 (OTA).

### 6.6 Other CCU services
| Purpose | Exchange | Tag |
|---|---|---|
| Clock | ← 9 → ctrl **10** `[reqId][localEpochSec u64]` (epoch + timezone offset) | [YCONNECT] |
| Language | ← 11 → ctrl **12** `[reqId][lang]`; `McLanguage` ordinal: en 0 … **vi 15** … others 31 | [YCONNECT] |
| Weather | ← **3** `[00 03][01]` (forecast here) or `[00 03][00][lat i32][lng i32]` (radians × 1e8) → ctrl **4** `[00 04][reqId][place, raw UTF-8 ≤ 21 B]` + ctrl **5** × N `[00 05][reqId][slot][icon][temp i16][max i16][min i16][pop/5]`, temps °C × 10. Slots: 0 now, 1-5 = days +1..+5, 9 = day +6, 6/7/8 = hours +1..+3 (hours send max/min 0). Icons: 0 sunny, 1 partly cloudy, 2 cloudy, 3 rain, 4 snow, 5 wind, 6 thunderstorm, 7 sleet, 8 fog, 11 smog, 12 hail, 13 showers, 14 scattered storms, 15 no data, 16/17 light/heavy rain, 18/19 light/heavy snow, 20/21 light/heavy sleet, 22 overcast, 23 dust, 24 tornado; night = 0x80 \| (0, 1, 2, 13, 14). Sent only when the dash asks. Y-Connect: Yamaha backend (OpenWeather One Call data); this app: Open-Meteo + VietMap Reverse for the place | [YCONNECT] |
| Message notification | DDT **303** `[01 2F][len u24][protobuf 3 id (uint32), 4 category (0 MESSAGE omitted, 1 CALL), 5-10 local date/time, 11 title, 12 subtitle, 13 detail]`; texts cut to 64/64/512 UTF-8 bytes. Y-Connect: title = EXTRA_TITLE, detail = BIG_TEXT else TEXT, subtitle empty, CALL when `notification.category == "call"`; only user-picked apps (≤ 6), skips apps with an active media session, each (tag, id, when) once | [YCONNECT] |
| Call | DDT **280** `[01 18][len u8][name ≤ 64 B][len u8][number ≤ 12 B][missed u8]` (no u24 length: the SDK uses the varargs `[cmd][fields…]` wrapper, checked in bytecode); ctrl **308** `[01 34][1 started / 2 ended]` (no reqId). Y-Connect (`onCallStateChanged`): RINGING → 280 (name = contact match on digits, else the number; number = digits only); OFFHOOK → 308 started; IDLE → 308 ended, then 280 `missed = 1` if it was still ringing. A link coming up mid-call replays the current state | [YCONNECT] |
| Call buttons | ← 281 `[01 19][0 reject, 1 answer, 2 on-hook]` (length 3). Y-Connect sends a headset-hook media key; this app uses `TelecomManager.acceptRingingCall` / `endCall` | [YCONNECT] |
| Music | DDT **278** now playing `[01 16][len u8][artist][len][album][len][title][duration u24 s][state u8: 1 paused, 2 playing, 3 rewinding, 4 fast-forward, FF unavailable][rate×10 i16][elapsed u24 s]`, texts ≤ 64 UTF-8 bytes; sent when track/state changes (1 s check). ← **279** `[01 17][1 next, 2 prev, 3 play, 4 pause]`: Y-Connect toggles play/pause for both and sends media keys | [YCONNECT] |
| Volume / mic | ← 285 `[01 1D][1/2 call ±, 3 mic mute, 4 mute, 5/6 media ±]`; length 3, traced in Y-Connect bytecode (`motoconsdk.e.o`). Y-Connect: 1/2 adjust the Bluetooth SCO stream while a headset carries audio, else voice call; 5/6 music; 3 toggles `isMicrophoneMute`. Arrives on the control notify characteristic like 279 | [YCONNECT] |
| Phone status | ctrl **316** `[01 3C][battery% \| charging<<7][callVol% \| 0x80][mediaVol% \| 0x80][onHook \| signal<<4 \| thermal<<2 \| headset<<1]` (no reqId). signal: 0 no service (airplane / no SIM), 1 no signal, 2-5 = 1-4 bars; thermal: 0 low (status 0-2), 1 middle (3), 2 high (4-6). Y-Connect sends it when it changes (1 s check); call volume from the BT SCO stream while a headset carries audio, else voice call. ← **297** → ctrl **298** `[01 2A][controllable]`. We answer 298 `01`. Volume buttons ← **285** `[01 1D][op]`, see the Volume / mic row; Y-Connect reports the new level only through 316. 283→284 / 306→307 are covered by 316 | [YCONNECT] |

---

## 7. Session state machines [DESIGN]

```
TFT:  IDLE → LISTENING/DIALING → CONNECTED → (66) VALIDATING → (81,33) AUTHING
          → (83→84) WAIT_ACK → (82=1) READY → (55[1]) STREAMING ⇄ (56[1]) PAUSED
      any IOException / ACK timeout 2 s → CLOSED → backoff → LISTENING/DIALING
CCU:  IDLE → SPP_CONNECT → (313→314) BLE_INFO → [BLE_SCAN] → [BOND] → GATT_CONNECTED → MTU → SUBSCRIBED
          → (2, 257) AUTHING → (258 ok) STARTUP (259 → 294 → 272) → READY  [271 pushed every 100 ms; 309→310]
      BLE drop with SPP up → BLE_INFO;  SPP drop → backoff (3 s ×3, then 5-20 s) → SPP_CONNECT
```
Run both inside one foreground service. The TFT link must not wait for the CCU link, or the reverse.

## 8. Android requirements [DESIGN]
- **Permissions:** `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (`neverForLocation`; `BLUETOOTH_ADMIN` ≤ API 30 for LE scan + `createBond`), `ACCESS_FINE_LOCATION` (+ background location for screen-off navigation), `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`.
- **Optional permissions:** `BIND_NOTIFICATION_LISTENER_SERVICE` (messages + music), `READ_PHONE_STATE`/`ANSWER_PHONE_CALLS` (calls), `FOREGROUND_SERVICE_MEDIA_PROJECTION` (mirror mode).
- Inject an IO dispatcher for the socket and GATT work, per `CLAUDE.md`. Each socket gets a single writer coroutine fed by a `Channel`.

## 9. Verify first on a real bike
1. Packets from §2.3 round-trip. A CRC mismatch from the TFT means your framing is wrong.
2. Handshake with appVersion 1870 and the ESN auto-ACK.
3. Whether JPEGs need the ≤ 2048 → 2050 B padding (official parser has no such rule).
4. Whether the TFT accepts 1-byte VALUE payloads (it shouldn't matter if you always send 2).
5. Byte meaning of 14 bytes 1/3, plus message 19 (lanes) and message 9 (camera/school alerts). Trace them in `libnaviliteprotocol.so` if you want those features.
6. CCU (§6): which SPP UUID the bike's name selects; whether the bike dials our 8911 server; bond prompt; CCU id prefix (device type); 260 server list and 295 id list; first 271 bytes vs the dash ODO.

### References
- `apk/StreetCross-1.87-NaviLite-protocol.md`: official evidence (Garmin).
- `apk/Y-Connect-3.9.0-CCU-protocol.md`: official CCU evidence (Yamaha `motoconsdk`).
- VietMap: `docs/vietmap-agent-docs/` (`map-display.txt`, `routing.txt`, `search-geocoding.txt`, `console.txt`) and the `vietmap` project skill.
