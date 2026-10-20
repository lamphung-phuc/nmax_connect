# Garmin StreetCross 1.87 — NaviLite (TFT) protocol, official reference

Source: `com.garmin.android.apps.streetcross` 1.87 (versionCode 1870), APKM SHA-256 `406c134a…c982`.
Static analysis only, nothing captured from a bike. Tools used:
- **REA**: `open_binary` + `inspect_artifact` (Evidence `ev_e7561707…8d44`, 40 entries, integrity ok), `extract_artifact` (Evidence `ev_5af43b61…1d82`), `identify_flutter_build` (not Flutter).
- **jadx 1.5.1** for Java/Kotlin. REA's JADX provider isn't installed.
- **capstone** disassembly of `lib/arm64-v8a/libnaviliteprotocol.so` (741,200 B). REA's native engines (Ghidra/Hopper) aren't installed.

Work dir: `C:\Users\LAMPHU~1\AppData\Local\Temp\claude-re\` (`sc\` = jadx output, `lib\` = native libs, `lib\cases.txt` = parser jump-table dump).

> **Scope.** StreetCross only implements the **TFT ("LinkCard") link** (link A in `docs/bike-protocol.md`). It contains **no BLE, CCU, `YCCU_`, `AFA2CDF4…` or `00008911` code**: a grep over the Garmin sources for `connectGatt`/`BluetoothLeScanner` found nothing, and the manifest has no BLE-only features. The CCU / Y-Connect link (notifications, calls, music, weather, vehicle data) belongs to Yamaha's Y-Connect app, so it can't be verified from this APK.

---

## 1. Where things live

| Piece | Location |
|---|---|
| Protocol constants & enums (named) | `com/garmin/android/lib/navilite/protocol/*.java` (djinni-generated) |
| Packer / unpacker / header / CRC | native `libnaviliteprotocol.so`; C++ symbols are exported (`navilite::protocol::NaviLiteMessagePacker::Get…`) |
| RFCOMM transport | `apps/navilite/bluetooth/NaviBluetoothService.java` |
| Authentication flow | `apps/navilite/bluetooth/LinkCardAuthenticationManager.java` |
| Message engine (send log + receive dispatch) | `p087g5/C2990l.java` |
| Map / route / content logic | `apps/navilite/linkcard/C1827c.java` ("LinkCardMapManager") |

---

## 2. Transport
- RFCOMM UUID **`00007220-0000-1000-8000-00805F9B34FB`** (`NaviBluetoothService.java:30`).
- The phone does both:
  - listens with `listenUsingRfcommWithServiceRecord("NaviBluetoothSecure", uuid)`, **secure only** (`:221`), and rejects peers not on an accept whitelist ("Not In Whitelist!!", `:304`);
  - connects out with `createRfcommSocketToServiceRecord(uuid)` (`:391`).

## 3. Packet format (confirmed in native code)

```
off  size  field                 source
0    4     magic "nAl@"          NaviLiteConstant.MAGIC_CODE
4    1     version = 0x01        PROTOCOL_VERSION_MAJOR=1 (struct +0x18)
5    1     frame type            NaviLiteFrameType (struct +0x19)
6    1     service type          NaviLiteServiceType (struct +0x1a)
7    4     payload size u32 LE   (struct +0x1c)
11   1     payload data type     0 = AS_VALUE, 1 = AS_POINTER (struct +0x20)
           ── HEADER_SIZE = 12 (const @0x310a0) ──
12   4     CRC-32 u32 LE
16   N     payload
```
- `Packer` (`0x63da8`) calls `PackHeader` (12 B), then runs CRC over the **header bytes, then the payload bytes**. It appends the CRC as a u32 LE, then appends the payload.
- CRC table at `0x3724c` = MSB-first poly `0x04C11DB7`; init `0xFFFFFFFF` (`mov w21,#-1`); loop `crc = T[(crc>>24)^b] ^ (crc<<8)`; no final XOR. That is **CRC-32/MPEG-2**. `CHECKSUM_ENABLED = true`.
- Unpack errors (`NaviLiteUnpackedMessageStatus`): size < header, magic fail, payload length mismatch, **service type not matching frame type**, service type not matching payload, payload content error, checksum fail.

### Frame type is fixed per service (`isServiceTypeMatchFrameType` @ `0x6ce08`)
| Frame type | Value | Used for |
|---|---|---|
| `MCU_UPDATE` | **6** | every phone → TFT message (0-33, 81, 84, 97-99) |
| `MOBILE_REQUEST` | **1** | TFT → phone requests 48-56 |
| `MOBILE_UPDATE` | **3** | TFT → phone updates 65, 66, 69, 70, 80, 82, 83 |
| `MOBILE_RESPONSE` / `MCU_REQUEST` / `MCU_RESPONSE` | 2 / 4 / 5 | defined, unused |

Payload data type rule from the parser:
- `AS_VALUE` (0): payload is **exactly 2 bytes**, an int16 LE (bools are `[v, 00]`).
- `AS_POINTER` (1): structured payload.

---

## 4. Service types (official names; `NaviLiteServiceType.java`)

`F` = frame type, `P` = payload data type. Layouts marked ✔ were confirmed in the native parser (`unpackPayloadParameters` @ `0x6ceac`, jump table @ `0x3764c`) or packer. `·` = only the Java signature was checked.

### Phone → TFT (F=6)
| # | Name | P | Payload | |
|---|---|---|---|---|
| 0 | IMAGE_FRAME_UPDATE | 1 | `[imageType u8][seq i16 LE][JPEG…]` (≥4 B). imageType: 0 NAVIGATION, 1 JCV (junction view), 2 THROUGHPUT_TEST_DATA, 3 NAVIGATION_EXPANDED, 4 OTHER | ✔ |
| 1 | ETA_UPDATE | 1 | `[minuteOfDay i32 LE]` = hour×60+min (packer rejects hour > 23 / min > 59) | ✔ |
| 2 | NAVIGATION_STATUS_UPDATE | 0 | `[isNavigating, 00]` | ✔ |
| 3 | CUR_ROAD_NAME_UPDATE | 1 | road name string (max 64) | ✔ |
| 4 | NEXT_TURN_DIST_UPDATE | 1 | `[turnImage u8][dist f32 LE][unitLen u8][roadLen u8][unit][road]` | ✔ |
| 5 | TBT_LIST_UPDATE | 1 | `[count i16 LE][hasMore u8]` | ✔ |
| 97 | TBT_LIST_DATA_UPDATE | 1 | (itemIndex s, icon b, dist f, unit, description ≤ 112) | · |
| 6 | ACTIVE_TURN_LIST_UPDATE | 0 | `[activeIndex i16]` | ✔ |
| 7 / 8 | FAV / GAS_POI_LIST_UPDATE | 1 | `[count i16 LE][hasMore u8]` | ✔ |
| 98 / 99 | FAV / GAS_POI_LIST_DATA_UPDATE | 1 | `[itemIdx i16][listIdx i16][dir u8][nameLen u8][unitLen u8][dist f32][unit][name]` (≥ 11 B). dir: 0 undefined, 1 front, 2 front-right, 3 right, 4 back-right, 5 back, 6 back-left, 7 left, 8 front-left | ✔ |
| 9 | NAVI_EVENT_TEXT_UPDATE | 1 | text + eventType (0 traffic, 1 speed, 2 camera, 3 border, 4 school, 5 other) + subtype + show | · |
| 10 / 11 | HOME / OFFICE_SETTING_UPDATE | 0 | `[isSet, 00]` | ✔ |
| 12 | APP_SETTING_UPDATE ("InitSetupComplete") | 0 | `[bool, 00]` | ✔ |
| 13 | GPS_STATUS_UPDATE | 0 | `[gpsFixed, 00]` | ✔ |
| 14 | MAP_ZOOM_LEVEL_UPDATE | 1 | `[b0][b1][labelLen i8][b3][label]` from (current, min, max, zoomLabel, showLabel). The label is the map scale text, e.g. "300 m" | ✔ (layout) |
| 15 | ROUTE_CALC_PROGRESS_UPDATE | 0 | `[progress, 00]` | ✔ |
| 16 | BT_THROUGHPUT_TIMEOUT_UPDATE | 0 | `[value, 00]` | ✔ |
| 17 | SPEED_LIMIT_UPDATE | 1 | `[limit f32 LE][unitLen][unit]` ("no speed limit" variant exists) | ✔ |
| 18 | VIA_POINT_COUNT_UPDATE | 0 | `[count i16]` | ✔ |
| 19 | NAVIGATION_INFO_UPDATE | 1 | combined: turnImage, next-turn dist+unit, next road, remaining dist+unit, int, lane list (lane codes in `NaviLiteLaneImageType`) | · |
| 20 | IMAGE_STOPPED_UPDATE | 0 | (none) | ✔ |
| 25 / 32 | DIALOG_LC_PROMPT / DIALOG_PROMPT_UPDATE | 1 | dialog id, type (0 dismiss, 11 OK, 22 OK/Cancel, 33 Yes/No, 44 Yes/No/Cancel), timeout, … | · |
| 31 | DAY_NIGHT_MODE_UPDATE | 0 | `[mode i16]`; the parser **accepts only 1 = DAY, 2 = NIGHT** | ✔ |
| 33 | AUTH_REQUEST | 1 | `[appVersion i16 LE][00][01][extraData u32 LE]` (exactly 8 B). Official appVersion = **1870** | ✔ |
| 81 | MCU_ESN_UPDATE_ACK | 0 | `[validated, 00]` | ✔ |
| 84 | AUTH_REQUEST_SEC_DATA_ACK | 1 | the seeder key bytes, echoed back unchanged (no transform in packer) | ✔ |

### TFT → phone
| # | Name | F | P | Payload | Official app action |
|---|---|---|---|---|---|
| 48 | APP_START_ROUTE_REQUEST | 1 | 1 | `[itemIdx i16][listIdx i16][routeOption u8]` (5 B); routeOption: 0 unknown, 1 new route, 2 next stop, 3 last stop | navigate to POI from list (`C1827c:558`) |
| 49 | APP_STOP_ROUTE_REQUEST | 1 | 0 | – | stop route |
| 50 | APP_SKIP_NEXT_WAYPOINT_REQUEST | 1 | 0 | – | skip via point |
| 51 / 52 | APP_MAP_ZOOM_IN / OUT_REQUEST | 1 | 0 | – | zoom map |
| 53 / 54 | APP_GO_HOME / GO_OFFICE_REQUEST | 1 | 0 | `[v i16]` | route home/office |
| 55 / 56 | APP_START / STOP_CONTENT_UPDATE_REQUEST | 1 | 0 | `[contentType i16]`: 1 NAVI_IMAGE, 2 TBT_LIST, 3 FAVORITE_LIST, 4 STATIONS_LIST, 11 NAVI_IMAGE_FOR_THROUGHPUT_MODE | start/stop streaming that content (`C1827c:493,586`) |
| 65 | MCU_VEHICLE_SPEED_UPDATE | 3 | 1 | `[unit u8][speed i16 LE]` (exactly 3 B); unit 0 = km/h, 1 = mph | "Receive speedUnit, speedValue" (`C1827c:468`) |
| 66 | MCU_ESN_UPDATE | 3 | 1 | **ESN, exactly 10 ASCII bytes** | starts auth (step 0) |
| 69 | MCU_SYSINFO_UPDATE | 3 | 1 | string | logged/stored |
| 70 | MCU_DIALOG_USER_SELECT_UPDATE | 3 | 1 | `[dialogId u8][button u8]`; button: 0 dismiss, 1 OK, 2 cancel, 3 yes, 4 no | dialog answer |
| 80 | IMAGE_FRAME_UPDATE_ACK | 3 | 0 | `[seq i16]` | image ACK |
| 82 | AUTH_REQUEST_ACK | 3 | 0 | `[ok, 00]` | auth result |
| 83 | AUTH_REQUEST_SEC_DATA | 3 | 1 | ≥ 16 B, **every byte XOR 0x0A**; bytes 0-11 = LinkCard SW part number, rest = seeder key | steps 5 & 6 |

The XOR decode is at `0x6e374` (`eor w25, w9, #0xa` over 12 bytes, then the key).

---

## 5. Official connection / authentication sequence
From `LinkCardAuthenticationManager` log strings ("App Auth Step N"):

```
TFT → 66  MCU_ESN_UPDATE (10-char ESN)                        Step 0
         App: is the ESN already in ValidatedDeviceDatabase?   Step 1-2 (cached → skip server)
         else validate the ESN online through Garmin services  Step 1-1 (GarminOS "validatedEsnInt"; needs network)
phone → 81  ESN_UPDATE_ACK [validated]                          Step 2
phone → 33  AUTH_REQUEST [1870][00 01][extra=0]                 Step 3 (only if validated)
TFT → 83  AUTH_REQUEST_SEC_DATA (XOR 0x0A: partNo[12] + key)    Steps 5 & 6
phone → 84  AUTH_REQUEST_SEC_DATA_ACK [key]
TFT → 82  AUTH_REQUEST_ACK [1]                                  → "onAppAuthenticationCompleted"
phone → initial state: 2 nav status, 10 home, 11 office, 13 GPS fix, 12 init-setup-complete, 31 day/night, 14 zoom level
TFT → 55  START_CONTENT_UPDATE [1]  → phone streams 0 IMAGE_FRAME_UPDATE, TFT ACKs each with 80
```

**Display model, chosen from the part number in 83** (`LinkCardAuthenticationManager.h(boolean)`, jadx `--show-bad-code`):

| Part number contains | Model | Map image | Notes |
|---|---|---|---|
| `006-B3952` | `MODEL_IXWW22` | **480 × 234** | next-turn info **not** sent ("requestSendNextTurnInfo by IXWW22, skip") |
| `006-B4160` or `006-B4920` | `MODEL_IMWW23` | **480 × 240** | own layout `layout_imww23_map` |
| anything else | `MODEL_INVALID` | 480 × 234 | |

Sizes come from `res/values/dimens.xml`: `imww23_map_image_*` = 480/240 px, `linkcard_map_image_*` = 480/234 px.

---

## 6. Open points
- Field meaning of zoom-level bytes 1 and 3, `NAVIGATION_INFO_UPDATE` (19), event text (9), dialogs (25/32) and TBT data (97). I have their Java signatures but didn't trace them byte by byte.
- Whether the TFT firmware **requires** JPEGs ≤ 2048 B to be padded to 2050 B. The phone-side parser has no such rule.
- How the dash reacts to 1-byte VALUE payloads. The phone parser would reject them, but the MCU's parser isn't in this APK.
- Official features not used yet: TBT list (5/97/6), NAVIGATION_INFO (19, with lanes), event text (9: cameras/school/border/traffic), route progress (15), via count (18), dialogs (25/32), skip waypoint (50), content types 2 and 11.
- The CCU / Y-Connect link: needs Yamaha's Y-Connect APK (`jp.co.yamahamotor.yamahamotorcycleconnect.sccu`).
